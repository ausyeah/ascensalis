package com.mistakebook.di

import android.content.Context
import com.mistakebook.data.AppFiles
import com.mistakebook.data.ImageImporter
import com.mistakebook.data.backup.BackupManager
import com.mistakebook.data.local.MistakeBookDatabase
import com.mistakebook.data.prefs.SettingsStore
import com.mistakebook.data.prefs.UsageStatsStore
import com.mistakebook.data.repos.CaptureTaskRepository
import com.mistakebook.data.repos.NotebookRepository
import com.mistakebook.data.repos.QuestionRepository
import com.mistakebook.data.repos.SubjectRepository
import com.mistakebook.data.repos.TagRepository
import com.mistakebook.net.HttpFactory
import com.mistakebook.net.ImageGenClient
import com.mistakebook.net.llm.LlmApi
import com.mistakebook.net.mineru.MineruApi
import com.mistakebook.pipeline.LlmClient
import com.mistakebook.pipeline.MineruClient
import com.mistakebook.pipeline.RecognitionEngine
import com.mistakebook.pipeline.RecognitionSubmitter
import com.mistakebook.pipeline.SceneImageGenerator
import com.mistakebook.util.ImageDiskCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * 手写依赖容器：单 Activity + Repository 架构下足够使用，避免引入 DI 框架依赖。
 */
/** 生图任务的来源标识（同时用作 WorkManager 工作名的后缀）。 */
const val SOURCE_WORDBOOK = "wordbook"
const val SOURCE_POMODORO = "pomodoro"

class AppContainer(context: Context) {

    val appContext = context.applicationContext

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val files: AppFiles = AppFiles(appContext)

    val settingsStore: SettingsStore = SettingsStore(appContext)

    /** 大模型 / MinerU 用量累计（SharedPreferences 计数器）。 */
    val usageStatsStore: UsageStatsStore by lazy { UsageStatsStore(appContext) }

    private val httpClient = HttpFactory.createClient()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val mineruRetrofit: Retrofit = Retrofit.Builder()
        .baseUrl(MineruApi.BASE_URL)
        .client(httpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    // 大模型的 Base URL 由用户在设置里配置，所有请求走 @Url，这里只需要一个合法占位地址。
    private val llmRetrofit: Retrofit = Retrofit.Builder()
        .baseUrl(LLM_PLACEHOLDER_BASE_URL)
        .client(httpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    private val mineruApi: MineruApi = mineruRetrofit.create(MineruApi::class.java)

    private val llmApi: LlmApi = llmRetrofit.create(LlmApi::class.java)

    /**
     * 数据库实例。
     *
     * 刻意**不用** `by lazy` + 固定实例：备份/恢复需要先 `close()` 让 WAL 落进
     * `.db` 文件（Room 默认开 WAL，不关库的话备份缺最近数据，
     * 恢复时旧 `-wal` 还会被重放到新库上）。
 *
     * Room 的实例一旦 close 就不能重开，所以这里换成可替换的引用，
     * close 后由 [reopenDatabase] 重建一个新的。
     *
     * 读取方都是 `by lazy` 持有 DAO 实例，那些实例在重建后仍是旧的——
     * 所以恢复之后 App 需要重启才完全生效。界面在恢复完成后会提示重启。
     */
    @Volatile
    private var databaseRef: MistakeBookDatabase? = null

    val database: MistakeBookDatabase
        get() = databaseRef ?: synchronized(this) {
            databaseRef ?: MistakeBookDatabase.build(appContext).also { databaseRef = it }
        }

    /** 备份/恢复期间关库。 */
    fun closeDatabase() {
        databaseRef?.close()
        databaseRef = null
    }

    /** 关库之后重新打开。 */
    fun reopenDatabase() {
        databaseRef?.let { if (it.isOpen) return }
        databaseRef = MistakeBookDatabase.build(appContext)
    }

    val subjectRepository: SubjectRepository by lazy {
        SubjectRepository(database.subjectDao())
    }

    val tagRepository: TagRepository by lazy {
        TagRepository(database.tagDao(), database.subjectDao())
    }

    val questionRepository: QuestionRepository by lazy {
        QuestionRepository(
            questionDao = database.questionDao(),
            reviewLogDao = database.reviewLogDao(),
            subjectDao = database.subjectDao(),
            tagRepository = tagRepository
        )
    }

    val captureTaskRepository: CaptureTaskRepository by lazy {
        CaptureTaskRepository(database.captureTaskDao())
    }

    val notebookRepository: NotebookRepository by lazy {
        NotebookRepository(database.notebookDao())
    }

    val chatRepository: com.mistakebook.data.repos.ChatRepository by lazy {
        com.mistakebook.data.repos.ChatRepository(
            chatDao = database.chatDao(),
            questionDao = database.questionDao(),
            subjectDao = database.subjectDao(),
            appFiles = files,
            imageDataUrls = com.mistakebook.data.chat.ChatImageDataUrls()
        )
    }

    val wordbookDatabase: com.mistakebook.wordbook.data.WordbookDatabase by lazy {
        com.mistakebook.wordbook.data.WordbookDatabase.build(appContext)
    }

    val pomodoroDao: com.mistakebook.data.local.PomodoroDao by lazy {
        database.pomodoroDao()
    }

    /**
     * 学科 DAO：番茄钟的「新建学科」要写 subjects 表（与错题本共用一套学科）。
     * 单独暴露而不是每次都 database.subjectDao()——Room 的 dao() 每次调用
     * 都会新建一个实现实例，虽然无状态，但没必要。
     */
    val subjectDao: com.mistakebook.data.local.SubjectDao by lazy {
        database.subjectDao()
    }

    /**
     * 番茄钟计时核心：全局单例。
     *
     * 必须是单例——计时状态活在它里面，页面重建（旋转、切 Tab）时
     * 计时不能跟着重置。
     */
    val pomodoroTimer: com.mistakebook.pomodoro.PomodoroTimer by lazy {
        com.mistakebook.pomodoro.PomodoroTimer(appContext)
    }

    /** 学科列表（番茄钟任务挂在学科下，与错题本共用一套学科）。 */
    val subjectsFlow: kotlinx.coroutines.flow.StateFlow<List<com.mistakebook.data.local.entities.Subject>> by lazy {
        database.subjectDao().observeAll().stateIn(
            appScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            emptyList()
        )
    }

    val wordbookDao: com.mistakebook.wordbook.data.WordbookDao by lazy {
        wordbookDatabase.wordbookDao()
    }

    /**
     * 单词数据纪元信号：设置页重置单词数据时 +1。
     *
     * 为什么需要它：WordbookViewModel 的抽词缓存（progressCache /
     * wrongWordsCache）是**进程内内存**，只在 init 时全量载入一次、
     * 之后靠答题增量维护。设置页直接删库的话，缓存不会知道，
     * 于是出现「库里空了还在按熟词跳过抽词」的幽灵状态。
     * 而设置页与背单词页可能持有同一个 VM 实例（门户内嵌设置页时），
     * 也可能是两个（独立设置路由时）——所以不能用「调用 VM 方法」，
     * 必须有一个进程级的广播让所有实例各自重载。
     */
    val wordbookEpoch: kotlinx.coroutines.flow.MutableStateFlow<Long> =
        kotlinx.coroutines.flow.MutableStateFlow(0L)

    val vocabRepository: com.mistakebook.wordbook.data.VocabRepository by lazy {
        com.mistakebook.wordbook.data.VocabRepository(appContext)
    }

    /**
     * 单词发音（TTS）单例。
     *
     * 全局一个实例：TextToSpeech 初始化是重量级操作（绑系统 TTS 服务），
     * 每题一建不仅慢还会泄漏。未初始化完成时 speak 静默忽略。
     */
    val wordAudioPlayer: com.mistakebook.wordbook.audio.WordAudioPlayer by lazy {
        com.mistakebook.wordbook.audio.WordAudioPlayer(appContext)
    }

    val mineruClient: MineruClient by lazy { MineruClient(mineruApi, files, usageStatsStore) }

    /** 会话联网搜索专用客户端：比对话更长的超时（服务端搜索要真爬网页）。 */
    val searchHttpClient: okhttp3.OkHttpClient by lazy {
        com.mistakebook.data.chat.searchHttpClient()
    }

    val llmClient: LlmClient by lazy { LlmClient(llmApi) }

    /** 生图客户端（渠道由设置里配置，见 [com.mistakebook.data.prefs.ImageProfile]）。 */
    val imageGenClient: ImageGenClient by lazy { ImageGenClient() }

    /** 生图结果磁盘 LRU 缓存（filesDir/images/gen，上限 200 张，不进备份）。 */
    val imageDiskCache: ImageDiskCache by lazy { ImageDiskCache(files.genImageDir) }

    /**
     * 两步生图编排：LLM 出场景 prompt → 生图 API 出图（单词场景图 / 详情页辅助图共用）。
     */
    val sceneImageGenerator: SceneImageGenerator by lazy {
        SceneImageGenerator(llmClient, imageGenClient, imageDiskCache, settingsStore)
    }

    /**
     * 海报生图：打卡海报（词库统计页 / 番茄钟统计页）共用。
     *
     * 两种模式：只生背景（文字本地画，数字准确）或整图交给生图模型。
     */
    val posterImageService: com.mistakebook.poster.PosterImageService by lazy {
        com.mistakebook.poster.PosterImageService(imageGenClient, imageDiskCache, settingsStore)
    }

    /**
     * 词库页的海报生图**后台任务状态**。
     *
     * 挂在 [appScope] 上（与应用同生命周期），这样用户离开页面后
     * ViewModel 被销毁、状态也不会丢——回到页面还能看到任务已经完成。
     */
    val wordbookPosterMonitor: com.mistakebook.poster.PosterWorkMonitor by lazy {
        com.mistakebook.poster.PosterWorkMonitor(appContext, appScope, SOURCE_WORDBOOK)
    }

    /**
     * 番茄钟页的海报生图后台任务状态。
     *
     * 与 [wordbookPosterMonitor] 分开是因为两者用**不同的 WorkManager 工作名**
     * （否则在词库页点一次生图会把番茄钟的任务 REPLACE 掉）。
     */
    val pomodoroPosterMonitor: com.mistakebook.poster.PosterWorkMonitor by lazy {
        com.mistakebook.poster.PosterWorkMonitor(appContext, appScope, SOURCE_POMODORO)
    }


    /**
     * 对话流式客户端。
     *
     * 传的是**同一个** [httpClient]：`ChatCompletionStream` 内部会派生出
     * 一个关掉 call timeout 的副本，共享连接池与日志脱敏配置。
     */
    val chatCompletionStream: com.mistakebook.net.llm.ChatCompletionStream by lazy {
        com.mistakebook.net.llm.ChatCompletionStream(httpClient, llmApi)
    }

    /** 对话附件准备：复制到私有目录 + 图片压缩 / 文档抽文本。 */
    val chatAttachmentPreparer: com.mistakebook.data.chat.ChatAttachmentPreparer by lazy {
        com.mistakebook.data.chat.ChatAttachmentPreparer(appContext, files)
    }

    /**
     * 对话页的图片 OCR：模型不识图时，先让 MinerU 把图片读一遍。
     *
     * 与 [mineruClient] 共用同一个实例，因此用量统计是同一份口径。
     */
    val chatImageOcr: com.mistakebook.data.chat.ChatImageOcr by lazy {
        com.mistakebook.data.chat.ChatImageOcr(mineruClient, files)
    }

    /**
     * 会话上下文压缩器：旧轮次滚成摘要，长对话不再「越聊越失忆》。
     * 与 chatCompletionStream 共享同一个实例（内部各自派生 HTTP 配置，无冲突）。
     */
    val chatContextCompressor: com.mistakebook.data.chat.ChatContextCompressor by lazy {
        com.mistakebook.data.chat.ChatContextCompressor(database.chatDao(), chatCompletionStream, usageStatsStore)
    }

    val recognitionEngine: RecognitionEngine by lazy {
        RecognitionEngine(
            taskRepository = captureTaskRepository,
            mineruClient = mineruClient,
            llmClient = llmClient,
            settingsStore = settingsStore,
            files = files
        )
    }

    /** 周报数据（F2）：observeLatest 供入口角标，历史列表在弹层里直接查。 */
    val weeklyReportDao: com.mistakebook.data.local.WeeklyReportDao by lazy {
        database.weeklyReportDao()
    }

    /** 周报生成编排（F2）：与 recognitionEngine 同构的「data+net」编排者。 */
    val weeklyReportGenerator: com.mistakebook.report.WeeklyReportGenerator by lazy {
        com.mistakebook.report.WeeklyReportGenerator(database, llmClient, settingsStore)
    }

    /** 思维导图生成编排（F4）：薄弱点聚合 + LLM 层级 markdown + 树解析。 */
    val mindmapGenerator: com.mistakebook.mindmap.MindmapGenerator by lazy {
        com.mistakebook.mindmap.MindmapGenerator(database, llmClient, settingsStore)
    }

    val imageImporter: ImageImporter by lazy {
        // 增强开关每次导入时读一次当前设置，改设置后无需重启即可生效。
        // snapshotNow() 是挂起函数，这里在 appScope 里起个协程取值。
        ImageImporter(appContext, files) { ocrStrengthCache.get() }
    }

    /** 缓存的增强开关，供非挂起上下文（ImageImporter 的同步回调）读取。 */
    private val ocrStrengthCache = java.util.concurrent.atomic.AtomicInteger(2)

    val recognitionSubmitter: RecognitionSubmitter by lazy {
        RecognitionSubmitter(captureTaskRepository, recognitionEngine)
    }

    val backupManager: BackupManager by lazy {
        BackupManager(
            appContext,
            files,
            // 关库让 WAL 落进 .db，否则备份缺最近数据、
            // 恢复时旧 WAL 会被重放到新库上。详见 BackupManager 里的注释。
            closeDatabase = { closeDatabase() },
            reopenDatabase = { reopenDatabase() }
        )
    }

    // LaTeX 公式渲染（WebView + KaTeX，资源在 assets/katex）
    val mathRenderer: com.mistakebook.math.MathRenderer by lazy {
        com.mistakebook.math.MathRenderer(appContext)
    }

    val exportPublisher: com.mistakebook.print.ExportPublisher by lazy {
        com.mistakebook.print.ExportPublisher(appContext, files)
    }

    /** PDF 导入的中间状态：选中的文件与刚创建的任务 id。 */
    @Volatile
    var pendingPdfFile: java.io.File? = null


    @Volatile
    var lastImportTaskIds: List<Long> = emptyList()

    /**
     * 重裁剪：编辑页点「重新裁剪」时置 true，裁剪页据此进入重裁剪模式
     * （只回填原图路径，不创建识别任务、不需要 API Key）。
     */
    var recropping: Boolean by mutableStateOf(false)

    /**
     * 「重新识别（先裁切再识别）」模式下的题目 id。
     *
     * 详情页点右上角「重新识别」时置上：裁剪页据此知道
     * 裁切涂鸦完成后要**换掉这道题的封面图并重新提交识别**，
     * 而不是新建一道题。popBackStack / onConfirmed 时由 NavHost 清零。
     */
    var reRecognizeQuestionId: Long? by mutableStateOf(null)

    /**
     * 当前裁剪页的图片来源。相册进来的图要标成「相册导入」而不是「拍照识别」——
     * 进度页按组浏览时，组标题是用户判断这批题从哪来的唯一线索。
     * 提交后立即清空，避免污染下一次。
     */
    var cropSourceIsGallery: Boolean = false

    /**
     * 这台扫描相机这次为谁服务（T11）。
     *
     * QUESTION = 拍错题（原有链路，快门前查 Key、产物进识别管线）；
     * CHAT = 对话页拍照（T12，跳过 Key 门禁、产物经裁切回对话当附件）。
     *
     * **每个进入相机的入口都必须显式设置**，不依赖默认值——上次从对话
     * 进来 purpose 残留 CHAT，下次首页拍题就会走错产物路径，这正是
     * [cropSourceIsGallery] 注释里警告过的那类状态泄漏。
     */
    enum class CameraPurpose { QUESTION, CHAT }

    @Volatile
    var cameraPurpose: CameraPurpose = CameraPurpose.QUESTION

    /**
     * 连拍批次（F6）：相机页攒题、裁切页逐张接力、完成后统一提交。
     * 放这里而不是 remember 里，是为了跨导航目标与旋横屏存活——
     * 同 [recropping] 的容器标志惯例。详见 [CaptureBatch] 的注释。
     */
    val captureBatch = CaptureBatch()

    /**
     * 裁切页的「聊天附件」模式（T11/T12）：与 [recropping]、
     * [reRecognizeQuestionId] 互斥。置位时裁切确认不走识别提交，
     * 产物复制进聊天附件暂存后经 [pendingChatAttachmentPath] 交回对话页。
     */
    var chatCropping: Boolean by mutableStateOf(false)

    /**
     * 裁切页交给对话页的待发附件路径（T12）。ChatScreen 的
     * LaunchedEffect 观察它：非空则把该图加入待发附件并清空。
     * 必须是快照状态：普通 @Volatile 字段不触发重组，对话页读不到
     * 交接（同 recroppedImagePath 的教训）。进程死亡不持久化。
     */
    var pendingChatAttachmentPath: String? by mutableStateOf(null)

    /**
     * 重裁剪产生的新原图路径。
     * 必须是快照状态：普通 @Volatile 字段不会触发 Compose 重组，
     * 编辑页读不到变化，原图看起来就没被替换。
     */
    var recroppedImagePath: String? by mutableStateOf(null)

    /**
     * 启动时的三件预置工作。
     *
     * ## 为什么必须放在类的最后
     *
     * Kotlin 的属性初始化与 `init` 块**按声明顺序**执行。
     * 这个块原先写在类的中部，它启动的协程引用了
     * `notebookRepository` 与 `enhancePhotosCache`——
     * 而这两个声明在它**下面**，此时 `by lazy` 的委托字段还是 `null`。
     *
     * 协程一旦在构造函数跑完前被调度到别的线程，就会读到 null 委托：
     * ```
     * NullPointerException: kotlin.Lazy.getValue() on a null object reference
     *     at AppContainer.getNotebookRepository(AppContainer.kt:108)
     * ```
     * 表现为**启动即崩溃**，且触发概率与数据库打开速度相关——
     * 装包后首次启动、网络盘/慢存储下必现，平时却可能一直不复现。
     *
     * 移到类末尾后，所有委托都已初始化，竞态从结构上消失。
     */
    init {
        // 预置学科（数学 / 通信原理）保证下拉框一进来就有得选
        appScope.launch { subjectRepository.seedPresets() }
        // 默认错题本 + 把历史题目归入其中。必须在任何写入之前跑完，
        // 否则新题会以「未归类」入库，首页按默认本筛选时就看不到它们。
        appScope.launch { notebookRepository.seedDefault() }
        // 缓存增强开关，供 ImageImporter 的同步回调读取
        appScope.launch {
            ocrStrengthCache.set(
                runCatching { settingsStore.snapshotNow().ocrStrength }.getOrDefault(2)
            )
        }
        // 网络出口模式喂给 NetworkRouter：设置页改完后下一个请求就走新规则。
        // 客户端（含 ChatCompletionStream 的派生副本）共享同一个 ProxySelector，
        // 不需要重建。放在最后：settingsStore 是直接属性，此时必然已初始化。
        appScope.launch {
            settingsStore.settings.collect { snapshot ->
                com.mistakebook.net.NetworkRouter.mode = snapshot.networkMode
            }
        }
    }

    private companion object {
        const val LLM_PLACEHOLDER_BASE_URL = "https://placeholder.invalid/"
    }
}

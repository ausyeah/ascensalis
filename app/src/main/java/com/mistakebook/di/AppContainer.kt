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
import com.mistakebook.net.llm.LlmApi
import com.mistakebook.net.mineru.MineruApi
import com.mistakebook.pipeline.LlmClient
import com.mistakebook.pipeline.MineruClient
import com.mistakebook.pipeline.RecognitionEngine
import com.mistakebook.pipeline.RecognitionSubmitter
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
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

    val wordbookDao: com.mistakebook.wordbook.data.WordbookDao by lazy {
        wordbookDatabase.wordbookDao()
    }

    val vocabRepository: com.mistakebook.wordbook.data.VocabRepository by lazy {
        com.mistakebook.wordbook.data.VocabRepository(appContext)
    }

    val mineruClient: MineruClient by lazy { MineruClient(mineruApi, files, usageStatsStore) }

    val llmClient: LlmClient by lazy { LlmClient(llmApi) }

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

    val recognitionEngine: RecognitionEngine by lazy {
        RecognitionEngine(
            taskRepository = captureTaskRepository,
            mineruClient = mineruClient,
            llmClient = llmClient,
            settingsStore = settingsStore
        )
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
     * 当前裁剪页的图片来源。相册进来的图要标成「相册导入」而不是「拍照识别」——
     * 进度页按组浏览时，组标题是用户判断这批题从哪来的唯一线索。
     * 提交后立即清空，避免污染下一次。
     */
    var cropSourceIsGallery: Boolean = false

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
    }

    private companion object {
        const val LLM_PLACEHOLDER_BASE_URL = "https://placeholder.invalid/"
    }
}

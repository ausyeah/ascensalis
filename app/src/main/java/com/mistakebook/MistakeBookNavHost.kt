@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.mistakebook

import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.navigation.NavHostController
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import android.util.Log
import com.mistakebook.di.AppContainer
import com.mistakebook.di.PendingTool
import com.mistakebook.ui.capture.CaptureScreen
import com.mistakebook.data.chat.ChatToolPrompts
import com.mistakebook.ui.chat.ChatScreen
import com.mistakebook.ui.chat.ChatTool
import com.mistakebook.ui.crop.CropScreen
import com.mistakebook.ui.detail.DetailScreen
import com.mistakebook.ui.edit.EditScreen
import com.mistakebook.ui.edit.QuestionEditScreen
import com.mistakebook.ui.home.HomeScreen
import com.mistakebook.ui.portal.MainPortalScreen
import com.mistakebook.ui.importpdf.PdfImportScreen
import com.mistakebook.ui.notebook.NotebookScreen
import com.mistakebook.ui.print.PrintScreen
import com.mistakebook.ui.progress.ProgressScreen
import com.mistakebook.ui.settings.SettingsScreen
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val CAPTURE = "capture"
    const val CROP = "crop"
    const val PROGRESS = "progress"
    const val EDIT = "edit"
    const val DETAIL = "detail"
    const val PRINT = "print"
    const val PDF_IMPORT = "pdfImport"
    const val QUESTION_EDIT = "questionEdit"
    const val NOTEBOOKS = "notebooks"
    const val LIBRARY = "library"
    const val BADGE_WALL = "badgeWall"

    /** 独立复习页（T1）：走完今日到期队列。无参数。 */
    const val REVIEW = "review"

    /** AI 对话：按题目进入。questionId 用 -1 表示自由会话。 */
    const val CHAT = "chat"

    /** 会话列表（首页入口）。 */

    /** 自由会话的 questionId 哨兵值。0 是数据库自增起点，不能拿来当「无」。 */
    const val CHAT_FREE = -1L
    const val MEMORY = "memory"
    const val ARCHIVED_QUESTIONS = "archivedQuestions"
    const val ARCHIVED_SESSIONS = "archivedSessions"
    const val ARCHIVED_TASKS = "archivedTasks"

    /**
     * 聊天页路由。
     *
     * @param sessionId 已知会话 id 时带上，从对话记录点进来**必须**传——
     *   自由会话的 questionId 是 null，只靠它定位不到任何已有会话。
     */
    fun chat(questionId: Long?, sessionId: Long? = null): String =
        "$CHAT/${questionId ?: CHAT_FREE}?sessionId=${sessionId ?: CHAT_FREE}"

    /**
     * 手动录入用的编辑页入口。
     *
     * 用路径区分而不是给 `edit` 加一个可空参数：导航参数类型越简单越不容易出错，
     * 而且「没有 taskId」这件事本身就该体现在路由上。
     */
    const val MANUAL_EDIT = "manualEdit"

    fun crop(imagePath: String): String = "$CROP/${Uri.encode(imagePath)}"

    fun progress(taskId: Long): String = "$PROGRESS/$taskId"

    fun edit(taskId: Long, index: Int = 0): String = "$EDIT/$taskId?index=$index"

    fun detail(questionId: Long): String = "$DETAIL/$questionId"

    fun questionEdit(questionId: Long): String = "$QUESTION_EDIT/$questionId"
}

@androidx.compose.runtime.Composable
fun MistakeBookNavHost(
    container: AppContainer,
    filterDue: Boolean = false,
    filterDueRequest: Int = 0,
    navController: NavHostController = rememberNavController()
) {
    // 错题本页选中后回传给首页筛选。用 remember 而不是导航参数：
    // 筛选状态本来就归 HomeViewModel 管，多带一层参数只会让两边状态不同步。
    var homeNotebookPick by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()

    /**
     * 回主页（v0.0.98）：从任何对话返回都直接落地主页，而不是按进来的顺序
     * 逐层退。从题目详情进对话再返回，按栈退会落在详情页——用户的意图
     * 是「聊完了，回去」，不是「回到我看过的那道题」。
     * HOME 不在栈里（进程恢复的极端情况）就退一层兜底，至少不会没反应。
     */
    fun popToHome() {
        if (!navController.popBackStack(Routes.HOME, inclusive = false)) {
            navController.popBackStack()
        }
    }

    /**
     * F6：提交整批连拍成果。drainCropped 取走文件后**不能**再清空批次
     * ——识别任务还要读这些文件，删了就全批失败。只跳进度页第一张 task，
     * 其余在进度页按组列表可见（groupPosition）。
     */
    fun submitCaptureBatch() {
        val batch = container.captureBatch
        // sourceIsGallery 会被 drainCropped 重置，标题要先取
        val title = if (batch.sourceIsGallery) "相册导入" else "拍照识别"
        val files = batch.drainCropped()
        if (files.isEmpty()) return
        scope.launch {
            // 兜底（v0.0.65）：成果已从批次取走，创建任务若失败（磁盘满等）
            // 不能让异常裸奔——那会直接崩溃且成果失去引用。落一条日志并提示，
            // 文件仍留在原处（drain 不删），用户可重试导入。
            runCatching {
                val ids = container.recognitionSubmitter.submitImages(files, title)
                if (ids.isNotEmpty()) {
                    navController.navigate(Routes.progress(ids.first())) {
                        popUpTo(Routes.HOME)
                    }
                }
            }.onFailure { error ->
                android.util.Log.e("MistakeBookNavHost", "批量提交识别失败", error)
            }
        }
    }
    /**
     * T2 对话连拍：把已裁成果整体交接回对话页。
     *
     * 成果在 [AppContainer.chatBatchCropped]（每裁完一张推进一张），
     * 这里一次性交给 [AppContainer.pendingChatAttachmentPaths]，
     * 由 ChatScreen 的 LaunchedEffect 逐张转成待发附件。
     * 原图与成果文件**都不删**：附件准备流程（preparer）要复制它们，
     * 删除时机交给附件生命周期的既有逻辑。
     */
    fun submitChatBatch() {
        // drainCropped 取走成果文件且**不删**（附件准备流程要复制它们）；
        // 未裁原图按契约清空（接力完成时本就应为空，兜底防泄漏）。
        container.captureBatch.drainCropped()
        val paths = container.chatBatchCropped.toList()
        container.chatBatchCropped.clear()
        if (paths.isNotEmpty()) {
            container.pendingChatAttachmentPaths = paths
        }
        // v0.0.80：对话连拍/单张裁切完成后，弹出 CROP 返回对话页
        navController.popBackStack()
    }

    /**
     * v0.0.75：submitChatBatchRaw 已删除——对话拍照恢复走裁切接力
     * （与错题识别同一套裁切/涂鸦/增强，用户反馈原图直发丢功能）。
     * 原图直发路径的收尾逻辑并入 onStartBatchCrop 的 CHAT 分支。
     */
    androidx.compose.runtime.LaunchedEffect(filterDueRequest) {
        if (filterDueRequest > 0) {
            navController.navigate(Routes.HOME) {
                popUpTo(Routes.HOME) { inclusive = false }
                launchSingleTop = true
            }
        }
    }

    // 转场统一 200/150ms fade + 纵向 4% 滑动（T3 的 MOBILE_MOTION 口径）：
    // 默认平台转场（横滑+缩放）在多级导航里显得重，fade 更「快而顺」。
    // slide 位移取 it/25：恰好提示层级方向，又不至于把两屏内容同时甩进视口。
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        enterTransition = {
            fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 25 }
        },
        exitTransition = {
            fadeOut(tween(150))
        },
        popEnterTransition = {
            fadeIn(tween(200))
        },
        popExitTransition = {
            fadeOut(tween(150)) + slideOutVertically(tween(150)) { it / 25 }
        }
    ) {

        composable(Routes.HOME) {
            MainPortalScreen(
                container = container,
                filterDue = filterDue,
                filterDueRequest = filterDueRequest,
                pickedNotebookId = homeNotebookPick,
                onNotebookPicked = { homeNotebookPick = it },
                onAddByPhoto = {
                    // T11：每个相机入口都显式设置用途，不依赖默认值——
                    // 上次从对话进来残留 CHAT 的话，这里必须扳回来
                    container.cameraPurpose = AppContainer.CameraPurpose.QUESTION
                    // F6：新拍摄会话先清掉上次可能残留的批次（兜底；
                    // 正常路径提交/丢弃时都已清空，这里是防状态泄漏）
                    container.captureBatch.clear()
                    navController.navigate(Routes.CAPTURE)
                },
                onImportPdf = { navController.navigate(Routes.PDF_IMPORT) },
                onAddManual = { navController.navigate(Routes.MANUAL_EDIT) },
                onCropImage = { path ->
                    container.cropSourceIsGallery = true
                    navController.navigate(Routes.crop(path))
                },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenPrint = { navController.navigate(Routes.PRINT) },
                onOpenNotebooks = { navController.navigate(Routes.NOTEBOOKS) },
                // 会话：**直接**开一条新对话，不再先进「对话记录」列表（v0.0.96）。
                // openFreeSession 会复用已存在的空自由会话，连点几下也只有一条空白对话。
                onNewChat = {
                    scope.launch {
                        navController.navigate(
                            Routes.chat(null, container.chatRepository.openFreeSession())
                        )
                    }
                },
                onSnapAsk = {
                    scope.launch {
                        // 拍题讲解（v0.0.98）：先建会话再拍照——裁切接力需要
                        // 一个已经存在的会话来落地成果。指令进会话、自动开相机、
                        // 拍完自动发送，三件事都由对话页按容器标志接管。
                        val id = container.chatRepository.openFreeSession()
                        container.pendingTool = PendingTool(
                            sessionId = id,
                            tool = ChatTool.PHOTO,
                            instruction = ChatToolPrompts.ASK
                        )
                        navController.navigate(Routes.chat(null, id))
                    }
                },
                onOpenQuestion = { id -> navController.navigate(Routes.detail(id)) },
                onStartReview = { navController.navigate(Routes.REVIEW) },
                // 门户设置 Tab 内嵌 SettingsScreen，资料库/成就墙两个子页面
                // 必须经 NavHost 路由打开（v0.0.52：内嵌页此前没接导航回调，
                // 两个入口点了没反应）
                onOpenLibrary = { navController.navigate(Routes.LIBRARY) },
                onOpenBadgeWall = { navController.navigate(Routes.BADGE_WALL) },
                onOpenArchivedQuestions = { navController.navigate(Routes.ARCHIVED_QUESTIONS) },
                onOpenArchivedSessions = { navController.navigate(Routes.ARCHIVED_SESSIONS) },
                onOpenArchivedTasks = { navController.navigate(Routes.ARCHIVED_TASKS) },
                onOpenMemory = { navController.navigate(Routes.MEMORY) }
            )
        }

        composable(Routes.REVIEW) {
            com.mistakebook.ui.review.ReviewScreen(
                container = container,
                onBack = { navController.popBackStack() },
                // 「去看看错题本」：首页就在栈底，返回即到
                onGoNotebook = { navController.popBackStack() }
            )
        }

        composable(Routes.NOTEBOOKS) {
            NotebookScreen(
                container = container,
                onBack = { navController.popBackStack() },
                onSelected = { notebookId ->
                    // 选中后回首页并切到该错题本筛选，用户能立刻看到筛选生效
                    navController.popBackStack()
                    homeNotebookPick = notebookId
                }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                container = container,
                onBack = { navController.popBackStack() },
                onOpenLibrary = { navController.navigate(Routes.LIBRARY) },
                onOpenBadgeWall = { navController.navigate(Routes.BADGE_WALL) }
            )
        }

        composable(Routes.MEMORY) {
            com.mistakebook.ui.settings.MemoryScreen(
                container = container,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.ARCHIVED_QUESTIONS) {
            com.mistakebook.ui.settings.ArchivedItemsScreen(
                container = container,
                kind = com.mistakebook.ui.settings.ArchivedKind.QUESTIONS,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.ARCHIVED_SESSIONS) {
            com.mistakebook.ui.settings.ArchivedItemsScreen(
                container = container,
                kind = com.mistakebook.ui.settings.ArchivedKind.SESSIONS,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.ARCHIVED_TASKS) {
            com.mistakebook.ui.settings.ArchivedItemsScreen(
                container = container,
                kind = com.mistakebook.ui.settings.ArchivedKind.POMODORO_TASKS,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.LIBRARY) {
            com.mistakebook.ui.library.LibraryScreen(
                container = container,
                filesDir = container.files.rootDir,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.BADGE_WALL) {
            com.mistakebook.ui.badge.BadgeWallScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.CAPTURE) {
            CaptureScreen(
                container = container,
                onCropped = { cropPath ->
                    // CHAT 用途：产物走「裁切 → 回对话」链路，不进识别管线（T11/T12）。
                    // 必须带 popUpTo(CAPTURE) inclusive——否则裁切确认后
                    // popBackStack 会回到相机而不是对话页（QUESTION 分支同理）。
                    if (container.cameraPurpose == AppContainer.CameraPurpose.CHAT) {
                        container.chatCropping = true
                    }
                    navController.navigate(Routes.crop(cropPath)) {
                        popUpTo(Routes.CAPTURE) { inclusive = true }
                    }
                },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onManualEntry = {
                    container.cameraPurpose = AppContainer.CameraPurpose.QUESTION
                    navController.navigate(Routes.MANUAL_EDIT)
                },
                onBack = {
                    container.cameraPurpose = AppContainer.CameraPurpose.QUESTION
                    // 从对话进来的相机被关掉：连自动发送的意图一起作废。
                    // 留着的话，用户之后手动挑一张图做附件，会被静默发出去。
                    container.pendingTool = null
                    navController.popBackStack()
                },
                onStartBatchCrop = {
                    // F6/T2：点「完成」。
                    // - QUESTION：从第一张未裁的原图开始逐张接力裁切，裁完提交识别；
                    // - CHAT（v0.0.75 恢复）：**同样进裁切接力**——对话里拍题
                    //   一样要裁掉多余背景、涂掉隐私、增强清晰度，裁完再回会话
                    //   当图片附件。v0.0.58 曾改成「原图直发」，用户反馈丢功能，
                    //   改回裁切链路（CropScreen 的 chatMode 本来就在，含涂鸦遮蔽）。
                    // （参数 lambda 里不能写 return，用 if/else 分叉。）
                    if (container.cameraPurpose == AppContainer.CameraPurpose.CHAT) {
                        val batch = container.captureBatch
                        if (batch.paths.isNotEmpty()) {
                            // 裁切页走 chatMode：成果入 chatBatchCropped，全部裁完
                            // 由 onBatchCompleted 交接回对话页
                            container.chatCropping = true
                            navController.navigate(Routes.crop(batch.paths.first())) {
                                popUpTo(Routes.CAPTURE) { inclusive = true }
                            }
                        } else {
                            // 一张没拍就点完成：直接返回，别空跑裁切页
                            navController.popBackStack()
                        }
                    } else {
                        val batch = container.captureBatch
                        if (batch.paths.isNotEmpty()) {
                            navController.navigate(Routes.crop(batch.paths.first())) {
                                popUpTo(Routes.CAPTURE) { inclusive = true }
                            }
                        } else {
                            submitCaptureBatch()
                        }
                    }
                }
            )
        }

        composable(
            route = "${Routes.CROP}/{imagePath}",
            arguments = listOf(navArgument("imagePath") { type = NavType.StringType })
        ) { entry ->
            val recrop = container.recropping
            // 重新识别（先裁切再识别）：详情页入口置上的题目 id。
            // 与 recrop 互斥——前者要提交识别，后者只换图。
            val reRecognizeQuestionId = container.reRecognizeQuestionId
            val chatCropping = container.chatCropping
            // F6：连拍批次模式。批次只从相机 QUESTION 链路来，与这三个模式
            // 天然互斥；若批次偏偏非空，是状态泄漏——清掉防接力卡死
            // （带着幽灵批次会让每张裁切完都导航去「下一张」死循环）。
            val batchHasPending = container.captureBatch.hasPending
            // T2 对话连拍：chatCropping + 批次是合法组合（会话多图接力裁切）；
            // 与 recrop/reRecognize 仍互斥（F6 的冲突守卫保留）。
            val batchMode = batchHasPending &&
                !recrop && reRecognizeQuestionId == null
            if (batchHasPending && !batchMode) {
                Log.w(
                    "MistakeBookNavHost",
                    "裁切页模式标志冲突: 连拍批次与 recrop/reRecognize/chat 同时激活，已丢弃批次"
                )
                container.captureBatch.clear()
            }
            // 三个模式标志互斥（T11）。同时为真视为 bug：按
            // recropOnly > reRecognize > chatCropping 的优先级取第一个，
            // 其余清掉并留日志——静默择一会把产物送进错误的链路。
            if (listOf(recrop, reRecognizeQuestionId != null).count { it } > 1) {
                Log.w(
                    "MistakeBookNavHost",
                    "裁切页模式标志冲突: recropOnly=$recrop reRecognize=$reRecognizeQuestionId chatCropping=$chatCropping"
                )
                if (recrop) {
                    container.reRecognizeQuestionId = null
                    container.chatCropping = false
                } else if (reRecognizeQuestionId != null) {
                    container.chatCropping = false
                }
            }
            CropScreen(
                container = container,
                imagePath = entry.arguments?.getString("imagePath").orEmpty(),
                recropOnly = recrop,
                reRecognizeQuestionId = reRecognizeQuestionId,
                chatMode = chatCropping,
                batchMode = batchMode,
                onBatchNext = { next ->
                    // 接力裁下一张：区分 CHAT 与 QUESTION
                    if (container.cameraPurpose == AppContainer.CameraPurpose.CHAT) {
                        navController.navigate(Routes.crop(next)) {
                            popUpTo(Routes.crop(entry.arguments?.getString("imagePath").orEmpty())) {
                                inclusive = true
                            }
                        }
                    } else {
                        navController.navigate(Routes.crop(next)) {
                            popUpTo(Routes.HOME) { inclusive = false }
                        }
                    }
                },
                onBatchCompleted = {
                    // QUESTION 批次 → 识别；CHAT 批次（T2）→ 交接回对话页
                    if (container.cameraPurpose == AppContainer.CameraPurpose.CHAT) {
                        submitChatBatch()
                    } else {
                        submitCaptureBatch()
                    }
                },
                onBatchSkip = {
                    // 当前张原图丢失：出队跳过，不阻塞其余
                    container.captureBatch.dropCurrent()
                    val next = container.captureBatch.paths.firstOrNull()
                    if (next != null) {
                        val isChat = container.cameraPurpose == AppContainer.CameraPurpose.CHAT
                        navController.navigate(Routes.crop(next)) {
                            if (isChat) {
                                popUpTo(Routes.CHAT) { inclusive = false }
                            } else {
                                popUpTo(Routes.HOME)
                            }
                        }
                    } else if (container.captureBatch.cropped.isNotEmpty()) {
                        // 全部跳到只剩已裁成果，直接收尾：QUESTION → 识别，CHAT → 交接
                        if (container.cameraPurpose == AppContainer.CameraPurpose.CHAT) {
                            submitChatBatch()
                        } else {
                            submitCaptureBatch()
                        }
                    } else if (container.cameraPurpose == AppContainer.CameraPurpose.CHAT &&
                        container.chatBatchCropped.isNotEmpty()
                    ) {
                        // T2：本张跳过但前面已有裁好的 → 交接回会话
                        submitChatBatch()
                    } else {
                        // 一张都没裁成：回相机页重新来
                        navController.navigate(Routes.CAPTURE)
                    }
                },
                onChatConfirmed = {
                    val popped = navController.popBackStack(Routes.CHAT, inclusive = false)
                    if (!popped) {
                        navController.popBackStack()
                    }
                },
                onRecropped = { newPath ->
                    container.recroppedImagePath = newPath
                    container.recropping = false
                    navController.popBackStack()
                },
                onConfirmed = { taskIds ->
                    container.recropping = false
                    container.reRecognizeQuestionId = null
                    container.chatCropping = false
                    navController.navigate(Routes.progress(taskIds.first())) {
                        popUpTo(Routes.HOME)
                    }
                },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onManualEntry = { navController.navigate(Routes.MANUAL_EDIT) },
                onBack = {
                    container.recropping = false
                    container.reRecognizeQuestionId = null
                    // 聊天附件模式同样要在退出时清（T11）：不清的话下一次
                    // 正常拍题的裁切确认会被当成「回对话」而丢失产物
                    container.chatCropping = false
                    // 用户中途退出裁剪页时必须清掉来源标记，
                    // 否则下一次拍照提交会被错标成「相册导入」
                    container.cropSourceIsGallery = false
                    if (batchMode) {
                        // F6：当前张没裁完，出队（计数减 1），回相机页继续攒批。
                        // 先 pop 回 HOME 再压 CAPTURE——CROP 进栈时 CAPTURE
                        // 已被 popUpTo 弹掉，直接 popBackStack 只会回首页。
                        container.captureBatch.dropCurrent()
                        navController.popBackStack()
                        navController.navigate(Routes.CAPTURE)
                    } else {
                        navController.popBackStack()
                    }
                }
            )
        }

        composable(
            route = "${Routes.PROGRESS}/{taskId}",
            arguments = listOf(navArgument("taskId") { type = NavType.LongType })
        ) { entry ->
            val taskId = entry.arguments?.getLong("taskId") ?: 0L
            ProgressScreen(
                container = container,
                taskId = taskId,
                onBack = { navController.popBackStack() },
                onEdit = { id ->
                    navController.navigate(Routes.edit(id)) {
                        popUpTo(Routes.HOME)
                    }
                },
                onManualEntry = {
                    // 失败的任务留在进度页返回栈里，用户手动录完题
                    // 直接回首页——那个失败任务没有产出任何东西，留着只是垃圾
                    navController.navigate(Routes.MANUAL_EDIT) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) }
            )
        }

        composable(
            route = "${Routes.EDIT}/{taskId}?index={index}",
            arguments = listOf(
                navArgument("taskId") { type = NavType.LongType },
                navArgument("index") { type = NavType.IntType; defaultValue = 0 }
            )
        ) { entry ->
            EditScreen(
                container = container,
                taskId = entry.arguments?.getLong("taskId") ?: 0L,
                initialIndex = entry.arguments?.getInt("index") ?: 0,
                onRecrop = { path ->
                    // 重新裁切：只换图不识别。EditScreen 自己也会置这个标志，
                    // 重复置无害；QuestionEditScreen 没有置，这里统一补上。
                    container.recropping = true
                    navController.navigate(Routes.crop(path))
                },
                onBack = { navController.popBackStack() },
                onSaved = { questionId ->
                    navController.navigate(Routes.detail(questionId)) {
                        popUpTo(Routes.HOME)
                    }
                }
            )
        }

        /**
         * 手动录入：复用编辑页，传 null 表示「没有识别任务」。
         * 入口有三处：首页添加面板、识别失败页、Key 门禁对话框。
         */
        composable(Routes.MANUAL_EDIT) {
            EditScreen(
                container = container,
                taskId = null,
                initialIndex = 0,
                onRecrop = { },
                onBack = { navController.popBackStack() },
                onSaved = { questionId ->
                    navController.navigate(Routes.detail(questionId)) {
                        popUpTo(Routes.HOME)
                    }
                }
            )
        }

        composable(
            route = "${Routes.DETAIL}/{questionId}",
            arguments = listOf(navArgument("questionId") { type = NavType.LongType })
        ) { entry ->
            DetailScreen(
                container = container,
                questionId = entry.arguments?.getLong("questionId") ?: 0L,
                onBack = { navController.popBackStack() },
                onEdit = { id -> navController.navigate(Routes.questionEdit(id)) },
                // 重新识别：确认后先到裁剪页让用户自己裁切/涂鸦，
                // 裁完才提交识别（questionId 标志告诉裁剪页这是重新识别）。
                onReRecognizeWithCrop = { path ->
                    container.reRecognizeQuestionId = entry.arguments?.getLong("questionId") ?: 0L
                    navController.navigate(Routes.crop(path))
                },
                // 重新识别提交成功后跳进度页，让用户看着它跑完。
                // 之前调的是 onBack()，用户点完就「回到主页」，完全不知道任务已经提交了。
                onOpenChat = { questionId -> navController.navigate(Routes.chat(questionId)) },
                onReRecognize = { taskId ->
                    navController.navigate(Routes.progress(taskId)) {
                        popUpTo(Routes.HOME)
                    }
                },
                // 变式题存为错题后跳新题详情：栈上压在新题页，返回即回原题，
                // 「看完变式回原题再出一道」的动线不需要重新找题
                onVariantSaved = { newId ->
                    navController.navigate(Routes.detail(newId))
                }
            )
        }

        composable(
            route = "${Routes.QUESTION_EDIT}/{questionId}",
            arguments = listOf(navArgument("questionId") { type = NavType.LongType })
        ) { entry ->
            QuestionEditScreen(
                container = container,
                questionId = entry.arguments?.getLong("questionId") ?: 0L,
                onRecrop = { path ->
                    // 重新裁切：只换图不识别（v0.0.12 修复：此前漏置 recropping，
                    // 编辑页点「重新裁切」会走完整识别流程，裁好的图被当成新题提交）
                    container.recropping = true
                    navController.navigate(Routes.crop(path))
                },
                onBack = { navController.popBackStack() }
            )
        }

        /**
         * AI 对话。
         *
         * `questionId = -1` 表示自由会话。这里必须用哨兵值而不是可选参数：
         * 导航参数的 null 与「没传」分不清，而 0 是数据库自增起点，
         * 拿它当「无」会在极端情况下撞上一条 id=0 的题目。
         *
         * `sessionId` 是可选的（`-1` 表示没指定）：**从对话记录点进来时必须带上**。
         * 只靠 questionId 定位不到已有的自由会话——那条记录的 questionId 本来就是 null，
         * ViewModel 只能去找「空的自由会话」，找不到就新建一条空的，
         * 于是点进去是一片空白，且每点一次多插一条孤儿会话。
         */
        composable(
            route = "${Routes.CHAT}/{questionId}?sessionId={sessionId}",
            arguments = listOf(
                navArgument("questionId") { type = NavType.LongType },
                navArgument("sessionId") {
                    type = NavType.LongType
                    defaultValue = Routes.CHAT_FREE
                }
            )
        ) { entry ->
            val raw = entry.arguments?.getLong("questionId") ?: Routes.CHAT_FREE
            val rawSession = entry.arguments?.getLong("sessionId") ?: Routes.CHAT_FREE
            ChatScreen(
                container = container,
                questionId = raw.takeIf { it != Routes.CHAT_FREE },
                sessionId = rawSession.takeIf { it != Routes.CHAT_FREE },
                onBack = {
                    // 临时对话退出即删（v0.0.96）。判断放在这里而不是 ChatScreen：
                    // 删除是异步的，而 pop 之后 ChatScreen 的 rememberCoroutineScope
                    // 立刻被取消——在这里删（NavHost 的组合作用域比它活得久）才删得完。
                    val leaving = rawSession.takeIf { it != Routes.CHAT_FREE }
                    // 退出时把这条会话的功能入口交接包作废：
                    // 留着的话，下次进这条会话会凭空再弹一次相机
                    container.pendingTool?.let { mine ->
                        if (mine.sessionId == leaving) container.pendingTool = null
                    }
                    if (leaving == null) {
                        popToHome()
                    } else {
                        scope.launch {
                            runCatching { container.chatRepository.archiveTemporaryOnExit(leaving) }
                            // 一条消息都没发过就退出：这条会话整条删掉，不留记录。
                            // 草稿也一起清——它绑在一条已经不存在的会话上，留着只是垃圾。
                            runCatching {
                                if (container.chatRepository.discardIfEmpty(leaving)) {
                                    container.settingsStore.setChatDraft(leaving, "")
                                }
                            }
                            popToHome()
                        }
                    }
                },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenCamera = {
                    // v0.0.57：对话拍照复用 App 内置相机（与错题扫描同一套）。
                    // 用途显式设为 CHAT：连拍确认后回填 pendingChatAttachmentPaths，
                    // ChatScreen 的 LaunchedEffect 自动转成当轮附件。
                    container.cameraPurpose = AppContainer.CameraPurpose.CHAT
                    container.captureBatch.clear()
                    navController.navigate(Routes.CAPTURE)
                },
                onOpenSession = { sessionId, questionId ->
                    navController.navigate(Routes.chat(questionId, sessionId))
                },
                onNewTemporaryConversation = {
                    // 临时对话是**全新的一条**（不复用空会话）：退出时整条删掉，
                    // 复用别人的会话会连同别人的记录一起删掉。
                    scope.launch {
                        val id = container.chatRepository.openTemporarySession()
                        navController.navigate(Routes.chat(null, id))
                    }
                }
            )
        }

        composable(Routes.PRINT) {
            PrintScreen(container = container, onBack = { navController.popBackStack() })
        }

        composable(Routes.PDF_IMPORT) {
            PdfImportScreen(
                container = container,
                onStarted = {
                    val ids = container.lastImportTaskIds
                    if (ids.isNotEmpty()) {
                        navController.navigate(Routes.progress(ids.first())) {
                            popUpTo(Routes.HOME)
                        }
                    } else {
                        navController.popBackStack()
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }
    }
}

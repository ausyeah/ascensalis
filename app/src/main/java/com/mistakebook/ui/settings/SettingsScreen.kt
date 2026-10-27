package com.mistakebook.ui.settings

import android.os.Process
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mistakebook.R
import com.mistakebook.ui.library.LibraryEntrySection
import com.mistakebook.ui.badge.BadgeEntrySection
import com.mistakebook.di.AppContainer
import com.mistakebook.review.ReviewScheduler
import com.mistakebook.ui.common.containerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 设置页：Key 与大模型配置、识别参数、打印默认项、复习提醒、数据管理。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    /** 资料库管理页入口（T6）：设置页分区点进去。 */
    onOpenLibrary: () -> Unit = {},
    /** 成就墙入口（T10）。 */
    onOpenBadgeWall: () -> Unit = {},
    /**
     * 内嵌模式（门户第 4 页）：不画自己的顶栏与返回箭头——
     * 门户顶部 Tab 行就是它的导航，从任何一页都能一步切回来。
     * 同时把自己那层 Scaffold 的系统栏内边距清零：门户的顶栏
     * 已经消费过状态栏 inset，再收一次会把内容往下顶出双倍间距。
     */
    embedded: Boolean = false
) {
    val viewModel: SettingsViewModel = containerViewModel(container) { SettingsViewModel(it) }
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var showAddProfile by remember { mutableStateOf(false) }
    var showRestoreConfirm by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    val restoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val stream = context.contentResolver.openInputStream(uri)
            if (stream == null) {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.settings_restore_failed, "")
                )
                return@launch
            }
            val result = container.backupManager.restore(stream)
            result.onSuccess { count ->
                snackbarHostState.showSnackbar(
                    context.getString(R.string.settings_restore_done, count)
                )
                delay(1_500)
                Process.killProcess(Process.myPid())
            }
            result.onFailure { error ->
                snackbarHostState.showSnackbar(
                    context.getString(R.string.settings_restore_failed, error.message.orEmpty())
                )
            }
        }
    }

    // 测试结果**常驻显示**，不再用 snackbar。
    // snackbar 几秒就消失，用户往往还没看清「首字响应 xxx ms」就被划走了；
    // 而这类信息本来就需要对照着看（比如两次测试比速度）。
    // modelsError 仍走 snackbar：那类错误处理完就不用再看。
    LaunchedEffect(state.modelsError) {
        state.modelsError?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.consumeModels()
        }
    }
    LaunchedEffect(state.backupMessage) {
        state.backupMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.consumeBackupMessage()
        }
    }

    Scaffold(
        topBar = {
            // 内嵌模式下顶栏由门户提供，这里连占位都不留
            if (!embedded) {
                TopAppBar(
                    title = { Text(stringResource(R.string.settings_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back)
                            )
                        }
                    }
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = if (embedded) {
            androidx.compose.foundation.layout.WindowInsets(0.dp, 0.dp, 0.dp, 0.dp)
        } else {
            androidx.compose.material3.ScaffoldDefaults.contentWindowInsets
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // T8 重构：全部分组默认收起（CollapsibleSettingsGroup 手风琴卡片），
            // 一屏看全目录；组内内容原样复用既有 Section 组件，功能零改动。
            // 顺序：AI 服务 → 学习 → 资料库 → 用量与账户 → 网络（高级）→ 数据与关于。
            // 各组现有设置项清单（重构后一项不丢）：
            //   AI 服务: 主题、识别方式、MinerU Key、MinerU 参数、大模型配置（手风琴
            //     互斥：大模型/识图/生图/联网搜索）、推理档位
            //   学习: 番茄钟、复习提醒、打印默认
            //   资料库: 入口行
            //   用量与账户: LLM 用量、MinerU 用量
            //   网络与代理（高级）: 网络代理
            //   数据与关于: 数据管理（备份/恢复）、单词书数据重置

            // ---- AI 服务（手风琴互斥：同一时刻只展开一张配置卡）----
            CollapsibleSettingsGroup(
                title = "主题外观",
                exclusiveScope = "ai"
            ) {
                ThemeModeSection(
                    state = state,
                    viewModel = viewModel
                )
            }

            CollapsibleSettingsGroup(
                title = "识别方式",
                exclusiveScope = "ai"
            ) {
                RecognitionModeSection(
                    state = state,
                    viewModel = viewModel
                )
            }

            CollapsibleSettingsGroup(
                title = "MinerU 配置",
                exclusiveScope = "ai"
            ) {
                MineruKeySection(
                    state = state,
                    viewModel = viewModel
                )
                Spacer(Modifier.height(12.dp))
                MineruParamsSection(
                    state = state,
                    viewModel = viewModel
                )
            }

            CollapsibleSettingsGroup(
                title = "大模型配置",
                exclusiveScope = "ai"
            ) {
                LlmProfilesSection(
                    state = state,
                    viewModel = viewModel,
                    onAddProfileClick = { showAddProfile = true }
                )
                Spacer(Modifier.height(12.dp))
                ReasoningEffortSection(
                    state = state,
                    viewModel = viewModel
                )
            }

            CollapsibleSettingsGroup(
                title = "识图模型",
                exclusiveScope = "ai"
            ) {
                VisionProfilesSection(
                    state = state,
                    viewModel = viewModel
                )
            }

            CollapsibleSettingsGroup(
                title = "生图渠道",
                exclusiveScope = "ai"
            ) {
                ImageProfilesSection(
                    state = state,
                    viewModel = viewModel
                )
            }

            CollapsibleSettingsGroup(
                title = "联网搜索",
                exclusiveScope = "ai"
            ) {
                SearchProviderSection(
                    state = state,
                    viewModel = viewModel
                )
            }

            // ---- 学习 ----
            CollapsibleSettingsGroup(title = "学习") {
                // 成就墙入口放设置「学习」组（而非首页图标行）：
                // 首页图标行每一寸都要留给刷题主流程入口（用户每天进来
                // 是为了做题，不是为了看徽章），成就墙是阶段性回访目标，
                // 与番茄钟/复习提醒同属「学习」分组更合理。
                LibraryEntrySection(onOpen = onOpenLibrary)
                Spacer(Modifier.height(12.dp))
                BadgeEntrySection(onOpen = onOpenBadgeWall)
                Spacer(Modifier.height(12.dp))
                PomodoroSection(
                    state = state,
                    viewModel = viewModel
                )
                Spacer(Modifier.height(12.dp))
                ReviewReminderSection(
                    state = state,
                    viewModel = viewModel,
                    onPickTimeClick = { showTimePicker = true }
                )
            }

            CollapsibleSettingsGroup(title = "打印默认") {
                PrintDefaultsSection(
                    state = state,
                    viewModel = viewModel
                )
            }

            // ---- 资料库（T6）----
            CollapsibleSettingsGroup(title = stringResource(R.string.settings_library_section)) {
                LibraryEntrySection(onOpen = onOpenLibrary)
            }

            // ---- 用量与账户 ----
            CollapsibleSettingsGroup(title = "用量统计") {
                LlmUsageSection(container = container)
                Spacer(Modifier.height(12.dp))
                MineruUsageSection(container = container)
            }

            // ---- 网络与代理（高级）----
            CollapsibleSettingsGroup(title = "网络与代理（高级）") {
                NetworkProxySection(
                    state = state,
                    viewModel = viewModel
                )
            }

            // ---- 数据与关于 ----
            CollapsibleSettingsGroup(title = "数据与备份") {
                DataManageSection(
                    container = container,
                    viewModel = viewModel,
                    onExportClick = {
                        scope.launch {
                            val result = container.backupManager.export()
                            result.onSuccess { path ->
                                snackbarHostState.showSnackbar(
                                    context.getString(R.string.settings_backup_exported) + "：" + path
                                )
                            }
                            result.onFailure { error ->
                                snackbarHostState.showSnackbar(
                                    context.getString(
                                        R.string.settings_backup_failed,
                                        error.message.orEmpty()
                                    )
                                )
                            }
                        }
                    },
                    onRestoreClick = { showRestoreConfirm = true }
                )
            }

            CollapsibleSettingsGroup(title = "单词书数据") {
                // 英语单词数据重置：背单词页自己没有重置入口（刷题页每一寸
                // 都要留给题目），统一收到设置里，与数据管理相邻。
                WordbookDataSection(
                    container = container,
                    onResetDone = { message ->
                        scope.launch { snackbarHostState.showSnackbar(message) }
                    }
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showTimePicker) {
        SettingsTimePickerDialog(
            initialHour = state.snapshot.reminderHour,
            initialMinute = state.snapshot.reminderMinute,
            onDismiss = { showTimePicker = false },
            onConfirm = { hour, minute ->
                viewModel.setReminderTime(hour, minute)
                showTimePicker = false
                scope.launch {
                    ReviewScheduler.schedule(
                        context,
                        hour,
                        minute
                    )
                    snackbarHostState.showSnackbar(
                        "已设为 %02d:%02d".format(hour, minute)
                    )
                }
            }
        )
    }

    if (showAddProfile) {
        AddProfileDialog(
            onDismiss = { showAddProfile = false },
            onConfirm = { profile ->
                viewModel.addProfile(profile)
                showAddProfile = false
            }
        )
    }

    if (showRestoreConfirm) {
        SettingsRestoreConfirmDialog(
            onDismiss = { showRestoreConfirm = false },
            onConfirm = {
                showRestoreConfirm = false
                restoreLauncher.launch(arrayOf("application/zip"))
            }
        )
    }
}

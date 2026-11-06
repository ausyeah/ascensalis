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
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
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
    /** 记忆库入口（v0.0.108）。 */
    onOpenMemory: () -> Unit = {},
    /** 已归档题目入口（v0.0.104）。 */
    onOpenArchivedQuestions: () -> Unit = {},
    /** 已归档会话入口（v0.0.104）。 */
    onOpenArchivedSessions: () -> Unit = {},
    /** 已归档番茄钟任务入口（v0.0.126，issue #37）。 */
    onOpenArchivedTasks: () -> Unit = {},
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

    val restoreDoneCount by viewModel.restoreDoneCount.collectAsState()
    val restoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // 恢复全程在 SettingsViewModel（viewModelScope + NonCancellable 落库段）：
        // 原实现在这里 scope.launch——导航离开取消协程，restore 覆盖库文件
        // 中途被打断 = 半恢复 = 库损坏。
        viewModel.restoreBackup(uri, context.contentResolver)
    }
    // 恢复成功：让用户看清提示再杀进程换库。恢复完成的信号走独立通道，
    // 与普通 backupMessage 区分（后者只弹条不杀进程）。
    LaunchedEffect(restoreDoneCount) {
        val count = restoreDoneCount ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(context.getString(R.string.settings_restore_done, count))
        viewModel.consumeRestoreDone()
        delay(1_500)
        Process.killProcess(Process.myPid())
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
            // v0.0.54：设置页改成**三层目录**——分类卡（3 个，全局互斥）
            // → 类内分组（沿用 CollapsibleSettingsGroup，全局互斥）→ 具体设置项。
            //
            // 为什么要多这一层：平铺 12 个等价的折叠卡时，用户仍要读完
            // 每个标题才知道该点哪个，这是「臃肿、难理解」的真正来源。
            // 分类卡带一句摘要 + 图标，把「找设置」压成两步：
            // 3 选 1 定大类 → 类内再选分组。
            //
            // 分类与分组映射（一项不丢，v0.0.124 重组）：
            //   AI 与识别: 识别方式、OCR 配置、大模型配置、识图模型、
            //     生图渠道、联网搜索、记忆
            //   学习: 学习工具（资料库/成就墙）、背单词、番茄钟、复习提醒、
            //     打印默认——背单词与番茄钟从「学习工具」里拆出来独立成组，
            //     用户点名「功能很多但不好找」，一级分组名直接可见才好找
            //   数据: 数据与备份、已归档内容、单词书数据、番茄钟数据——
            //     数据类入口原来散在「通用」卡的三处，收拢到一张卡
            //   通用: 主题外观、用量统计、网络与代理（高级）、关于与更新
            SettingsCategoryCard(
                title = stringResource(R.string.settings_group_ai),
                summary = "大模型、识图、生图、搜索的 Key 与参数",
                icon = Icons.Default.Psychology
            ) {
                CollapsibleSettingsGroup(title = stringResource(R.string.settings_group_recognition_mode), nested = true) {
                    RecognitionModeSection(state = state, viewModel = viewModel)
                }

                CollapsibleSettingsGroup(title = stringResource(R.string.settings_group_ocr), nested = true) {
                    MineruKeySection(state = state, viewModel = viewModel)
                    Spacer(Modifier.height(12.dp))
                    MineruParamsSection(state = state, viewModel = viewModel)
                }

                CollapsibleSettingsGroup(title = stringResource(R.string.settings_group_llm), nested = true) {
                    LlmProfilesSection(
                        state = state,
                        viewModel = viewModel,
                        onAddProfileClick = { showAddProfile = true }
                    )
                    Spacer(Modifier.height(12.dp))
                    ReasoningEffortSection(state = state, viewModel = viewModel)
                }

                CollapsibleSettingsGroup(title = stringResource(R.string.settings_group_vision_model), nested = true) {
                    VisionProfilesSection(state = state, viewModel = viewModel)
                }

                CollapsibleSettingsGroup(title = stringResource(R.string.settings_group_image_gen), nested = true) {
                    ImageProfilesSection(state = state, viewModel = viewModel)
                }

                CollapsibleSettingsGroup(title = stringResource(R.string.settings_group_websearch), nested = true) {
                    SearchProviderSection(state = state, viewModel = viewModel)
                }

                CollapsibleSettingsGroup(title = stringResource(R.string.settings_group_memory), nested = true) {
                    // 记忆是「让模型认识这个用户」的地方，跟模型配置同属 AI 一侧；
                    // 放进通用组的话，用户会以为它和数据备份是一类东西。
                    SettingsEntryRow(
                        titleRes = R.string.settings_memory_entry,
                        icon = Icons.Default.Memory,
                        onOpen = onOpenMemory
                    )
                }
            }

            SettingsCategoryCard(
                title = stringResource(R.string.settings_group_learning),
                summary = "背单词、番茄钟、复习提醒、打印与学习资料",
                icon = Icons.AutoMirrored.Filled.MenuBook
            ) {
                CollapsibleSettingsGroup(title = stringResource(R.string.settings_group_learning_tools), nested = true) {
                    // 成就墙入口放设置「学习」组（而非首页图标行）：
                    // 首页图标行每一寸都要留给刷题主流程入口（用户每天进来
                    // 是为了做题，不是为了看徽章），成就墙是阶段性回访目标。
                    LibraryEntrySection(onOpen = onOpenLibrary)
                    Spacer(Modifier.height(12.dp))
                    BadgeEntrySection(onOpen = onOpenBadgeWall)
                }

                // v0.0.124：背单词 / 番茄钟从「学习工具」拆出独立分组——
                // 折在二级组里时用户得挨个展开才知道在哪，一级名直接可见。
                CollapsibleSettingsGroup(title = stringResource(R.string.settings_group_wordbook), nested = true) {
                    WordbookSection(state = state, viewModel = viewModel)
                }

                CollapsibleSettingsGroup(title = stringResource(R.string.settings_group_pomodoro), nested = true) {
                    PomodoroSection(state = state, viewModel = viewModel)
                }

                CollapsibleSettingsGroup(title = stringResource(R.string.settings_group_review_reminder), nested = true) {
                    ReviewReminderSection(
                        state = state,
                        viewModel = viewModel,
                        onPickTimeClick = { showTimePicker = true }
                    )
                }

                CollapsibleSettingsGroup(title = stringResource(R.string.settings_group_print_defaults), nested = true) {
                    PrintDefaultsSection(state = state, viewModel = viewModel)
                }
            }

            // v0.0.124：数据类入口收拢成第四张卡——备份、归档、两类
            // 数据重置原来散在「通用」卡的三处，找哪个都要先想起它在哪。
            SettingsCategoryCard(
                title = stringResource(R.string.settings_group_data),
                summary = "备份、归档与数据重置",
                icon = Icons.Default.Storage
            ) {
                CollapsibleSettingsGroup(title = stringResource(R.string.settings_group_data_backup), nested = true) {
                    DataManageSection(
                        container = container,
                        viewModel = viewModel,
                        onExportClick = { viewModel.exportBackup() },
                        onRestoreClick = { showRestoreConfirm = true }
                    )
                }

                CollapsibleSettingsGroup(title = stringResource(R.string.settings_group_archived), nested = true) {
                    // 归档不是删除：主列表里「删掉一道题」现在只是把它收起来，
                    // 这里就是把它再拿回来的地方。
                    SettingsEntryRow(
                        titleRes = R.string.settings_archived_questions,
                        icon = Icons.Default.Archive,
                        onOpen = onOpenArchivedQuestions
                    )
                    Spacer(Modifier.height(8.dp))
                    SettingsEntryRow(
                        titleRes = R.string.settings_archived_sessions,
                        icon = Icons.Default.Forum,
                        onOpen = onOpenArchivedSessions
                    )
                    Spacer(Modifier.height(8.dp))
                    // v0.0.126（issue #37）：番茄钟任务也能归档，但归档后
                    // 原本没有任何界面能看到它——用户「找不到、不能恢复」。
                    SettingsEntryRow(
                        titleRes = R.string.settings_archived_tasks,
                        icon = Icons.Default.Timer,
                        onOpen = onOpenArchivedTasks
                    )
                }

                CollapsibleSettingsGroup(title = stringResource(R.string.settings_group_wordbook_data), nested = true) {
                    // 英语单词数据重置：背单词页自己没有重置入口（刷题页每一寸
                    // 都要留给题目），统一收到设置里，与数据管理相邻。
                    WordbookDataSection(viewModel = viewModel)
                }

                CollapsibleSettingsGroup(title = stringResource(R.string.settings_group_pomodoro_data), nested = true) {
                    // 番茄钟数据重置（v0.0.124）：统计页有「重置统计数据」，
                    // 但入口埋在图表下面；这里与单词书数据同款交互，
                    // 「重置今日记录」+「全部重置」两步确认。
                    PomodoroDataSection(viewModel = viewModel)
                }
            }

            SettingsCategoryCard(
                title = stringResource(R.string.settings_group_general),
                summary = "外观、用量、网络与关于",
                icon = Icons.Default.Settings
            ) {
                CollapsibleSettingsGroup(title = stringResource(R.string.settings_group_appearance), nested = true) {
                    ThemeModeSection(state = state, viewModel = viewModel)
                    Spacer(Modifier.height(12.dp))
                    TextStyleSection(state = state, viewModel = viewModel)
                }

                CollapsibleSettingsGroup(title = stringResource(R.string.settings_group_usage), nested = true) {
                    LlmUsageSection(container = container)
                    Spacer(Modifier.height(12.dp))
                    MineruUsageSection(container = container)
                }

                CollapsibleSettingsGroup(title = stringResource(R.string.settings_group_network), nested = true) {
                    NetworkProxySection(state = state, viewModel = viewModel)
                }

                CollapsibleSettingsGroup(title = stringResource(R.string.settings_group_about), nested = true) {
                    // 版本号 / 检查更新 / 去 star
                    AboutUpdateSection()
                }
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

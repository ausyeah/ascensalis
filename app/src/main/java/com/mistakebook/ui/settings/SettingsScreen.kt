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
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
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
                // bump 背单词纪元：万一进程没死（ROM 拦截 killProcess），
                // 词库页的内存缓存也会立刻重建，不会拿旧进度写回新库。
                container.wordbookEpoch.value += 1
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
            // v0.0.54：设置页改成**三层目录**——分类卡（3 个，全局互斥）
            // → 类内分组（沿用 CollapsibleSettingsGroup，全局互斥）→ 具体设置项。
            //
            // 为什么要多这一层：平铺 12 个等价的折叠卡时，用户仍要读完
            // 每个标题才知道该点哪个，这是「臃肿、难理解」的真正来源。
            // 分类卡带一句摘要 + 图标，把「找设置」压成两步：
            // 3 选 1 定大类 → 类内再选分组。
            //
            // 分类与分组映射（一项不丢）：
            //   AI 与识别: 识别方式、MinerU 配置、大模型配置、识图模型、
            //     生图渠道、联网搜索
            //   学习: 学习工具（资料库/成就墙/番茄钟）、复习提醒、打印默认
            //   通用: 主题外观、用量统计、网络与代理（高级）、数据与备份、
            //     单词书数据
            SettingsCategoryCard(
                title = "AI 与识别",
                summary = "大模型、识图、生图、搜索的 Key 与参数",
                icon = Icons.Default.Psychology
            ) {
                CollapsibleSettingsGroup(title = "识别方式", nested = true) {
                    RecognitionModeSection(state = state, viewModel = viewModel)
                }

                CollapsibleSettingsGroup(title = "MinerU 配置", nested = true) {
                    MineruKeySection(state = state, viewModel = viewModel)
                    Spacer(Modifier.height(12.dp))
                    MineruParamsSection(state = state, viewModel = viewModel)
                }

                CollapsibleSettingsGroup(title = "大模型配置", nested = true) {
                    LlmProfilesSection(
                        state = state,
                        viewModel = viewModel,
                        onAddProfileClick = { showAddProfile = true }
                    )
                    Spacer(Modifier.height(12.dp))
                    ReasoningEffortSection(state = state, viewModel = viewModel)
                }

                CollapsibleSettingsGroup(title = "识图模型", nested = true) {
                    VisionProfilesSection(state = state, viewModel = viewModel)
                }

                CollapsibleSettingsGroup(title = "生图渠道", nested = true) {
                    ImageProfilesSection(state = state, viewModel = viewModel)
                }

                CollapsibleSettingsGroup(title = "联网搜索", nested = true) {
                    SearchProviderSection(state = state, viewModel = viewModel)
                }
            }

            SettingsCategoryCard(
                title = "学习",
                summary = "番茄钟、复习提醒、打印与学习资料",
                icon = Icons.AutoMirrored.Filled.MenuBook
            ) {
                CollapsibleSettingsGroup(title = "学习工具", nested = true) {
                    // 成就墙入口放设置「学习」组（而非首页图标行）：
                    // 首页图标行每一寸都要留给刷题主流程入口（用户每天进来
                    // 是为了做题，不是为了看徽章），成就墙是阶段性回访目标，
                    // 与番茄钟/复习提醒同属「学习」分组更合理。
                    LibraryEntrySection(onOpen = onOpenLibrary)
                    Spacer(Modifier.height(12.dp))
                    BadgeEntrySection(onOpen = onOpenBadgeWall)
                    Spacer(Modifier.height(12.dp))
                    PomodoroSection(state = state, viewModel = viewModel)
                }

                CollapsibleSettingsGroup(title = "复习提醒", nested = true) {
                    ReviewReminderSection(
                        state = state,
                        viewModel = viewModel,
                        onPickTimeClick = { showTimePicker = true }
                    )
                }

                CollapsibleSettingsGroup(title = "打印默认", nested = true) {
                    PrintDefaultsSection(state = state, viewModel = viewModel)
                }
            }

            SettingsCategoryCard(
                title = "通用",
                summary = "外观、用量、网络与数据备份",
                icon = Icons.Default.Settings
            ) {
                CollapsibleSettingsGroup(title = "主题外观", nested = true) {
                    ThemeModeSection(state = state, viewModel = viewModel)
                }

                CollapsibleSettingsGroup(title = "用量统计", nested = true) {
                    LlmUsageSection(container = container)
                    Spacer(Modifier.height(12.dp))
                    MineruUsageSection(container = container)
                }

                CollapsibleSettingsGroup(title = "网络与代理（高级）", nested = true) {
                    NetworkProxySection(state = state, viewModel = viewModel)
                }

                CollapsibleSettingsGroup(title = "数据与备份", nested = true) {
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

                CollapsibleSettingsGroup(title = "单词书数据", nested = true) {
                    // 英语单词数据重置：背单词页自己没有重置入口（刷题页每一寸
                    // 都要留给题目），统一收到设置里，与数据管理相邻。
                    WordbookDataSection(
                        container = container,
                        onResetDone = { message ->
                            scope.launch { snackbarHostState.showSnackbar(message) }
                        }
                    )
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

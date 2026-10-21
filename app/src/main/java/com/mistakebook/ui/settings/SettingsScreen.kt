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
import com.mistakebook.di.AppContainer
import com.mistakebook.review.ReviewScheduler
import com.mistakebook.ui.common.containerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 设置页：Key 与大模型配置、识别参数、打印默认项、复习提醒、数据管理。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val viewModel: SettingsViewModel = containerViewModel(container) { SettingsViewModel(it) }
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var showAddProfile by remember { mutableStateOf(false) }
    var showRestoreConfirm by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }

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
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            MineruKeySection(
                state = state,
                viewModel = viewModel
            )

            LlmProfilesSection(
                state = state,
                viewModel = viewModel,
                onAddProfileClick = { showAddProfile = true },
                onShowModelsClick = { showModels = true }
            )

            // 用量概览放在大模型配置之后、MinerU 参数之前——
            // 两类调用（LLM 对话 / MinerU 解析）的计数都在这儿，
            // 挨着各自的配置区，换 Key 后顺手就能清零对比。
            UsageOverviewSection(container = container)

            ThemeModeSection(
                state = state,
                viewModel = viewModel
            )

            MineruParamsSection(
                state = state,
                viewModel = viewModel
            )

            PrintDefaultsSection(
                state = state,
                viewModel = viewModel
            )

            ReviewReminderSection(
                state = state,
                viewModel = viewModel,
                onPickTimeClick = { showTimePicker = true }
            )

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

    if (showModels) {
        SettingsModelsDialog(
            models = state.models,
            pickedModel = state.pickedModel,
            loadingModels = state.loadingModels,
            onDismiss = { showModels = false },
            onPickModel = { model -> viewModel.pickModel(model) },
            onFetchModels = {
                val draft = state.snapshot.activeProfile
                if (draft != null) {
                    viewModel.fetchModels(draft)
                }
                showModels = false
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

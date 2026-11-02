package com.mistakebook.ui.settings

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SuggestionChip

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mistakebook.R
import com.mistakebook.data.prefs.LlmKeyEntry
import com.mistakebook.data.prefs.MAX_DAILY_NEW_LIMIT
import com.mistakebook.data.prefs.MIN_DAILY_NEW_LIMIT
import com.mistakebook.data.prefs.LlmProfile
import com.mistakebook.data.chat.ChatSearchProvider
import com.mistakebook.data.prefs.NetworkMode
import com.mistakebook.data.prefs.ReasoningEffort
import com.mistakebook.data.prefs.RecognitionMode
import com.mistakebook.data.prefs.ThemeMode
import com.mistakebook.data.prefs.UsageSnapshot
import com.mistakebook.data.prefs.formatCount
import com.mistakebook.domain.ErrorReason

@Composable
internal fun MineruParamsSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel
) {
    SettingsGroup(title = stringResource(R.string.settings_mineru_params)) {
        SwitchRow(
            label = stringResource(R.string.settings_attach_image),
            checked = state.snapshot.attachOriginalImage,
            onChange = viewModel::setAttachImage
        )
    }
}


@Composable
internal fun PrintDefaultsSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel
) {
    SettingsGroup(title = stringResource(R.string.settings_print_defaults)) {
        SwitchRow(
            label = stringResource(R.string.print_include_image),
            checked = state.snapshot.printIncludeImage,
            onChange = viewModel::setPrintIncludeImage
        )
        SwitchRow(
            label = stringResource(R.string.print_show_answer),
            checked = state.snapshot.printShowAnswer,
            onChange = viewModel::setPrintShowAnswer
        )
        SwitchRow(
            label = stringResource(R.string.print_blank_redo),
            checked = state.snapshot.printBlankRedo,
            onChange = viewModel::setPrintBlankRedo
        )
        if (state.snapshot.printBlankRedo) {
            BlankHeightRow(
                height = state.snapshot.printBlankHeightPt,
                onChange = viewModel::setPrintBlankHeight
            )
        }
    }
}


@Composable
internal fun ReviewReminderSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onPickTimeClick: () -> Unit
) {
    SettingsGroup(title = stringResource(R.string.settings_review_reminder)) {
        SwitchRow(
            label = stringResource(R.string.settings_review_reminder),
            checked = state.snapshot.reviewReminderEnabled,
            onChange = viewModel::setReminderEnabled
        )
        if (state.snapshot.reviewReminderEnabled) {
            val hour = state.snapshot.reminderHour
            val minute = state.snapshot.reminderMinute
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "%02d:%02d".format(hour, minute),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = onPickTimeClick) {
                    Text(stringResource(R.string.settings_reminder_pick))
                }
            }
        }
    }
}


@Composable
internal fun PomodoroSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel
) {
    SettingsGroup(title = stringResource(R.string.settings_section_pomodoro)) {
        SwitchRow(
            label = stringResource(R.string.settings_pomodoro_auto_record),
            checked = state.snapshot.pomodoroAutoRecord,
            onChange = viewModel::setPomodoroAutoRecord
        )
        SwitchRow(
            label = stringResource(R.string.settings_pomodoro_break_loop),
            checked = state.snapshot.pomodoroBreakLoop,
            onChange = viewModel::setPomodoroBreakLoop
        )
        if (state.snapshot.pomodoroBreakLoop) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_pomodoro_break_length),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                listOf(5, 10).forEach { minutes ->
                    FilterChip(
                        selected = state.snapshot.pomodoroBreakMinutes == minutes,
                        onClick = { viewModel.setPomodoroBreakMinutes(minutes) },
                        label = { Text(stringResource(R.string.settings_break_minutes_n, minutes)) }
                    )
                }
            }
        }
    }
}


@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun WordbookSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel
) {
    // 自定义档位弹窗：档位换成 50/100/200/自定义（v0.0.84 用户反馈，
    // 旧档 15/20/30 太少）。自定义打开数字输入弹窗，范围 1..500。
    var showCustomLimitDialog by remember { mutableStateOf(false) }
    // 快照侧已把历史非法值（旧版 0 = 不限）收敛进 1..500，所以这里
    // 永远能落到「某一个档位被选中」，不会出现四个 chip 全不选中的鬼状态。
    val current = state.snapshot.wordbookDailyNewLimit

    SettingsGroup(title = stringResource(R.string.settings_section_wordbook)) {
        Column {
            // 标签独占一行，右侧报当前生效值（v0.0.86 用户反馈：原来标签和
            // 四个 chip 挤在同一行，一超宽标签就被压成「一字一行」、
            // 档位换行成两排还错位，整块设置看着是坏的）。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.settings_daily_new_limit),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(R.string.settings_new_words_n, current),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(8.dp))
            // 档位走 FlowRow：窄屏放不下就整行换行，不再有谁被压变形的问题。
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(50, 100, 200).forEach { limit ->
                    FilterChip(
                        selected = current == limit,
                        onClick = { viewModel.setWordbookDailyNewLimit(limit) },
                        label = { Text(stringResource(R.string.settings_new_words_n, limit)) }
                    )
                }
                FilterChip(
                    selected = current != 50 && current != 100 && current != 200,
                    onClick = { showCustomLimitDialog = true },
                    label = {
                        Text(
                            if (current == 50 || current == 100 || current == 200) {
                                stringResource(R.string.settings_daily_limit_custom)
                            } else {
                                stringResource(R.string.settings_daily_limit_custom_value, current)
                            }
                        )
                    }
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_daily_new_limit_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showCustomLimitDialog) {
        CustomDailyLimitDialog(
            initial = current,
            onConfirm = { value ->
                viewModel.setWordbookDailyNewLimit(value)
                showCustomLimitDialog = false
            },
            onDismiss = { showCustomLimitDialog = false }
        )
    }
}


/** 自定义每日新词上限：数字输入，区间见 [MIN_DAILY_NEW_LIMIT]..[MAX_DAILY_NEW_LIMIT]，非法输入时确认按钮置灰。 */
@Composable
internal fun CustomDailyLimitDialog(
    initial: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial.toString()) }
    val parsed = text.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_daily_limit_dialog_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.settings_daily_limit_dialog_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit).take(3) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = parsed == null ||
                        parsed !in MIN_DAILY_NEW_LIMIT..MAX_DAILY_NEW_LIMIT
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsed != null &&
                    parsed in MIN_DAILY_NEW_LIMIT..MAX_DAILY_NEW_LIMIT,
                onClick = { parsed?.let(onConfirm) }
            ) { Text(stringResource(R.string.action_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}


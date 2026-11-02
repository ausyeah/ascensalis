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
internal fun ThemeModeSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel
) {
    SettingsGroup(title = stringResource(R.string.settings_theme_title)) {
        Text(
            text = stringResource(R.string.settings_theme_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ThemeOptionCard(
                title = stringResource(R.string.settings_theme_system),
                icon = Icons.Default.BrightnessAuto,
                selected = state.snapshot.themeMode == ThemeMode.SYSTEM,
                onClick = { viewModel.setThemeMode(ThemeMode.SYSTEM) },
                modifier = Modifier.weight(1f)
            )
            ThemeOptionCard(
                title = stringResource(R.string.settings_theme_light),
                icon = Icons.Default.LightMode,
                selected = state.snapshot.themeMode == ThemeMode.LIGHT,
                onClick = { viewModel.setThemeMode(ThemeMode.LIGHT) },
                modifier = Modifier.weight(1f)
            )
            ThemeOptionCard(
                title = stringResource(R.string.settings_theme_dark),
                icon = Icons.Default.DarkMode,
                selected = state.snapshot.themeMode == ThemeMode.DARK,
                onClick = { viewModel.setThemeMode(ThemeMode.DARK) },
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(10.dp))
        val hintText = when (state.snapshot.themeMode) {
            ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_hint_system)
            ThemeMode.LIGHT -> stringResource(R.string.settings_theme_hint_light)
            ThemeMode.DARK -> stringResource(R.string.settings_theme_hint_dark)
        }
        Text(
            text = hintText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(14.dp))
        // 自选主题色（v0.0.76）：一排色点，选中描边。只换强调色
        // （按钮/选中态/徽标），背景与正文基调不变。
        Text(
            text = stringResource(R.string.settings_theme_color),
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(8.dp))
        val isDarkNow = com.mistakebook.ui.theme.LocalDarkTheme.current
        val circleShape = androidx.compose.foundation.shape.CircleShape
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                com.mistakebook.data.prefs.AccentTone.entries.forEach { tone ->
                    val selected = state.snapshot.accentTone == tone
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(circleShape)
                                .background(com.mistakebook.ui.theme.swatchColorFor(tone, isDarkNow))
                                .then(
                                    if (selected) {
                                        Modifier.border(
                                            width = 2.dp,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            shape = circleShape
                                        )
                                    } else {
                                        Modifier
                                    }
                                )
                                .clickable { viewModel.setAccentTone(tone) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = tone.label,
                                    tint = if (isDarkNow) {
                                        androidx.compose.ui.graphics.Color(0xFF1F1F1F)
                                    } else {
                                        androidx.compose.ui.graphics.Color.White
                                    },
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = state.snapshot.accentTone.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}


@Composable
internal fun ThemeOptionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    }
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.onSurface else contentColor,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}


@Composable
internal fun ReasoningEffortSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel
) {
    SettingsGroup(title = stringResource(R.string.settings_reasoning_title)) {
        Text(
            text = stringResource(R.string.settings_reasoning_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        val current = state.snapshot.reasoningEffort
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ThemeOptionCard(
                title = stringResource(R.string.settings_reasoning_off),
                icon = Icons.Default.Close,
                selected = current == ReasoningEffort.OFF,
                onClick = { viewModel.setReasoningEffort(ReasoningEffort.OFF) },
                modifier = Modifier.weight(1f)
            )
            ThemeOptionCard(
                title = stringResource(R.string.settings_reasoning_low),
                icon = Icons.Default.BatteryStd,
                selected = current == ReasoningEffort.LOW,
                onClick = { viewModel.setReasoningEffort(ReasoningEffort.LOW) },
                modifier = Modifier.weight(1f)
            )
            ThemeOptionCard(
                title = stringResource(R.string.settings_reasoning_medium),
                icon = Icons.Default.Speed,
                selected = current == ReasoningEffort.MEDIUM,
                onClick = { viewModel.setReasoningEffort(ReasoningEffort.MEDIUM) },
                modifier = Modifier.weight(1f)
            )
            ThemeOptionCard(
                title = stringResource(R.string.settings_reasoning_high),
                icon = Icons.Default.Bolt,
                selected = current == ReasoningEffort.HIGH,
                onClick = { viewModel.setReasoningEffort(ReasoningEffort.HIGH) },
                modifier = Modifier.weight(1f)
            )
            ThemeOptionCard(
                title = stringResource(R.string.settings_reasoning_max),
                icon = Icons.Default.Whatshot,
                selected = current == ReasoningEffort.MAX,
                onClick = { viewModel.setReasoningEffort(ReasoningEffort.MAX) },
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.settings_reasoning_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}


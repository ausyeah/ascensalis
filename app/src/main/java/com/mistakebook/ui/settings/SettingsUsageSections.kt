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

/**
 * 用量概览：大模型调用次数 + 输入 / 输出 / 缓存命中 token，MinerU 解析次数。
 *
 * 刻意不做成图表——这是**计数器**而不是趋势：用户关心的是
 * 「这把 Key 到现在烧了多少」。一行一个数，扫一眼就完。
 *
 * 计数存在 SharedPreferences（[com.mistakebook.data.prefs.UsageStatsStore]），
 * 没有 Flow，所以进页面时读一次、重置后重读一次；读取走 IO 不卡合成。
 * 口径是「一次 Done 事件 = 一次调用」：失败与取消不计，
 * 流式降级非流式重发的那一次会照常计入（服务端确实又收了一次钱）。
 */
@Composable
internal fun LlmUsageSection(container: com.mistakebook.di.AppContainer) {
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<UsageSnapshot?>(null) }

    LaunchedEffect(Unit) {
        snapshot = withContext(Dispatchers.IO) { container.usageStatsStore.snapshot() }
    }

    SettingsGroup(title = stringResource(R.string.settings_usage_title_llm)) {
        val snap = snapshot
        if (snap == null) {
            Text(
                text = "…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            UsageRow(
                label = stringResource(R.string.settings_usage_llm_calls),
                value = "${formatCount(snap.llmCalls)} 次"
            )
            UsageRow(
                label = stringResource(R.string.settings_usage_prompt),
                value = formatCount(snap.llmPromptTokens)
            )
            UsageRow(
                label = stringResource(R.string.settings_usage_completion),
                value = formatCount(snap.llmCompletionTokens)
            )
            // 总计 = 输入 + 输出。缓存命中 token 是**输入里的一部分**
            // （服务端从 prompt 里划出来的那截），再加一遍就成了重复计数，
            // 所以这里只加 prompt + completion，口径与下面那行提示一致。
            UsageRow(
                label = stringResource(R.string.settings_usage_total),
                value = formatCount(snap.llmPromptTokens + snap.llmCompletionTokens)
            )
            Text(
                text = stringResource(R.string.settings_usage_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            ResetUsageButton(container, scope) { snapshot = it }
        }
    }
}


@Composable
internal fun MineruUsageSection(container: com.mistakebook.di.AppContainer) {
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<UsageSnapshot?>(null) }

    LaunchedEffect(Unit) {
        snapshot = withContext(Dispatchers.IO) { container.usageStatsStore.snapshot() }
    }

    SettingsGroup(title = stringResource(R.string.settings_usage_title_mineru)) {
        val snap = snapshot
        if (snap == null) {
            Text(
                text = "…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            UsageRow(
                label = stringResource(R.string.settings_usage_mineru_calls),
                value = "${formatCount(snap.mineruCalls)} 次"
            )
            // 页数是 MinerU 的真实计费单位（免费额度 2000 页/天）：
            // 「解析 3 次」可能是 3 张截图，也可能是一份 150 页的 PDF，
            // 不看页数根本判断不了额度消耗。
            UsageRow(
                label = stringResource(R.string.settings_usage_mineru_pages),
                value = "${formatCount(snap.mineruPages)} 页"
            )
            Text(
                text = stringResource(R.string.settings_usage_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            ResetUsageButton(container, scope) { snapshot = it }
        }
    }
}


/**
 * 两个用量区共用的「清零重算」按钮：重置后把最新快照回传给调用方刷新界面。
 *
 * 两步确认（首击变全红 + 「确认清零」，再击才执行）——清零不可恢复，
 * 误触一次整段用量历史就没了。见 [com.mistakebook.ui.common.TwoStepConfirmButton]。
 */
@Composable
internal fun ResetUsageButton(
    container: com.mistakebook.di.AppContainer,
    scope: kotlinx.coroutines.CoroutineScope,
    onReset: (UsageSnapshot) -> Unit
) {
    com.mistakebook.ui.common.TwoStepConfirmButton(
        text = stringResource(R.string.settings_usage_reset),
        confirmText = stringResource(R.string.settings_usage_reset_confirm),
        icon = Icons.Default.Refresh,
        onConfirm = {
            scope.launch {
                withContext(Dispatchers.IO) { container.usageStatsStore.reset() }
                onReset(container.usageStatsStore.snapshot())
            }
        }
    )
}


@Composable
internal fun UsageRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}


/**
 * Tavily 额度查询块（v0.0.57）。
 *
 * ## 为什么放在 Key 输入框正下方
 *
 * 用户关心的是「这把 Key 还能不能用」。查额度是验证 Key 有效性的最直接方式：
 * 能查到额度 = Key 有效 + 网络通；查不到 = Key 错或没网。放在这里比放到
 * 「用量统计」区更贴合使用场景——那边统计的是本地调用次数，与官方额度无关。
 *
 * ## 为什么失败也保留上次结果
 *
 * 「这次没查成」不代表「额度变了」。把上次的数字留在屏幕上、下面挂一条
 * 失败提示，比直接清空成一片空白更有信息量。清空只发生在换 Key 时
 * （见 `SettingsViewModel.setTavilyApiKey`）。
 */
@Composable
internal fun TavilyQuotaBlock(
    state: SettingsUiState,
    viewModel: SettingsViewModel
) {
    val usage = state.tavilyUsage
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(
            onClick = viewModel::queryTavilyUsage,
            enabled = !state.tavilyUsageLoading
        ) {
            if (state.tavilyUsageLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.width(16.dp).height(16.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_usage_querying))
            } else {
                Text(stringResource(R.string.settings_usage_query))
            }
        }
        if (usage != null) {
            Text(
                text = if (usage.hasQuota) "额度可用" else "额度已用尽",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (usage.hasQuota) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
        }
    }

    if (usage != null) {
        // 账号级与 Key 级都展示：一个账号可能有多把 Key，单看一边会漏。
        // 只显示「有值」的行，避免一片「—」占满屏幕。
        if (usage.plan.isNotBlank()) {
            UsageRow(label = stringResource(R.string.settings_usage_plan), value = usage.plan)
        }
        if (usage.planLimit != null || usage.planUsage != null) {
            UsageRow(
                label = stringResource(R.string.settings_usage_period),
                value = quotaPair(usage.planUsage, usage.planLimit)
            )
        }
        if (usage.keyLimit != null || usage.keyUsage != null) {
            UsageRow(
                label = stringResource(R.string.settings_usage_key),
                value = quotaPair(usage.keyUsage, usage.keyLimit)
            )
        }
        if (usage.paygoLimit != null || usage.paygoUsage != null) {
            UsageRow(
                label = stringResource(R.string.settings_usage_payg),
                value = quotaPair(usage.paygoUsage, usage.paygoLimit)
            )
        }
    }

    if (state.tavilyUsageError != null) {
        Text(
            text = state.tavilyUsageError,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp)
        )
    } else if (usage == null) {
        Text(
            text = stringResource(R.string.settings_usage_warn),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}


/** 「已用 / 上限」；上限为 null（不限量）时只显示已用。 */
internal fun quotaPair(used: Long?, limit: Long?): String {
    val usedText = used?.let { formatQuota(it) } ?: "0"
    return if (limit == null) "$usedText（不限量）" else "$usedText / ${formatQuota(limit)}"
}


/** 千分位。额度可能超过 Int 上限，所以按 Long 格式化而不是复用 formatCount。 */
internal fun formatQuota(n: Long): String = "%,d".format(n)


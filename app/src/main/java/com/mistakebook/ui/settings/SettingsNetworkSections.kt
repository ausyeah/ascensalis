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
 * 网络代理分区：系统代理 / 直连 / 自动。
 *
 * 用户诉求：「mineru 走代理就会说没网，把它改成直连吧，在设置里面加一个
 * 网络代理怎么走的设置」。三种模式与主题三卡片的选法保持一致，
 * 选中即写入，下一个请求生效（见 [com.mistakebook.net.NetworkRouter]）。
 */
@Composable
internal fun NetworkProxySection(
    state: SettingsUiState,
    viewModel: SettingsViewModel
) {
    SettingsGroup(title = stringResource(R.string.settings_network_title)) {
        Text(
            text = stringResource(R.string.settings_network_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ThemeOptionCard(
                title = stringResource(R.string.settings_network_system),
                icon = Icons.Default.Public,
                selected = state.snapshot.networkMode == NetworkMode.SYSTEM,
                onClick = { viewModel.setNetworkMode(NetworkMode.SYSTEM) },
                modifier = Modifier.weight(1f)
            )
            ThemeOptionCard(
                title = stringResource(R.string.settings_network_direct),
                icon = Icons.Default.WifiOff,
                selected = state.snapshot.networkMode == NetworkMode.DIRECT,
                onClick = { viewModel.setNetworkMode(NetworkMode.DIRECT) },
                modifier = Modifier.weight(1f)
            )
            ThemeOptionCard(
                title = stringResource(R.string.settings_network_auto),
                icon = Icons.Default.BrightnessAuto,
                selected = state.snapshot.networkMode == NetworkMode.AUTO,
                onClick = { viewModel.setNetworkMode(NetworkMode.AUTO) },
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(10.dp))
        val hintText = when (state.snapshot.networkMode) {
            NetworkMode.SYSTEM -> stringResource(R.string.settings_network_hint_system)
            NetworkMode.DIRECT -> stringResource(R.string.settings_network_hint_direct)
            NetworkMode.AUTO -> stringResource(R.string.settings_network_hint_auto)
        }
        Text(
            text = hintText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}


/**
 * 联网搜索分区（v0.0.43）。
 *
 * v0.0.43 起 API 配置从「模型面板」挪到这里：会话里只留「选择 + 开启」，
 * Key 这类「配一次就再也不碰」的东西统一进设置页。搜索本身已改为
 * function calling（模型自主决策），本分区只决定**给它挂哪个出口**。
 */
@Composable
internal fun SearchProviderSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel
) {
    val snapshot = state.snapshot
    SettingsGroup(title = stringResource(R.string.settings_search_title)) {
        Text(
            text = stringResource(R.string.settings_search_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        SwitchRow(
            label = stringResource(R.string.settings_search_enabled),
            checked = snapshot.chatSearchEnabled,
            onChange = viewModel::setChatSearchEnabled
        )
        if (snapshot.chatSearchEnabled) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.settings_search_provider),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = snapshot.chatSearchProvider == ChatSearchProvider.DEEPSEEK,
                    onClick = { viewModel.setChatSearchProvider(ChatSearchProvider.DEEPSEEK) },
                    label = { Text("DeepSeek") },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = snapshot.chatSearchProvider == ChatSearchProvider.TAVILY,
                    onClick = { viewModel.setChatSearchProvider(ChatSearchProvider.TAVILY) },
                    label = { Text("Tavily") },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (snapshot.chatSearchProvider == ChatSearchProvider.DEEPSEEK) {
                    stringResource(R.string.settings_search_deepseek_hint)
                } else {
                    stringResource(R.string.settings_search_tavily_hint)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // DeepSeek 出口也配独立 Key：与 Tavily 对等的可配置形态。
            // 留空时执行层自动回退到已配置的 DeepSeek 模型 Key（pickSearchKey），
            // 所以这里是「增强配置」而非「必填」——用别家大模型对话时
            // 单独填一把，搜索服务照常走 DeepSeek 官方 Search API。
            if (snapshot.chatSearchProvider == ChatSearchProvider.DEEPSEEK) {
                Spacer(Modifier.height(10.dp))
                PasswordField(
                    label = stringResource(R.string.settings_search_deepseek_key),
                    value = snapshot.deepseekSearchApiKey,
                    onValueChange = viewModel::setDeepseekSearchApiKey
                )
            }
            if (snapshot.chatSearchProvider == ChatSearchProvider.TAVILY) {
                Spacer(Modifier.height(10.dp))
                PasswordField(
                    label = stringResource(R.string.settings_search_tavily_key),
                    value = snapshot.tavilyApiKey,
                    onValueChange = viewModel::setTavilyApiKey
                )
                TavilyQuotaBlock(state = state, viewModel = viewModel)
            }
        }
    }
}


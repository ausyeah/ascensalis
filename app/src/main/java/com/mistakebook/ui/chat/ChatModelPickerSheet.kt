package com.mistakebook.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.view.WindowManager
import androidx.compose.ui.platform.LocalView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Note
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import android.content.ClipData
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import com.mistakebook.R
import com.mistakebook.data.chat.ChatContextAssembler
import com.mistakebook.data.chat.ChatSearchProvider
import com.mistakebook.data.local.entities.ChatMessage
import com.mistakebook.data.prefs.ReasoningEffort
import com.mistakebook.di.AppContainer
import com.mistakebook.domain.ChatRole
import com.mistakebook.domain.MessageStatus
import com.mistakebook.ui.common.ApiKeyRequiredDialog
import com.mistakebook.ui.common.RichText
import com.mistakebook.ui.common.rememberStreamTextDebounced
import com.mistakebook.ui.common.containerViewModel
import kotlinx.coroutines.Dispatchers
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.ExpandLess
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File
import android.net.Uri
import android.widget.Toast

/**
 * 会话相册一次最多选几张（v0.0.52 多选）。
 *
 * 取 9 与旧 CHAT 连拍上限一致：附件要逐张压缩/转 data URL，
 * 一次十几张既慢也没意义，真需要更多分几次选。
 */
private const val CHAT_GALLERY_MAX = 9

/** 选文档。PDF 单独列出，因为部分文件管理应用不会把 .pdf 归到 text 类型。 */

/**
 * 模型选择弹层（效仿主流 AI 应用的模型列表）。
 *
 * 两页内容共用一个弹层：
 * - **模型列表**：每行圆形首字母徽标 + 模型名 + 选中对勾；
 * - **设置**（右上角齿轮）：思考强度五档单选，写全局设置，
 *   与设置页同一数据源，下一次发送即生效。
 *
 * 为什么把思考强度塞在这里：它是「换模型时最常一起动的参数」——
 * 切到推理模型想拉满思考、切到快模型想关掉思考省额度，
 * 藏到设置页里每次都要跳出去改，打断对话节奏。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelPickerSheet(
    models: List<String>,
    selected: String,
    providers: List<ChatProviderOption>,
    activeProviderId: String?,
    currentEffort: ReasoningEffort,
    enabled: Boolean,
    searchEnabled: Boolean,
    searchProvider: ChatSearchProvider,
    onSelect: (String) -> Unit,
    onSelectProvider: (String) -> Unit,
    onEffortChange: (ReasoningEffort) -> Unit,
    onSearchEnabledChange: (Boolean) -> Unit,
    onSearchProviderChange: (ChatSearchProvider) -> Unit,
    /** 跳设置页的大模型配置区：面板里的列表只是「勾选结果」，增删配置要回设置页。 */
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    var page by remember { mutableStateOf(0) } // 0 模型列表 / 1 思考强度
    ModalBottomSheet(onDismissRequest = {
        page = 0
        onDismiss()
    }) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (page == 1) {
                    // 设置页左上角是「返回模型列表」
                    IconButton(onClick = { page = 0 }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.chat_cd_back_models))
                    }
                }
                Text(
                    text = if (page == 0) stringResource(R.string.chat_pick_model)
                    else stringResource(R.string.settings_reasoning_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (page == 0) {
                    IconButton(onClick = { page = 1 }, enabled = enabled) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.chat_cd_reasoning_settings))
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close))
                }
            }

            if (page == 0) {
                // ---- 供应商切换（扁平列表，点选即换，不展开不成表单）----
                //
                // 用户诉求：「切换模型不能跳转切换供应商……点开设置里面
                // 就是几个供应商列表，但是不能展开，可以自己选择哪个
                // 供应商来切换模型」。所以这里只做「选一个」：
                // 写全局 activeProfileId，模型列表随设置流自动换成
                // 新供应商的；编辑配置仍然回设置页。
                if (providers.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.chat_provider_label),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 6.dp)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        providers.forEach { provider ->
                            FilterChip(
                                selected = provider.id == activeProviderId,
                                onClick = { onSelectProvider(provider.id) },
                                enabled = enabled,
                                label = { Text(provider.name, maxLines = 1) }
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                if (models.isEmpty()) {
                    Text(
                        text = stringResource(R.string.chat_model_unset),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                    )
                }
                models.forEach { model ->
                    val isSelected = selected == model ||
                        (selected.isBlank() && model == models.firstOrNull())
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = enabled) {
                                onSelect(model)
                                page = 0
                                onDismiss()
                            }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(30.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = model.firstOrNull()?.uppercase() ?: "?",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = model,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (isSelected) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = stringResource(R.string.chat_cd_selected),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // ---- 管理模型配置（常驻）----
                //
                // 面板里的模型列表只是设置页勾选的**结果**：一个都没勾时
                // 列表只剩主模型（availableModels 的口径），用户观感是
                // 「换不了模型」。入口必须常驻，少模型时附一句提示指路。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenSettings)
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.chat_model_manage),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (needsModelHint(models)) {
                            Text(
                                text = stringResource(R.string.chat_model_few_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                // 思考强度：五档单选。当场生效打勾，不关弹层——
                // 用户往往要「调完强度继续换模型」。
                ReasoningEffort.entries.forEach { effort ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onEffortChange(effort) }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = currentEffort == effort, onClick = { onEffortChange(effort) })
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = effortLabel(effort),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = effortHint(effort),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // ---- 联网搜索（v0.0.43）：只留「选择 + 开启」 ----
            //
            // v0.0.43 起改走 function calling：开关只决定**要不要给模型挂
            // web_search 工具**，用不用、什么时候用由模型自己决定——
            // 不再是「回答前先盲目搜一次」。API Key 等具体配置已移到
            // 设置页（SearchProviderSection），这里不再堆表单。
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSearchEnabledChange(!searchEnabled) }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.chat_websearch_label), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "需要时由模型自主检索最新网页（DeepSeek / Tavily）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = searchEnabled, onCheckedChange = onSearchEnabledChange)
            }
            if (searchEnabled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = searchProvider == ChatSearchProvider.DEEPSEEK,
                        onClick = { onSearchProviderChange(ChatSearchProvider.DEEPSEEK) },
                        label = { Text("DeepSeek") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = searchProvider == ChatSearchProvider.TAVILY,
                        onClick = { onSearchProviderChange(ChatSearchProvider.TAVILY) },
                        label = { Text("Tavily") },
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    when (searchProvider) {
                        ChatSearchProvider.DEEPSEEK ->
                            "调用 DeepSeek 官方搜索服务，与对话模型无关：用别家大模型时也能获得搜索能力。API Key 在「设置 → 联网搜索」配置，留空则复用 DeepSeek 模型 Key。"

                        ChatSearchProvider.TAVILY ->
                            "使用 Tavily 独立搜索服务，API Key 请在「设置 → 识别与大模型 → 联网搜索」里配置。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
        }
    }
}


/**
 * 模型列表是否需要「去设置勾选更多」的指路提示（纯函数，可单测）。
 *
 * availableModels() 的口径是「主模型 + 设置页勾选的模型」：
 * 一个都没勾时列表只有主模型一项，用户观感是「换不了模型」——
 * 这个判定把「少」显式化，提示行跟着它出现或消失。
 * 提到顶层 internal 是为了让 JVM 单测能引用（测试源集看不到 private）。
 */
internal fun needsModelHint(models: List<String>): Boolean = models.size <= 1


internal fun effortLabel(effort: ReasoningEffort): String = when (effort) {
    ReasoningEffort.OFF -> "关闭"
    ReasoningEffort.LOW -> "低"
    ReasoningEffort.MEDIUM -> "中"
    ReasoningEffort.HIGH -> "高"
    ReasoningEffort.MAX -> "最高"
}


internal fun effortHint(effort: ReasoningEffort): String = when (effort) {
    ReasoningEffort.OFF -> "不带思考字段，服务端按默认来"
    ReasoningEffort.LOW -> "浅想一下，最快"
    ReasoningEffort.MEDIUM -> "平衡思考与速度"
    ReasoningEffort.HIGH -> "想清楚再答"
    ReasoningEffort.MAX -> "思考拉满（部分中转站支持）"
}


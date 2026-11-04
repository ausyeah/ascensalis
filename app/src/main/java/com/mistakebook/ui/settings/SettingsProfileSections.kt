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
import kotlinx.coroutines.delay

@Composable
internal fun ProfileRow(
    profile: LlmProfile,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onSave: (LlmProfile) -> Unit,
    /**
     * 拉模型列表。传的是**当前输入框内容**，不是已保存的配置——
     * 否则「填了 URL 和 Key 但没按保存」时必然失败。
     *
     * 回调带两样东西：合并后的模型池、以及**自动生成的候选链**
     * （model -> 能调它的全部 keyId，按尝试顺序；链头就是首选 Key，
     * 剩下的留给失败转移，都不用手填）。
     */
    onFetchModels: (LlmProfile, (List<String>, Map<String, List<String>>) -> Unit) -> Unit,
    /** 每个模型是从哪把 key 拉到的（「主 Key」/「Key 2」）。 */
    modelSources: Map<String, String> = emptyMap(),
    onTest: (LlmProfile) -> Unit,
    testing: Boolean = false,

    /**
     * 草稿里任何一项被改动时回调。
     *
     * 用来作废这个配置上一次测试的结论：改了 Key / URL 却还挂着
     * 「连接成功」，用户会以为新配置也通了。
     */
    onDraftChanged: () -> Unit = {},

    /**
     * 这个配置自己的测试结果。
     *
     * 早先所有结果共用一对字段、统一渲染在大模型分组的**最底部**——
     * 那个位置和「测试」按钮之间隔着输入框与「可用模型」按钮，
     * 用户分不清眼前这行字是自己点的哪个测试的结果。
     *
     * 同样**不设默认值**：有默认值时漏传不会报错，
     * 结果就是「测了但什么都没显示」。
     */
    result: String?,
    resultOk: Boolean,
    fetchingModels: Boolean = false,

    /**
     * 这套配置**已拉取到的模型列表**（按配置 id 归属）。
     *
     * 用户诉求：「你反正获取到了这个模型列表，为什么不让我自己选择勾选与否
     * 来确定这个配置可用模型呢」。
     *
     * 所以拉到列表后不能只让用户「挑一个填进模型名」——
     * 而是要能**勾选若干个**作为「本配置可用模型」，
     * 对话页据此提供模型切换；没勾选的不出现。
     */
    fetchedModels: List<String> = emptyList(),

    /**
     * 是否展开。
     *
     * 传 null 表示「卡片自己管展开态」（旧行为，多张卡可同时展开）；
     * 传 true/false 表示**受控**——由外层统一决定谁能展开，
     * 实现「一个配置展开时，其他的要关闭」的互斥。
     */
    expanded: Boolean? = null,

    /** 受控模式下的展开/收起切换回调。 */
    onToggleExpand: (() -> Unit)? = null,

    /** 保存后收起（受控模式下通知外层把展开权收回去）。 */
    onCollapsed: (() -> Unit)? = null
) {
    // 展开态跟着配置 id 走：点标题栏展开/收起， RadioButton 仍负责「当前使用」
    //
    // 受控模式（expanded != null）下展开权归外层统一调度：
    // 同一时刻只有一张卡片能展开，点开另一张会自动收起这一张。
    var localExpanded by remember(profile.id) { mutableStateOf(selected) }
    val isExpanded = expanded ?: localExpanded
    val toggleExpand: () -> Unit = {
        if (onToggleExpand != null) onToggleExpand() else localExpanded = !localExpanded
    }
    val collapse: () -> Unit = {
        if (onCollapsed != null) onCollapsed() else localExpanded = false
    }
    var name by remember(profile.id) { mutableStateOf(profile.name) }
    var baseUrl by remember(profile.id) { mutableStateOf(profile.baseUrl) }
    var apiKey by remember(profile.id) { mutableStateOf(profile.apiKey) }
    var model by remember(profile.id) { mutableStateOf(profile.model) }
    // 「已保存」反馈（v0.0.114）：点保存后亮 2.5s，用户反馈「不知道保存与否」
    var justSaved by remember(profile.id) { mutableStateOf(false) }
    LaunchedEffect(justSaved) {
        if (justSaved) {
            kotlinx.coroutines.delay(2500)
            justSaved = false
        }
    }

    // **附加 Key 池**：同一个 baseUrl 下的其他 key。
    // 中转站（one-api / new-api）就是这个形态：一个 URL、多份 key，
    // 每份 key 的可用模型与额度都不同。哪把 key 调哪个模型由拉列表时
    // 自动生成的候选链决定，不需要（也不再提供）手填绑定。
    var extraKeys by remember(profile.id) {
        mutableStateOf(profile.extraKeys.toList())
    }

    // **勾选的可用模型**：对话页的模型选项只从这里出，没勾选的不出现。
    var checkedModels by remember(profile.id) {
        mutableStateOf(profile.models.toList())
    }

    // **模型 -> keyId 首选表**：拉列表时自动生成，用户不填也看不到。
    // 它只是候选链的链头（见 modelKeyChain），保存时两者一起落盘。
    var modelKeys by remember(profile.id) {
        mutableStateOf(profile.modelKeys)
    }

    // **模型 -> 候选 key 链**：链头即上面的路由表，链尾是失败转移的顺序。
    var modelKeyChain by remember(profile.id) {
        mutableStateOf(profile.modelKeyChain)
    }

    // 这个模型能不能直接看图。不识图的模型在对话里收到图片时，
    // 应用会先调 MinerU 把图里的字识别出来，再把文字喂给模型。
    var supportsVision by remember(profile.id) { mutableStateOf(profile.supportsVision) }

    // 当前输入拼成的草稿。保存 / 拉列表 / 测试都用它，保证三者看到的是同一份值
    fun draft(): LlmProfile = profile.copy(
        name = name,
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        supportsVision = supportsVision,
        models = checkedModels,
        extraKeys = extraKeys,
        modelKeys = modelKeys,
        modelKeyChain = modelKeyChain
    )

    // 只有域名和 Key 齐了才能发请求；模型名**不**参与判断
    val canRequest = baseUrl.isNotBlank() && apiKey.isNotBlank()

    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 标题栏：点整行展开/收起配置详情；RadioButton 仍负责标记「当前使用」
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { toggleExpand() }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = selected, onClick = onSelect)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = name.ifBlank { model.ifBlank { "未命名配置" } },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (selected) {
                        Text(
                            text = stringResource(R.string.settings_active_profile),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.settings_delete_profile)
                    )
                }
            }
            if (isExpanded) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; onDraftChanged() },
                    label = { Text(stringResource(R.string.settings_profile_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it; onDraftChanged() },
                    label = { Text(stringResource(R.string.settings_base_url)) },
                    placeholder = { Text("https://api.deepseek.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                PasswordField(
                    label = stringResource(R.string.settings_api_key),
                    value = apiKey,
                    onValueChange = { apiKey = it; onDraftChanged() }
                )
                Spacer(Modifier.height(8.dp))
                // ===== 附加 Key 池 =====
                //
                // 用户诉求：「不能给一个 URL 配置多个 key，从而实现一个 provider
                // 供应多种模型，适配中转站实用」。
                //
                // 做法：主 Key 之外可以再加若干 Key。**每把 Key 独立查看 / 独立删除**，
                // 不再手填「该 Key 负责哪些模型」——拉模型列表时会逐把 Key 探测，
                // 自动生成「模型 -> 候选 Key 链」，用户勾选模型即可调用；
                // 链头是首选 Key，后面是这把 Key 失败时自动接管的备选。
                Text(
                    text = stringResource(R.string.settings_extra_keys),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(R.string.settings_extra_keys_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                extraKeys.forEachIndexed { index, entry ->
                    Spacer(Modifier.height(6.dp))
                    // 每把附加 Key 自带「显示 / 隐藏」与删除：多把 Key 长得一样，
                    // 不能显示就没法确认哪把是哪把，更不能单独删掉失效的那把。
                    ExtraKeyRow(
                        entry = entry,
                        onChange = { value ->
                            extraKeys = extraKeys.toMutableList().also {
                                it[index] = entry.copy(key = value)
                            }
                            onDraftChanged()
                        },
                        onRemove = {
                            extraKeys = extraKeys.toMutableList().also { it.removeAt(index) }
                            onDraftChanged()
                        }
                    )
                }
                Spacer(Modifier.height(6.dp))
                TextButton(
                    onClick = {
                        extraKeys = extraKeys + LlmKeyEntry()
                        onDraftChanged()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.settings_add_key))
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(12.dp))
                // ===== 模型获取与选择 =====
                //
                // 一键获取该 URL 下**所有 Key**可见的模型，合并去重后勾选。
                // 勾选的模型写进 profile.models——对话页的模型选项只从这里出，
                // 没勾选的不出现。候选链（模型 -> 能调它的 Key，按顺序）在拉取时
                // 自动生成：保存后 resolveKey 取链头当首选，链尾留给失败转移，
                // 选任何一个都调得动。
                Text(
                    text = stringResource(R.string.settings_models_fetch_title),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(R.string.settings_models_fetch_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        onFetchModels(draft()) { _, chain ->
                            // 探测出的候选链写回草稿：保存之后 resolveKey
                            // 才能按模型名找到首选 key，选了就能调。
                            // 首选表取链头——**同一个来源拆成两个字段**，
                            // 不各算一份，否则「首选」和「备选顺序」迟早对不上。
                            modelKeys = chain.mapValues { it.value.first() }
                            modelKeyChain = chain
                            onDraftChanged()
                        }
                    },
                    enabled = canRequest && !fetchingModels,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (fetchingModels) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.settings_fetching_models))
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.settings_fetch_models))
                    }
                }

                if (fetchedModels.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(
                            R.string.settings_pick_provider_models_count,
                            fetchedModels.size
                        ),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Column(
                        modifier = Modifier.heightIn(max = 220.dp).verticalScroll(
                            rememberScrollState()
                        )
                    ) {
                        fetchedModels.forEach { m ->
                            val checked = checkedModels.any { it.equals(m, ignoreCase = true) }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val next = checkedModels.toMutableList()
                                        if (checked) {
                                            next.removeAll { it.equals(m, ignoreCase = true) }
                                        } else {
                                            next.add(m)
                                        }
                                        checkedModels = next.distinct()
                                        onDraftChanged()
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { on ->
                                        val next = checkedModels.toMutableList()
                                        if (on) next.add(m) else {
                                            next.removeAll { e -> e.equals(m, ignoreCase = true) }
                                        }
                                        checkedModels = next.distinct()
                                        onDraftChanged()
                                    }
                                )
                                Text(
                                    text = m,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                // 来源标注：这个模型是哪几把 key 探到的（灰色小字，跟在名称右边）。
                                // 多 key 中转站里「看得见」和「调得动」是两件事——
                                // 标出来用户才知道勾选之后到底走的哪把钥匙。
                                val source = modelSources[m]
                                if (source != null) {
                                    Text(
                                        text = source,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                                // 一键把某个模型设为主模型
                                TextButton(onClick = {
                                    model = m
                                    onDraftChanged()
                                }) {
                                    Text(
                                        stringResource(R.string.settings_set_as_main),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                    // 全选 / 全不选
                    Row {
                        TextButton(onClick = {
                            checkedModels = fetchedModels.toList()
                            onDraftChanged()
                        }) { Text(stringResource(R.string.settings_select_all)) }
                        TextButton(onClick = {
                            checkedModels = emptyList()
                            onDraftChanged()
                        }) { Text(stringResource(R.string.settings_select_none)) }
                    }
                }

                Spacer(Modifier.height(8.dp))
                // 默认模型：手动填写，或在上方列表点「设为主模型」。
                // 服务端不提供 /models 列表时，这是唯一的模型名入口。
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it; onDraftChanged() },
                    label = { Text(stringResource(R.string.settings_model)) },
                    placeholder = { Text(stringResource(R.string.settings_model_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_supports_vision),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(
                                if (supportsVision) R.string.settings_supports_vision_on
                                else R.string.settings_supports_vision_off
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = supportsVision,
                        onCheckedChange = {
                            supportsVision = it
                            onDraftChanged()
                        }
                    )
                }

                Spacer(Modifier.height(10.dp))
                // 两个按钮等宽平分。原先是三个按钮挤在一行，
                // 「可用模型」被挤成两行竖排（用户反馈：UI 有问题）。
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            onSave(draft())
                            // v0.0.114：保存后亮「已保存」2.5s——用户反馈
                            // 「点了没反馈，不知道保存与否」。
                            justSaved = true
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.action_save))
                    }
                    OutlinedButton(
                        onClick = { onTest(draft()) },
                        enabled = canRequest && !testing,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (testing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(stringResource(R.string.settings_test_llm))
                        }
                    }
                }
                // 结果紧跟在按钮下方（这一组是竖排布局，按钮已占满宽度）
                result?.let { message ->
                    Spacer(Modifier.height(8.dp))
                    TestResultBanner(message = message, ok = resultOk)
                }
                // 「已保存」反馈：与测试结果互斥展示，2.5s 自动消失（v0.0.114）
                androidx.compose.animation.AnimatedVisibility(visible = justSaved) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.settings_profile_saved),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}


/**
 * OCR 配置（v0.0.78：MinerU 配置 → 通用 OCR 配置）。
 *
 * 用户诉求：「把 MinerU 配置改成 OCR 配置吧，需要自己填写相关配置，
 * 希望能够适配这个 MinerU 和 DeepSeekOCR」。
 *
 * 顶部选供应商，下面只显示所选供应商的字段——两套都摊开会让这一屏
 * 变得不可读，而用户同一时间只会用一个。
 */
@Composable
internal fun MineruKeySection(
    state: SettingsUiState,
    viewModel: SettingsViewModel
) {
    val snapshot = state.snapshot
    var expandedProfileId by remember { mutableStateOf<String?>(null) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    var pendingDeleteName by remember { mutableStateOf("") }

    SettingsGroup(title = stringResource(R.string.settings_ocr_config_title)) {
        snapshot.ocrProfiles.forEach { profile ->
            OcrProfileCard(
                profile = profile,
                selected = profile.id == snapshot.activeOcrProfileId,
                expanded = expandedProfileId == profile.id,
                onToggleExpand = {
                    expandedProfileId = if (expandedProfileId == profile.id) null else profile.id
                },
                onSelect = { viewModel.selectOcrProfile(profile.id) },
                onSave = { draft -> viewModel.saveOcrProfile(draft) },
                onDelete = {
                    pendingDeleteId = profile.id
                    pendingDeleteName = profile.displayName()
                },
                onTest = { draft -> viewModel.testOcrProfile(draft) },
                testing = state.isTestingOcr(profile.id),
                testResult = state.ocrOutcome(profile.id),
                testOk = state.ocrOk(profile.id)
            )
            Spacer(Modifier.height(8.dp))
        }

        OutlinedButton(
            onClick = {
                val newProfile = com.mistakebook.data.prefs.OcrProfile(
                    name = "自定义 OCR",
                    type = com.mistakebook.data.prefs.OcrType.OPENAI_VISION,
                    baseUrl = "",
                    model = ""
                )
                viewModel.addOcrProfile(newProfile)
                expandedProfileId = newProfile.id
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.settings_ocr_add_provider))
        }

        if (!snapshot.ocrConfigured) {
            Spacer(Modifier.height(8.dp))
            FirstRunHint(R.string.settings_hint_mineru_key)
        }
    }

    pendingDeleteId?.let { id ->
        ConfirmDialog(
            title = stringResource(R.string.settings_ocr_delete_title),
            text = stringResource(R.string.settings_ocr_delete_confirm, pendingDeleteName),
            confirmText = stringResource(R.string.action_confirm),
            dismissText = stringResource(R.string.action_cancel),
            onConfirm = {
                viewModel.deleteOcrProfile(id)
                pendingDeleteId = null
            },
            onDismiss = { pendingDeleteId = null }
        )
    }
}


@Composable
internal fun OcrProfileCard(
    profile: com.mistakebook.data.prefs.OcrProfile,
    selected: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onSelect: () -> Unit,
    onSave: (com.mistakebook.data.prefs.OcrProfile) -> Unit,
    onDelete: () -> Unit,
    onTest: (com.mistakebook.data.prefs.OcrProfile) -> Unit,
    testing: Boolean,
    testResult: String?,
    testOk: Boolean
) {
    var draftName by remember(profile.id) { mutableStateOf(profile.name) }
    var draftType by remember(profile.id) { mutableStateOf(profile.type) }
    var draftBaseUrl by remember(profile.id) { mutableStateOf(profile.baseUrl) }
    var draftApiKey by remember(profile.id) { mutableStateOf(profile.apiKey) }
    var draftModel by remember(profile.id) { mutableStateOf(profile.model) }
    var draftNote by remember(profile.id) { mutableStateOf(profile.note) }

    val isBuiltin = profile.id == com.mistakebook.data.prefs.OcrProfile.DEFAULT_MINERU_ID ||
        profile.id == com.mistakebook.data.prefs.OcrProfile.DEFAULT_DEEPSEEK_ID

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpand() }
            ) {
                RadioButton(
                    selected = selected,
                    onClick = onSelect
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile.displayName(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                    if (profile.note.isNotBlank()) {
                        Text(
                            text = profile.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = onToggleExpand) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "收起" else "展开"
                    )
                }
            }

            if (expanded) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(Modifier.height(10.dp))

                if (draftType == com.mistakebook.data.prefs.OcrType.MINERU) {
                    OutlinedTextField(
                        value = draftName,
                        onValueChange = { draftName = it },
                        label = { Text(stringResource(R.string.settings_ocr_channel_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    PasswordField(
                        label = "MinerU API Key",
                        value = draftApiKey,
                        onValueChange = { draftApiKey = it }
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = draftNote,
                        onValueChange = { draftNote = it },
                        label = { Text(stringResource(R.string.settings_ocr_note_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    OutlinedTextField(
                        value = draftName,
                        onValueChange = { draftName = it },
                        label = { Text(stringResource(R.string.settings_ocr_channel_name)) },
                        placeholder = { Text(stringResource(R.string.settings_ocr_name_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = draftBaseUrl,
                        onValueChange = { draftBaseUrl = it },
                        label = { Text("Base URL") },
                        placeholder = { Text("https://api.openai.com/v1") },
                        singleLine = true,
                        supportingText = { Text(stringResource(R.string.settings_ocr_url_support)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    PasswordField(
                        label = "API Key",
                        value = draftApiKey,
                        onValueChange = { draftApiKey = it }
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = draftModel,
                        onValueChange = { draftModel = it },
                        label = { Text(stringResource(R.string.settings_ocr_model_label)) },
                        placeholder = { Text(stringResource(R.string.settings_ocr_model_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = draftNote,
                        onValueChange = { draftNote = it },
                        label = { Text(stringResource(R.string.settings_ocr_note_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(12.dp))

                val currentDraft = profile.copy(
                    name = draftName,
                    type = draftType,
                    baseUrl = draftBaseUrl,
                    apiKey = draftApiKey,
                    model = draftModel,
                    note = draftNote
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onSave(currentDraft) },
                            enabled = currentDraft != profile
                        ) {
                            Text(stringResource(R.string.action_save))
                        }
                        TestButton(
                            text = stringResource(R.string.settings_test_connection),
                            loading = testing,
                            onClick = { onTest(currentDraft) },
                            result = testResult,
                            resultOk = testOk
                        )
                    }

                    if (!isBuiltin) {
                        IconButton(onClick = onDelete) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.action_delete),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}


/**
 * 识别方式：仅 MinerU / 识图模型 / 当前模型 / 自动（自动回落链）。
 *
 * 放在 MinerU 配置之后、大模型配置之前——这里是两条路的「路口」，
 * 两条路各自的配置刚展示完，用户在这里决定拍照后走哪条。
 *
 * 四个选项（v0.0.49 补齐「识图模型」与「当前模型」两个）：
 * - 仅 MinerU：只用 MinerU 解析。
 * - 识图模型：只用**用户单独配置的识图模型**看原图（主对话模型识不识图都行）。
 * - 当前模型：用**当前会话/默认的大模型**看原图。
 * - 自动：按「MinerU → 当前模型 → 识图模型」的顺序回落，任一环节失败不中断。
 */
@Composable
internal fun RecognitionModeSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel
) {
    val snapshot = state.snapshot
    val mode = snapshot.recognitionMode
    val profile = state.activeProfile
    // 大模型这条路通不通：没配任何模型，或当前模型不能识图，都算不通。
    val llmPathBlocked = profile == null || !profile.supportsVision
    val visionReady = snapshot.visionConfigured
    SettingsGroup(title = stringResource(R.string.settings_recognition_title)) {
        Text(
            text = stringResource(R.string.settings_recognition_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        // 四个选项分两行放：一行四个会挤到标签只剩半个字。
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = mode == RecognitionMode.MINERU,
                    onClick = { viewModel.setRecognitionMode(RecognitionMode.MINERU) },
                    label = { Text(stringResource(R.string.settings_recognition_mineru)) },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = mode == RecognitionMode.VISION,
                    onClick = { viewModel.setRecognitionMode(RecognitionMode.VISION) },
                    label = { Text(stringResource(R.string.settings_recognition_vision)) },
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = mode == RecognitionMode.CURRENT,
                    onClick = { viewModel.setRecognitionMode(RecognitionMode.CURRENT) },
                    label = { Text(stringResource(R.string.settings_recognition_llm)) },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = mode == RecognitionMode.AUTO,
                    onClick = { viewModel.setRecognitionMode(RecognitionMode.AUTO) },
                    label = { Text(stringResource(R.string.settings_recognition_auto)) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = when (mode) {
                RecognitionMode.MINERU ->
                    stringResource(R.string.settings_recognition_hint_mineru)
                RecognitionMode.VISION ->
                    stringResource(R.string.settings_recognition_hint_vision)
                RecognitionMode.CURRENT ->
                    stringResource(R.string.settings_recognition_hint_llm)
                RecognitionMode.AUTO ->
                    stringResource(R.string.settings_recognition_hint_auto)
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // 「当前模型」这条路必须醒目警告：它不通时整条识别直接失败。
        if (mode == RecognitionMode.CURRENT && llmPathBlocked) {
            Spacer(Modifier.height(10.dp))
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.settings_recognition_warn_llm),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
        // 「识图模型」没配时也要提示——否则用户选了才发现这条路是空的。
        if (mode == RecognitionMode.VISION && !visionReady) {
            Spacer(Modifier.height(10.dp))
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.settings_recognition_warn_vision),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}


@Composable
internal fun LlmProfilesSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onAddProfileClick: () -> Unit
) {
    // **互斥展开**：同一时刻只允许一张配置卡展开。
    //
    // 用户诉求：「一个配置展开时，其他的要关闭」。
    // 早先每张卡各自 `remember(profile.id)` 存展开态，结果三张卡能同时展开，
    // 一屏塞满输入框，配置之间对不上号。
    //
    // 展开权统一放在 Section 层：这里记「当前展开的是哪一个 id」，
    // 卡片只负责上报「我要展开 / 我要收起」。
    var expandedProfileId by remember { mutableStateOf<String?>(null) }
    // 删除二次确认：先记下待删的 id/名字并弹确认框，确认才真删（防误触不可恢复）
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    var pendingDeleteName by remember { mutableStateOf("") }
    SettingsGroup(title = stringResource(R.string.settings_llm_profiles)) {
        state.snapshot.llmProfiles.forEach { profile ->
            ProfileRow(
                profile = profile,
                selected = profile.id == state.snapshot.activeProfileId,
                expanded = expandedProfileId == profile.id,
                onToggleExpand = {
                    expandedProfileId =
                        if (expandedProfileId == profile.id) null else profile.id
                },
                onCollapsed = { expandedProfileId = null },
                onSelect = { viewModel.selectProfile(profile.id) },
                onDelete = { pendingDeleteId = profile.id; pendingDeleteName = profile.name },
                onSave = { draft -> viewModel.saveProfile(draft) },
                onDraftChanged = { viewModel.clearLlmTestResult(profile.id) },
                onFetchModels = { draft, onDone ->
                    viewModel.fetchModels(draft) { list, bindings ->
                        onDone(list, bindings)
                    }
                },
                onTest = viewModel::testLlm,
                testing = state.isTestingLlm(profile.id),
                result = state.llmOutcome(profile.id)?.message,
                resultOk = state.llmOutcome(profile.id)?.ok ?: false,
                fetchingModels = state.loadingModels,
                // 这套配置自己拉到的模型列表（勾选区用）
                fetchedModels = state.fetchedModelsByProfile[profile.id].orEmpty(),
                modelSources = state.modelSourcesByProfile[profile.id].orEmpty()
            )
            Spacer(Modifier.height(8.dp))
        }
        OutlinedButton(
            onClick = onAddProfileClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text(stringResource(R.string.settings_add_profile))
        }
        if (!state.snapshot.llmConfigured) {
            Spacer(Modifier.height(8.dp))
            FirstRunHint(R.string.settings_hint_llm_key)
        }

        // 识图模型已独立成下方的一个分区：那里的地址 / Key / 模型名
        // 全部由用户自由填写，不再受「已有对话配置」限制。
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.settings_vision_profile_moved),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    pendingDeleteId?.let { id ->
        ConfirmDialog(
            title = stringResource(R.string.settings_delete_profile_title),
            text = stringResource(R.string.settings_delete_profile_body, pendingDeleteName),
            confirmText = stringResource(R.string.action_confirm),
            dismissText = stringResource(R.string.action_cancel),
            onConfirm = { viewModel.deleteProfile(id); pendingDeleteId = null },
            onDismiss = { pendingDeleteId = null }
        )
    }
}


/**
 * 一把附加 Key 的输入行：密码框 + **自己的**「显示 / 隐藏」+ 删除。
 *
 * 为什么每把 key 都要独立显示：一个 URL 下挂多把 key 时它们长得一模一样
 * （都是一串圆点），不能显示就没法确认哪把是哪把；某把 key 过期了，
 * 也得能单独删掉它而不是清空整套配置。
 */
@Composable
internal fun ExtraKeyRow(
    entry: LlmKeyEntry,
    onChange: (String) -> Unit,
    onRemove: () -> Unit
) {
    var visible by remember(entry.id) { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = entry.key,
            onValueChange = onChange,
            label = { Text(stringResource(R.string.settings_api_key)) },
            singleLine = true,
            visualTransformation = if (visible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                TextButton(onClick = { visible = !visible }) {
                    Text(if (visible) "隐藏" else "显示")
                }
            },
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.settings_remove_key)
            )
        }
    }
}


/**
 * 生图渠道（多套，可切换）：单词场景图、详情页辅助图都走这里配置的渠道。
 *
 * 交互仿 [LlmProfilesSection] 的展开式卡片，但字段更少——生图 API
 * 没有统一端点，请求体差异用「模板」兜底（见 [ImageProfile.requestBodyTemplate]），
 * 所以没有拉模型列表、key 池那套。
 */
@Composable
internal fun ImageProfilesSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel
) {
    // 「新增渠道」后强制展开刚建的空渠道：用户点了新增就是要填它，
    // 不该再手动点开。空渠道 id 只在本次组合有效，记住它即可。
    var forceExpandId by remember { mutableStateOf<String?>(null) }
    // 同上：生图渠道之间互斥展开
    var expandedImageId by remember { mutableStateOf<String?>(null) }
    // 删除二次确认（同大模型配置）
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    var pendingDeleteName by remember { mutableStateOf("") }
    SettingsGroup(title = stringResource(R.string.settings_image_profiles)) {
        Text(
            text = stringResource(R.string.settings_image_profiles_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        state.snapshot.imageProfiles.forEach { profile ->
            ImageProfileRow(
                profile = profile,
                selected = profile.id == state.snapshot.activeImageProfileId,
                forceExpanded = forceExpandId == profile.id,
                expanded = expandedImageId == profile.id,
                onToggleExpand = {
                    expandedImageId =
                        if (expandedImageId == profile.id) null else profile.id
                },
                onCollapsed = { expandedImageId = null },
                onSelect = { viewModel.selectImageProfile(profile.id) },
                onDelete = { pendingDeleteId = profile.id; pendingDeleteName = profile.name },
                onSave = { draft -> viewModel.saveImageProfile(draft) }
            )
            Spacer(Modifier.height(8.dp))
        }
        OutlinedButton(
            onClick = {
                val fresh = com.mistakebook.data.prefs.ImageProfile()
                forceExpandId = fresh.id
                expandedImageId = fresh.id
                viewModel.addImageProfile(fresh)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text(stringResource(R.string.settings_add_image_profile))
        }
        if (!state.snapshot.imageConfigured) {
            Spacer(Modifier.height(8.dp))
            FirstRunHint(R.string.settings_hint_image_key)
        }
    }
    pendingDeleteId?.let { id ->
        ConfirmDialog(
            title = stringResource(R.string.settings_delete_profile_title),
            text = stringResource(R.string.settings_delete_profile_body, pendingDeleteName),
            confirmText = stringResource(R.string.action_confirm),
            dismissText = stringResource(R.string.action_cancel),
            onConfirm = { viewModel.deleteImageProfile(id); pendingDeleteId = null },
            onDismiss = { pendingDeleteId = null }
        )
    }
}


/**
 * 一张生图渠道卡片：RadioButton 管激活，标题栏管展开/收起，
 * 展开后是五个字段的编辑表单 + 保存 / 删除。
 */
@Composable
internal fun ImageProfileRow(
    profile: com.mistakebook.data.prefs.ImageProfile,
    selected: Boolean,
    forceExpanded: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onSave: (com.mistakebook.data.prefs.ImageProfile) -> Unit,
    /** 受控展开态（见 [ProfileRow] 的同名参数）：null = 卡片自己管。 */
    expanded: Boolean? = null,
    onToggleExpand: (() -> Unit)? = null,
    onCollapsed: (() -> Unit)? = null
) {
    // 展开态跟着配置 id 走；forceExpanded 只在新渠道创建那一刻为真，
    // 用户手动收起后不被它强行拽回来（初始值只用一次）。
    //
    // 受控模式下展开权归外层统一调度，实现「一个展开、其余关闭」。
    var localExpanded by remember(profile.id) {
        mutableStateOf(selected || forceExpanded)
    }
    val isExpanded = expanded ?: localExpanded
    val toggleExpand: () -> Unit = {
        if (onToggleExpand != null) onToggleExpand() else localExpanded = !localExpanded
    }
    val collapse: () -> Unit = {
        if (onCollapsed != null) onCollapsed() else localExpanded = false
    }
    var name by remember(profile.id) { mutableStateOf(profile.name) }
    var baseUrl by remember(profile.id) { mutableStateOf(profile.baseUrl) }
    var apiKey by remember(profile.id) { mutableStateOf(profile.apiKey) }
    var model by remember(profile.id) { mutableStateOf(profile.model) }
    var template by remember(profile.id) { mutableStateOf(profile.requestBodyTemplate) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { toggleExpand() }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.displayName(),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = profile.baseUrl.ifBlank { "未填写地址" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (isExpanded) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.settings_image_profile_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text(stringResource(R.string.settings_base_url)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                PasswordField(
                    label = stringResource(R.string.settings_api_key),
                    value = apiKey,
                    onValueChange = { apiKey = it }
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text(stringResource(R.string.settings_image_profile_model)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = template,
                    onValueChange = { template = it },
                    label = { Text(stringResource(R.string.settings_image_profile_template)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(R.string.settings_image_profile_template_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    Button(
                        onClick = {
                            onSave(
                                profile.copy(
                                    name = name.trim(),
                                    baseUrl = baseUrl.trim(),
                                    apiKey = apiKey.trim(),
                                    model = model.trim(),
                                    requestBodyTemplate = template
                                )
                            )
                            collapse()
                        },
                        enabled = name.isNotBlank() || baseUrl.isNotBlank() ||
                            apiKey.isNotBlank() || model.isNotBlank()
                    ) {
                        Text(stringResource(R.string.settings_save))
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = stringResource(R.string.settings_image_profile_delete),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}


/**
 * 识图模型（多套，可切换）：**每一个字段都由用户自己填**。
 *
 * 与「大模型配置」分开是刻意的：用户要的是「我想用哪个模型识图就填哪个」，
 * 从已有对话配置里挑一套等于被供应商绑死——识图模型常常是另一家
 * （对话用 DeepSeek、识图用通义千问 VL 这类组合很常见）。
 */
@Composable
internal fun VisionProfilesSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel
) {
    var forceExpandId by remember { mutableStateOf<String?>(null) }
    // 同上：识图模型之间互斥展开
    var expandedVisionId by remember { mutableStateOf<String?>(null) }
    // 删除二次确认（同大模型配置）
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    var pendingDeleteName by remember { mutableStateOf("") }
    SettingsGroup(title = stringResource(R.string.settings_vision_profiles)) {
        Text(
            text = stringResource(R.string.settings_vision_profiles_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        state.snapshot.visionProfiles.forEach { profile ->
            VisionProfileRow(
                profile = profile,
                selected = profile.id == state.snapshot.activeVisionProfileId,
                forceExpanded = forceExpandId == profile.id,
                expanded = expandedVisionId == profile.id,
                onToggleExpand = {
                    expandedVisionId =
                        if (expandedVisionId == profile.id) null else profile.id
                },
                onCollapsed = { expandedVisionId = null },
                onSelect = { viewModel.selectVisionProfile(profile.id) },
                onDelete = { pendingDeleteId = profile.id; pendingDeleteName = profile.name },
                onSave = { draft -> viewModel.saveVisionProfile(draft) }
            )
            Spacer(Modifier.height(8.dp))
        }
        OutlinedButton(
            onClick = {
                val fresh = LlmProfile()
                forceExpandId = fresh.id
                expandedVisionId = fresh.id
                viewModel.addVisionProfile(fresh)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text(stringResource(R.string.settings_add_vision_profile))
        }
        if (!state.snapshot.visionConfigured) {
            Spacer(Modifier.height(8.dp))
            FirstRunHint(R.string.settings_hint_vision_key)
        }
    }
    pendingDeleteId?.let { id ->
        ConfirmDialog(
            title = stringResource(R.string.settings_delete_profile_title),
            text = stringResource(R.string.settings_delete_profile_body, pendingDeleteName),
            confirmText = stringResource(R.string.action_confirm),
            dismissText = stringResource(R.string.action_cancel),
            onConfirm = { viewModel.deleteVisionProfile(id); pendingDeleteId = null },
            onDismiss = { pendingDeleteId = null }
        )
    }
}


/** 一张识图模型卡片：Radio 管激活，展开后是四个字段 + 保存 / 删除。 */
@Composable
internal fun VisionProfileRow(
    profile: LlmProfile,
    selected: Boolean,
    forceExpanded: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onSave: (LlmProfile) -> Unit,
    /** 受控展开态（见 [ProfileRow] 的同名参数）：null = 卡片自己管。 */
    expanded: Boolean? = null,
    onToggleExpand: (() -> Unit)? = null,
    onCollapsed: (() -> Unit)? = null
) {
    var localExpanded by remember(profile.id) { mutableStateOf(selected || forceExpanded) }
    val isExpanded = expanded ?: localExpanded
    val toggleExpand: () -> Unit = {
        if (onToggleExpand != null) onToggleExpand() else localExpanded = !localExpanded
    }
    val collapse: () -> Unit = {
        if (onCollapsed != null) onCollapsed() else localExpanded = false
    }
    var name by remember(profile.id) { mutableStateOf(profile.name) }
    var baseUrl by remember(profile.id) { mutableStateOf(profile.baseUrl) }
    var apiKey by remember(profile.id) { mutableStateOf(profile.apiKey) }
    var model by remember(profile.id) { mutableStateOf(profile.model) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { toggleExpand() }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Column(modifier = Modifier.weight(1f)) {
                Text(text = profile.displayName(), style = MaterialTheme.typography.bodyLarge)
                Text(
                    // 模型名是最关键的一行：用户就是要确认「识图用的是哪个模型」
                    text = profile.model.ifBlank { "未填写模型名" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (isExpanded) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.settings_image_profile_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text(stringResource(R.string.settings_base_url)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                PasswordField(
                    label = stringResource(R.string.settings_api_key),
                    value = apiKey,
                    onValueChange = { apiKey = it }
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text(stringResource(R.string.settings_vision_profile_model)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(R.string.settings_vision_profile_model_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    Button(
                        onClick = {
                            onSave(
                                profile.copy(
                                    name = name.trim(),
                                    baseUrl = baseUrl.trim(),
                                    apiKey = apiKey.trim(),
                                    model = model.trim()
                                )
                            )
                            collapse()
                        },
                        enabled = name.isNotBlank() || baseUrl.isNotBlank() ||
                            apiKey.isNotBlank() || model.isNotBlank()
                    ) {
                        Text(stringResource(R.string.settings_save))
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = stringResource(R.string.settings_image_profile_delete),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}


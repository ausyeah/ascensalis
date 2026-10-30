package com.mistakebook.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mistakebook.R
import com.mistakebook.data.prefs.LlmKeyEntry
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
internal fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

// ChoiceRow 已删：唯一的使用者是 MinerU 的模型版本/识别语言两项，
// 而这两项现已固定为 vlm / ch，不再可配。
// SettingsSectionHeader 也删了（v0.0.60）：v0.0.54 设置页改折叠分组后全工程无调用。

@Composable
internal fun SwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    /** 可选的补充说明，放在标签下方，用来解释这个开关到底影响什么。 */
    subtitle: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * 重做区高度：60~300pt，步进 10pt。
 * 设置页与打印页共用同一控件，保证两处显示与实际取值永远一致
 * （之前打印页是写死的 60/100/150 三个 chip，设置里调成别的值就「一个都不选中」）。
 */
@Composable
fun BlankHeightRow(height: Int, onChange: (Int) -> Unit) {
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.settings_blank_height_title),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(R.string.settings_blank_height_range, height),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = height.toFloat(),
            onValueChange = { value -> onChange((value / 10).toInt() * 10) },
            valueRange = 60f..300f,
            steps = 23
        )
    }
}

/**
 * 首启引导条。
 *
 * 公开分发的 APK 不预填任何 API Key（密钥在 DEX 里是明文，预填等于公开），
 * 用户第一次进设置页看到空输入框会不知道该做什么。这里明确告诉他要填什么、
 * 去哪里拿，避免「以为坏了」。
 */
@Composable
internal fun FirstRunHint(textRes: Int) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(textRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

/**
 * 测试结果常驻条。
 *
 * 用 Surface 而不是 snackbar：snackbar 会消失，而「首字响应 428ms」这类信息
 * 用户经常要对照看（改了配置再测一次，比一比快慢）。
 */
@Composable
internal fun TestResultBanner(
    message: String,
    ok: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    val container = if (ok) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val onContainer = if (ok) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = container,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (ok) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = onContainer,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = onContainer
            )
        }
    }
}

/**
 * 测试按钮。
 *
 * 加载态与空闲态**等高**（都用 [BUTTON_HEIGHT]）：原先用固定高度的 Box 包住，
 * 但没给宽度约束，加载态的「测试中…」会把整行布局挤变形。
 */
@Composable
internal fun TestButton(
    text: String,
    loading: Boolean,
    onClick: () -> Unit,
    /**
     * 这个按钮自己的测试结果。
     *
     * **结果必须跟着按钮走。** 早先两个测试共用一对 `testResult` / `testOk`，
     * 而横幅统一渲染在大模型那一组的底部——点 MinerU 的按钮，
     * 结果却显示在几屏之外的另一个分组里，用户根本对不上是哪个按钮的结果。
     * 而且两个测试还会互相覆盖：后测的那个把先测的结果冲掉。
     *
     * **没有默认值，是故意的。** 之前写成 `result: String? = null`，
     * 于是 MinerU 那个调用点漏传了 `result` / `resultOk` 也能编译通过，
     * 结果 ViewModel 里算好、存进 state 的测试结果**永远没人渲染**——
     * 点「测试 MinerU」，按钮右边空空如也，没有任何报错。
     * 去掉默认值后，漏传会直接编译不过。
     */
    result: String?,
    resultOk: Boolean
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(modifier = Modifier.height(BUTTON_HEIGHT)) {
            if (loading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.settings_testing),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                Button(onClick = onClick) { Text(text) }
            }
        }
        // 结果在按钮右边，不另起一行——换行会让按钮下方留一大片空白
        result?.let { message ->
            TestResultBanner(
                message = message,
                ok = resultOk,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** 按钮统一高度，避免加载态切换时行高跳动。 */
internal val BUTTON_HEIGHT = 40.dp

/**
 * 存储占用信息。
 *
 * 用户在数据管理这一块最想知道的其实是「我的错题占了多少磁盘」——
 * 照片是错题本里最大的开销，删了几百道题后空间没降会让人以为删失败。
 * 原来只有三个按钮、零信息，出了问题无从判断。
 *
 * 目录统计走 IO 线程，不阻塞滚动。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StorageInfoRow(container: com.mistakebook.di.AppContainer) {
    var stats by remember { mutableStateOf<StorageStats?>(null) }

    LaunchedEffect(Unit) {
        stats = withContext(Dispatchers.IO) { computeStorageStats(container) }
    }

    val current = stats
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_storage_title),
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (current == null) {
                        stringResource(R.string.settings_storage_loading)
                    } else {
                        stringResource(
                            R.string.settings_storage_detail,
                            current.questionCount,
                            formatSize(current.imagesBytes),
                            formatSize(current.databaseBytes),
                            formatSize(current.totalBytes)
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

internal data class StorageStats(
    val questionCount: Int,
    /** 原图 + 题目附图 + MinerU 切图 */
    val imagesBytes: Long,
    /** 数据库（含 -wal / -shm） */
    val databaseBytes: Long,
    val totalBytes: Long
)

/**
 * 按实际目录规划统计，不猜。
 *
 * 分开统计「图片」和「数据库」是有意义的：图片占绝大部分且会随删除题目而下降，
 * 数据库则基本不变。用户删完题发现空间没降，多半是没意识到图片还在。
 */
private fun computeStorageStats(container: com.mistakebook.di.AppContainer): StorageStats {
    val files = container.files
    var images = 0L
    images += dirSize(files.cropDir)
    images += dirSize(files.importDir)
    images += dirSize(java.io.File(files.cropDir.parentFile, "questions"))
    images += dirSize(java.io.File(files.cropDir.parentFile, "mineru"))

    val db = files.databaseFile
    var database = if (db.exists()) db.length() else 0L
    // WAL 里可能有还没 checkpoint 的数据，不算进来会低估
    listOf("-wal", "-shm").forEach { suffix ->
        val extra = java.io.File(db.path + suffix)
        if (extra.exists()) database += extra.length()
    }

    return StorageStats(
        questionCount = countFiles(java.io.File(files.cropDir.parentFile, "questions")),
        imagesBytes = images,
        databaseBytes = database,
        totalBytes = images + database
    )
}

private fun dirSize(dir: java.io.File?): Long {
    if (dir == null || !dir.exists()) return 0L
    var total = 0L
    val stack = ArrayDeque<java.io.File>()
    stack.addLast(dir)
    while (stack.isNotEmpty()) {
        val current = stack.removeLast()
        val children = current.listFiles() ?: continue
        children.forEach { child ->
            if (child.isDirectory) stack.addLast(child) else total += child.length()
        }
    }
    return total
}

private fun countFiles(dir: java.io.File?): Int {
    if (dir == null || !dir.exists()) return 0
    var total = 0
    val stack = ArrayDeque<java.io.File>()
    stack.addLast(dir)
    while (stack.isNotEmpty()) {
        val current = stack.removeLast()
        val children = current.listFiles() ?: continue
        children.forEach { child ->
            if (child.isDirectory) stack.addLast(child) else total++
        }
    }
    return total
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
internal fun PasswordField(label: String, value: String, onValueChange: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
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
        modifier = Modifier.fillMaxWidth()
    )
}

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
     * 回调带两样东西：合并后的模型池、以及**自动生成的路由表**
     * （model -> keyId，哪个模型走哪把 key，由探测结果决定，不用用户手填）。
     */
    onFetchModels: (LlmProfile, (List<String>, Map<String, String>) -> Unit) -> Unit,
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

    // **附加 Key 池**：同一个 baseUrl 下的其他 key。
    // 中转站（one-api / new-api）就是这个形态：一个 URL、多份 key，
    // 每份 key 的可用模型与额度都不同。哪把 key 调哪个模型由拉列表时
    // 自动生成的路由表决定，不需要（也不再提供）手填绑定。
    var extraKeys by remember(profile.id) {
        mutableStateOf(profile.extraKeys.toList())
    }

    // **勾选的可用模型**：对话页的模型选项只从这里出，没勾选的不出现。
    var checkedModels by remember(profile.id) {
        mutableStateOf(profile.models.toList())
    }

    // **模型 -> keyId 路由表**：拉列表时自动生成，用户不填也看不到。
    var modelKeys by remember(profile.id) {
        mutableStateOf(profile.modelKeys)
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
        modelKeys = modelKeys
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
                // 自动生成 model -> keyId 路由表，用户勾选模型即可调用。
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
                // 没勾选的不出现。路由表（model -> keyId）在拉取时自动生成，
                // 保存后 resolveKey 按它找到正确的 Key，选任何一个都调得动。
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
                        onFetchModels(draft()) { _, bindings ->
                            // 探测出的路由表写回草稿：保存之后 resolveKey
                            // 才能按模型名找到正确的 key，选了就能调。
                            modelKeys = bindings
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
                        onClick = { onSave(draft()) },
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
            }
        }
    }
}

@Composable
internal fun MineruKeySection(
    state: SettingsUiState,
    viewModel: SettingsViewModel
) {
    SettingsGroup(title = stringResource(R.string.settings_mineru_key)) {
        PasswordField(
            label = stringResource(R.string.settings_mineru_key),
            value = state.snapshot.mineruKey,
            onValueChange = viewModel::setMineruKey
        )
        Spacer(Modifier.height(8.dp))
        TestButton(
            text = stringResource(R.string.settings_test_mineru),
            loading = state.testingMineru,
            onClick = viewModel::testMineru,
            result = state.mineruTestResult,
            resultOk = state.mineruTestOk
        )
    }

    if (!state.snapshot.mineruConfigured) {
        FirstRunHint(R.string.settings_hint_mineru_key)
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
private fun ExtraKeyRow(
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
private fun ImageProfileRow(
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
private fun VisionProfileRow(
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
            UsageRow(
                label = stringResource(R.string.settings_usage_cached),
                value = formatCount(snap.llmCachedTokens)
            )
            // v0.0.58：缓存命中这一行的口径要说清。
            // 用户反馈「缓存命中肯定有问题，从来没变过」——真实情况是
            // 只有服务端在 usage 里上报 `prompt_cache_hit_tokens` 时才统计得到，
            // 多数中转站/模型不给这个字段，于是这一行会长期不动。
            // 不说清楚的话，用户只会以为是我们算错了。
            Text(
                text = "缓存命中仅统计服务端上报了缓存命中 token 的调用；" +
                    "当前模型若不上报该字段，这一行会保持不变。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
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
private fun ResetUsageButton(
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
private fun UsageRow(label: String, value: String) {
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
            text = "主题色",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(8.dp))
        val isDarkNow = com.mistakebook.ui.theme.LocalDarkTheme.current
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            com.mistakebook.data.prefs.AccentTone.entries.forEach { tone ->
                val selected = state.snapshot.accentTone == tone
                val circleShape = androidx.compose.foundation.shape.CircleShape
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
                            modifier = Modifier.size(16.dp)
                        )
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
private fun ThemeOptionCard(
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
private fun TavilyQuotaBlock(
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
                Text("查询中")
            } else {
                Text("查询额度")
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
            UsageRow(label = "当前套餐", value = usage.plan)
        }
        if (usage.planLimit != null || usage.planUsage != null) {
            UsageRow(
                label = "本周期用量",
                value = quotaPair(usage.planUsage, usage.planLimit)
            )
        }
        if (usage.keyLimit != null || usage.keyUsage != null) {
            UsageRow(
                label = "此 Key 用量",
                value = quotaPair(usage.keyUsage, usage.keyLimit)
            )
        }
        if (usage.paygoLimit != null || usage.paygoUsage != null) {
            UsageRow(
                label = "按量付费",
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
            text = "额度用尽后搜索会静默失效，建议定期查询。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/** 「已用 / 上限」；上限为 null（不限量）时只显示已用。 */
private fun quotaPair(used: Long?, limit: Long?): String {
    val usedText = used?.let { formatQuota(it) } ?: "0"
    return if (limit == null) "$usedText（不限量）" else "$usedText / ${formatQuota(limit)}"
}

/** 千分位。额度可能超过 Int 上限，所以按 Long 格式化而不是复用 formatCount。 */
private fun formatQuota(n: Long): String = "%,d".format(n)

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
    SettingsGroup(title = "番茄钟") {
        SwitchRow(
            label = "到点自动记录",
            checked = state.snapshot.pomodoroAutoRecord,
            onChange = viewModel::setPomodoroAutoRecord
        )
        SwitchRow(
            label = "休息循环",
            checked = state.snapshot.pomodoroBreakLoop,
            onChange = viewModel::setPomodoroBreakLoop
        )
        if (state.snapshot.pomodoroBreakLoop) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "休息时长",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                listOf(5, 10).forEach { minutes ->
                    FilterChip(
                        selected = state.snapshot.pomodoroBreakMinutes == minutes,
                        onClick = { viewModel.setPomodoroBreakMinutes(minutes) },
                        label = { Text("$minutes 分钟") }
                    )
                }
            }
        }
    }
}

@Composable
internal fun WordbookSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel
) {
    SettingsGroup(title = "背单词") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "每日新词上限",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            listOf(15, 20, 30, 0).forEach { limit ->
                FilterChip(
                    selected = state.snapshot.wordbookDailyNewLimit == limit,
                    onClick = { viewModel.setWordbookDailyNewLimit(limit) },
                    label = { Text(if (limit == 0) "不限" else "$limit 词") }
                )
            }
        }
        Text(
            text = "一天狂刷新词容易前学后忘；错词复习不受这个限制",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun DataManageSection(
    container: com.mistakebook.di.AppContainer,
    viewModel: SettingsViewModel,
    onExportClick: () -> Unit,
    onRestoreClick: () -> Unit
) {
    SettingsGroup(title = stringResource(R.string.settings_data_manage)) {
        StorageInfoRow(container = container)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onExportClick,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = stringResource(R.string.settings_export_backup),
                    maxLines = 1
                )
            }
            OutlinedButton(
                onClick = onRestoreClick,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = stringResource(R.string.settings_restore_backup),
                    maxLines = 1
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        // 两步确认：清空回收站不可恢复，首击变全红 + 「确认清空」，再击才执行
        com.mistakebook.ui.common.TwoStepConfirmButton(
            text = stringResource(R.string.settings_clear_trash),
            confirmText = stringResource(R.string.settings_clear_trash_confirm),
            onConfirm = viewModel::clearTrash,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * 英语单词数据重置区。
 *
 * 两个口径分开是因为破坏力完全不同：
 * - **重置今日记录**：只删今天的作答流水。今日已学归零、热力图今天
 *   那一格清空、连续打卡回到今天之前；熟词/错词本/生词本全保留。
 *   是「手滑乱点了一通」之后的橡皮擦。
 * - **全部重置**：进度 + 流水全部清空，从零开始。确认文案必须写清楚
 *   会没掉什么——不可逆操作里，写明白是唯一能救用户的。
 *
 * 删库后必须 bump `wordbookEpoch`：背单词页的抽词缓存是进程内内存
 * （progressCache / wrongWordsCache），不广播的话它会继续按「已掌握」
 * 跳过一堆词，而库里其实已经空了。
 */
@Composable
internal fun WordbookDataSection(
    container: com.mistakebook.di.AppContainer,
    onResetDone: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val dao = container.wordbookDao
    var confirmToday by remember { mutableStateOf(false) }
    var confirmAll by remember { mutableStateOf(false) }
    val todayDoneMsg = stringResource(R.string.settings_wordbook_reset_today_done)
    val allDoneMsg = stringResource(R.string.settings_wordbook_reset_all_done)

    SettingsGroup(title = stringResource(R.string.settings_wordbook_reset_title)) {
        Text(
            text = stringResource(R.string.settings_wordbook_reset_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { confirmToday = true },
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = stringResource(R.string.settings_wordbook_reset_today),
                    maxLines = 1
                )
            }
            OutlinedButton(
                onClick = { confirmAll = true },
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = stringResource(R.string.settings_wordbook_reset_all),
                    maxLines = 1
                )
            }
        }
    }

    if (confirmToday) {
        AlertDialog(
            onDismissRequest = { confirmToday = false },
            title = { Text(stringResource(R.string.settings_wordbook_reset_today)) },
            text = { Text(stringResource(R.string.settings_wordbook_reset_today_body)) },
            confirmButton = {
                com.mistakebook.ui.common.TwoStepConfirmTextButton(
                    text = stringResource(R.string.action_confirm),
                    confirmText = stringResource(R.string.settings_wordbook_reset_today_confirm),
                    onConfirm = {
                        confirmToday = false
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val startOfDay = java.util.Calendar.getInstance().apply {
                                    set(java.util.Calendar.HOUR_OF_DAY, 0)
                                    set(java.util.Calendar.MINUTE, 0)
                                    set(java.util.Calendar.SECOND, 0)
                                    set(java.util.Calendar.MILLISECOND, 0)
                                }.timeInMillis
                                dao.clearLogsSince(startOfDay)
                            }
                            // 广播后再提示：背单词页先完成重载，用户切回去看到的就是新状态
                            container.wordbookEpoch.value += 1
                            onResetDone(todayDoneMsg)
                        }
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { confirmToday = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text(stringResource(R.string.settings_wordbook_reset_all)) },
            text = { Text(stringResource(R.string.settings_wordbook_reset_all_body)) },
            confirmButton = {
                com.mistakebook.ui.common.TwoStepConfirmTextButton(
                    text = stringResource(R.string.action_confirm),
                    confirmText = stringResource(R.string.settings_wordbook_reset_all_confirm),
                    onConfirm = {
                        confirmAll = false
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                // 先清进度再清流水：反过来的话，若进程在两步之间被杀，
                                // 会留下「有流水无进度」的中间态，统计页按旧流水
                                // 还能算出学习量，进度却是全新的，两边对不上。
                                dao.clearAllProgress()
                                dao.clearLogs()
                            }
                            container.wordbookEpoch.value += 1
                            onResetDone(allDoneMsg)
                        }
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { confirmAll = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

// 题目编辑页共用的学科下拉
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubjectDropdown(
    subjects: List<com.mistakebook.data.local.entities.Subject>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val label = subjects.firstOrNull { it.id == selectedId }?.name ?: "未分类"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.edit_subject)) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            subjects.forEach { subject ->
                DropdownMenuItem(
                    text = { Text(subject.name) },
                    onClick = {
                        onSelect(subject.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

// 编辑页知识点输入
@Composable
internal fun KnowledgePointEditor(
    points: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit
) {
    var input by remember { mutableStateOf("") }
    Column {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            placeholder = { Text(stringResource(R.string.edit_knowledge_add_hint)) },
            singleLine = true,
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onDone = {
                    onAdd(input)
                    input = ""
                }
            ),
            modifier = Modifier.fillMaxWidth()
        )
        if (points.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                points.forEach { point ->
                    InputChip(
                        selected = false,
                        onClick = { onRemove(point) },
                        label = { Text(point) },
                        trailingIcon = {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.action_delete),
                                modifier = Modifier.height(16.dp)
                            )
                        }
                    )
                }
            }
        }
    }
}

// 编辑页错因单选
@Composable
internal fun ErrorReasonChooser(
    selected: ErrorReason,
    onSelect: (ErrorReason) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ErrorReason.entries.forEach { reason ->
            FilterChip(
                selected = selected == reason,
                onClick = { onSelect(reason) },
                label = { Text(reason.label) }
            )
        }
    }
}

@Composable
internal fun AddOptionButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(Icons.Default.Add, contentDescription = null)
        Text(stringResource(R.string.edit_add_option))
    }
}

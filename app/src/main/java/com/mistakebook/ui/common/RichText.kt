package com.mistakebook.ui.common

import kotlinx.coroutines.delay
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mistakebook.R
import com.mistakebook.math.MathRenderer
import com.mistakebook.markdown.MarkdownParser

/**
 * Markdown / MinerU 文本渲染：支持标题、加粗斜体、列表、引用、代码、表格、图片与 LaTeX 公式。
 *
 * 公式相关的三条硬规则（都踩过）：
 * 1. **所有公式先渲染完再交给 Text 排版**。InlineTextContent 的 lambda 是在 Text 的布局过程中
 *    执行的，在里面挂 produceState 会导致位图异步到位后占位框不重新测量，公式永远空白。
 * 2. **按字号等比缩放**（目标字号 / 渲染字号），而不是按包围盒高度。这样公式里的字母数字与
 *    正文中文同号，公式之间大小关系也一致。
 * 3. **超过行高的公式单独占一行**。行内内容撑破行框会与上下行重叠；过高的分式必须提成块级。
 */
@Composable
fun RichText(
    text: String,
    mathRenderer: MathRenderer,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = LocalTextStyle.current,
    color: Color = MaterialTheme.colorScheme.onSurface,
    /**
     * 回调：**KaTeX 渲染失败**的公式原文集合。
     *
     * 渲染失败时 `mathCache` 里对应项是 `null`，等于是白捡一份权威判定——
     * 比我们自己用正则猜「这条公式大概会失败」准得多。
     * 文本体检（`TextAudit`）拿它来报「KaTeX 解析失败」这类问题。
     *
     * 默认空实现，调用方不关心就不必传。
     */
    onMathFailures: (Set<String>) -> Unit = {},
    /**
     * 是否为流式输出（聊天正文生成中）。
     *
     * 流式期间两条规则与静态文本不同：
     * 1. **未闭合的 `$$` 块不显示源码**——半截公式先隐藏，等配对符到齐再渲染；
     * 2. **已闭合但位图未就绪的公式不回退显示 latex**——只留等宽占位，
     *    避免"先闪源码、再跳成公式"的排版跳动。
     *
     * 静态文本（编辑页/详情页/历史消息）保持原行为：失败与半截内容显示源码，
     * 那是文本体检和可读性的兜底。
     */
    streaming: Boolean = false
) {
    val blocks = remember(text) { MarkdownParser.parse(text) }
    val density = LocalDensity.current
    val targetFontPx = with(density) { style.fontSize.toPx() }

    val mathKeys = remember(blocks) { collectMathKeys(blocks) }
    val requests = remember(mathKeys) {
        mathKeys.map { key ->
            val display = key.startsWith("d:")
            val run = key.startsWith("r:")
            val raw = key.substring(2)
            val target = if (run) "\\begin{aligned}$raw\\end{aligned}" else raw
            Triple(key, target, !run && display)
        }
    }
    // 按 MathRenderer 实例保留已渲染位图；流式文本变化时只补新公式，旧公式不会闪回占位态。
    val renderedMath = remember(mathRenderer) {
        mutableStateMapOf<String, com.mistakebook.math.RenderedMath?>()
    }
    val cachedMath = remember(mathRenderer, requests) { mathRenderer.cachedOf(requests) }

    LaunchedEffect(mathRenderer, requests) {
        renderedMath.putAll(cachedMath)
        var pending = requests.filter { it.first !in renderedMath }
        var attempt = 0
        while (pending.isNotEmpty()) {
            val result = mathRenderer.renderAll(pending)
            renderedMath.putAll(result)
            val failedKeys = result.filterValues { it == null }.keys
            if (failedKeys.isEmpty() || attempt >= MAX_RENDER_ATTEMPTS - 1) break
            attempt++
            delay(RETRY_DELAY_MS * attempt)
            pending = requests.filter { it.first in failedKeys }
        }
    }
    // 合并已渲染位图；流式文本变化时旧公式不退回占位态。
    val mathCache = renderedMath + cachedMath

    val mathFailures = remember(mathCache, mathKeys) {
        mathCache.filterKeys { it in mathKeys }
            .filterValues { it == null }.keys
            .mapNotNull { key -> if (key.length > 2) key.substring(2) else null }
            .toSet()
    }
    LaunchedEffect(mathFailures) { onMathFailures(mathFailures) }

    // 把渲染失败的公式回传给调用方，供文本体检（TextAudit）使用。
    // 失败时 cache 里对应项是 `null` —— 这是 **KaTeX 自己的判定**，
    // 比用正则猜「这条公式大概会失败」准得多，也省掉一次额外渲染。

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownParser.Block.Heading -> Text(
                    text = block.text,
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    color = color,
                    modifier = Modifier.padding(top = 4.dp)
                )

                is MarkdownParser.Block.Paragraph -> InlineText(
                    text = block.text,
                    mathCache = mathCache,
                    targetFontPx = targetFontPx,
                    style = style,
                    color = color,
                    showSourceFallback = !streaming
                )

                is MarkdownParser.Block.ListItem -> Row(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = block.marker,
                        style = style,
                        color = color,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                    InlineText(
                        text = block.text,
                        mathCache = mathCache,
                        targetFontPx = targetFontPx,
                        style = style,
                        color = color,
                        modifier = Modifier.weight(1f),
                        showSourceFallback = !streaming
                    )
                }

                is MarkdownParser.Block.Quote -> Row(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(20.dp)
                            .background(MaterialTheme.colorScheme.outline)
                    )
                    Spacer(Modifier.width(8.dp))
                    InlineText(
                        text = block.text,
                        mathCache = mathCache,
                        targetFontPx = targetFontPx,
                        style = style.copy(fontStyle = FontStyle.Italic),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        showSourceFallback = !streaming
                    )
                }

                is MarkdownParser.Block.Code -> Text(
                    text = block.text,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = color,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(6.dp)
                        )
                        .padding(8.dp)
                )

                is MarkdownParser.Block.Table -> TableBlock(block)

                is MarkdownParser.Block.Image -> AsyncImage(
                    model = block.path,
                    contentDescription = block.alt,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )

                is MarkdownParser.Block.Math -> if (block.complete) {
                    MathImage(
                        latex = block.latex,
                        rendered = mathCache["d:${block.latex}"],
                        targetFontPx = targetFontPx,
                        display = true,
                        centered = true,
                        color = color,
                        showSourceFallback = !streaming
                    )
                } else if (streaming) {
                    // 未闭合的 `$$`：流式中先隐藏，配对符到齐后自然变成完整块渲染。
                    // 静态文本仍显示源码（原行为，见 [RichText] 的 streaming 说明）。
                } else {
                    Text(
                        text = "\$\$${block.latex}",
                        style = style,
                        color = color,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                MarkdownParser.Block.Rule -> HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }
}

private fun collectMathKeys(blocks: List<MarkdownParser.Block>): List<String> {
    val keys = linkedSetOf<String>()
    blocks.forEach { block ->
        when (block) {
            is MarkdownParser.Block.Math -> if (block.complete) keys += "d:${block.latex}"
            is MarkdownParser.Block.Paragraph -> keys += segmentMathKeys(block.text)
            is MarkdownParser.Block.ListItem -> keys += segmentMathKeys(block.text)
            is MarkdownParser.Block.Quote -> keys += segmentMathKeys(block.text)
            else -> Unit
        }
    }
    return keys.toList()
}

/** 与 InlineRow 保持一致：逐个行内公式单独作为 key。 */
private fun segmentMathKeys(text: String): List<String> =
    parseInline(text).filterIsInstance<InlineSpan.Math>()
        .map { "i:${it.latex}" }

/**
 * 一张表格。
 *
 * 单元格用**纯 [Text]** 而非 [RichText]：公式渲染要 WebView 往返 + 位图，
 * 而表格单元格宽度固定且很窄，塞一张公式图进去必然被压到看不清甚至溢出错位。
 *
 * 提示词已要求模型不要在表格里写 LaTeX（见 `ChatPrompts.SYSTEM`），
 * 但模型不一定听。所以这里加一层兜底：把单元格里的 `$…$` **降级成可读的纯文本**，
 * 而不是原样显示美元符号包着的源码。
 *
 * 注意是**降级**不是渲染——`$\frac{1}{2}$` 变成 `1/2`，
 * 至少用户看得懂内容，好过看到 `$\frac{1}{2}$` 这串噪声。
 */
@Composable
private fun TableBlock(block: MarkdownParser.Block.Table) {
    val columnCount = block.rows.maxOf { it.size }.coerceAtLeast(1)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
    ) {
        block.rows.forEachIndexed { rowIndex, row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                for (column in 0 until columnCount) {
                    Text(
                        text = tableCellToPlainText(row.getOrElse(column) { "" }),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (rowIndex == 0) FontWeight.Medium else null,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 6.dp, vertical = 5.dp)
                    )
                }
            }
            if (rowIndex < block.rows.lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

/**
 * 表格单元格文本：剥掉 Markdown 强调与行内公式标记，并去掉 LaTeX 反斜杠命令。
 *
 * 纯函数，可单测。
 */
// 表格单元格清洗用的正则**必须是顶层常量**。
// 它们原本写在函数体里，于是每个单元格、每次重组都要重新编译 6 个 Regex——
// 一个 5×8 的表格就是 240 次编译，而流式输出每来一块都要重算一遍整张表。
private val RE_TABLE_DISPLAY_MATH = Regex("""\$\$([^$]+)\$\$""")
private val RE_TABLE_INLINE_MATH = Regex("""\$([^$]+)\$""")
private val RE_TABLE_CODE = Regex("""`+([^`]*)`+""")
private val RE_TABLE_FRAC = Regex("""\\d?frac\s*\{([^{}]*)\}\s*\{([^{}]*)\}""")
private val RE_TABLE_COMMAND = Regex("""\\([a-zA-Z]+)""")
private val RE_TABLE_BRACES = Regex("""\{([^}]*)\}""")

internal fun tableCellToPlainText(raw: String): String {
    var text = raw.trim()
    // 先取公式内容（`$x$` / `$$x$$`），把美元符号丢掉
    text = text.replace(RE_TABLE_DISPLAY_MATH, "$1")
    text = text.replace(RE_TABLE_INLINE_MATH, "$1")
    // **落单的那个**也去掉。
    //
    // 流式输出到一半时表格单元格可能只收到 `$x^2`（闭合标记还没到），
    // 上面的正则要求成对，匹配不上。而单个 `$` 在纯文本里只会变成噪声。
    // 放到最后做，免得把已经配好对的公式内容里的 `$` 也吃掉。
    if (text.count { it == '$' } % 2 == 1) {
        text = text.replace("$", "")
    }
    // 强调标记：表格里不需要斜体/粗体语义，去掉反引号与星号
    text = text.replace(RE_TABLE_CODE, "$1")
    text = text.replace("**", "").replace("*", "")

    // **顺序要紧**：分式先转成可读形式，再剥剩下的命令反斜杠。
    // 反过来 `\frac{1}{2}` 会先变成 `frac{1}{2}`，那条规则就再也匹配不到了。
    text = text.replace(RE_TABLE_FRAC, "$1/$2")
    // 剩下的是识别不出的命令名，去掉反斜杠：`\alpha` → `alpha`
    text = text.replace(RE_TABLE_COMMAND, "$1")
    // 剩下的花括号在纯文本里没有意义，去掉
    text = text.replace(RE_TABLE_BRACES, "$1")
    return text.trim()
}

/** 一张公式图（独立成行）。display=true 用于 $$...$$，false 用于被提成的过高行内公式。 */
@Composable
private fun MathImage(
    latex: String,
    rendered: com.mistakebook.math.RenderedMath?,
    targetFontPx: Float,
    display: Boolean,
    centered: Boolean,
    color: Color,
    /** 渲染未就绪时是否回退显示 latex 源码。流式时为 false，只留空白。 */
    showSourceFallback: Boolean = true
) {
val image = rendered?.bitmap
    if (rendered != null && image != null) {
        val density = LocalDensity.current
        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val maxWidthPx = constraints.maxWidth.toFloat()
                // 字母与正文**同大**（比例 1.0），但整体受高度上限约束。
                //
                // 这里踩过一次来回：先把上限去掉，想着「不压公式」，
                // 结果分式按全尺寸渲染，一行就占掉半屏——用户反馈「latex 太大」。
                // 正确取舍是用户要的那样：
                // **普通字母与汉字同大，天生高的结构（分式、大指数）整体适当缩小。**
                val ratio = com.mistakebook.math.MathLayout.MATH_LETTER_RATIO
                val maxH = if (display) com.mistakebook.math.MathLayout.displayMathMaxHeightPx(targetFontPx) else com.mistakebook.math.MathLayout.inlineMathMaxHeightPx(targetFontPx)
                val fitted = com.mistakebook.math.MathLayout.fit(
                    bitmapW = image.width,
                    bitmapH = image.height,
                    srcFontPx = rendered.fontPx,
                    targetFontPx = targetFontPx * ratio,
                    maxWidthPx = maxWidthPx,
                    maxHeightPx = maxH
                )
                val w = fitted.width.coerceAtLeast(1f)
                val h = fitted.height.coerceAtLeast(1f)
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = if (centered) Alignment.Center else Alignment.CenterStart
                ) {
                    Image(
                        bitmap = image.asImageBitmap(),
                        contentDescription = latex,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(
                            with(density) { w.toDp() },
                            with(density) { h.toDp() }
                        )
                    )
                }
            }
        }
    } else if (showSourceFallback) {
        Text(
            text = latex,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            fontFamily = FontFamily.Monospace
        )
    }
}


/**
 * 备注编辑器：看题时直接记思路。
 * 有内容时直接显示（走 RichText，公式也能渲染），点一下展开编辑；收起或确认时落库。
 */
@Composable
fun NoteEditor(
    note: String,
    mathRenderer: MathRenderer,
    onChange: (String) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier
) {
    var editing by remember { mutableStateOf(note.isBlank()) }
    var draft by remember(note) { mutableStateOf(note) }

    if (!editing && note.isNotBlank()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { editing = true; draft = note }
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                .padding(12.dp)
        ) {
            RichText(
                text = note,
                mathRenderer = mathRenderer,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        return
    }

    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = draft,
            onValueChange = {
                draft = it
                onChange(it)
            },
            placeholder = { Text(stringResource(R.string.edit_note_hint)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 10
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            if (note.isNotBlank()) {
                TextButton(onClick = {
                    draft = note
                    onChange(note)
                    onCommit()
                    editing = false
                }) { Text(stringResource(R.string.action_cancel)) }
            }
            TextButton(onClick = {
                onCommit()
                editing = false
            }) { Text(stringResource(R.string.action_confirm)) }
        }
    }
}

/**
 * 行内排版：文字与公式在同一个文本流里。
 *
 * 行高统一放大到能容下最常见的分式（约 2 倍字号），这样带公式的行不会和上下行重叠；
 * 更高的公式（长积分、大分式）按行高等比缩小，宁可小一点也不重叠。
 *
 * **宽度必须约束**：行内公式的占位框宽度一旦超过屏幕，Compose 不会裁剪也不会换行，
 * 公式直接被切掉右半截（用户看到「公式跑到屏幕外」）。
 * 所以每个公式都按可用宽度等比压缩——宁可变小，也不能缺笔画。
 */
@Composable
private fun InlineRow(
    spans: List<InlineSpan>,
    mathCache: Map<String, com.mistakebook.math.RenderedMath?>,
    targetFontPx: Float,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    /** 渲染未就绪时是否回退显示 latex 源码。流式时为 false，只留占位。 */
    showSourceFallback: Boolean = true
) {
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier) {
        // 可用宽度。留 2dp 余量，避免正好贴边时被父容器裁掉 1px。
        val availableWidthPx = (constraints.maxWidth - with(density) { 2.dp.toPx() })
            .coerceAtLeast(targetFontPx * 2f)

        // 行高是**固定**的正文倍数，不是按本段最高公式动态撑开。
        //
        // 这里也走过一次弯路：先改成「行高迁就公式」，结果带分式的段落
        // 行高被拉到三四倍，一行占掉半屏（用户反馈"latex 太大"）。
        //
        // 最终取舍是用户要的那样，也是唯一自洽的方案：
        // 1. 字母按正文大小渲染（[MATH_LETTER_RATIO] = 1.0）；
        // 2. 整体高度封顶在 [INLINE_MATH_MAX_HEIGHT_RATIO]，
        //    天生高的分式/嵌套指数等比缩小，矮公式（`x\to0`）完全不受影响；
        // 3. 行高取一个**略大于**该上限的固定值，给公式留出上下呼吸空间，
        //    保证既不压行框、也不会被无限撑高。
        val lineHeightPx = com.mistakebook.math.MathLayout.lineBoxPx(targetFontPx)
        val inlineMathMaxHeightPx = com.mistakebook.math.MathLayout.inlineMathMaxHeightPx(targetFontPx)

        val inlineContent = remember(spans, mathCache, lineHeightPx, availableWidthPx, targetFontPx) {
            buildMap<String, InlineTextContent> {
                spans.forEachIndexed { index, span ->
                    when (span) {
                        is InlineSpan.Math -> {
                            val rendered = mathCache["i:${span.latex}"]
                            val image = rendered?.bitmap
                            // 渲染失败时按一个正文字号的空位占位，
                            // 让回退出来的源码文字还能正常参与换行。
                            val fitted = if (rendered != null && image != null) {
                                com.mistakebook.math.MathLayout.fit(
                                    bitmapW = image.width,
                                    bitmapH = image.height,
                                    srcFontPx = rendered.fontPx,
                                    targetFontPx = com.mistakebook.math.MathLayout.letterTargetPx(targetFontPx),
                                    maxWidthPx = availableWidthPx,
                                    maxHeightPx = inlineMathMaxHeightPx
                                )
                            } else {
                                null
                            }
                            val w = fitted?.width ?: targetFontPx
                            val h = fitted?.height ?: (targetFontPx * 0.9f)
                            put(
                                "math$index",
                                InlineTextContent(
                                    placeholder = Placeholder(
                                        // w/h 是**设备像素**，必须先 px -> dp 再 dp -> sp。
                                        // 直接 `w.toSp()` 相当于除了 fontScale 就当成了 sp，
                                        // 排版回推时再乘 fontScale*density → 占位框大了 density 倍
                                        //（约 3 倍），公式被挤在巨大空框的左上角。
                                        width = with(density) { w.toDp().toSp() },
                                        height = with(density) { h.toDp().toSp() },
                                        placeholderVerticalAlign = PlaceholderVerticalAlign.TextBottom
                                    )
                                ) { _ ->
                                    if (image != null && fitted != null) {
                                        Image(
                                            bitmap = image.asImageBitmap(),
                                            contentDescription = span.latex,
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier.size(
                                                with(density) { w.toDp() },
                                                with(density) { h.toDp() }
                                            )
                                        )
                                    } else if (showSourceFallback) {
                                        Text(
                                            text = span.latex,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1
                                        )
                                    }
                                }
                            )
                        }

                        is InlineSpan.Image -> put(
                            "img$index",
                            InlineTextContent(
                                placeholder = Placeholder(
                                    width = 120.sp,
                                    height = 60.sp,
                                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextBottom
                                )
                            ) { _ ->
                                AsyncImage(
                                    model = span.path,
                                    contentDescription = span.alt,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        )

                        is InlineSpan.Text -> Unit
                    }
                }
            }
        }
        val annotated = remember(spans) {
            buildAnnotatedString {
                spans.forEachIndexed { index, span ->
                    when (span) {
                        is InlineSpan.Text -> withStyle(
                            SpanStyle(
                                fontWeight = if (span.bold) FontWeight.Bold else null,
                                fontStyle = if (span.italic) FontStyle.Italic else null,
                                fontFamily = if (span.code) FontFamily.Monospace else null
                            )
                        ) { append(span.text) }

                        is InlineSpan.Math -> appendInlineContent("math$index", "公式")

                        is InlineSpan.Image -> appendInlineContent("img$index", "图片")
                    }
                }
            }
        }
        Text(
            text = annotated,
            // 行高放大到能容下行内公式，否则带公式的行会压到上下行。
            // lineHeightPx 同样是像素，转换路径必须是 px -> dp -> sp，见上面 Placeholder 的注释。
            style = style.copy(lineHeight = with(density) { lineHeightPx.toDp().toSp() }),
            color = color,
            modifier = Modifier.fillMaxWidth(),
            inlineContent = inlineContent
        )
    }
}


/** 段落入口：文字与公式在同一个文本流里排版，不拆块、不逐公式占行。 */
@Composable
private fun InlineText(
    text: String,
    mathCache: Map<String, com.mistakebook.math.RenderedMath?>,
    targetFontPx: Float,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    /** 渲染未就绪时是否回退显示 latex 源码。流式时为 false，只留占位。 */
    showSourceFallback: Boolean = true
) {
    val spans = remember(text) { parseInline(text) }
    InlineRow(
        spans = spans,
        mathCache = mathCache,
        targetFontPx = targetFontPx,
        style = style,
        color = color,
        modifier = modifier,
        showSourceFallback = showSourceFallback
    )
}

/**
 * 行内片段。
 *
 * 提到文件顶层（原来是 `private`）是为了让 [parseInline] 能被单测引用——
 * 测试源集看不到 `private`，而**测试里复制一份解析逻辑就等于没有测试**。
 */
sealed interface InlineSpan {
    data class Text(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false
    ) : InlineSpan

    data class Math(val latex: String) : InlineSpan
    data class Image(val path: String, val alt: String) : InlineSpan
}

private val inlineMath = Regex("\\$\\$([^$]+)\\$\\$|\\$([^$]+)\\$")
// 排版常量统一放在 com.mistakebook.math.MathLayout，屏幕与 PDF 共用同一套规则。
/**
 * 把一段文本解析成行内片段（文本 / 公式 / 图片，带粗斜体行内标记）。
 *
 * 提到文件顶层（原来 `private`）是为了可单测。见 [InlineSpan] 的说明。
 *
 * ## 性能：为什么不用 `substring` 逐段推进
 *
 * 原实现每轮循环做一次 `input.substring(cursor)`，而每轮只推进 1~2 个字符
 * ——长度 n 的段落要切 n 次、每次切 O(n)，合计 **O(n²) 次字符复制**。
 * 而流式期间这段代码每 500ms 对**每个段落**重跑一次。
 *
 * 现在改成按索引比较与切片，语义不变、分配降到 0。见下面几个局部函数。
 */
fun parseInline(input: String): List<InlineSpan> {
    val spans = mutableListOf<InlineSpan>()
    var cursor = 0
    var bold = false
    var italic = false
    var code = false
    val buffer = StringBuilder()

    fun flush() {
        if (buffer.isNotEmpty()) {
            spans += InlineSpan.Text(buffer.toString(), bold, italic, code)
            buffer.clear()
        }
    }

    /**
     * `input` 从 [cursor] 起的开头是否等于 [token]。
     *
     * ## 为什么不直接 `input.substring(cursor).startsWith(token)`
     *
     * 原来是每轮循环切一次剩余全文。而循环**每轮只推进 1~2 个字符**
     * （走 `else` 分支时只推进 1），于是长度 n 的段落要切 n 次、
     * 每次切出 O(n) 长度的串——**合计 O(n²) 次字符复制**。
     *
     * 纯文本段落（没有任何标记）是最坏情况：一段 2000 字的中文正文
     * 就是 2000 次、共约 200 万次字符复制。而这段代码在流式期间
     * 每 500ms 对**每个段落**重跑一次。
     *
     * 改成按索引比较：语义完全一致，分配降到 0。
     */
    fun startsWithAt(token: String): Boolean {
        if (cursor + token.length > input.length) return false
        for (i in token.indices) {
            if (input[cursor + i] != token[i]) return false
        }
        return true
    }

    /** `input` 从 [cursor] 起、到 [end]（不含）为止的子串。 */
    fun slice(from: Int, end: Int): String = input.substring(cursor + from, cursor + end)

    /** 从 [cursor] 起第一个出现 [ch] 的**相对**下标；找不到返回 -1。 */
    fun indexOfFrom(ch: Char, startRel: Int): Int =
        input.indexOf(ch, cursor + startRel) - cursor

    /**
     * 从 cursor + [startRel] 起找第一个 `$$`，返回其**相对 cursor** 的下标；找不到返回 -1。
     *
     * 等价于原实现的 `rest.indexOf("$$", startRel)`，其中 rest 是 `input.substring(cursor)`。
     */
    fun indexOfPairFrom(startRel: Int): Int {
        var i = cursor + startRel
        val last = input.length - 2
        while (i <= last) {
            if (input[i] == '$' && input[i + 1] == '$') return i - cursor
            i++
        }
        return -1
    }

    while (cursor < input.length) {
        when {
            startsWithAt("**") -> {
                flush()
                bold = !bold
                cursor += 2
            }

            startsWithAt("`") -> {
                flush()
                code = !code
                cursor += 1
            }

            startsWithAt("$$") -> {
                // 找**配对的第二个 `$$`**：即从 cursor+2 起的第一个 `$$`。
                // 原实现是 rest.indexOf("$$", 2) —— rest 就是 input.substring(cursor)，
                // 所以等价于从 cursor+2 起找长度为 2 的匹配。
                val end = indexOfPairFrom(2)
                if (end > 2) {
                    flush()
                    spans += InlineSpan.Math(slice(2, end).trim())
                    cursor += end + 2
                } else {
                    buffer.append('$')
                    cursor++
                }
            }

            startsWithAt("$") -> {
                val end = indexOfFrom('$', 1)
                if (end > 1) {
                    flush()
                    spans += InlineSpan.Math(slice(1, end).trim())
                    cursor += end + 1
                } else {
                    buffer.append('$')
                    cursor++
                }
            }

            startsWithAt("![") -> {
                // 图片语法在 `![` 之后才可能是完整的一整段，
                // 所以这里才允许切一次——命中的分支会立刻把 cursor 推过整张图，
                // 不会像 `else` 那样逐字符推进。
                val match = imageSpan.find(input, cursor)
                if (match != null && match.range.first == cursor) {
                    flush()
                    spans += InlineSpan.Image(match.groupValues[2].trim(), match.groupValues[1])
                    cursor = match.range.last + 1
                } else {
                    buffer.append(input[cursor])
                    cursor++
                }
            }

            startsWithAt("*") -> {
                flush()
                italic = !italic
                cursor += 1
            }

            else -> {
                buffer.append(input[cursor])
                cursor++
            }
        }
    }
    flush()
    return spans
}

/**
 * 图片语法 `![alt](path)`。
 *
 * 提成 `val` 是为了不再每次进入该分支都新建一个 [Regex] 对象——
 * 原实现在循环体内 `Regex("...").find(rest)`，每遇一个 `!` 就重新编译一次正则。
 */
private val imageSpan = Regex("!\\[([^\\]]*)\\]\\(([^)]+)\\)")

/**
 * 公式批量渲染的最大尝试次数。
 *
 * KaTeX 页面首次 `loadUrl` 还没光栅化完、或 WebView 正被同屏其他
 * `RichText` 占用时，本批可能整体返回 null。这些都是**瞬时**故障，
 * 而 `mathKeys` 不变就不会再跑一遍渲染 effect——一次失败就把这次会话的
 * 公式永久钉成裸源码。
 */
private const val MAX_RENDER_ATTEMPTS = 3

/** 重试间隔（毫秒）。 */
private const val RETRY_DELAY_MS = 250L
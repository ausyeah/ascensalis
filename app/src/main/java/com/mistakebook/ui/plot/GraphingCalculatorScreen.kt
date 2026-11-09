package com.mistakebook.ui.plot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mistakebook.R
import com.mistakebook.di.AppContainer
import com.mistakebook.math.CurveSampler
import com.mistakebook.math.FunctionPlotter
import com.mistakebook.math.FunctionSampler
import com.mistakebook.math.PlotCurve
import com.mistakebook.ui.chat.ChatViewModel
import com.mistakebook.ui.common.containerViewModel
import com.mistakebook.ui.theme.LocalDarkTheme
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 曲线配色：与 [FunctionPlotter] 导出到会话里的 SVG 用**同一组色相**。
 *
 * 两条路（画布与 SVG）颜色对得上，用户存进会话的那张图才和他刚才在画布上
 * 看到的是同一张。深色主题下把同一组色相提亮一档——深蓝、深绿在深底上
 * 根本看不见。色相写死是有意的：它不是主题色，是**曲线身份**。
 */
private val CURVE_COLORS_LIGHT = listOf(
    Color(0xFF1B4ED8),
    Color(0xFFC62828),
    Color(0xFF2E7D32),
    Color(0xFF6A1B9A)
)

private val CURVE_COLORS_DARK = listOf(
    Color(0xFF8AB4FF),
    Color(0xFFFF8A80),
    Color(0xFF81C784),
    Color(0xFFCE93D8)
)

/**
 * 函数计算器页（v0.0.144，issue #54 的手动入口改成了全屏页）。
 *
 * ## 为什么从对话框改成整页
 *
 * 函数图像是**要动手看的**：拖动看拐点在哪、缩放看两端怎么走、双击回原点。
 * 这些手势在一个弹窗里做不了（弹窗一碰就关）。所以抽屉里的「函数画图」
 * 现在开的是这一页：上面一块固定画布，下面是可以增删改的函数列表。
 *
 * ## 为什么画布用 Compose 画，不是 SVG
 *
 * 会话里那张图走 SVG（能缩放、能存档），但画布**每一帧都要跟着手指重画**，
 * 塞 SVG 字符串进来渲染等于每帧解析一遍 XML。所以这里直接画 Path——但
 * 采样、断线、刻度的算法与 SVG 共用 [FunctionSampler]，保证「画布上断开的
 * 地方，存进会话的图也断开」。
 *
 * ## 存到对话
 *
 * 存的是**当前视口**：用户把窗口挪到哪就存哪一段（这正是他要的「我看到的
 * 这张图」）。落库走 [ChatViewModel.insertPlotExchange]，与工具路径同一套
 * 口径——那条消息里的 SVG 就是标准导出的 SVG，会话/打印/导出都认。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GraphingCalculatorScreen(
    container: AppContainer,
    sessionId: Long,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    // 落库复用会话页的 ViewModel：「存到对话」写的就是一趟会话的一问一答。
    // key 与会话页一致，同一会话无论从哪个入口进来都指向同一份会话。
    val chatViewModel = containerViewModel(container, key = "chat-$sessionId") { c ->
        ChatViewModel(
            repository = c.chatRepository,
            questionRepository = c.questionRepository,
            stream = c.chatCompletionStream,
            settingsStore = c.settingsStore,
            usageStatsStore = c.usageStatsStore,
            preparer = c.chatAttachmentPreparer,
            compressor = c.chatContextCompressor,
            titleGenerator = c.chatTitleGenerator,
            imageOcr = c.chatImageOcr,
            questionId = null,
            sessionId = sessionId,
            appContext = c.appContext
        )
    }

    var expressions by remember { mutableStateOf(listOf("")) }
    // 视口与画布尺寸用「显式的 State 对象」而不是 `var + 值传递`：手势处理器只在
    // 首次组合时挂一次，它必须**随时读到最新的视口**。读值要等重组跑完，而重组是
    // 按帧排的——一帧里来两个触摸事件时，第二个会拿着旧视口算位移，整段拖动就少走
    // 一格（跑得越快抖得越明显）。读 State 对象没有这个时间差。
    val viewportState = remember { mutableStateOf<PlotViewport?>(null) }
    val canvasSizeState = remember { mutableStateOf(IntSize.Zero) }
    var viewport by viewportState
    var canvasSize by canvasSizeState
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val rows = remember(expressions) { expressions.map(::curveRowOf) }
    val colors = if (LocalDarkTheme.current) CURVE_COLORS_DARK else CURVE_COLORS_LIGHT

    val needExpressionText = stringResource(R.string.plot_dialog_need_expression)
    val plotFailedTemplate = stringResource(R.string.plot_dialog_error)

    // 视口要等画布量出尺寸才定得下来：y 的跨度按画布纵横比等比例铺开
    // （照抄 ±10 会把单位圆画成椭圆）。量到之前画布上什么都不画，一帧的事。
    LaunchedEffect(canvasSize) {
        if (viewport == null && canvasSize.width > 0 && canvasSize.height > 0) {
            viewport = PlotViewport.default(
                canvasSize.width.toFloat(),
                canvasSize.height.toFloat()
            )
        }
    }

    fun resetViewport() {
        val size = canvasSize
        if (size.width <= 0 || size.height <= 0) return
        viewport = PlotViewport.default(size.width.toFloat(), size.height.toFloat())
    }

    fun saveToChat() {
        val current = viewport ?: return
        val usable = rows.filter { it.curve != null }
        if (usable.isEmpty()) {
            scope.launch { snackbarHost.showSnackbar(needExpressionText) }
            return
        }
        // 只有极坐标时，画布上的「有效范围」其实是 θ（见 polarThetaMax），
        // 所以存图也把 [0, max(2π, xSpan)] 当 x 范围传下去——这样会话里那张
        // 图和用户刚才在画布上看到的是同一条曲线、同一段 θ；y 交给绘图器
        // 自动定（极坐标的窗口本来就是按曲线极值自动铺的）。
        val polarOnly = usable.all { it.curve is PlotCurve.Polar }
        // 混合曲线时仍把当前视口的四条边界一起交给绘图器：存下来的就是用户
        // 看到的那一段（x 是他拖到的位置，y 是他缩到的倍率）。此时极坐标的
        // θ 范围也取视口 x 范围——这是「x 范围对极坐标就是 θ 范围」的既定
        // 口径，只是和画布上的 [0, max(2π, xSpan)] 不完全相同。
        val request = FunctionPlotter.Request(
            curves = usable.map { FunctionPlotter.Curve(it.text.trim()) },
            xMin = if (polarOnly) 0.0 else current.xMin,
            xMax = if (polarOnly) polarThetaMax(current) else current.xMax,
            yMin = if (polarOnly) null else current.yMin,
            yMax = if (polarOnly) null else current.yMax
        )
        when (val plotted = FunctionPlotter.plot(request)) {
            is FunctionPlotter.Result.Ok ->
                // 导航放在两条消息**都落库之后**：先跳走的话这一页的 ViewModel
                // 会被清掉，写消息的协程跟着取消，会话里就只剩半条（或一条都没有）。
                chatViewModel.insertPlotExchange(sessionId, request, plotted) { onSaved() }

            is FunctionPlotter.Result.Failed -> {
                val text = plotFailedTemplate.format(plotted.message)
                scope.launch { snackbarHost.showSnackbar(text) }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.chat_tool_plot)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = { saveToChat() },
                        enabled = rows.any { it.curve != null }
                    ) {
                        Text(stringResource(R.string.plotter_save))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            PlotCanvasBox(
                rows = rows,
                colors = colors,
                viewport = viewportState,
                canvasSize = canvasSizeState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .onSizeChanged { canvasSizeState.value = it }
            )
            HorizontalDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // 函数列表最多四条，给个上限：再多就把画布挤没了
                    .heightIn(max = 288.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 6.dp, top = 4.dp)
                ) {
                    // 画布量出尺寸之前先按复位视口的口径显示（一帧的事）：
                    // 这里的数字就是拖动/缩放后存进会话的那个 x 范围。
                    val shown = viewport
                        ?: PlotViewport.default(
                            canvasSize.width.toFloat(),
                            canvasSize.height.toFloat()
                        )
                    // 只有极坐标曲线时，视口的 x 范围其实就是 θ 范围：
                    // 标签还写 "x ∈ …" 会让人以为极坐标也要设 x（v0.0.146）。
                    val polarOnly = rows.isNotEmpty() && rows.all { it.curve is PlotCurve.Polar }
                    val rangeText = if (polarOnly) {
                        stringResource(
                            R.string.plotter_theta_range,
                            "0",
                            FunctionSampler.formatTick(polarThetaMax(shown))
                        )
                    } else {
                        stringResource(
                            R.string.plotter_range,
                            FunctionSampler.formatTick(shown.xMin),
                            FunctionSampler.formatTick(shown.xMax)
                        )
                    }
                    Text(
                        text = rangeText,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { resetViewport() }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.plotter_reset))
                    }
                }

                Text(
                    text = stringResource(R.string.plotter_functions),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 4.dp)
                )

                rows.forEachIndexed { index, row ->
                    CurveRowEditor(
                        row = row,
                        color = colors[index % colors.size],
                        removable = expressions.size > 1,
                        onTextChange = { updated ->
                            expressions = expressions.toMutableList().also { it[index] = updated }
                        },
                        onRemove = {
                            expressions = expressions.toMutableList().also { it.removeAt(index) }
                        }
                    )
                }

                if (expressions.size < FunctionPlotter.MAX_CURVES) {
                    TextButton(
                        onClick = { expressions = expressions + "" },
                        modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.plot_dialog_add_curve))
                    }
                } else {
                    Text(
                        text = stringResource(R.string.plotter_max_curves),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 24.dp, bottom = 8.dp)
                    )
                }
            }
        }
    }
}

/**
 * 固定画布：网格 + 坐标轴 + 刻度 + 曲线，手势只改视口。
 *
 * 只有**视口**动，采样与断线判据一个字都不改——缩放不该让曲线换一种断法，
 * 否则「这里到底有没有渐近线」就说不清了。
 *
 * [viewport] 与 [canvasSize] 收的是 State 对象而不是值：手势处理器挂上就不再
 * 重挂，它得随时读到最新视口（理由见调用处那段注释）。
 */
@Composable
private fun PlotCanvasBox(
    rows: List<CurveRow>,
    colors: List<Color>,
    viewport: MutableState<PlotViewport?>,
    canvasSize: MutableState<IntSize>,
    modifier: Modifier = Modifier
) {
    val canvasDescription = stringResource(R.string.plotter_canvas_desc)
    val gestureHint = stringResource(R.string.plotter_gesture_hint)
    // 画布内不能读主题（绘制 lambda 不是组合上下文），颜色与字体都提到这里
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = TextStyle(
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.sp
    )
    val textMeasurer = rememberTextMeasurer()

    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .semantics { contentDescription = canvasDescription }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val size = canvasSize.value
                    if (size.width <= 0 || size.height <= 0) return@detectTransformGestures
                    val width = size.width.toFloat()
                    val height = size.height.toFloat()
                    val current = viewport.value ?: PlotViewport.default(width, height)
                    // 先平移再以捏合中心缩放：单指拖动时 zoom 恒为 1，
                    // 双指时两者同时生效，跟地图一个手感。
                    var next = current.pan(pan.x, pan.y, width, height)
                    if (zoom != 1f) {
                        next = next.zoom(zoom, centroid.x, centroid.y, width, height)
                    }
                    viewport.value = next
                }
            }
            .pointerInput(Unit) {
                // 双击复位：单独一个 detector。捏合要越过触摸 slop 才成立，
                // 与双击互不干扰。
                detectTapGestures(onDoubleTap = {
                    val size = canvasSize.value
                    if (size.width > 0 && size.height > 0) {
                        viewport.value =
                            PlotViewport.default(size.width.toFloat(), size.height.toFloat())
                    }
                })
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val vp = viewport.value ?: return@Canvas
            if (size.width <= 0f || size.height <= 0f) return@Canvas
            if (vp.xSpan <= 0.0 || vp.ySpan <= 0.0) return@Canvas
            val plotW = size.width
            val plotH = size.height

            // ---- 网格与刻度：步长算法与 SVG 用同一套（niceStep/ticks） ----
            val xTicks = FunctionSampler.ticks(
                vp.xMin, vp.xMax, FunctionSampler.niceStep(vp.xSpan, target = 8)
            )
            val yTicks = FunctionSampler.ticks(
                vp.yMin, vp.yMax, FunctionSampler.niceStep(vp.ySpan, target = 6)
            )
            xTicks.forEach { x ->
                val px = vp.xToPx(x, plotW).toFloat()
                drawLine(gridColor, Offset(px, 0f), Offset(px, plotH), 1.dp.toPx())
            }
            yTicks.forEach { y ->
                val py = vp.yToPx(y, plotH).toFloat()
                drawLine(gridColor, Offset(0f, py), Offset(plotW, py), 1.dp.toPx())
            }

            // ---- 坐标轴：0 在窗口内就画在 0 处，否则贴边（与 SVG 导出同一口径，
            //      保证任何视口下都有一条轴可读） ----
            val hasXAxis = vp.yMin <= 0.0 && vp.yMax >= 0.0
            val hasYAxis = vp.xMin <= 0.0 && vp.xMax >= 0.0
            val axisY = if (hasXAxis) vp.yToPx(0.0, plotH).toFloat() else plotH
            val axisX = if (hasYAxis) vp.xToPx(0.0, plotW).toFloat() else 0f
            drawLine(axisColor, Offset(0f, axisY), Offset(plotW, axisY), 1.6.dp.toPx())
            drawLine(axisColor, Offset(axisX, 0f), Offset(axisX, plotH), 1.6.dp.toPx())

            // ---- 刻度数字 ----
            // 画布没有留边（整块都是绘图区），所以贴着边框的那条轴要把数字翻到
            // 轴的另一侧去，否则数字会被画到画布外面，用户只看到刻度线。
            val gap = 8.dp.toPx()
            val axisAtBottom = axisY > plotH - 24.dp.toPx()
            xTicks.forEach { x ->
                if (abs(x) < 1e-9 && hasYAxis) return@forEach
                val layout = textMeasurer.measure(
                    AnnotatedString(FunctionSampler.formatTick(x)), labelStyle
                )
                val left = (vp.xToPx(x, plotW).toFloat() - layout.size.width / 2f)
                    .coerceIn(0f, (plotW - layout.size.width).coerceAtLeast(0f))
                val top = if (axisAtBottom) axisY - gap - layout.size.height else axisY + gap
                drawText(
                    layout,
                    topLeft = Offset(left, top.coerceIn(0f, (plotH - layout.size.height).coerceAtLeast(0f)))
                )
            }
            yTicks.forEach { y ->
                if (abs(y) < 1e-9 && hasXAxis) return@forEach
                val layout = textMeasurer.measure(
                    AnnotatedString(FunctionSampler.formatTick(y)), labelStyle
                )
                val fitsLeft = axisX - gap - layout.size.width >= 0f
                val left = if (fitsLeft) axisX - gap - layout.size.width else axisX + gap
                val top = (vp.yToPx(y, plotH).toFloat() - layout.size.height / 2f)
                    .coerceIn(0f, (plotH - layout.size.height).coerceAtLeast(0f))
                drawText(layout, topLeft = Offset(left, top))
            }
            // 原点只写一个 0（两条轴各写一次就是两个数字叠在一起）
            if (hasXAxis && hasYAxis) {
                val layout = textMeasurer.measure(AnnotatedString("0"), labelStyle)
                val left = if (axisX - gap - layout.size.width >= 0f) {
                    axisX - gap - layout.size.width
                } else {
                    axisX + gap
                }
                drawText(
                    layout,
                    topLeft = Offset(
                        left,
                        (axisY + gap).coerceAtMost(
                            (plotH - layout.size.height).coerceAtLeast(0f)
                        )
                    )
                )
            }

            // ---- 曲线：与 SVG 共用采样与断线判据，只是画在 Compose 画布上 ----
            // 显函数、极坐标、隐函数都走 CurveSampler，三条路只有采样方式不同，
            // 「断在哪、去重到多少像素」完全一致。
            val thetaMax = polarThetaMax(vp)
            rows.forEachIndexed { index, row ->
                val curve = row.curve ?: return@forEachIndexed
                val segments = CurveSampler.segments(
                    curve = curve,
                    xMin = vp.xMin,
                    xMax = vp.xMax,
                    yMin = vp.yMin,
                    yMax = vp.yMax,
                    mapX = { x -> vp.xToPx(x, plotW) },
                    mapY = { y -> vp.yToPx(y, plotH) },
                    thetaMin = 0.0,
                    thetaMax = thetaMax
                )
                if (segments.isEmpty()) return@forEachIndexed
                val path = Path()
                segments.forEach { segment ->
                    segment.forEachIndexed { pointIndex, point ->
                        if (pointIndex == 0) {
                            path.moveTo(point.x.toFloat(), point.y.toFloat())
                        } else {
                            path.lineTo(point.x.toFloat(), point.y.toFloat())
                        }
                    }
                }
                drawPath(
                    path = path,
                    color = colors[index % colors.size],
                    style = Stroke(
                        width = 2.5.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }
        }

        Text(
            text = gestureHint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp)
        )
    }
}

/** 一行表达式输入：颜色标识 + 文本框 + 删掉这条。 */
@Composable
private fun CurveRowEditor(
    row: CurveRow,
    color: Color,
    removable: Boolean,
    onTextChange: (String) -> Unit,
    onRemove: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 颜色点：与画布上那条曲线同色，四条一起画时不用猜哪条是哪条
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            OutlinedTextField(
                value = row.text,
                onValueChange = onTextChange,
                singleLine = true,
                isError = row.error != null,
                placeholder = { Text(stringResource(R.string.plot_dialog_curve_hint)) },
                modifier = Modifier.weight(1f)
            )
            if (removable) {
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.plot_dialog_remove_curve)
                    )
                }
            }
        }
        row.error?.let { reason ->
            Text(
                text = stringResource(R.string.plotter_expression_error, reason),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 24.dp, bottom = 4.dp)
            )
        }
    }
}

/**
 * 一行的编辑状态。
 *
 * [curve] 与 [error] 互斥：解析不通过时**不画这条**，并把原因摆在输入框
 * 下面——用户改完立刻就能看到曲线回来，中间不需要点任何按钮。
 * 存的是 [PlotCurve] 而不是求值器：极坐标要按 θ 采样、隐函数要按网格求
 * 等值线，只有知道「是哪一种曲线」才能选对采样方式。
 */
private data class CurveRow(
    val text: String,
    val curve: PlotCurve?,
    val error: String?
)

/** 空表达式不算错（用户刚点「再加一条」）；写错的才报，且报原文原因。 */
private fun curveRowOf(text: String): CurveRow {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return CurveRow(text, null, null)
    return try {
        CurveRow(text, PlotCurve.parse(trimmed), null)
    } catch (error: IllegalArgumentException) {
        CurveRow(text, null, error.message)
    }
}

/**
 * 画布上极坐标的 θ 上界：`max(2π, 视口 x 跨度)`。
 *
 * 取舍：没有单独的 θ 输入框，而 θ 范围又必须有个说法。取 2π 保证玫瑰线、
 * 心形线在一个周期内闭合；视口拉宽（用户明显想「看多圈」）时跟着变宽，
 * 于是 `r=cos(θ/3)` 这种要 6π 才闭合的曲线把图画缩小就能看全——比再加
 * 一个控件更省事，也不用解释「θ 范围」这个学生还没建立的概念。
 *
 * 代价：`CurveSampler` 的极坐标采样固定 720 步，θ 范围越宽每圈越粗；
 * 周期曲线会重复画几遍（视觉上看不出来，只是多算一点）。
 */
private fun polarThetaMax(viewport: PlotViewport): Double =
    maxOf(2.0 * Math.PI, viewport.xSpan)

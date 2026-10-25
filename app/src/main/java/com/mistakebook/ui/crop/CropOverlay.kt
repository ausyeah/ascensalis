package com.mistakebook.ui.crop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 裁切页覆盖层：图片 + 遮罩涂鸦 Canvas + 裁剪框手柄。
 *
 * T14 手势模型：**一根手指 = 涂鸦（或拖框），两根手指 = 双指缩放 + 平移**。
 * 用 `awaitEachGesture` 手工分流而不是 `detectTransformGestures`，原因是要在
 * 「第二根手指落下」的精确时刻作废正在画的半笔—— Transform 回调是变化量回调，
 * 拿不到指针数的跳变时刻。
 */
@Composable
internal fun CropOverlay(
    displayBitmap: ImageBitmap,
    rawBitmap: android.graphics.Bitmap,
    viewport: IntSize,
    onViewportChange: (IntSize) -> Unit,
    maskMode: Boolean,
    strokes: List<MaskStroke>,
    activeStroke: List<Offset>,
    onActiveStrokeChange: (List<Offset>) -> Unit,
    onStrokeCommit: () -> Unit,
    rect: CropRect,
    rotationQuarter: Int,
    onRectChange: (CropRect) -> Unit,
    onHandleDragStart: (Offset) -> Unit,
    onHandleDragEnd: () -> Unit,
    onHandleDragCancel: () -> Unit,
    onHandleDrag: (Offset) -> Unit,
    imageLeft: Float,
    imageTop: Float,
    imageSize: IntSize,
    baseToScreen: (Offset) -> Offset,
    screenToBase: (x: Float, y: Float) -> Offset,
    brushWidthPx: Float,
    // T14：双指缩放/平移。状态由调用方持有（旋转屏幕保持），变换增量经 onTransform 回写，
    // 双击/工具栏「重置」经 onZoomReset 复位。zoomPan 传入的已是调用方按图片显示尺寸钳制后的值。
    zoomScale: Float,
    zoomPan: Offset,
    onTransform: (zoomFactor: Float, panDelta: Offset) -> Unit,
    onZoomReset: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { onViewportChange(it) }
    ) {
        // T14：Image 与遮罩 Canvas 必须在**同一个** graphicsLayer 里同步缩放/平移，
        // 否则图片放大了遮罩还停在原地，涂鸦位置整体错位。
        // 默认 transformOrigin 是中心，与调用方 pan 钳制公式的绕中心缩放假设一致。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = zoomScale,
                    scaleY = zoomScale,
                    translationX = zoomPan.x,
                    translationY = zoomPan.y
                )
        ) {
            Image(
                bitmap = displayBitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )

            val currentPoints = remember { mutableListOf<Offset>() }

            // T14：屏幕视觉笔宽恒定。graphicsLayer 会把绘制宽度一起放大，
            // 这里先除以 zoomScale，放大后笔画落在原图上更细、屏幕上看起来一样粗。
            val visualBrushWidthPx = brushWidthPx / zoomScale

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    // 双击复位单独一个 pointerInput。短 tap 不超 touch slop，
                    // 下面的分流逻辑不会消费它的 down/up，两个 modifier 都能收到完整序列。
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { onZoomReset() })
                    }
                    .pointerInput(viewport, rawBitmap, maskMode) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            // 一次手势流（down 到全部抬起）只属于一种模式：
                            // DRAW = 单指绘制/拖框；TRANSFORM = 进过变换模式。
                            // 进过变换模式后单指 up 前不再回到绘制分支——
                            // 两指抬起时手指位置相对落笔点已经跳变，接上去画出来的是废线。
                            var transformMode = false
                            var lastDragPosition = down.position
                            var lastCentroid = down.position
                            var lastSpread = 0f

                            // 落笔即开始，与改造前 detectDragGestures 的 onDragStart 语义一致
                            if (maskMode) {
                                currentPoints.clear()
                                currentPoints.add(screenToBase(down.position.x, down.position.y))
                                onActiveStrokeChange(currentPoints.toList())
                            } else {
                                onHandleDragStart(down.position)
                            }

                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) break

                                if (!transformMode && pressed.size >= 2) {
                                    // 第二根手指落下：进入变换模式，正在画的半笔立即作废（不 commit）
                                    transformMode = true
                                    if (maskMode) {
                                        currentPoints.clear()
                                        onActiveStrokeChange(emptyList())
                                    } else {
                                        onHandleDragCancel()
                                    }
                                    lastCentroid = pressed.centroid()
                                    lastSpread = pressed.spread(lastCentroid)
                                    pressed.forEach { it.consume() }
                                    continue
                                }

                                if (transformMode) {
                                    if (pressed.size < 2) {
                                        // 两指变一指：不接续绘制，事件全消费掉等抬手
                                        pressed.forEach { it.consume() }
                                        continue
                                    }
                                    val centroid = pressed.centroid()
                                    val spread = pressed.spread(centroid)
                                    val panDelta = centroid - lastCentroid
                                    val zoomFactor =
                                        if (lastSpread > 0.01f) spread / lastSpread else 1f
                                    pressed.forEach { it.consume() }
                                    if (zoomFactor != 1f || panDelta != Offset.Zero) {
                                        onTransform(zoomFactor, panDelta)
                                    }
                                    lastCentroid = centroid
                                    lastSpread = spread
                                    continue
                                }

                                // 单指绘制 / 拖框（维持改造前逻辑）
                                val change = pressed.first()
                                val dragAmount = change.position - lastDragPosition
                                lastDragPosition = change.position
                                change.consume()
                                if (maskMode) {
                                    val point = screenToBase(change.position.x, change.position.y)
                                    val last = currentPoints.lastOrNull()
                                    val lastOnScreen = last?.let { baseToScreen(it) }
                                    if (lastOnScreen == null ||
                                        abs(lastOnScreen.x - change.position.x) > 2f ||
                                        abs(lastOnScreen.y - change.position.y) > 2f
                                    ) {
                                        currentPoints.add(point)
                                        onActiveStrokeChange(currentPoints.toList())
                                    }
                                } else {
                                    onHandleDrag(dragAmount)
                                }
                            }

                            // 全部抬起：只有从未进过变换模式的手势流才结算
                            if (!transformMode) {
                                if (maskMode) {
                                    onStrokeCommit()
                                    currentPoints.clear()
                                } else {
                                    onHandleDragEnd()
                                }
                            }
                        }
                    }
            ) {
                // 绘制遮罩笔迹：不论是否在涂鸦模式，已确认的遮罩笔迹都要呈现（让框选时也能明确知道被遮蔽区域）
                // 在涂鸦模式下，额外包含正在绘制的一笔（activeStroke）
                val allStrokes = if (maskMode && activeStroke.isNotEmpty()) {
                    strokes.map { it.points } + listOf(activeStroke)
                } else {
                    strokes.map { it.points }
                }

                allStrokes.forEach { points ->
                    if (points.size == 1) {
                        drawCircle(
                            color = Color.White,
                            radius = visualBrushWidthPx / 2f,
                            center = baseToScreen(points[0])
                        )
                    } else if (points.size >= 2) {
                        for (i in 0 until points.size - 1) {
                            drawLine(
                                color = Color.White,
                                start = baseToScreen(points[i]),
                                end = baseToScreen(points[i + 1]),
                                strokeWidth = visualBrushWidthPx,
                                cap = androidx.compose.ui.graphics.StrokeCap.Round
                            )
                        }
                    }
                }

                val displayRect = rect.rotatedQuarters(rotationQuarter)
                val left = imageLeft + displayRect.left * imageSize.width
                val top = imageTop + displayRect.top * imageSize.height
                val right = imageLeft + displayRect.right * imageSize.width
                val bottom = imageTop + displayRect.bottom * imageSize.height

                val dim = Color.Black.copy(alpha = if (maskMode) 0f else 0.6f)
                drawRect(dim, size = Size(size.width, top))
                drawRect(dim, topLeft = Offset(0f, bottom), size = Size(size.width, size.height - bottom))
                drawRect(dim, topLeft = Offset(0f, top), size = Size(left, bottom - top))
                drawRect(dim, topLeft = Offset(right, top), size = Size(size.width - right, bottom - top))

                if (!maskMode) {
                    for (i in 1..2) {
                        val x = left + (right - left) * i / 3f
                        val y = top + (bottom - top) * i / 3f
                        drawLine(Color.White.copy(alpha = 0.5f), Offset(x, top), Offset(x, bottom), 1f)
                        drawLine(Color.White.copy(alpha = 0.5f), Offset(left, y), Offset(right, y), 1f)
                    }
                }

                drawRect(
                    color = if (maskMode) Color.White.copy(alpha = 0.7f) else Color.White,
                    topLeft = Offset(left, top),
                    size = Size(right - left, bottom - top),
                    style = Stroke(width = 2f)
                )

                if (!maskMode) {
                    // Corners as L-shapes
                    val cornerLength = 40f
                    val strokeW = 8f
                    val color = Color.White

                    // Top Left
                    drawLine(color, Offset(left, top), Offset(left + cornerLength, top), strokeW)
                    drawLine(color, Offset(left, top), Offset(left, top + cornerLength), strokeW)

                    // Top Right
                    drawLine(color, Offset(right, top), Offset(right - cornerLength, top), strokeW)
                    drawLine(color, Offset(right, top), Offset(right, top + cornerLength), strokeW)

                    // Bottom Left
                    drawLine(color, Offset(left, bottom), Offset(left + cornerLength, bottom), strokeW)
                    drawLine(color, Offset(left, bottom), Offset(left, bottom - cornerLength), strokeW)

                    // Bottom Right
                    drawLine(color, Offset(right, bottom), Offset(right - cornerLength, bottom), strokeW)
                    drawLine(color, Offset(right, bottom), Offset(right, bottom - cornerLength), strokeW)

                    // Edges midpoints as small dots
                    val midpoints = listOf(
                        Offset((left + right) / 2, top),
                        Offset((left + right) / 2, bottom),
                        Offset(left, (top + bottom) / 2),
                        Offset(right, (top + bottom) / 2)
                    )
                    midpoints.forEach { center ->
                        drawCircle(color = color, radius = 10f, center = center)
                        drawCircle(color = Color(0xFF4F7DF3), radius = 6f, center = center)
                    }
                }
            }
        }
    }
}

/** 所有按下指针位置的重心。双指变换的平移基准。 */
private fun List<PointerInputChange>.centroid(): Offset {
    if (isEmpty()) return Offset.Zero
    var sx = 0f
    var sy = 0f
    forEach {
        sx += it.position.x
        sy += it.position.y
    }
    return Offset(sx / size, sy / size)
}

/** 所有按下指针到重心的平均距离。双指捏合的缩放基准（>2 指时用均值，稳定且无需选主指）。 */
private fun List<PointerInputChange>.spread(centroid: Offset): Float {
    if (size < 2) return 0f
    var total = 0f
    forEach {
        val dx = it.position.x - centroid.x
        val dy = it.position.y - centroid.y
        total += sqrt(dx * dx + dy * dy)
    }
    return total / size
}

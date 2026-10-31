package com.mistakebook.ui.crop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs

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
    brushWidthPx: Float
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { onViewportChange(it) }
    ) {
        Image(
            bitmap = displayBitmap,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )

        val currentPoints = remember { mutableListOf<Offset>() }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(viewport, rawBitmap, maskMode) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            if (maskMode) {
                                currentPoints.clear()
                                val startPoint = screenToBase(offset.x, offset.y)
                                currentPoints.add(startPoint)
                                onActiveStrokeChange(currentPoints.toList())
                            } else {
                                onHandleDragStart(offset)
                            }
                        },
                        onDragEnd = {
                            if (maskMode) {
                                onStrokeCommit()
                                currentPoints.clear()
                            } else {
                                onHandleDragEnd()
                            }
                        },
                        onDragCancel = {
                            if (maskMode) {
                                currentPoints.clear()
                                onActiveStrokeChange(emptyList())
                            } else {
                                onHandleDragCancel()
                            }
                        },
                        onDrag = { change, dragAmount ->
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
                    )
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
                        radius = brushWidthPx / 2f,
                        center = baseToScreen(points[0])
                    )
                } else if (points.size >= 2) {
                    for (i in 0 until points.size - 1) {
                        drawLine(
                            color = Color.White,
                            start = baseToScreen(points[i]),
                            end = baseToScreen(points[i + 1]),
                            strokeWidth = brushWidthPx,
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
                    drawCircle(color = Color.White, radius = 10f, center = center)
                    drawCircle(color = Color(0xFFB0673A), radius = 6f, center = center)
                }
            }
        }
    }
}

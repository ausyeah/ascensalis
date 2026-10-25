package com.mistakebook.ui.crop

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import com.mistakebook.R
import com.mistakebook.di.AppContainer
import com.mistakebook.ui.common.ApiKeyRequiredDialog
import com.mistakebook.domain.TaskStatus
import com.mistakebook.data.local.entities.CaptureTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 裁剪框（归一化到 0..1 的图片坐标）。
 *
 * 必须保持不可变（val + copy() 产生新实例）：Compose 只会在 State 的「引用」变化时
 * 触发重绘，之前这里是带 var 字段的可变对象，applyHandleDrag 原地改字段界面完全不动，
 * 表现就是「手柄看得见但拖不动、确认后裁出来的还是默认框」。
 */
internal data class CropRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun width() = right - left

    fun height() = bottom - top

    companion object {
        val Default = CropRect(0.06f, 0.08f, 0.94f, 0.92f)
    }
}

private enum class Handle { NONE, MOVE, LEFT, RIGHT, TOP, BOTTOM, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/**
 * 一笔涂鸦，坐标用**原图归一化坐标**（0..1）而不是屏幕像素。
 *
 * ## 关键约定：笔迹永远存在「未旋转的原图」坐标系里
 * 旋转**不会**改写笔迹。屏幕上是哪个朝向，由 [rotationQuarter] 单独决定，
 * 绘制和保存时再各自把笔迹映射过去。
 *
 * 这样做的直接好处：旋转在数学上不可能破坏涂鸦。
 * 旧实现是「位图每转一次就地替换、笔迹跟着做一次变换」，
 * 于是每转一次都是一次出错机会——转错了不报错，只是慢慢跑偏，
 * 而且误差会累积。这套逻辑改了四轮，每轮都在新地方出错。
 */
internal data class MaskStroke(val points: List<Offset>) {
    /** 转到**显示朝向**。只在送往裁剪/预览时调用，屏幕绘制走 [baseToScreen] 同一变换。 */
    fun rotated(quarters: Int): MaskStroke = MaskStroke(
        points.map { rotateNormalized(it.x, it.y, quarters) }
    )
}

/**
 * 归一化坐标的**逆时针 90°** 旋转，[quarters] 为圈数（0..3）。
 *
 * ## 这个公式为什么对任意宽高比都成立
 * 归一化坐标里做旋转变换的常见陷阱，是直接套 `(x,y) -> (1-y, x)`：
 * 那个公式只对**正方形**成立。照片是 4:3 / 3:4，套上去必然错位。
 *
 * 推导（原图 W×H，归一化 (nx, ny) 对应像素 (nx·W, ny·H)）：
 * ```
 * 逆时针 90°： (x, y) -> (y, W - x)      新尺寸 W'=H, H'=W
 * newNx = (ny·H) / H' = (ny·H)/H = ny
 * newNy = (W - nx·W) / W' = W(1-nx)/W = 1 - nx
 * ```
 * 宽高比被约掉了，所以结果就是 `(ny, 1-nx)`——与宽高比无关。
 *
 * ## 方向必须和 [rotate90] 一致
 * 位图用 `postRotate(-90f)`（逆时针），这里也必须是逆时针。
 * 两者不一致 = 遮罩相对图片整体镜像。
 *
 * ## 它是群作用
 * 连用四次回到原点（见 [MASK_ROTATION_PERIOD]），逆变换是 `4 - quarters`。
 * 屏幕坐标 ↔ 原图坐标的来回换算因此不会累积误差。
 */
internal fun rotateNormalized(x: Float, y: Float, quarters: Int): Offset =
    when (((quarters % 4) + 4) % 4) {
        1 -> Offset(y, 1f - x)
        2 -> Offset(1f - x, 1f - y)
        3 -> Offset(1f - y, x)
        else -> Offset(x, y)
    }

/** 旋转一圈（4 次 90°）回到原样。见 [rotateNormalized] 的「它是群作用」。 */
internal const val MASK_ROTATION_PERIOD = 4

/**
 * 把 [CropRect] 整体旋转 [quarters] 圈。
 *
 * 轴对齐矩形转 90° 之后**仍然是轴对齐的**，所以取四个角变换后的 min/max
 * 就是精确结果，不需要近似。
 */
internal fun CropRect.rotatedQuarters(quarters: Int): CropRect {
    val tl = rotateNormalized(left, top, quarters)
    val tr = rotateNormalized(right, top, quarters)
    val br = rotateNormalized(right, bottom, quarters)
    val bl = rotateNormalized(left, bottom, quarters)
    return CropRect(
        left = minOf(tl.x, tr.x, br.x, bl.x),
        top = minOf(tl.y, tr.y, br.y, bl.y),
        right = maxOf(tl.x, tr.x, br.x, bl.x),
        bottom = maxOf(tl.y, tr.y, br.y, bl.y)
    )
}

/**
 * 自研裁剪页（PRD 7.3）：全屏显示 + 可拖拽裁剪框（8 个手柄，热区 40dp），
 * 框外压暗 60%；支持旋转 90°、重置；结果长边压到 1600px、JPEG q85 存盘。
 */
@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun CropScreen(
    container: AppContainer,
    imagePath: String,
    onConfirmed: (List<Long>) -> Unit,
    onOpenSettings: () -> Unit,
    /** Key 门禁里的「改用手动录入」出口，见 [ApiKeyRequiredDialog]。 */
    onManualEntry: () -> Unit,
    onBack: () -> Unit,
    /**
     * 重裁剪模式：录入完成后回来重新框选/旋转原图。
     * 确认后只回填新路径并返回编辑页，不提交识别、也不检查 API Key。
     */
    recropOnly: Boolean = false,
    /**
     * 「重新识别（先裁切再识别）」模式下的题目 id：详情页重新识别入口置上。
     * 确认后先用裁切图换掉题目封面，再提交识别覆盖内容。
     * 与 [recropOnly] 互斥——后者连识别都不跑。
     */
    reRecognizeQuestionId: Long? = null,
    onRecropped: (String) -> Unit = {},
    /**
     * 聊天附件模式（T12）：确认不走识别提交，产物复制进聊天附件暂存
     * 后经 container.pendingChatAttachmentPath 交回对话页。
     * 由 NavHost 传入（container.chatCropping 且已通过互斥校验）。
     */
    chatMode: Boolean = false,
    /** 聊天附件模式确认后的退出：popBackStack 回到对话页。 */
    onChatConfirmed: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 大图解码必须放 IO 线程，并且显式捕获 OOM：runCatching 会连 OutOfMemoryError 一起吞掉，
    // 让内存问题伪装成「拍照失败」。
    //
    // **decodedBitmap 是「未旋转的原图」，全生命周期只被赋值一次，永不被旋转。**
    // 屏幕上看到的朝向由 [rotationQuarter] 单独决定。分离这两者是这次重构的全部意义：
    // 旧实现把位图本身当作旋转状态，每次旋转都新建一张位图并就地替换，
    // 连带要求笔迹跟着做坐标变换——于是旋转成了会「破坏」涂鸦的操作。
    var decodedBitmap by remember(imagePath) { mutableStateOf<Bitmap?>(null) }
    var decodeError by remember(imagePath) { mutableStateOf<String?>(null) }
    var decoding by remember(imagePath) { mutableStateOf(true) }

    LaunchedEffect(imagePath) {
        decodedBitmap = null
        decoding = true
        decodeError = null
        val file = File(imagePath)
        val decoded = withContext(Dispatchers.IO) {
            when {
                !file.exists() -> {
                    Log.e(CROP_TAG, "照片文件不存在: $imagePath")
                    null
                }

                file.length() == 0L -> {
                    Log.e(CROP_TAG, "照片文件为空: $imagePath")
                    null
                }

                else -> try {
                    decodeWithExif(imagePath)
                } catch (oom: OutOfMemoryError) {
                    // 必须显式捕获，否则会被上层当成普通失败
                    Log.e(CROP_TAG, "解码 OOM: size=${file.length()} path=$imagePath", oom)
                    null
                } catch (error: Throwable) {
                    Log.e(CROP_TAG, "解码失败: size=${file.length()} path=$imagePath", error)
                    null
                }
            }
        }
        decodedBitmap = decoded
        decoding = false
        decodeError = when {
            decoded != null -> null
            !file.exists() || file.length() == 0L -> context.getString(R.string.crop_decode_missing)
            else -> context.getString(R.string.crop_decode_oom)
        }
    }
    var rotationQuarter by remember { mutableStateOf(0) }
    var rect by remember { mutableStateOf(CropRect.Default) }
    var activeHandle by remember { mutableStateOf(Handle.NONE) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var showKeyGate by remember { mutableStateOf(false) }
    var mineruMissing by remember { mutableStateOf(false) }
    var llmMissing by remember { mutableStateOf(false) }

    // 涂鸦遮蔽：把不想识别的区域（红笔批注、旁边的题、草稿）涂白
    var maskMode by remember { mutableStateOf(false) }
    var strokes by remember { mutableStateOf<List<MaskStroke>>(emptyList()) }
    var activeStroke by remember { mutableStateOf<List<Offset>>(emptyList()) }

    // T14：双指缩放/平移。zoom 是**视图级**变换，与 [rotationQuarter]（bitmap 级）正交：
    // 坐标链 = 屏幕 → zoom 逆变换 → 现有旋转换算（见 screenToBase/baseToScreen）。
    // 状态提升到本 composable 持有（不随 recomposition 丢失、旋转屏幕保持缩放不变），
    // 经 CropOverlay 的 onTransform 回写。pan 的钳制基准是**图片实际显示尺寸**，
    // 不能用预览弹窗里 1500f/2000f 那对硬编码——那是预览自己的尺寸。
    var zoomScale by remember { mutableStateOf(1f) }
    var zoomPan by remember { mutableStateOf(Offset.Zero) }

    val sessionStore = remember(container) { CropSessionStore(container.settingsStore) }

    /**
     * 恢复上次的裁剪框 / 笔迹 / 旋转。
     *
     * ## 为什么现在这么短
     * 旧实现是 `LaunchedEffect(imagePath, bitmap)`，body 里会**重放**已保存的旋转
     * （`repeat(quarters) { rotate90() }`）并写回 `bitmap`。
     * 两个致命问题叠在一起：
     *
     * 1. **自我触发的死循环**——`bitmap` 既是 key 又是被写的状态。每写一次换一次 key、
     *    effect 重跑一次、再写一次。表现是用户点两下旋转后图片开始「正反跳动」：
     *    每圈叠了多次旋转，转满 4 次回到原位，看起来像在两个方向之间来回弹。
     * 2. **误差累积**——每次重放都是一次浮点运算和一次重新解码的位图，
     *    叠加起来遮罩会相对图片慢慢跑偏。
     *
     * 现在位图不再被旋转（见 [rotationQuarter]），笔迹与裁剪框本来就存在
     * 原图坐标系，所以恢复只是**原样赋值**，没有任何需要重放的东西。
     */
    LaunchedEffect(imagePath, decodedBitmap) {
        val session = sessionStore.load(imagePath) ?: return@LaunchedEffect
        rotationQuarter = ((session.rotationQuarter % MASK_ROTATION_PERIOD) + MASK_ROTATION_PERIOD) %
            MASK_ROTATION_PERIOD
        rect = CropRect(session.left, session.top, session.right, session.bottom)
        strokes = session.strokes.toMaskStrokes()
    }

    // 每一步操作后都落盘：用户要求「裁切后涂鸦、涂鸦后裁切，
    // 始终保证每一步做完都保存状态，然后在此基础上实现下一步编辑」。
    // 只在「松手」「点按钮」这些动作边界保存，不在拖拽过程中保存——
    // 每个 drag event 都写 DataStore 会把 IO 打满。
    //
    // 注意存的是**原图坐标系**的 rect / strokes + 圈数，两者都是原样落盘，
    // 不做任何旋转变换。旋转由 [rotationQuarter] 单独表达。
    suspend fun persistSession() {
        sessionStore.save(
            imagePath,
            CropSession(
                left = rect.left,
                top = rect.top,
                right = rect.right,
                bottom = rect.bottom,
                rotationQuarter = ((rotationQuarter % MASK_ROTATION_PERIOD) + MASK_ROTATION_PERIOD) %
                    MASK_ROTATION_PERIOD,
                strokes = strokes.toStrokePoints()
            )
        )
    }

    val density = LocalDensity.current
    val touchSlop = with(density) { 40.dp.toPx() }
    val brushWidthPx = with(density) { 26.dp.toPx() }

    // 「识别图预览」：把即将送去 MinerU 的那张图摊开给用户看
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var previewing by remember { mutableStateOf(false) }

    if (decoding || decodedBitmap == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when {
                decoding -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.crop_decoding))
                }

                else -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(32.dp)
                ) {
                    Text(
                        text = decodeError ?: stringResource(R.string.crop_decode_failed, "未知原因"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.crop_back), color = Color.White)
                    }
                }
            }
        }
        return
    }

    val base = decodedBitmap ?: return

    /**
     * 屏幕上显示的位图 = 原图转 [rotationQuarter] 圈。
     *
     * ## 为什么用朝向缓存，而不是每次旋转都新建 + 立刻回收旧的
     * 「新建即回收旧图」有个致命竞态：用户点旋转时，如果正好有一次
     * 保存/预览还在 IO 线程上读那张旧位图，回收会让它变成已释放的原生内存，
     * 直接 native crash——而且是偶发的、极难复现的那种。
     *
     * 缓存按圈数存，最多 4 张，且**只有用户真正转到的朝向才会被创建**。
     * 同一朝向反复旋转（转 4 圈回到原位再转）复用同一张，不产生额外分配。
     * 回收推迟到离开页面时，那时组合作用域的协程已取消，不会有任务在读。
     */
    val rotatedCache = remember(base) { HashMap<Int, Bitmap>() }
    val displayBitmap = remember(base, rotationQuarter) {
        val quarters = rotationQuarter
        rotatedCache.getOrPut(quarters) {
            var current = base
            repeat(quarters) { current = rotate90(current) }
            current
        }
    }
    DisposableEffect(base) {
        onDispose {
            // base 本身不归这里管（displayBitmap 在未旋转时就等于 base）
            rotatedCache.values.forEach { if (it !== base) it.recycle() }
            rotatedCache.clear()
        }
    }

    // 图片在 FIT 布局下实际占据的区域（按**显示朝向**的尺寸算）
    val imageSize = computeFitSize(displayBitmap.width, displayBitmap.height, viewport)
    val imageLeft = (viewport.width - imageSize.width) / 2f
    val imageTop = (viewport.height - imageSize.height) / 2f

    // T14：pan 按图片显示尺寸方向性钳制。graphicsLayer 默认绕中心缩放，
    // 缩放 s 后图片边缘最多外扩 (s-1)*尺寸/2，pan 超过这个范围就会把图片拖出视口。
    // 旋转会交换宽高，这里每次重组都按当前朝向的 imageSize 重算——
    // 状态里的 zoomPan 即使越界（旋转后旧 pan 不再合法）也会被钳回，渲染与坐标变换用的是钳制后的值。
    val maxPanX = (zoomScale - 1f) * imageSize.width / 2f
    val maxPanY = (zoomScale - 1f) * imageSize.height / 2f
    val zoomPanClamped = Offset(
        zoomPan.x.coerceIn(-maxPanX, maxPanX),
        zoomPan.y.coerceIn(-maxPanY, maxPanY)
    )

    /** 屏幕坐标 -> 显示朝向的归一化坐标（0..1） */
    fun toDisplayX(x: Float): Float = ((x - imageLeft) / imageSize.width).coerceIn(0f, 1f)
    fun toDisplayY(y: Float): Float = ((y - imageTop) / imageSize.height).coerceIn(0f, 1f)
    fun toScreenX(fraction: Float): Float = imageLeft + fraction * imageSize.width
    fun toScreenY(fraction: Float): Float = imageTop + fraction * imageSize.height

    /** 屏幕坐标 -> **原图**归一化坐标。笔迹与裁剪框都存在这个坐标系里。 */
    fun screenToBase(x: Float, y: Float): Offset {
        // T14：zoom 逆变换包在最外层——屏幕坐标先减 pan、除 scale 回到视图（FIT）坐标，
        // 再走原有旋转换算。zoom == 1f 且 pan 为 0 时本段是恒等变换，行为与改造前逐项一致。
        val viewX = (x - zoomPanClamped.x) / zoomScale
        val viewY = (y - zoomPanClamped.y) / zoomScale
        return rotateNormalized(
            toDisplayX(viewX),
            toDisplayY(viewY),
            MASK_ROTATION_PERIOD - rotationQuarter
        )
    }

    /** 原图归一化坐标 -> 屏幕坐标。绘制遮罩与裁剪框时用。 */
    fun baseToScreen(p: Offset): Offset {
        val d = rotateNormalized(p.x, p.y, rotationQuarter)
        // T14：zoom 正变换——视图坐标乘 scale 再加 pan，与 screenToBase 严格互逆。
        // 导出/预览走 base 坐标系（saveCrop/buildPreviewBitmap），天然不受 zoom 影响，
        // 所以「放大状态下确认导出的结果与未放大一致」是这套设计的必然结果，不需要额外代码。
        return Offset(
            toScreenX(d.x) * zoomScale + zoomPanClamped.x,
            toScreenY(d.y) * zoomScale + zoomPanClamped.y
        )
    }

    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.crop_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.crop_back))
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                previewing = true
                                val preview = withContext(Dispatchers.IO) {
                                    buildPreviewBitmap(
                                        displayBitmap,
                                        rect.rotatedQuarters(rotationQuarter),
                                        strokes.map { it.rotated(rotationQuarter) },
                                        brushWidthPx,
                                        imageSize.width.toFloat()
                                    )
                                }
                                previewBitmap = preview
                                previewing = false
                                if (preview == null) {
                                    errorText = context.getString(R.string.crop_too_small)
                                }
                            }
                        },
                        enabled = !previewing
                    ) {
                        Text(
                            text = stringResource(R.string.crop_preview),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.5f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        bottomBar = {
            CropToolbar(
                maskMode = maskMode,
                onMaskModeChange = { newMode ->
                    if (!newMode && activeStroke.isNotEmpty()) {
                        strokes = strokes + MaskStroke(activeStroke)
                        activeStroke = emptyList()
                        scope.launch { persistSession() }
                    }
                    maskMode = newMode
                },
                hasStrokes = strokes.isNotEmpty(),
                onUndo = {
                    strokes = strokes.dropLast(1)
                    scope.launch { persistSession() }
                },
                onClear = {
                    strokes = emptyList()
                    activeStroke = emptyList()
                    scope.launch { persistSession() }
                },
                onRotate = {
                    rotationQuarter = (rotationQuarter + 1) % MASK_ROTATION_PERIOD
                    activeStroke = emptyList()
                    activeHandle = Handle.NONE
                    scope.launch { persistSession() }
                },
                onReset = {
                    rect = CropRect.Default
                    strokes = emptyList()
                    activeStroke = emptyList()
                    // T14：重置必须连缩放一起复位，只重置裁切框的话，
                    // 用户在 3x 下点重置会看到「框回去了但图还放着」的分裂状态
                    zoomScale = 1f
                    zoomPan = Offset.Zero
                    scope.launch { persistSession() }
                },
                onConfirm = {
                    scope.launch {
                        val committed = strokes
                        val cropRect = rect.rotatedQuarters(rotationQuarter)
                        val cropStrokes = committed.map { it.rotated(rotationQuarter) }
                        if (recropOnly) {
                            val saved = withContext(Dispatchers.IO) {
                                saveCrop(
                                    displayBitmap, cropRect, container, cropStrokes,
                                    brushWidthPx, imageSize.width.toFloat()
                                )
                            }
                            if (saved != null) {
                                sessionStore.clear(imagePath)
                                onRecropped(saved.absolutePath)
                            } else {
                                errorText = context.getString(R.string.crop_too_small)
                            }
                            return@launch
                        }
                        // 聊天附件模式（T12）：不走 RecognitionSubmitter、不查 Key。
                        // 产物复制进暂存目录后经容器标志交回对话页。
                        // 涂鸦遮蔽等全部能力照常可用（遮住草稿对批改很有用）。
                        if (chatMode) {
                            val saved = withContext(Dispatchers.IO) {
                                saveCrop(
                                    displayBitmap, cropRect, container, cropStrokes,
                                    brushWidthPx, imageSize.width.toFloat()
                                )
                            }
                            if (saved == null) {
                                errorText = context.getString(R.string.crop_too_small)
                                return@launch
                            }
                            sessionStore.clear(imagePath)
                            val stagingPath = withContext(Dispatchers.IO) {
                                val dir = container.files.chatAttachmentStagingDir().apply { mkdirs() }
                                val target = File(dir, "chat_crop_${System.currentTimeMillis()}.jpg")
                                saved.copyTo(target, overwrite = true)
                                target.absolutePath
                            }
                            container.pendingChatAttachmentPath = stagingPath
                            container.chatCropping = false
                            onChatConfirmed()
                            return@launch
                        }
                        // 重新识别（先裁切再识别）：详情页入口进来。
                        // 裁切涂鸦完成后——先用新图换掉题目封面/显示图，
                        // 再按原图的语义提交识别覆盖内容。用户在裁切页里
                        // 已经把图修到满意，不需要再回确认对话框。
                        val replacesQuestionId = reRecognizeQuestionId
                        if (replacesQuestionId != null) {
                            val snapshot = container.settingsStore.snapshotNow()
                            if (!snapshot.mineruConfigured || !snapshot.llmConfigured) {
                                mineruMissing = !snapshot.mineruConfigured
                                llmMissing = !snapshot.llmConfigured
                                showKeyGate = true
                                return@launch
                            }
                            val saved = withContext(Dispatchers.IO) {
                                saveCrop(
                                    displayBitmap, cropRect, container, cropStrokes,
                                    brushWidthPx, imageSize.width.toFloat()
                                )
                            }
                            if (saved == null) {
                                errorText = context.getString(R.string.crop_too_small)
                                return@launch
                            }
                            sessionStore.clear(imagePath)
                            // 先换封面：识别要跑几十秒，这期间详情页/列表页
                            // 看到的应该已经是裁好的新图，而不是旧原图。
                            container.questionRepository.updateImagePath(
                                replacesQuestionId,
                                saved.absolutePath
                            )
                            val taskId = container.recognitionSubmitter.reRecognize(
                                file = saved,
                                replacesQuestionId = replacesQuestionId,
                                groupTitle = "重新识别"
                            )
                            if (taskId == null) {
                                errorText = context.getString(R.string.detail_re_recognize_failed)
                            } else {
                                onConfirmed(listOf(taskId))
                            }
                            return@launch
                        }
                        val snapshot = container.settingsStore.snapshotNow()
                        if (!snapshot.mineruConfigured || !snapshot.llmConfigured) {
                            mineruMissing = !snapshot.mineruConfigured
                            llmMissing = !snapshot.llmConfigured
                            showKeyGate = true
                            return@launch
                        }

                        val saved = withContext(Dispatchers.IO) {
                            saveCrop(
                                displayBitmap, cropRect, container, cropStrokes,
                                brushWidthPx, imageSize.width.toFloat()
                            )
                        }
                        if (saved != null) {
                            val title = if (container.cropSourceIsGallery) {
                                "相册导入"
                            } else {
                                "拍照识别"
                            }
                            container.cropSourceIsGallery = false
                            val ids = container.recognitionSubmitter.submitImages(listOf(saved), title)
                            onConfirmed(ids)
                        } else {
                            errorText = context.getString(R.string.crop_too_small)
                        }
                    }
                }
            )
        },
        containerColor = Color.Black
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            CropOverlay(
                displayBitmap = displayBitmap.asImageBitmap(),
                rawBitmap = base,
                viewport = viewport,
                onViewportChange = { viewport = it },
                maskMode = maskMode,
                strokes = strokes,
                activeStroke = activeStroke,
                onActiveStrokeChange = { activeStroke = it },
                onStrokeCommit = {
                    if (activeStroke.isNotEmpty()) {
                        strokes = strokes + MaskStroke(activeStroke)
                        activeStroke = emptyList()
                        scope.launch { persistSession() }
                    }
                },
                rect = rect,
                rotationQuarter = rotationQuarter,
                onRectChange = { newRect ->
                    rect = newRect
                    scope.launch { persistSession() }
                },
                onHandleDragStart = { offset ->
                    activeHandle = pickHandle(
                        offset = offset,
                        rect = rect.rotatedQuarters(rotationQuarter),
                        // 手柄在屏幕上的实际位置也要经 zoom 正变换（baseToScreen 同款），
                        // 否则放大后热区还停在未放大的旧位置，点不中。slop 本身仍是屏幕 32dp，保持可点性。
                        toScreenX = { f -> toScreenX(f) * zoomScale + zoomPanClamped.x },
                        toScreenY = { f -> toScreenY(f) * zoomScale + zoomPanClamped.y },
                        slop = touchSlop
                    )
                },
                onHandleDragEnd = {
                    activeHandle = Handle.NONE
                    scope.launch { persistSession() }
                },
                onHandleDragCancel = {
                    activeHandle = Handle.NONE
                },
                onHandleDrag = { dragAmount ->
                    // T14：放大时同样的手指位移对应更小的归一化位移，除掉 zoomScale 后
                    // 手柄移动才和指尖跟手；未放大时除 1f 与原来逐字一致。
                    val dx = dragAmount.x / zoomScale / imageSize.width
                    val dy = dragAmount.y / zoomScale / imageSize.height
                    val displayRect = draggedRect(
                        rect.rotatedQuarters(rotationQuarter),
                        activeHandle,
                        dx,
                        dy
                    )
                    rect = displayRect.rotatedQuarters(MASK_ROTATION_PERIOD - rotationQuarter)
                },
                imageLeft = imageLeft,
                imageTop = imageTop,
                imageSize = imageSize,
                baseToScreen = ::baseToScreen,
                screenToBase = ::screenToBase,
                brushWidthPx = brushWidthPx,
                zoomScale = zoomScale,
                zoomPan = zoomPanClamped,
                onTransform = { zoomFactor, panDelta ->
                    // zoomFactor 是相对上一帧的比值，乘出来再钳制在 1f..5f
                    zoomScale = (zoomScale * zoomFactor).coerceIn(1f, 5f)
                    // pan 增量是屏幕像素；每次按最新 scale 重新钳制在图片范围内
                    val maxX = (zoomScale - 1f) * imageSize.width / 2f
                    val maxY = (zoomScale - 1f) * imageSize.height / 2f
                    zoomPan = Offset(
                        (zoomPan.x + panDelta.x).coerceIn(-maxX, maxX),
                        (zoomPan.y + panDelta.y).coerceIn(-maxY, maxY)
                    )
                },
                onZoomReset = {
                    zoomScale = 1f
                    zoomPan = Offset.Zero
                }
            )

            if (showKeyGate) {
                ApiKeyRequiredDialog(
                    mineruMissing = mineruMissing,
                    llmMissing = llmMissing,
                    onOpenSettings = onOpenSettings,
                    onManualEntry = onManualEntry,
                    onDismiss = { showKeyGate = false }
                )
            }

            if (errorText != null) {
                Text(
                    text = errorText.orEmpty(),
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp)
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(12.dp)
                )
            }
        }
    }

    val preview = previewBitmap
    if (preview != null) {
        var scale by remember { mutableStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val transformableState = rememberTransformableState { zoomChange, offsetChange, _ ->
            scale = (scale * zoomChange).coerceIn(1f, 5f)
            // 限制在安全范围内（避免原图像素过小导致无法平移）
            val maxX = (scale - 1) * 1500f
            val maxY = (scale - 1) * 2000f
            offset = Offset(
                x = (offset.x + offsetChange.x).coerceIn(-maxX, maxX),
                y = (offset.y + offsetChange.y).coerceIn(-maxY, maxY)
            )
        }
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { previewBitmap = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                Image(
                    bitmap = preview.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y
                        )
                        .transformable(state = transformableState)
                )
                TopAppBar(
                    title = {
                        Text(
                            stringResource(
                                if (chatMode) R.string.crop_preview_title_chat else R.string.crop_preview_title
                            ),
                            color = Color.White
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { previewBitmap = null }) {
                            Icon(androidx.compose.material.icons.Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(alpha = 0.5f))
                )
            }
        }
    }

}

private fun pickHandle(
    offset: Offset,
    rect: CropRect,
    toScreenX: (Float) -> Float,
    toScreenY: (Float) -> Float,
    slop: Float
): Handle {
    val left = toScreenX(rect.left)
    val right = toScreenX(rect.right)
    val top = toScreenY(rect.top)
    val bottom = toScreenY(rect.bottom)
    val nearLeft = abs(offset.x - left) < slop
    val nearRight = abs(offset.x - right) < slop
    val nearTop = abs(offset.y - top) < slop
    val nearBottom = abs(offset.y - bottom) < slop
    return when {
        nearLeft && nearTop -> Handle.TOP_LEFT
        nearRight && nearTop -> Handle.TOP_RIGHT
        nearLeft && nearBottom -> Handle.BOTTOM_LEFT
        nearRight && nearBottom -> Handle.BOTTOM_RIGHT
        nearTop -> Handle.TOP
        nearBottom -> Handle.BOTTOM
        nearLeft -> Handle.LEFT
        nearRight -> Handle.RIGHT
        offset.x in left..right && offset.y in top..bottom -> Handle.MOVE
        else -> Handle.NONE
    }
}

private fun draggedRect(rect: CropRect, handle: Handle, dx: Float, dy: Float): CropRect {
    val minSize = 0.08f
    return when (handle) {
        Handle.LEFT -> rect.copy(left = min(rect.left + dx, rect.right - minSize).coerceAtLeast(0f))
        Handle.RIGHT -> rect.copy(right = max(rect.right + dx, rect.left + minSize).coerceAtMost(1f))
        Handle.TOP -> rect.copy(top = min(rect.top + dy, rect.bottom - minSize).coerceAtLeast(0f))
        Handle.BOTTOM -> rect.copy(bottom = max(rect.bottom + dy, rect.top + minSize).coerceAtMost(1f))
        Handle.TOP_LEFT -> draggedRect(draggedRect(rect, Handle.LEFT, dx, 0f), Handle.TOP, 0f, dy)
        Handle.TOP_RIGHT -> draggedRect(draggedRect(rect, Handle.RIGHT, dx, 0f), Handle.TOP, 0f, dy)
        Handle.BOTTOM_LEFT -> draggedRect(draggedRect(rect, Handle.LEFT, dx, 0f), Handle.BOTTOM, 0f, dy)
        Handle.BOTTOM_RIGHT -> draggedRect(draggedRect(rect, Handle.RIGHT, dx, 0f), Handle.BOTTOM, 0f, dy)
        Handle.MOVE -> {
            val w = rect.width()
            val h = rect.height()
            val left = (rect.left + dx).coerceIn(0f, 1f - w)
            val top = (rect.top + dy).coerceIn(0f, 1f - h)
            rect.copy(left = left, top = top, right = left + w, bottom = top + h)
        }

        Handle.NONE -> rect
    }
}

private fun computeFitSize(bitmapWidth: Int, bitmapHeight: Int, viewport: IntSize): IntSize {
    if (viewport.width == 0 || viewport.height == 0) return IntSize(0, 0)
    val scale = min(
        viewport.width.toFloat() / bitmapWidth,
        viewport.height.toFloat() / bitmapHeight
    )
    return IntSize(
        (bitmapWidth * scale).toInt(),
        (bitmapHeight * scale).toInt()
    )
}

/**
 * 逆时针旋转 90°。
 *
 * 需求是「默认逆时针」：手举手机拍纸质题目时，为了让文字正过来，
 * 用户心里的动作是「把手机往左转」——那是逆时针。
 * `postRotate` 正角度是顺时针，所以这里用 -90f。
 */
private fun rotate90(source: Bitmap): Bitmap {
    val matrix = Matrix().apply { postRotate(-90f) }
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
}

private const val CROP_TAG = "MistakeBookCrop"

// 拍照文件带 EXIF 方向（CameraX 只写标记不转像素），这里解码后按方向转正。
private fun decodeWithExif(path: String): Bitmap? {
    val file = File(path)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    val options = BitmapFactory.Options().apply {
        // 裁完要送 MinerU 做 OCR，必须保持 ARGB_8888，不能用 RGB_565
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, CROP_MAX_EDGE)
    }
    Log.i(
        CROP_TAG,
        "decode file=${file.length()}B bounds=${bounds.outWidth}x${bounds.outHeight}" +
            " mime=${bounds.outMimeType} sample=${options.inSampleSize}"
    )
    val decoded = BitmapFactory.decodeFile(path, options) ?: return null
    val degrees = runCatching {
        val exif = android.media.ExifInterface(path)
        when (
            exif.getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_NORMAL
            )
        ) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    }.getOrDefault(0f)
    if (degrees == 0f) return decoded
    val rotated = rotateDegrees(decoded, degrees)
    if (rotated !== decoded) {
        // 纯旋转时 createBitmap 可能复用原对象，判一下再回收，避免把自己回收掉
        decoded.recycle()
    }
    return rotated
}

private fun rotateDegrees(source: Bitmap, degrees: Float): Bitmap {
    val matrix = Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
}

private fun sampleSizeFor(width: Int, height: Int, maxEdge: Int): Int {
    if (width <= 0 || height <= 0) return 1
    var sample = 1
    var longEdge = maxOf(width, height)
    // 必须保证降采样后的长边 <= maxEdge；旧写法 (longEdge / 2 >= maxEdge) 会让 12MP 原图
    // 算出 inSampleSize=1，等于按 4032×3024 全尺寸解码（单张约 48.8MB），直接触发 OOM。
    while (longEdge > maxEdge) {
        longEdge /= 2
        sample *= 2
    }
    return sample
}

private const val CROP_MAX_EDGE = 1600

/**
 * 裁剪 + 缩放 + 涂白遮罩 + 存盘。
 *
 * ## 坐标换算（这里改错过一次，别再动）
 *
 * 笔迹 [strokes] 存的是**整图归一化坐标**（0..1，横跨整张照片），
 * 而 [target] 是**裁剪之后**的位图。两套坐标系，必须换算。
 *
 * 正确做法：先把归一化坐标还原成**整图像素坐标**，再减去裁剪原点：
 * ```
 * cropX = nx * bitmap.width  - cropOriginX
 * brushWidth = brushScreenPx * (bitmap.width / referenceWidthPx)
 * ```
 *
 * 之前写错成 `nx * croppedWidth`（漏了减裁剪原点）和
 * `brushScreenPx * (cropWidth / referenceWidthPx)`（漏了除以 rect.width()），
 * 结果是：裁剪框越靠右/越收紧，遮罩偏得越远、笔画细到看不见——
 * 用户看到的就是「裁切的图片根本没管涂鸦的变化」。
 */
private fun saveCrop(
    bitmap: Bitmap,
    rect: CropRect,
    container: AppContainer,
    strokes: List<MaskStroke>,
    brushScreenPx: Float,
    referenceWidthPx: Float
): File? {
    val width = (bitmap.width * rect.width()).toInt()
    val height = (bitmap.height * rect.height()).toInt()
    // 最小裁剪尺寸 100x100 像素当量（原图尺寸下）
    if (width < 100 || height < 100) return null
    val x = (bitmap.width * rect.left).toInt().coerceIn(0, bitmap.width - width)
    val y = (bitmap.height * rect.top).toInt().coerceIn(0, bitmap.height - height)
    val cropped = Bitmap.createBitmap(bitmap, x, y, width, height)
    if (strokes.isNotEmpty() && referenceWidthPx > 0f) {
        // 笔宽按「整图缩放比」换算，与裁剪框大小无关：
        // 用户画的时候，笔的粗细只跟图片在屏幕上的显示大小有关
        val brushInBitmapPx = brushScreenPx * (bitmap.width / referenceWidthPx)
        applyWhiteMask(cropped, strokes, bitmap, x, y, brushInBitmapPx)
    }
    val scaled = scaleLongEdge(cropped, MAX_LONG_EDGE)
    val target = container.files.newCropFile()
    FileOutputStream(target).use { stream ->
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, stream)
    }
    return target
}

/**
 * 生成「即将送去识别的那张图」，**不落盘**。
 *
 * 走的是与 [saveCrop] **完全相同**的裁剪 + 遮罩换算路径——
 * 如果这里另写一套，预览和实际结果不一致的 bug 会原样复现。
 * 两者共用同一段逻辑是这个函数存在的全部意义。
 */
private fun buildPreviewBitmap(
    bitmap: Bitmap,
    rect: CropRect,
    strokes: List<MaskStroke>,
    brushScreenPx: Float,
    referenceWidthPx: Float
): Bitmap? {
    val width = (bitmap.width * rect.width()).toInt()
    val height = (bitmap.height * rect.height()).toInt()
    if (width < 100 || height < 100) return null
    val x = (bitmap.width * rect.left).toInt().coerceIn(0, bitmap.width - width)
    val y = (bitmap.height * rect.top).toInt().coerceIn(0, bitmap.height - height)
    val cropped = Bitmap.createBitmap(bitmap, x, y, width, height)
    if (strokes.isNotEmpty() && referenceWidthPx > 0f) {
        val brushInBitmapPx = brushScreenPx * (bitmap.width / referenceWidthPx)
        applyWhiteMask(cropped, strokes, bitmap, x, y, brushInBitmapPx)
    }
    // 不再缩放：预览要如实反映遮罩位置，缩放会引入额外的插值误差
    return cropped
}

/**
 * 把笔迹画成不透明白色，直接涂在裁剪结果上。
 *
 * [bitmap] 是裁剪前的整图（提供像素尺寸），[originX]/[originY] 是裁剪原点。
 * 笔迹按整图像素坐标定位后减去原点，得到在 [target] 上的位置。
 *
 * 用不透明纯白而不是半透明：MinerU 会把淡淡的灰当成「淡淡的字」照样识别，
 * 半透明遮罩等于没遮。宁可直接把那块内容抹掉。
 */
private fun applyWhiteMask(
    target: Bitmap,
    strokes: List<MaskStroke>,
    bitmap: Bitmap,
    originX: Int,
    originY: Int,
    brushPx: Float
) {
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.WHITE
        isAntiAlias = true
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = brushPx
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    val canvas = android.graphics.Canvas(target)

    /** 整图归一化坐标 -> 裁剪结果内的像素坐标 */
    fun localX(nx: Float): Float = nx * bitmap.width - originX
    fun localY(ny: Float): Float = ny * bitmap.height - originY

    strokes.forEach { stroke ->
        val points = stroke.points
        if (points.isEmpty()) return@forEach
        if (points.size == 1) {
            // 单点也要看得见遮罩：画一个点，否则用户以为没生效
            canvas.drawPoint(localX(points[0].x), localY(points[0].y), paint)
            return@forEach
        }
        for (i in 0 until points.size - 1) {
            canvas.drawLine(
                localX(points[i].x),
                localY(points[i].y),
                localX(points[i + 1].x),
                localY(points[i + 1].y),
                paint
            )
        }
    }
}

private fun scaleLongEdge(bitmap: Bitmap, maxLongEdge: Int): Bitmap {
    val longEdge = max(bitmap.width, bitmap.height)
    if (longEdge <= maxLongEdge) return bitmap
    val ratio = maxLongEdge.toFloat() / longEdge
    return Bitmap.createScaledBitmap(
        bitmap,
        (bitmap.width * ratio).toInt().coerceAtLeast(1),
        (bitmap.height * ratio).toInt().coerceAtLeast(1),
        true
    )
}

private const val MAX_LONG_EDGE = 1600

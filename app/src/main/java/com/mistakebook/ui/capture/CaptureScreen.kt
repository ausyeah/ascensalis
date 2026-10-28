package com.mistakebook.ui.capture

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mistakebook.R
import com.mistakebook.di.AppContainer
import com.mistakebook.di.CaptureBatch
import com.mistakebook.ui.common.ApiKeyRequiredDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val FLASH_AUTO = 0
private const val FLASH_ON = 1
private const val FLASH_OFF = 2

private const val GALLERY_MAX_ITEMS = 20

/**
 * 拍照页（CameraX，PRD 7.2）：全屏预览 + 3×3 网格 + 闪光灯/前后摄切换，
 * 快门在左下方是相册入口（支持多选批量导入）。
 *
 * F6 连拍模式：QUESTION 用途下快门拍完**留在本页**，右下角显示
 * 「本批 N 张」与「完成」；点完成才进入逐张裁切（CropScreen 接力）。
 * CHAT 用途保持单张直走裁切，行为与连拍上线前完全一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(
    container: AppContainer,
    onCropped: (String) -> Unit,
    onOpenSettings: () -> Unit,
    /** Key 门禁里的「改用手动录入」出口，见 [ApiKeyRequiredDialog]。 */
    onManualEntry: () -> Unit,
    onBack: () -> Unit,
    /**
     * F6：点「完成」——NavHost 据此进入逐张裁切流；若批次已全部裁完
     * （从裁切页返回后 paths 空、cropped 非空）则直接提交识别。
     */
    onStartBatchCrop: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionRequested by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission && !permissionRequested) {
            permissionRequested = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    var flashMode by remember { mutableIntStateOf(FLASH_AUTO) }
    var flashAvailable by remember { mutableStateOf<Boolean?>(null) }
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var capturing by remember { mutableStateOf(false) }

    // 照片对比度增强开关：进页面读一次设置，改设置后重进页面生效
    var ocrStrength by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        ocrStrength = runCatching { container.settingsStore.snapshotNow().ocrStrength }
            .getOrDefault(2)
    }
    var importing by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var cameraBound by remember { mutableStateOf(false) }
    var showKeyGate by remember { mutableStateOf(false) }
    var mineruMissing by remember { mutableStateOf(false) }
    var llmMissing by remember { mutableStateOf(false) }
    suspend fun checkRecognitionKeys(): Boolean {
        val snapshot = runCatching { container.settingsStore.snapshotNow() }.getOrNull()
        mineruMissing = snapshot?.mineruConfigured != true
        llmMissing = snapshot?.llmConfigured != true
        if (mineruMissing || llmMissing) {
            showKeyGate = true
            return false
        }
        return true
    }
    val executor: ExecutorService = remember {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "mistakebook-camera").apply { isDaemon = true }
        }
    }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }

    // F6：QUESTION 用途走多选——选中的图全部进入同一连拍批次，
    // 之后和拍照攒的批次走完全相同的逐张裁切流。
    // CHAT 用途保持单选：对话附件一次只要一张。
    val galleryMultiLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(CaptureBatch.MAX)
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            // CHAT 用途跳过 Key 门禁（T11）：附件与批改不强依赖 Key，
            // 发消息时才按现有逻辑提示
            if (container.cameraPurpose == com.mistakebook.di.AppContainer.CameraPurpose.QUESTION &&
                !checkRecognitionKeys()
            ) return@launch
            importing = true
            val files = container.imageImporter.importUris(uris)
            importing = false
            if (files.isEmpty()) {
                errorText = context.getString(R.string.gallery_copy_failed, "")
            } else {
                var overflow = false
                files.forEach { file ->
                    if (!container.captureBatch.add(file.absolutePath, fromGallery = true)) {
                        overflow = true
                    }
                }
                if (overflow) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.capture_batch_full),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                // 系统选择器里已经选完了，直接开裁第一张，不用再按「完成」
                onCropped(container.captureBatch.paths.first())
            }
        }
    }

    // T2 对话连拍：CHAT 相册多选——全部导入批次，攒完统一走「完成」→ 接力裁切。
    val galleryChatMultiLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(9)
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            importing = true
            val files = container.imageImporter.importUris(uris)
            importing = false
            if (files.isEmpty()) {
                errorText = context.getString(R.string.gallery_copy_failed, "")
            } else {
                var overflow = false
                files.forEach { file ->
                    if (!container.captureBatch.add(file.absolutePath, fromGallery = true)) {
                        overflow = true
                    }
                }
                if (overflow) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.capture_chat_batch_full),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
    // CHAT 用途的单相册入口：一次一张，和连拍上线前行为一致。
    val gallerySingleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            // CHAT 用途跳过 Key 门禁（T11）：附件与批改不强依赖 Key，
            // 发消息时才按现有逻辑提示
            if (container.cameraPurpose == com.mistakebook.di.AppContainer.CameraPurpose.QUESTION &&
                !checkRecognitionKeys()
            ) return@launch
            importing = true
            val files = container.imageImporter.importUris(listOf(uri))
            importing = false
            if (files.isEmpty()) {
                errorText = context.getString(R.string.gallery_copy_failed, "")
            } else {
                // 和拍照走同一条路：裁剪页 -> 框选/旋转/涂鸦 -> 提交识别
                // 标记来源，裁剪页据此给任务分组起名
                container.cropSourceIsGallery = true
                onCropped(files.first().absolutePath)
            }
        }
    }

    // F6：批次存在时拦截系统返回键，弹「本批 N 张将丢弃」确认，
    // 不让一次误触把刚拍的 5 张题悄无声息地丢掉。
    // v0.0.51 T2：CHAT 用途的对话连拍共用同一套批次与丢弃确认，
    // 上限独立 9 张（消息附件数受大模型接口约束，低于识别批次的 12）。
    val isChatPurpose =
        container.cameraPurpose == com.mistakebook.di.AppContainer.CameraPurpose.CHAT
    val chatBatchLimit = 9
    val batchSize = container.captureBatch.size
    val batchActive = batchSize > 0
    val batchFull = if (isChatPurpose) {
        batchSize >= chatBatchLimit
    } else {
        container.captureBatch.isFull()
    }
    var showDiscardDialog by remember { mutableStateOf(false) }

    // T5：单张拍完待调节的原图路径；非空时显示增强调节覆盖层。
    var adjustPath by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = batchActive) { showDiscardDialog = true }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = {
                Text(
                    stringResource(
                        if (isChatPurpose) R.string.capture_chat_batch_discard_title
                        else R.string.capture_batch_discard_title
                    )
                )
            },
            text = {
                Text(
                    stringResource(
                        if (isChatPurpose) R.string.capture_chat_batch_discard_body
                        else R.string.capture_batch_discard_body,
                        batchSize
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    // clear() 会连文件一起删：未裁原图与已裁成果都属于本批，
                    // 用户确认丢弃后不应再在缓存里留残影
                    container.captureBatch.clear()
                    showDiscardDialog = false
                    onBack()
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // T5：单张增强调节覆盖层（CHAT 单张链路）。确认后进裁切；取消丢弃本次拍摄。
    if (adjustPath != null) {
        EnhanceAdjustOverlay(
            imagePath = adjustPath.orEmpty(),
            initialStrength = ocrStrength ?: 2,
            onConfirmed = { adjustedPath ->
                adjustPath = null
                onCropped(adjustedPath)
            },
            onDiscarded = {
                // 丢弃：删掉刚拍的临时原图
                runCatching { java.io.File(adjustPath.orEmpty()).delete() }
                adjustPath = null
            }
        )
        // 覆盖层盖住整页时不再渲染相机控制（提前 return 不行——Composable 结构，
        // 用 Box 层级覆盖即可；快门等控件在覆盖层之下不可点）
    }

    if (showKeyGate) {
        ApiKeyRequiredDialog(
            mineruMissing = mineruMissing,
            llmMissing = llmMissing,
            onOpenSettings = onOpenSettings,
            onManualEntry = onManualEntry,
            onDismiss = { showKeyGate = false }
        )
    }
    Box(modifier = Modifier.fillMaxSize().background(CaptureScrim)) {
        if (hasCameraPermission) {
            // 取景器卡片：圆角 + 细描边浮在深色底上。
            //
            // 预览与网格都裁在卡片内——网格铺满全屏时，参考线会飘到
            // 底部控制区上方，视觉上散成一片；裁进卡片才像「一个取景框」。
            //
            // 高度用「屏高 - 底部操作区预留」而不是 weight：底部控制区是
            // 这个 Box 的**兄弟节点**（它要靠 align 贴在底边），weight 只
            // 在 Column/Row 作用域里存在。控制区高度固定，预留一个常量即可。
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxSize()
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        top = 16.dp,
                        bottom = BottomAreaReserve
                    )
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Black)
                    .border(1.dp, ViewfinderBorder, RoundedCornerShape(24.dp))
            ) {
                CameraPreview(
                    imageCapture = imageCapture,
                    flashMode = flashMode,
                    lensFacing = lensFacing,
                    onBound = { cameraBound = it },
                    onFlashAvailability = { available ->
                        flashAvailable = available
                        if (!available) flashMode = FLASH_OFF
                    },
                    onError = { errorText = it }
                )
                GridOverlay(modifier = Modifier.fillMaxSize())
                ViewfinderCorners(modifier = Modifier.fillMaxSize())
            }
        } else {
            CameraPermissionRequest(
                onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onOpenSettings = { openAppSettings(context) },
                onBack = onBack
            )
        }

        // F6：连拍批次悬浮计数 + 「完成」。QUESTION 与 CHAT（T2 对话连拍）都有批次；
        // 计数文案不同（本批 N 张），上限不同（12 / 9）。
        if (batchActive) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 96.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.92f))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(
                        if (isChatPurpose) R.string.capture_chat_batch_count
                        else R.string.capture_batch_count,
                        batchSize
                    ),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelMedium
                )
                TextButton(
                    onClick = onStartBatchCrop,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(
                        stringResource(
                            if (isChatPurpose) R.string.capture_chat_batch_done
                            else R.string.capture_batch_done
                        )
                    )
                }
            }
        }

        if (errorText != null) {
            CapturePill(
                modifier = Modifier.align(Alignment.Center),
                container = MaterialTheme.colorScheme.errorContainer
            ) {
                Text(
                    text = errorText.orEmpty(),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (importing) {
            CapturePill(modifier = Modifier.align(Alignment.Center)) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.size(10.dp))
                Text(stringResource(R.string.gallery_copying), color = Color.White)
            }
        }

        // ---------------------------------------------------------- 底部操作区
        //
        // 三层结构：引导语 → 控制排 → 快门行。
        // 控制排收编了原先散落在顶部的闪光灯/前后摄与 OCR 档位——
        // 拍摄时眼睛盯着取景器，手边能摸到的才是入口，浮在预览上方的
        // 细碎图标既难按也干扰取景。
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "对准题目，光线充足识别更准",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(Modifier.height(14.dp))

            // 控制排：闪光灯 / OCR 增强档 / 前后摄
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                FrostyCircleButton(
                    onClick = { flashMode = (flashMode + 1) % 3 },
                    enabled = flashAvailable != false,
                    active = flashMode != FLASH_AUTO
                ) {
                    Icon(
                        imageVector = when (flashMode) {
                            FLASH_ON -> Icons.Default.FlashOn
                            FLASH_OFF -> Icons.Default.FlashOff
                            else -> Icons.Default.FlashAuto
                        },
                        contentDescription = stringResource(
                            when (flashMode) {
                                FLASH_ON -> R.string.capture_flash_on
                                FLASH_OFF -> R.string.capture_flash_off
                                else -> R.string.capture_flash_auto
                            }
                        ),
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }

                val strength = ocrStrength
                if (strength != null) {
                    Spacer(Modifier.width(10.dp))
                    val options = listOf(
                        R.string.capture_ocr_off,
                        R.string.capture_ocr_light,
                        R.string.capture_ocr_standard,
                        R.string.capture_ocr_strong
                    )
                    // 不另设高度：SingleChoiceSegmentedButtonRow 内部会按
                    // Material3 的默认档位高度自绘，外面再套一层固定高度
                    // 只会被它覆盖（白写），档位按钮的自然高度与两侧圆钮
                    // 已经够接近。
                    SingleChoiceSegmentedButtonRow {
                        options.forEachIndexed { index, titleRes ->
                            SegmentedButton(
                                selected = strength == index,
                                onClick = {
                                    ocrStrength = index
                                    scope.launch { container.settingsStore.setOcrStrength(index) }
                                },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = options.size
                                ),
                                colors = SegmentedButtonDefaults.colors(
                                    activeContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                    inactiveContainerColor = Color.White.copy(alpha = 0.12f),
                                    activeContentColor = Color.White,
                                    inactiveContentColor = Color.White,
                                    activeBorderColor = Color.White.copy(alpha = 0.35f),
                                    inactiveBorderColor = Color.White.copy(alpha = 0.2f)
                                )
                            ) {
                                Text(
                                    stringResource(titleRes),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.width(10.dp))
                FrostyCircleButton(
                    onClick = {
                        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                            CameraSelector.LENS_FACING_FRONT
                        } else {
                            CameraSelector.LENS_FACING_BACK
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Cameraswitch,
                        contentDescription = stringResource(R.string.capture_switch_camera),
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // 快门行：相册入口 / 大快门 / 关闭（有批次时先弹丢弃确认）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                FrostyCircleButton(
                    onClick = {
                        if (isChatPurpose) {
                            // T2 对话连拍：相册也多选入批（上限 9 由批次满员 toast 兜底）
                            galleryChatMultiLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        } else {
                            galleryMultiLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    },
                    size = 52.dp
                ) {
                    Icon(
                        imageVector = Icons.Default.PhotoLibrary,
                        contentDescription = stringResource(R.string.capture_gallery),
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                ShutterButton(
                    // 相机没绑成功时禁止按快门：否则 CameraX 立刻回 "Not bound to a valid Camera"
                    enabled = !capturing && hasCameraPermission && cameraBound,
                    onClick = {
                        errorText = null
                        val isQuestion =
                            container.cameraPurpose == com.mistakebook.di.AppContainer.CameraPurpose.QUESTION
                        // F6：批次满员时快门不再拍照，toast 引导去裁切/完成。
                        // MinerU 串行解析，12 张已接近耐心极限，更大批量走 PDF 导入。
                        if (batchFull) {
                            Toast.makeText(
                                context,
                                context.getString(
                                    if (isQuestion) R.string.capture_batch_full
                                    else R.string.capture_chat_batch_full
                                ),
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            capturing = true
                            scope.launch {
                                // CHAT 用途跳过 Key 门禁（T11），见 checkRecognitionKeys 处注释
                                if (isQuestion && !checkRecognitionKeys()
                                ) {
                                    capturing = false
                                    return@launch
                                }
                                // 看门狗：任何未预料的回调丢失都不能让快门永久禁用
                                scope.launch {
                                    delay(15_000)
                                    if (capturing) {
                                        capturing = false
                                        errorText = context.getString(R.string.capture_failed)
                                    }
                                }
                                takePhoto(
                                    context = context,
                                    executor = executor,
                                    imageCapture = imageCapture,
                                    flashMode = flashMode,
                                    strength = ocrStrength ?: 2,
                                    onCaptured = { path ->
                                        // 连拍模式拍完**不离开相机页**：capturing 必须在这里复位，
                                        // 否则第二张按不动快门（旧流程直接导航走，页面销毁掩盖了这点）。
                                        capturing = false
                                        if (isQuestion) {
                                            container.captureBatch.add(path, fromGallery = false)
                                        } else {
                                            // T2 对话连拍：CHAT 拍完入批不退出（与 QUESTION 共用批次容器），
                                            // 「完成」后经 NavHost 进逐张裁切接力，全部裁完回会话。
                                            // 无批次时保持单张原链路：直接 onCropped(path) 进裁切。
                                            if (batchActive || !container.captureBatch.add(path, fromGallery = false)) {
                                                if (!batchActive) {
                                                    // T5 拍照增强可调：单张拍完先进调节步（对刚拍的原图
                                                    // 重新 enhanceForOcr，可切「关」回到原像素），确认再进裁切。
                                                    adjustPath = path
                                                } else {
                                                    Toast.makeText(
                                                        context,
                                                        context.getString(R.string.capture_chat_batch_full),
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            }
                                        }
                                    },
                                    onError = {
                                        capturing = false
                                        errorText = context.getString(R.string.capture_failed)
                                    }
                                )
                            }
                        }
                    }
                )
                FrostyCircleButton(
                    onClick = {
                        // F6：有未完成批次时先弹丢弃确认，别让误触丢掉刚拍的题
                        if (batchActive) {
                            showDiscardDialog = true
                        } else {
                            onBack()
                        }
                    },
                    size = 52.dp
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.capture_cancel),
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CameraPermissionRequest(
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.capture_permission_title),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.capture_permission_body),
            color = Color.White.copy(alpha = 0.8f),
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onRequest) { Text(stringResource(R.string.capture_permission_grant)) }
            Button(onClick = onOpenSettings) {
                Text(stringResource(R.string.capture_permission_settings))
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onBack) { Text(stringResource(R.string.action_cancel)) }
    }
}

/**
 * 拍照页的沉浸底色。
 *
 * 不再用纯黑：取景器卡片浮在这层上时需要一点「纸面感」，
 * 纯黑让卡片边缘的 1dp 描边完全看不见，卡片像贴错的图。
 */
private val CaptureScrim = Color(0xFF14151A)

/** 取景器描边：白色 8% 不透明度，细到不抢内容，又足以勾出卡片边界。 */
private val ViewfinderBorder = Color.White.copy(alpha = 0.08f)

/**
 * 底部操作区的高度预留（dp）。
 *
 * 引导语 ~20 + 控制排 44 + 快门 76 + 间距 ~50 ≈ 190，取 210 留余量。
 * 取景器卡片高度 = 屏高 - 这个值，两者正好首尾相接不重叠。
 */
private val BottomAreaReserve = 210.dp

/**
 * 取景器四角的 L 形角标。
 *
 * 圆角卡片 + 九宫格已经够了，再补角标是因为「框」的边界感对拍照很重要：
 * 用户要知道题目的哪一侧会被切出去。角标比四周边框更克制——
 * 只有四个角有线，中间干干净净，不会和九宫格抢注意力。
 */
@Composable
private fun ViewfinderCorners(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val arm = size.minDimension * 0.09f
        val thickness = arm * 0.18f
        val inset = size.minDimension * 0.04f
        val color = Color.White.copy(alpha = 0.85f)
        val w = size.width
        val h = size.height
        val corners = listOf(
            Triple(inset, inset, true),           // 左上：向右、向下
            Triple(w - inset, inset, false),      // 右上：向左、向下
            Triple(inset, h - inset, true),       // 左下：向右、向上
            Triple(w - inset, h - inset, false)   // 右下：向左、向上
        )
        corners.forEach { (x, y, openRight) ->
            val dirX = if (openRight) 1f else -1f
            val dirY = if (y < h / 2f) 1f else -1f
            drawLine(
                color = color,
                start = androidx.compose.ui.geometry.Offset(x, y),
                end = androidx.compose.ui.geometry.Offset(x + dirX * arm, y),
                strokeWidth = thickness
            )
            drawLine(
                color = color,
                start = androidx.compose.ui.geometry.Offset(x, y),
                end = androidx.compose.ui.geometry.Offset(x, y + dirY * arm),
                strokeWidth = thickness
            )
        }
    }
}

/**
 * 居中的半透明提示胶囊（错误 / 相册导入中）。
 *
 * 旧版是一块带 60% 黑底的裸 Text，宽度撑满内容且四角直角，
 * 在取景器上像一块补丁。统一成胶囊 + surface 容器色，
 * 错误态改用 errorContainer，颜色本身就是语义。
 */
@Composable
private fun CapturePill(
    modifier: Modifier = Modifier,
    container: Color = Color.Black.copy(alpha = 0.65f),
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(container)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content
    )
}

/**
 * 磨砂圆形控制钮（闪光灯 / 相册 / 关闭 / 前后摄）。
 *
 * 12% 白的容器在黑底上做出毛玻璃的层次；选中态（active）换成
 * primary 容器 + onPrimary 图标，用户一眼能看出「闪光灯开着」。
 * 尺寸给足 44~52dp：相机页的按钮都是在举着手机的情况下按的，
 * 44dp 是触摸目标的底线。
 */
@Composable
private fun FrostyCircleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 44.dp,
    enabled: Boolean = true,
    active: Boolean = false,
    content: @Composable () -> Unit
) {
    val container = when {
        active -> MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
        else -> Color.White.copy(alpha = 0.12f)
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(container)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/**
 * 快门：外环白描边 + 白芯，按下整体缩到 0.92f。
 *
 * 按压反馈用 graphicsLayer 的 scale——不参与测量，不会因为缩放
 * 让周围按钮的位置抖一下。禁用态降到 40% 白并失去交互。
 */
@Composable
private fun ShutterButton(enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = tween(durationMillis = 90),
        label = "shutter-scale"
    )
    Box(
        modifier = Modifier
            .size(76.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(Color.Transparent)
            .border(width = 4.dp, color = Color.White.copy(alpha = if (enabled) 0.95f else 0.4f), shape = CircleShape)
            .clickable(
                // 用自己的 interactionSource：按压态要驱动下面的缩放动画，
                // 走默认源的话拿不到 pressed 状态。
                interactionSource = interaction,
                // 不要默认水波：快门是「按下去缩一下」的触感，
                // 水波纹会在缩放动画之上再叠一层，视觉很脏。
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = if (enabled) 0.95f else 0.4f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.PhotoCamera,
                contentDescription = stringResource(R.string.capture_shutter),
                tint = Color.Black.copy(alpha = if (enabled) 0.75f else 0.4f),
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

@Composable
private fun GridOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val thirdW = size.width / 3f
        val thirdH = size.height / 3f
        for (i in 1..2) {
            drawLine(
                color = Color.White.copy(alpha = 0.4f),
                start = androidx.compose.ui.geometry.Offset(thirdW * i, 0f),
                end = androidx.compose.ui.geometry.Offset(thirdW * i, size.height),
                strokeWidth = 1f
            )
            drawLine(
                color = Color.White.copy(alpha = 0.4f),
                start = androidx.compose.ui.geometry.Offset(0f, thirdH * i),
                end = androidx.compose.ui.geometry.Offset(size.width, thirdH * i),
                strokeWidth = 1f
            )
        }
    }
}

@Composable
private fun CameraPreview(
    imageCapture: ImageCapture,
    flashMode: Int,
    lensFacing: Int,
    onBound: (Boolean) -> Unit,
    onFlashAvailability: (Boolean) -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val latestFlashMode = androidx.compose.runtime.rememberUpdatedState(flashMode)

    LaunchedEffect(lensFacing) {
        onBound(false)
        Log.i(TAG, "开始绑定相机 lens=$lensFacing flash=$flashMode")
        try {
            // 不在主线程同步阻塞等相机冷启动：用 addListener 异步取 provider
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    bindCamera(
                        future.get(),
                        lifecycleOwner,
                        previewView,
                        imageCapture,
                        lensFacing,
                        latestFlashMode.value,
                        onBound,
                        onFlashAvailability,
                        onError
                    )
                } catch (error: Exception) {
                    Log.e(TAG, "绑定相机失败 lens=$lensFacing", error)
                    onBound(false)
                    onError("${error::class.java.simpleName}: ${error.message}")
                }
            }, ContextCompat.getMainExecutor(context))
        } catch (error: Exception) {
            Log.e(TAG, "获取 ProcessCameraProvider 失败", error)
            onBound(false)
            onError("${error::class.java.simpleName}: ${error.message}")
        }
    }

    LaunchedEffect(flashMode) {
        imageCapture.flashMode = when (flashMode) {
            FLASH_ON -> ImageCapture.FLASH_MODE_ON
            FLASH_OFF -> ImageCapture.FLASH_MODE_OFF
            else -> ImageCapture.FLASH_MODE_AUTO
        }
    }

    // 离开拍照页必须解绑：Camera2 session、Preview Surface、ImageCapture 的 ImageReader
    // 都会继续驻留本进程（小米系 gralloc 计入 RSS），是裁剪页 OOM 的帮凶。
    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                val future = ProcessCameraProvider.getInstance(context)
                if (future.isDone) {
                    future.get().unbindAll()
                    Log.i(TAG, "已解绑相机")
                }
            }
        }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

private fun bindCamera(
    cameraProvider: ProcessCameraProvider,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    previewView: PreviewView,
    imageCapture: ImageCapture,
    lensFacing: Int,
    flashMode: Int,
    onBound: (Boolean) -> Unit,
    onFlashAvailability: (Boolean) -> Unit,
    onError: (String) -> Unit
) {
    try {
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        cameraProvider.unbindAll()
        val camera = cameraProvider.bindToLifecycle(
            lifecycleOwner,
            selector,
            preview,
            imageCapture
        )
        // 竖拍时让 EXIF 方向与显示方向一致（CameraX 默认 ROTATION_0）
        imageCapture.targetRotation = previewView.display?.rotation
            ?: android.view.Surface.ROTATION_0
        imageCapture.flashMode = when (flashMode) {
            FLASH_ON -> ImageCapture.FLASH_MODE_ON
            FLASH_OFF -> ImageCapture.FLASH_MODE_OFF
            else -> ImageCapture.FLASH_MODE_AUTO
        }
        // 无闪光灯硬件时不要请求开闪，否则部分机型拍照直接失败
        val hasFlash = camera.cameraInfo.hasFlashUnit()
        if (!hasFlash && imageCapture.flashMode == ImageCapture.FLASH_MODE_ON) {
            imageCapture.flashMode = ImageCapture.FLASH_MODE_OFF
        }
        onFlashAvailability(hasFlash)
        Log.i(TAG, "相机绑定成功 lens=$lensFacing hasFlash=$hasFlash")
        onBound(true)
    } catch (error: Exception) {
        Log.e(TAG, "bindToLifecycle 失败 lens=$lensFacing flash=$flashMode", error)
        onBound(false)
        onError("${error::class.java.simpleName}: ${error.message}")
    }
}

private const val MAX_DECODE_EDGE = 2400

private const val TAG = "MistakeBookCapture"

// 相机回调跑在 CameraX 的工作线程，导航/改状态必须切回主线程。
private fun onUi(block: () -> Unit) {
    android.os.Handler(android.os.Looper.getMainLooper()).post { block() }
}

private fun takePhoto(
    context: Context,
    executor: ExecutorService,
    imageCapture: ImageCapture,
    flashMode: Int,
    strength: Int,
    onCaptured: (String) -> Unit,
    onError: () -> Unit
) {
    Log.i(TAG, "takePicture 开始 flash=$flashMode mode=file")
    imageCapture.flashMode = when (flashMode) {
        FLASH_ON -> ImageCapture.FLASH_MODE_ON
        FLASH_OFF -> ImageCapture.FLASH_MODE_OFF
        else -> ImageCapture.FLASH_MODE_AUTO
    }
    // 主链路：直接写文件。这条路径最稳，不依赖 ImageProxy 的解码能力。
    captureToFile(
        context = context,
        executor = executor,
        imageCapture = imageCapture,
        strength = strength,
        onCaptured = onCaptured,
        onFallback = {
            Log.w(TAG, "文件输出失败，改走内存捕获", it)
            captureInMemory(context, executor, imageCapture, strength, onCaptured, onError)
        }
    )
}

// 主链路：OutputFileOptions 写 JPEG。CameraX 只写 EXIF 方向标记、不转像素，
// 所以落盘后立刻过一遍 ImageNormalizer：把方向烘进像素 + 对比度增强。
// 不这么做的话，同一张照片在裁剪页（不读 EXIF）和别的路径（读 EXIF）朝向会不一致，
// 表现为「明明已经摆正了，识别时莫名其妙转了 90 度」。
private fun captureToFile(
    context: Context,
    executor: ExecutorService,
    imageCapture: ImageCapture,
    strength: Int,
    onCaptured: (String) -> Unit,
    onFallback: (ImageCaptureException) -> Unit
) {
    val out = File(context.cacheDir, "capture_${System.currentTimeMillis()}.jpg")
    Log.i(TAG, "拍照输出文件: ${out.absolutePath} 可用空间=${context.cacheDir.usableSpace / 1024 / 1024}MB")
    val options = ImageCapture.OutputFileOptions.Builder(out).build()
    imageCapture.takePicture(
        options,
        executor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                Log.i(TAG, "拍照成功 savedUri=${results.savedUri} len=${out.length()} exists=${out.exists()}")
                if (out.length() == 0L) {
                    onUi { onFallback(ImageCaptureException(ImageCapture.ERROR_FILE_IO, "空文件", null)) }
                    return
                }
                // 方向烘进像素 + 对比度增强。归一化失败仍把原文件交出去，
                // 顶多少一次增强，不能因为增强失败就不让用户拍照。
                val normalized = File(out.parentFile, "capture_norm_${System.currentTimeMillis()}.jpg")
                val ok = runCatching {
                    com.mistakebook.data.ImageNormalizer.normalize(out, normalized, strength)
                }.getOrDefault(false)
                if (ok && normalized.length() > 0) {
                    out.delete()
                    onUi { onCaptured(normalized.absolutePath) }
                } else {
                    normalized.delete()
                    Log.w(TAG, "归一化失败，沿用原始文件: ${out.absolutePath}")
                    onUi { onCaptured(out.absolutePath) }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e(TAG, "文件输出拍照失败: code=${exception.imageCaptureError}", exception)
                onUi { onFallback(exception) }
            }
        }
    )
}

// 兜底链路：内存里拿 ImageProxy，自己解码落盘；旋转信息用 imageInfo.rotationDegrees。
private fun captureInMemory(
    context: Context,
    executor: ExecutorService,
    imageCapture: ImageCapture,
    strength: Int,
    onCaptured: (String) -> Unit,
    onError: () -> Unit
) {
    imageCapture.takePicture(
        executor,
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val rotation = image.imageInfo.rotationDegrees
                    val bitmap = decodeFromProxy(image) ?: run {
                        image.close()
                        onError()
                        return
                    }
                    val rotated = rotate(bitmap, rotation.toFloat())
                    val out = File(context.cacheDir, "capture_${System.currentTimeMillis()}.jpg")
                    // 这条路径已经手动转过像素了，只需做对比度增强
                    val final = com.mistakebook.data.ImageNormalizer.enhanceForOcr(rotated, strength)
                    FileOutputStream(out).use { stream ->
                        final.compress(Bitmap.CompressFormat.JPEG, 92, stream)
                    }
                    if (final !== rotated) final.recycle()
                    image.close()
                    onUi { onCaptured(out.absolutePath) }
                } catch (error: Exception) {
                    Log.e(TAG, "内存捕获解码失败", error)
                    runCatching { image.close() }
                    onUi { onError() }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e(TAG, "内存捕获失败: code=${exception.imageCaptureError}", exception)
                onUi { onError() }
            }
        }
    )
}

// 从 ImageProxy 里按 JPEG 字节降采样解码，避免 toBitmap() 在部分机型不支持或 OOM。
private fun decodeFromProxy(image: ImageProxy): Bitmap? {
    if (image.format == ImageFormat.JPEG) {
        val buffer = image.planes[0].buffer
        buffer.rewind()
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_DECODE_EDGE)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
    // YUV 路径会把整帧转 JPEG 再全尺寸解码，显式捕获 OOM，别让 runCatching 吞掉
    return try {
        image.toBitmap()
    } catch (oom: OutOfMemoryError) {
        Log.e(TAG, "ImageProxy.toBitmap OOM", oom)
        null
    }
}

private fun sampleSizeFor(width: Int, height: Int, maxEdge: Int): Int {
    if (width <= 0 || height <= 0) return 1
    var sample = 1
    var longEdge = maxOf(width, height)
    // 旧写法 (longEdge / 2 >= maxEdge) 会让 4032px 原图算出 inSampleSize=1，等于全尺寸解码
    while (longEdge > maxEdge) {
        longEdge /= 2
        sample *= 2
    }
    return sample
}

private fun rotate(bitmap: Bitmap, degrees: Float): Bitmap {
    if (degrees == 0f) return bitmap
    val matrix = Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

private fun openAppSettings(context: Context) {
    val intent = android.content.Intent(
        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    )
    context.startActivity(intent)
}

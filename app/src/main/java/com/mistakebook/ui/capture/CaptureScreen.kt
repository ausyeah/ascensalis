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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mistakebook.R
import com.mistakebook.di.AppContainer
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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(
    container: AppContainer,
    onCropped: (String) -> Unit,
    onOpenSettings: () -> Unit,
    /** Key 门禁里的「改用手动录入」出口，见 [ApiKeyRequiredDialog]。 */
    onManualEntry: () -> Unit,
    onBack: () -> Unit
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

    // 单选而不是多选：每张图都要走完整裁剪流程（框选 → 旋转 → 涂鸦 → 提交），
    // 多选会让用户在同一个页面里反复进出。批量场景走「导入 PDF」。
    val galleryLauncher = rememberLauncherForActivityResult(
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


    if (showKeyGate) {
        ApiKeyRequiredDialog(
            mineruMissing = mineruMissing,
            llmMissing = llmMissing,
            onOpenSettings = onOpenSettings,
            onManualEntry = onManualEntry,
            onDismiss = { showKeyGate = false }
        )
    }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (hasCameraPermission) {
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
        } else {
            CameraPermissionRequest(
                onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onOpenSettings = { openAppSettings(context) },
                onBack = onBack
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 28.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(
                onClick = { flashMode = (flashMode + 1) % 3 },
                enabled = flashAvailable != false
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
                    tint = Color.White
                )
            }
            IconButton(onClick = {
                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                    CameraSelector.LENS_FACING_FRONT
                } else {
                    CameraSelector.LENS_FACING_BACK
                }
            }) {
                Icon(
                    imageVector = Icons.Default.Cameraswitch,
                    contentDescription = stringResource(R.string.capture_switch_camera),
                    tint = Color.White
                )
            }
        }

        val strength = ocrStrength
        if (strength != null) {
            val options = listOf(
                R.string.capture_ocr_off,
                R.string.capture_ocr_light,
                R.string.capture_ocr_standard,
                R.string.capture_ocr_strong
            )
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 90.dp)
            ) {
                options.forEachIndexed { index, titleRes ->
                    SegmentedButton(
                        selected = strength == index,
                        onClick = {
                            ocrStrength = index
                            scope.launch { container.settingsStore.setOcrStrength(index) }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = Color.Black.copy(alpha = 0.5f),
                            inactiveContainerColor = Color.Black.copy(alpha = 0.3f),
                            activeContentColor = Color.White,
                            inactiveContentColor = Color.White,
                            activeBorderColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(stringResource(titleRes))
                    }
                }
            }
        }

        if (errorText != null) {
            Text(
                text = errorText.orEmpty(),
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(12.dp)
            )
        }

        if (importing) {
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color.White
                )
                Spacer(Modifier.size(12.dp))
                Text(stringResource(R.string.gallery_copying), color = Color.White)
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.35f))
                .padding(horizontal = 32.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = {
                galleryLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            }) {
                Icon(
                    imageVector = Icons.Default.PhotoLibrary,
                    contentDescription = stringResource(R.string.capture_gallery),
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
            ShutterButton(
                // 相机没绑成功时禁止按快门：否则 CameraX 立刻回 "Not bound to a valid Camera"
                enabled = !capturing && hasCameraPermission && cameraBound,
                onClick = {
                    errorText = null
                    capturing = true
                    scope.launch {
                        // CHAT 用途跳过 Key 门禁（T11），见 checkRecognitionKeys 处注释
                        if (container.cameraPurpose == com.mistakebook.di.AppContainer.CameraPurpose.QUESTION &&
                            !checkRecognitionKeys()
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
                            onCaptured = { path -> onCropped(path) },
                            onError = {
                                capturing = false
                                errorText = context.getString(R.string.capture_failed)
                            }
                        )
                    }
                }
            )
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.capture_cancel),
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
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

@Composable
private fun ShutterButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (enabled) 0.95f else 0.4f)),
        contentAlignment = Alignment.Center
    ) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(
                imageVector = Icons.Default.PhotoCamera,
                contentDescription = stringResource(R.string.capture_shutter),
                tint = Color.Black,
                modifier = Modifier.size(36.dp)
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

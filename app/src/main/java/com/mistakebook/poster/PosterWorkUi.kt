package com.mistakebook.poster

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import java.io.File

/**
 * 订阅海报生图的**后台任务**状态，并在完成时把位图交给界面。
 *
 * ## 为什么需要这层
 *
 * 生图是「点了按钮就等」的长任务，而用户随时可能离开这个页面。
 * 任务本身交给 WorkManager 后不会断，但**结果怎么送回界面**是另一回事：
 * 界面已经销毁时回调无处可去，回来时又得把磁盘上的结果重新捡起来。
 *
 * 这里统一处理：
 * - 状态来自 [PosterWorkMonitor]（挂在 appScope 上），页面重建后自动接上；
 * - [PosterWorkState.Done] 出现时解码 PNG 并回调 [onBitmap]；
 * - 失败时回调 [onFailed]。
 *
 * 回调用 [rememberUpdatedState] 包一层，保证界面重组后拿到的是最新 lambda
 * （否则捕获旧 lambda，界面里的状态更新会丢）。
 */
@Composable
fun rememberPosterWorkHandler(
    monitor: PosterWorkMonitor,
    onBitmap: (Bitmap) -> Unit,
    onFailed: (String) -> Unit
): State<PosterWorkState> {
    val state = monitor.state.collectAsState()
    val currentOnBitmap by rememberUpdatedState(onBitmap)
    val currentOnFailed by rememberUpdatedState(onFailed)

    // 页面回来时把「已经完成」的结果重新捡起来（进程重启后内存状态会丢）。
    LaunchedEffect(monitor) { monitor.replayLatest() }

    LaunchedEffect(state.value) {
        when (val current = state.value) {
            is PosterWorkState.Done -> {
                val bitmap = decodeBitmap(current.filePath)
                if (bitmap == null) {
                    currentOnFailed("海报文件读取失败")
                } else {
                    currentOnBitmap(bitmap)
                }
                monitor.reset()
            }

            is PosterWorkState.Failed -> {
                currentOnFailed(current.message)
                monitor.reset()
            }

            else -> Unit
        }
    }
    return state
}

/** 读海报 PNG；失败返回 null。 */
private fun decodeBitmap(path: String): Bitmap? = runCatching {
    val file = File(path)
    if (!file.isFile) return null
    BitmapFactory.decodeFile(file.absolutePath)
}.getOrNull()

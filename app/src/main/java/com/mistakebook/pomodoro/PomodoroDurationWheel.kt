@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.mistakebook.pomodoro

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mistakebook.data.local.entities.PomodoroTask
import com.mistakebook.data.local.entities.Subject
import com.mistakebook.di.AppContainer
import com.mistakebook.pomodoro.stats.PomodoroStats
import com.mistakebook.pomodoro.stats.StatsRange
import com.mistakebook.ui.common.containerViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import com.mistakebook.R

/**
 * 番茄钟页面。
 *
 * ## 三个 Tab 的分工
 *
 * 番茄钟 / 正向计时是**两种心智模型**（有终点 vs 没有终点），各占一页；
 * 统计数据单独一页。旧版的「定时」模式与番茄钟完全是同一个东西
 * （都是倒计时，只是默认时长不同），已合并——默认就是 45 分钟，
 * 想换时长在番茄钟页把轮盘拨一下即可。
 *
 * ## 布局顺序是按「一次专注的动作顺序」排的
 *
 * 看时间 → 选任务 → 按开始。统计被挪到独立 Tab：它是**事后回看**
 * 的东西，混在计时页里会把主链路挤碎。
 *
 * ## 计时控件为什么在表盘里
 *
 * 「暂停 / 结束并记录」是计时途中最高频的两个操作，放在屏幕最底部
 * 等于让用户每次暂停都多伸一次拇指。它们贴在居中时间的正下方，
 * 「放弃」另起一行——常用的一步够到，破坏性的远一步。
 *
 * ## 审美上刻意做的克制
 *
 * - 全屏只有一个强调色（主题 primary），其余全走中性灰阶；
 * - 大数字用等宽字形（`tnum`），否则秒数每跳一次整串字都在抖；
 * - 进度环是**一根线**，不加渐变、不加呼吸动效——计时器不是装饰品；
 * - 层次靠容器色阶（surfaceContainerLow/High），不用阴影。
 */
/**
 * 时长轮盘对话框：小时 0–12、分钟 0–59，两列各自**无限循环**竖向滑动。
 *
 * ## 为什么是竖向
 *
 * 时间是「从上往下拨」的——闹钟、计时器、飞机表盘全是竖向滚轮。
 * 横向拨轮拨的是「挑选」，竖向拨轮拨的是「设定」，手感不能反。
 *
 * ## 无限循环怎么做的
 *
 * 真实取值范围各重复 [WHEEL_REPEAT] 份，初始位置停在正中间那一份。
 * 滑到接近边缘时用户早就滑回来了——2600 / 12000 个 item 的 LazyColumn
 * 只组合可见的那几个，成本与 13 / 60 个 item 完全一样。真要滑到天边
 * 之外，把 [WHEEL_REPEAT] 调大即可，交互语义不变。
 *
 * 选中值 = 距轮盘中线最近的 item 的下标 % 真实数量，与滚动位置天然同步，
 * 不需要额外记「当前选中第几项」的状态。
 */
@Composable
internal fun DurationWheelDialog(
    initialMinutes: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val hours = remember { (0..12).toList() }
    val minutes = remember { (0..59).toList() }
    val initH = initialMinutes / 60
    val initM = initialMinutes % 60

    val hourState = rememberLazyListState(
        initialFirstVisibleItemIndex = WHEEL_REPEAT / 2 * hours.size + initH
    )
    val minuteState = rememberLazyListState(
        initialFirstVisibleItemIndex = WHEEL_REPEAT / 2 * minutes.size + initM
    )

    // 当前选中的真实值：每帧重算太浪费，只在滚动中/停下时变——
    // derivedStateOf 依赖的是 layoutInfo，滚动停了对它求值也不重组。
    val selectedHour by remember {
        derivedStateOf { centeredWheelValue(hourState, hours.size) }
    }
    val selectedMinute by remember {
        derivedStateOf { centeredWheelValue(minuteState, minutes.size) }
    }
    val totalMinutes = selectedHour * 60 + selectedMinute

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pomodoro_pick_duration_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 预览行：把两列的选择合成一句人话
                Text(
                    text = if (totalMinutes >= 60) {
                        "${selectedHour} 小时 ${selectedMinute} 分钟"
                    } else {
                        "${selectedMinute} 分钟"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    WheelColumn(
                        values = hours,
                        state = hourState,
                        label = stringResource(R.string.pomodoro_wheel_hour),
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = ":",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    WheelColumn(
                        values = minutes,
                        state = minuteState,
                        label = stringResource(R.string.pomodoro_wheel_minute),
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.pomodoro_wheel_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(totalMinutes) },
                // 0 小时 0 分钟不是时长：拨到全零时确定不可点，
                // 比让用户点完才弹一个「时长不能为 0」好。
                enabled = totalMinutes >= 1
            ) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}


/** 单列竖向滚轮。 */
@Composable
private fun WheelColumn(
    values: List<Int>,
    state: androidx.compose.foundation.lazy.LazyListState,
    label: String,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    Box(modifier = modifier.height(180.dp), contentAlignment = Alignment.Center) {
        // 选中带的底：一条居中横带，把「选中项」这件事画出来
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .height(48.dp)
                .fillMaxWidth()
        ) {}

        // 上下渐隐：让轮盘有「圆柱」的纵深感，而不是一列平板数字
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surface,
                            Color.Transparent,
                            Color.Transparent,
                            MaterialTheme.colorScheme.surface
                        )
                    )
                )
        )

        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            flingBehavior = androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior(state),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            items(
                count = values.size * WHEEL_REPEAT,
                key = { index -> index }
            ) { index ->
                val value = values[index % values.size]
                val selected = centeredWheelValue(state, values.size) == value &&
                    isItemCentered(state, index)
                // 选中项放大、主色、加粗；其余按距中线距离淡出
                val centerOffsetPx = wheelCenterOffset(state, index)
                val distance = abs(centerOffsetPx)
                val scale = (1f - (distance / 500f)).coerceIn(0.7f, 1.2f)
                val alpha = (1f - (distance / 700f)).coerceIn(0.25f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = String.format("%02d", value),
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontSize = 22.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            fontFeatureSettings = "tnum"
                        ),
                        color = if (selected) primary else onSurfaceVariant.copy(alpha = alpha),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            this.alpha = alpha
                        }
                    )
                }
            }
        }

        // 单位标签贴在轮盘右上角，不占轮盘高度
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = onSurface.copy(alpha = 0.6f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
        )
    }
}

/** 轮盘数据重复份数：够滑几千屏，又不会让下标溢出 Int。 */
private const val WHEEL_REPEAT = 200


/** 当前居中 item 的真实值（下标 % 真实数量）。 */
private fun centeredWheelValue(
    state: androidx.compose.foundation.lazy.LazyListState,
    realCount: Int
): Int {
    val center = (state.layoutInfo.viewportStartOffset + state.layoutInfo.viewportEndOffset) / 2
    var bestIndex = state.firstVisibleItemIndex
    var bestDistance = Int.MAX_VALUE
    state.layoutInfo.visibleItemsInfo.forEach { item ->
        val itemCenter = item.offset + item.size / 2
        val distance = abs(itemCenter - center)
        if (distance < bestDistance) {
            bestDistance = distance
            bestIndex = item.index
        }
    }
    return bestIndex % realCount
}


/** 指定下标是否就是居中那一个（用于加粗放大）。 */
private fun isItemCentered(
    state: androidx.compose.foundation.lazy.LazyListState,
    index: Int
): Boolean {
    val center = (state.layoutInfo.viewportStartOffset + state.layoutInfo.viewportEndOffset) / 2
    var bestIndex = state.firstVisibleItemIndex
    var bestDistance = Int.MAX_VALUE
    state.layoutInfo.visibleItemsInfo.forEach { item ->
        val itemCenter = item.offset + item.size / 2
        val distance = abs(itemCenter - center)
        if (distance < bestDistance) {
            bestDistance = distance
            bestIndex = item.index
        }
    }
    return bestIndex == index
}


/** item 中心相对轮盘中线的偏移（px）。 */
private fun wheelCenterOffset(
    state: androidx.compose.foundation.lazy.LazyListState,
    index: Int
): Int {
    val center = (state.layoutInfo.viewportStartOffset + state.layoutInfo.viewportEndOffset) / 2
    val item = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return Int.MAX_VALUE
    return item.offset + item.size / 2 - center
}

// =========================================================================
// 学科 → 任务看板
// =========================================================================


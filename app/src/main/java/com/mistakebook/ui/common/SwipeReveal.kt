package com.mistakebook.ui.common

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.Dp
import kotlin.math.abs
import androidx.compose.ui.unit.dp

/**
 * 左滑露出操作区的公共口径（错题卡片与对话历史共用）。
 *
 * 两处原本各写一份宽度与弹簧：错题 88dp + 84dp×2、都挂
 * `DampingRatioMediumBouncy`。同一套交互在两个列表里手感不一致，
 * 用户说不出哪里不对，只会觉得「一边顺手一边别扭」——所以收敛到一处。
 */

/** 单块操作区的宽度。放得下图标 + 一行小字，拇指点得中。 */
val SwipeActionBlockWidth: Dp = 88.dp

/**
 * 松手吸附的弹簧。
 *
 * 从 `DampingRatioMediumBouncy` 换成 `NoBouncy`：有弹跳的吸附在
 * 手机上像橡皮筋，色块会过头再缩回来，「丝滑」的反面正是这一下哆嗦。
 * 临界阻尼 + 中低刚度 ≈ 250ms 稳定到位，快而不冲。
 */
val SwipeRevealSpring: androidx.compose.animation.core.SpringSpec<Float> = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow
)

/** 露出内容的起始淡入进度：前 1/4 不出现，避免刚起手就看见图标闪一下。 */
private const val SwipeRevealFadeInStart = 0.25f

/**
 * 露出进度 0..1：卡片左移得越多越接近 1。
 *
 * 纯函数（单测见 SwipeRevealProgressTest）：offset 为负是向左，
 * 取绝对值；超过整块宽度按 1 收口，回弹过程中也不会溢出。
 */
internal fun swipeRevealProgress(offsetPx: Float, revealWidthPx: Float): Float {
    if (revealWidthPx <= 0f) return 0f
    return (abs(offsetPx) / revealWidthPx).coerceIn(0f, 1f)
}

/**
 * 露出内容的透明度：从 [SwipeRevealFadeInStart] 线性淡入到 1。
 *
 * 图标全程静止会被卡片「切」出来（一半被挡一半可见），跟着进度淡入
 * 才有「被手势带出来」的感觉。透明度经 `graphicsLayer { }` 在绘制期读，
 * 不触发重组——一屏几十张卡同时滑也不会掉帧。
 */
internal fun swipeRevealContentAlpha(progress: Float): Float {
    val start = SwipeRevealFadeInStart
    return ((progress - start) / (1f - start)).coerceIn(0f, 1f)
}

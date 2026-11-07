package com.mistakebook.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

/**
 * 左滑露出操作区的公共口径（错题卡片与对话历史共用）。
 *
 * 两处原本各写一份宽度与弹簧：错题 88dp + 84dp×2、都挂
 * `DampingRatioMediumBouncy`。同一套交互在两个列表里手感不一致，
 * 用户说不出哪里不对，只会觉得「一边顺手一边别扭」——所以收敛到一处。
 */

/** 单块操作区的宽度。放得下图标 + 一行小字，拇指点得中。 */
val SwipeActionBlockWidth: Dp = 96.dp

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

/**
 * 红蓝两块相接处的圆角半径（v0.0.121）。
 *
 * 用户看截图说「蓝色左边缘没有做圆润化处理，红蓝连接的方式也没有做
 * 圆润化处理」。左缘的圆角跟卡片走，相接处用这个独立半径：整条操作区
 * 高度几十 dp，16dp 的弧既看得见圆润，又不会把色块削成胶囊。
 */
val SwipeActionJunctionRadius: Dp = 16.dp

/**
 * 滑动露出的**操作块**：整块色底 + 居中的图标和一行小字。
 *
 * 错题卡片、对话历史、归档页三处共用同一颗组件——同一套交互在不同页面
 * 各画一份，颜色、字号、图标大小迟早各长各样（用户就是对着对话历史的
 * 版本说「其他页面的太丑了」）。
 *
 * [shape] 默认直角；需要与卡片圆角或相邻色块做圆润过渡的页面自己传
 * （见首页错题卡的红蓝双块）。
 */
@Composable
internal fun SwipeActionBlock(
    width: Dp,
    color: Color,
    contentColor: Color,
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .clip(shape)
            .background(color)
            // 滑开前点不到这里（被卡片挡住），显式禁用更保险
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(28.dp)
            )
            Text(
                text = label,
                color = contentColor,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }
    }
}

/**
 * 叠画操作层的公共排布（v0.0.131，从对话历史抽屉提取）。
 *
 * 靠右对齐：先画的块在最右，`inset` 把后面的块往左顶——每块都多铺一段
 * [SwipeActionJunctionRadius] 的衔接带，被上层块的圆弧压住，交界处才是
 * 弧线不是直线。错题卡、对话历史、归档页三处左滑共用一套画法。
 */
@Composable
internal fun SwipeActionLayer(
    modifier: Modifier,
    inset: Dp,
    content: @Composable () -> Unit
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.End) {
        content()
        if (inset > 0.dp) Spacer(Modifier.width(inset))
    }
}

/**
 * 中间块的形状（v0.0.131 修正）：**左缘直角、右缘收 [SwipeActionJunctionRadius]
 * 圆弧**。
 *
 * 为什么左缘不能也收弧：中间块向左多铺的那段衔接带，正是用来补住
 * 上层块右缘圆弧削掉的月牙缺口的——左缘若也收弧，两道弧「弧对弧」
 * 都缺料，交界处露出父容器底色（用户截图里的缝隙就是它）。方角铺满
 * + 右缘收弧，每条交界恰好一条弧线：左边的弧由上层块提供，右边的
 * 弧由自己提供，缺口全部被下层块的方角实体补满，零缝隙。
 */
internal fun swipeActionJunctionShape(): Shape = RoundedCornerShape(
    topStart = CornerSize(0.dp),
    topEnd = CornerSize(SwipeActionJunctionRadius),
    bottomStart = CornerSize(0.dp),
    bottomEnd = CornerSize(SwipeActionJunctionRadius)
)

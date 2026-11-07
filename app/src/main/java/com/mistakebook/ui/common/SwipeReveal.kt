package com.mistakebook.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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

/**
 * 左滑展开的**互斥协调器**（v0.0.132，用户：「同时不能展开两个，切换
 * 其他页面要自动收回防止误触」）。
 *
 * 由列表父级 [androidx.compose.runtime.remember] 持有，同一列表的每行
 * 拿到同一个实例并带上自己的 id：
 * - 某行滑开 → 写入自己的 id，其余行监听到「不是自己」就自动收回；
 * - 开始一次新的滑动 → 先清空（收回已开的行），自己滑到位再登记；
 * - 页面/抽屉关闭 → 父级销毁，状态随之消失，回到页面不会残留展开行。
 *
 * 谁该收回由各行自行判断（`revealed && revealedId != 我的id`）：
 * 拖动中的行尚未「已开」，不会被别的手势误拽。
 */
@androidx.compose.runtime.Stable
class SwipeRevealCoordinator {
    var revealedId: Any? by androidx.compose.runtime.mutableStateOf<Any?>(null)
}

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
 * 每个操作块自身的圆角半径（v0.0.132，口径从「交界衔接弧」改为「完整
 * 图形拼贴」）。
 *
 * 用户给的设计图：「）（这样的两个都是完整图形的拼贴」——每块是**独立
 * 的完整圆角矩形**并排，块间留一道细缝，交界的圆是每块自己的轮廓，
 * 不再叠画互压。
 */
val SwipeActionJunctionRadius: Dp = 16.dp

/** 相邻两块之间的缝（v0.0.132 初为 4dp，用户「最好没有缝隙」→ 归零：
 * 完整图形紧贴拼贴，交界上下角的小凹是各块自身圆角的天然轮廓）。 */
val SwipeActionGap: Dp = 0.dp

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
 * 操作块的统一形状（v0.0.132）：**四角全圆的完整图形**。
 *
 * 用户给的设计图推翻了叠画（「ui重叠」「我希望两个都是完整图形的
 * 拼贴」）——每块独立、完整、四角圆润，并排时块间留 [SwipeActionGap]
 * 细缝。交界不再互压，也就没有补弧/缺料的问题。
 */
internal fun swipeActionTileShape(): Shape = RoundedCornerShape(
    topStart = CornerSize(SwipeActionJunctionRadius),
    topEnd = CornerSize(SwipeActionJunctionRadius),
    bottomStart = CornerSize(SwipeActionJunctionRadius),
    bottomEnd = CornerSize(SwipeActionJunctionRadius)
)

package com.mistakebook.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 折叠键「留在原位」的补偿归属。
 *
 * ## 用户原话
 *
 * 「收起思考和展开思考还是有问题，不能准确定位同一位置保持不变……
 *   我希望展开收回，这个展开收起按钮始终在原位不变」。
 *
 * ## 真机打脸之后的原则：归属只认**量出来的残差**
 *
 * 曾经用位置启发式预测「增长锚定会不会补这一段」（贴底？被折叠的是不是视口最上面
 * 那条？）。模拟器实测直接否掉了它：思考块在视口中部、下面还有别的消息时，锚定
 * 其实**没补**（增长把锚点那条推走后它换了 key、只重取基线），折叠块却按启发式
 * 让路 ⇒ 没人补 ⇒ 按钮被顶上去 293px。所以现在的规则是：
 *
 * - 折叠块每轮重新量「按钮离原位还差多少」（残差）；
 * - 锚定补过 → 残差 ≈ 0 → 这里返回 0，**不会出现第二个补偿者**；
 * - 锚定没补 → 残差还在 → 这里原样滚回去（「它不接手就我们接手」）。
 */
class ThinkingAnchorCompensationTest {

    // ---- 方向：反向列表里 scrollBy(+d) = 内容往下 d ----

    @Test
    fun `按钮被顶上去时按残差往下补`() {
        // 展开：按钮被顶上去 371px（残差为正）→ 内容往下滚同样的量
        assertEquals(
            371f,
            thinkingAnchorCompensation(residualPx = 371f, listBusy = false),
            0f
        )
    }

    @Test
    fun `收起时按残差往回补`() {
        // 收起：按钮被顶下来（残差为负）→ 内容往上滚同样的量，符号原样交出去
        assertEquals(
            -371f,
            thinkingAnchorCompensation(residualPx = -371f, listBusy = false),
            0f
        )
    }

    @Test
    fun `补偿与残差一比一不缩放`() {
        // 滚动量的单位就是像素：补一半，按钮就停在半路。
        assertEquals(123.5f, thinkingAnchorCompensation(residualPx = 123.5f, listBusy = false), 0f)
    }

    // ---- 归属：锚定没接手才轮到折叠块 ----

    @Test
    fun `锚定本轮没接手时折叠块接手`() {
        // 锚定没补（真机情形 B：它在「锚点换条」那支重取了基线），残差还在
        // → 必须由折叠块滚回去，否则按钮就被顶上去没人管。
        assertEquals(
            293f,
            thinkingAnchorCompensation(residualPx = 293f, listBusy = false),
            0f
        )
    }

    @Test
    fun `锚定已经补掉时折叠块不再补——不会叠成 2Δ`() {
        // 锚定补过这一轮之后按钮已经在原位：残差≈0，这里必须一个像素都不补。
        assertEquals(0f, thinkingAnchorCompensation(residualPx = 0f, listBusy = false), 0f)
    }

    // ---- 闸：列表被用户占着的时候不插手 ----

    @Test
    fun `列表正被拖着的时候这一轮不补`() {
        // 用户手指按着 / 惯性在跑：这一轮不插手，返回 false 让重测循环收手，
        // 绝不跟用户抢滚动。
        assertEquals(0f, thinkingAnchorCompensation(residualPx = 371f, listBusy = true), 0f)
        assertEquals(0f, thinkingAnchorCompensation(residualPx = -371f, listBusy = true), 0f)
    }

    // ---- 闸：渲染误差 ----

    @Test
    fun `误差范围内不补以免来回抖`() {
        assertEquals(0f, thinkingAnchorCompensation(residualPx = 0.9f, listBusy = false), 0f)
        assertEquals(0f, thinkingAnchorCompensation(residualPx = -0.9f, listBusy = false), 0f)
        // 阈值本身要补：1px 已经能被眼睛看出「没钉住」
        assertEquals(1f, thinkingAnchorCompensation(residualPx = 1f, listBusy = false), 0f)
    }

    // ---- 源码契约：这条链上任何一环被拆掉都是静默退化 ----

    @Test
    fun `折叠块按自己的高度增量补偿`() {
        val block = thinkingBlockSource()
        // 为什么量高度而不是按钮坐标：按钮坐标同时受「布局重排」与「列表滚动」
        // 影响，按它反推补偿量会形成反馈环——真机实测中部那条消息展开时越补越
        // 偏、收起时一次都没补上。块高度是纯布局量，与滚动无关。
        assertTrue("点下去那一刻要记块高度", block.contains("heightAtToggle = blockHeight"))
        assertTrue("必须量块自身高度", block.contains("onSizeChanged { blockHeight = it.height }"))
        assertTrue("把高度增量交给列表补偿", block.contains("onAnchorShift(delta.toFloat())"))
        assertTrue(
            "残差落进渲染误差就不必补（省一次没必要的滚动）",
            block.contains("THINKING_ANCHOR_TOLERANCE_PX")
        )
        assertTrue(
            "开窗：这段布局变化归折叠块管，锚定让路（两个补偿者 = 2Δ）",
            block.contains("onThinkingToggleWindow(true)")
        )
        assertTrue(
            "关窗：补偿收尾把滚动权还给锚定",
            block.contains("onThinkingToggleWindow(false)")
        )
        assertTrue(
            "一轮不够：公式位图/流式尾帧会在随后几帧继续改高度（真机首轮补完还差 84px）",
            block.contains("THINKING_ANCHOR_MAX_ROUNDS")
        )
        // v0.0.144 的确定性做法：记住「已经补过的累计量」，每轮只补**新增加的**
        // 那一段（reportedTotal）。这比「残差没变就跳过」更可靠——后者要判断
        // 「残差没变是布局没跟上还是已经到位」，而累计量做减法没有这种歧义。
        assertTrue(
            "每轮只补新增的增量，别把同一段位移补两遍",
            block.contains("reportedTotal")
        )
    }

    @Test
    fun `列表按残差滚，不再靠位置启发式推断归属`() {
        val list = listSource()
        assertTrue("列表要真的按残差滚", list.contains("listState.scrollBy(shiftPx)"))
        assertTrue(
            "归属判据要同时看残差与「列表忙不忙」",
            list.contains("thinkingAnchorCompensation(") &&
                list.contains("residualPx =") &&
                list.contains("listBusy =")
        )
        // 真机教训：位置启发式（贴底 / 被折叠的是不是最上面那条）预测不了锚定
        // 会不会补，猜错就没人补。这里把它钉成「不许回来」。
        assertFalse(
            "归属不许再靠位置启发式推断——它在真机上把情形 B 判错了",
            list.contains("viewportAnchoredAboveToggle")
        )
    }

    private fun thinkingBlockSource(): String {
        val bubble = readSource("app/src/main/java/com/mistakebook/ui/chat/ChatMessageBubble.kt")
        val start = bubble.indexOf("internal fun ThinkingBlock(")
        val end = bubble.indexOf("\n}\n", start)
        return bubble.substring(start, end)
    }

    private fun listSource(): String =
        readSource("app/src/main/java/com/mistakebook/ui/chat/ChatMessageList.kt")

    private fun readSource(path: String): String {
        val candidates = listOf(
            File(path),
            File("../$path"),
            File(path.removePrefix("app/")),
            File("../" + path.removePrefix("app/"))
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: throw IllegalArgumentException("Cannot find file: $path among $candidates")
        return file.readText()
    }
}

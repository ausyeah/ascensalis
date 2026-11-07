package com.mistakebook.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 三处左滑的几何口径（v0.0.116）。
 *
 * 用户的原话：「图三图二的地方的这个改成图一一样的啊饱满圆润的」——
 * 图一是对话历史抽屉，色块由 `matchParentSize()` 撑满卡片高度、并被父级
 * 圆角裁过外沿；图二（首页错题卡）和 图三（已归档）各自写了一份，色块
 * 只有图标加一行字那么高，滑开就是一小块飘在卡片中间，边角还是直的。
 *
 * 这类退化不报错、不影响点击，只能靠钉源码守住：「色块必须撑满父级」。
 * 三条断言分别对应三个页面，任何一个被改回「按内容高度」都会红。
 */
class SwipeActionGeometryTest {

    private val chatHistory = readSource("src/main/java/com/mistakebook/ui/chat/ChatHistorySheet.kt")
    private val home = readSource("src/main/java/com/mistakebook/ui/home/HomeScreen.kt")
    private val archived = readSource("src/main/java/com/mistakebook/ui/settings/ArchivedItemsScreen.kt")

    @Test
    fun `对话历史是参照实现：matchParentSize 撑满卡片`() {
        assertTrue(
            "对话历史的操作区必须 matchParentSize()，它是「饱满」的来源",
            chatHistory.contains(".matchParentSize()")
        )
    }

    @Test
    fun `首页错题卡不再用 fillMaxHeight 撑色块`() {
        val body = home.substringAfter("private fun SwipeableQuestionCard(")
            .substringBefore("private val SwipeDeleteRevealWidth")

        assertTrue(
            "错题卡在 LazyColumn 里纵向约束无限，fillMaxHeight() 等于不撑高",
            body.contains(".matchParentSize()")
        )
        assertTrue(
            "色块靠右排列（v0.0.132 拼贴：End 写进 spacedBy 参数）",
            body.contains("Arrangement.spacedBy(SwipeActionGap, Alignment.End)")
        )
    }

    @Test
    fun `归档页色块也撑满并带父级圆角`() {
        val body = archived.substringAfter("private fun ArchiveSwipeRow(")

        assertTrue("归档操作区必须 matchParentSize()", body.contains(".matchParentSize()"))
        assertTrue(
            "父级必须 clip 圆角，否则色块外沿是直角的",
            body.contains(".clip(MaterialTheme.shapes.medium)")
        )
        assertTrue(
            "露出内容随滑动进度淡入，与另两处同一手感",
            body.contains("swipeRevealContentAlpha(")
        )
    }

    /**
     * v0.0.132 完整图形拼贴（用户给设计图：「）（这样的两个都是完整
     * 图形的拼贴」，并明确「ui重叠」不可接受）：叠画画法整体废弃——
     * 每块是**四角全圆的独立图形**，并排留 [SwipeActionGap] 细缝，
     * 交界的圆是每块自己的轮廓。
     */
    @Test
    fun `三处左滑都是独立完整图形的拼贴`() {
        val swipeReveal = readSource("src/main/java/com/mistakebook/ui/common/SwipeReveal.kt")
        val chat = chatHistory()
        val archived = readSource("src/main/java/com/mistakebook/ui/settings/ArchivedItemsScreen.kt")
        val home = home()

        assertTrue(
            "统一形状必须是四角全圆的 tile",
            swipeReveal.contains("internal fun swipeActionTileShape()") &&
                swipeReveal.contains("topStart = CornerSize(SwipeActionJunctionRadius)") &&
                swipeReveal.contains("bottomEnd = CornerSize(SwipeActionJunctionRadius)")
        )
        assertTrue(
            "块间必须有细缝常量",
            swipeReveal.contains("val SwipeActionGap: Dp = 0.dp")
        )
        data class TileCase(val name: String, val src: String, val minTiles: Int)
        listOf(
            TileCase("对话历史", chat, 3),
            TileCase("归档页", archived, 2),
            TileCase("首页错题卡", home, 2)
        ).forEach { case ->
            assertTrue(
                "${case.name} 必须用 tile 形状（×${case.minTiles}）",
                case.src.split("shape = swipeActionTileShape()").size - 1 >= case.minTiles
            )
            assertFalse(
                "${case.name} 不许再出现叠画层",
                case.src.contains("SwipeActionLayer(")
            )
            assertFalse(
                "${case.name} 不许再有方角补弧的中间块",
                case.src.contains("swipeActionJunctionShape")
            )
        }
        assertTrue(
            "对话历史露出宽度 = 三块 + 两道缝",
            chat.contains("SwipeActionRevealWidth + SwipeActionGap * 2")
        )
        assertTrue(
            "归档页露出宽度 = 两块 + 一道缝",
            archived.contains("SwipeActionBlockWidth * 2 + SwipeActionGap")
        )
        assertTrue(
            "首页露出宽度 = 两块 + 一道缝",
            home.contains("SwipeDeleteRevealWidth + SwipeActionGap")
        )
    }

    /**
     * v0.0.132 互斥与自动收回（用户：「焦点在他上面的时候可以展开，
     * 同时不能展开两个，切换其他页面要自动收回防止误触」）：
     * 三处左滑共用 SwipeRevealCoordinator——某行滑开登记自己，别的行
     * 监听到「不是自己」自动收回；页面/抽屉关闭时协调器随 composition
     * 销毁，回到页面不残留展开行。
     */
    @Test
    fun `三处左滑接入互斥协调器`() {
        val swipeReveal = readSource("src/main/java/com/mistakebook/ui/common/SwipeReveal.kt")
        val chat = chatHistory()
        val archived = readSource("src/main/java/com/mistakebook/ui/settings/ArchivedItemsScreen.kt")
        val home = home()

        assertTrue(
            "协调器必须是共享组件",
            swipeReveal.contains("class SwipeRevealCoordinator")
        )
        assertTrue(
            "对话历史必须接入协调器（行签名 + 起手收别行 + 滑开登记）",
            chat.contains("coordinator: SwipeRevealCoordinator") &&
                chat.contains("if (coordinator.revealedId != myId) coordinator.revealedId = null") &&
                chat.contains("coordinator.revealedId = if (opened) myId else null")
        )
        assertTrue(
            "错题卡必须接入协调器（v0.0.132 双向滑：三态吸附登记）",
            home.contains("coordinator: SwipeRevealCoordinator") &&
                home.contains("coordinator.revealedId = if (openedLeft || openedRight) myId else null")
        )
        assertTrue(
            "归档页必须接入协调器（题目/会话/任务三个列表都要传）",
            archived.split("coordinator = swipeCoordinator").size - 1 >= 3
        )
        assertTrue(
            "互斥收回必须放过拖动中的行（只在「已开」时响应）",
            chat.contains("if (revealed && coordinator.revealedId != myId)")
        )
    }

    private fun chatHistory(): String =
        readSource("src/main/java/com/mistakebook/ui/chat/ChatHistorySheet.kt")

    private fun home(): String =
        readSource("src/main/java/com/mistakebook/ui/home/HomeScreen.kt")

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
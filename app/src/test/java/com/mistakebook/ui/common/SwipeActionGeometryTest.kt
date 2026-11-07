package com.mistakebook.ui.common

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
            "色块靠右排列，去掉 align(CenterEnd) 后必须用 Arrangement.End",
            body.contains("horizontalArrangement = Arrangement.End")
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
     * v0.0.121 圆润化（用户：「蓝色左边缘没有做圆润化处理，红蓝连接的
     * 方式也没有做圆润化处理」）：蓝块左缘随卡片圆角，右缘以衔接圆弧
     * 压在向左多铺一段的红块上——交界处是弧线不是直线。
     */
    @Test
    fun `首页红蓝双块圆润过渡`() {
        val swipeReveal = readSource("src/main/java/com/mistakebook/ui/common/SwipeReveal.kt")
        assertTrue(
            "公共块必须支持 shape（默认直角，圆润页自己传）",
            swipeReveal.contains("shape: Shape = RectangleShape")
        )
        val body = home.substringAfter("private fun SwipeableQuestionCard(")
            .substringBefore("private val SwipeDeleteRevealWidth")

        assertTrue(
            "蓝块必须带 RoundedCornerShape（左缘随卡片、右缘压红）",
            body.contains("shape = RoundedCornerShape(")
        )
        assertTrue(
            "红块必须向左多铺衔接带（SwipeActionBlockWidth + SwipeActionJunctionRadius），否则蓝块圆角下漏缝",
            body.contains("width = SwipeActionBlockWidth + SwipeActionJunctionRadius")
        )
        assertTrue(
            "红块先画、蓝块后画（Box 子级顺序），蓝块圆弧才压得住红",
            body.indexOf("color = MaterialTheme.colorScheme.errorContainer") <
                body.indexOf("color = MaterialTheme.colorScheme.primaryContainer")
        )
    }

    /**
     * v0.0.131（用户看已归档会话页截图：「恢复这个左边的蓝色左边没有
     * 倒角啊看着怪别扭的」）：归档页两块也从 Row 并排改成叠画——
     * 恢复块左缘取卡片圆角、右缘以衔接圆弧压在删除块上。三处左滑
     * （错题卡 / 对话历史 / 归档页）到此共用同一套画法与形状。
     */
    @Test
    fun `归档页双块叠画且恢复块左缘随卡片圆角`() {
        val body = archived.substringAfter("private fun ArchiveSwipeRow(")

        assertTrue(
            "归档页必须用共享的叠画层（与对话历史同一画法）",
            body.contains("SwipeActionLayer(Modifier.matchParentSize()")
        )
        assertTrue(
            "删除块必须向左多铺衔接带，恢复块的圆弧才压得住",
            body.contains("width = SwipeActionBlockWidth + SwipeActionJunctionRadius")
        )
        assertTrue(
            "恢复块必须带形状：左缘随卡片圆角、右缘衔接圆弧",
            body.contains(
                "topStart = MaterialTheme.shapes.medium.topStart"
            ) && body.contains("topEnd = CornerSize(SwipeActionJunctionRadius)")
        )
        assertTrue(
            "删除块先画、恢复块后画（Box 子级顺序），恢复块圆弧才压得住删除块",
            body.indexOf("R.string.archived_delete") <
                body.indexOf("R.string.archived_restore")
        )
    }

    @Test
    fun `叠画层与衔接形状只此一份——三处共用不各抄`() {
        val swipeReveal = readSource("src/main/java/com/mistakebook/ui/common/SwipeReveal.kt")
        val chatHistory = chatHistory()
        val home = home()

        assertTrue(
            "SwipeReveal 必须提供共享叠画层 SwipeActionLayer",
            swipeReveal.contains("internal fun SwipeActionLayer(")
        )
        assertTrue(
            "SwipeReveal 必须提供共享衔接形状 swipeActionJunctionShape",
            swipeReveal.contains("internal fun swipeActionJunctionShape()")
        )
        assertTrue(
            "对话历史必须改用共享叠画层，不许留私有副本",
            chatHistory.contains("SwipeActionLayer(Modifier.matchParentSize()") &&
                !chatHistory.contains("private fun ActionLayer(")
        )
        assertTrue(
            "对话历史必须改用共享衔接形状",
            chatHistory.contains("shape = swipeActionJunctionShape()") &&
                !chatHistory.contains("private fun junctionShape(")
        )
    }

    /**
     * v0.0.131 二轮（用户看对话历史截图：「中间不要留空隙，不能够有
     * 不圆润的边角」）：中间块四角全收弧时，左缘弧与上层块右缘弧
     * 「弧对弧」双双缺料，交界露出父容器底色月牙。中间块必须
     * **左缘直角**（方角填满上层弧的缺口）、右缘收弧（自己成为
     * 下一条交界弧线）——每条交界恰好一条弧线，零缝隙。
     */
    @Test
    fun `中间块左直角右弧——弧对弧露底色的缝隙必须消失`() {
        val swipeReveal = readSource("src/main/java/com/mistakebook/ui/common/SwipeReveal.kt")
        val body = swipeReveal
            .substringAfter("internal fun swipeActionJunctionShape()")
            .take(400)
        assertTrue(
            "中间块左缘必须直角（topStart/bottomStart = 0），方角才能补住上层弧缺口",
            body.contains("topStart = CornerSize(0.dp)") &&
                body.contains("bottomStart = CornerSize(0.dp)")
        )
        assertTrue(
            "中间块右缘必须收衔接弧，作为与底层块的交界弧线",
            body.contains("topEnd = CornerSize(SwipeActionJunctionRadius)") &&
                body.contains("bottomEnd = CornerSize(SwipeActionJunctionRadius)")
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
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
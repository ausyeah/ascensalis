package com.mistakebook.ui.home

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 首页顶部的层级（v0.0.121）。
 *
 * 用户对着截图定的规矩：「标签第一个，复习第二个，倒计时第三个，
 * 倒计时和复习可以随题目往下翻被掩盖，筛选永远是最高级」。
 *
 * 落到布局上是三层：
 *  1. 筛选条钉在搜索栏正下方——它不进列表，永远不会被卷走；
 *  2. 复习横幅是列表第一个条目；
 *  3. 倒计时卡是列表第二个条目，随滚动一起被盖住。
 *
 * 之前倒计时卡钉在筛选条上方，题一多它还占着屏幕第一行，
 * 用户想要的信息反而要往下找——这条层级只能钉源码守住。
 */
class HomeTopHierarchyTest {

    private val home = readSource("src/main/java/com/mistakebook/ui/home/HomeScreen.kt")

    /** 搜索栏之后、滚动列表之前的静态区：只允许放筛选条。 */
    private val staticArea: String = home
        .substringAfter("Column(modifier = Modifier.padding(padding).fillMaxSize())")
        .substringBefore("when {")

    private val listBody: String = home
        .substringAfter("else -> LazyColumn(")
        .substringBefore("if (showAddSheet)")

    @Test
    fun `筛选条在搜索栏正下方且不再有东西压在它上面`() {
        assertTrue(
            "静态区里必须还有 FilterRow 调用",
            staticArea.contains("FilterRow(")
        )
        assertTrue(
            "倒计时卡不能再钉在筛选条上方，它是列表条目了",
            !staticArea.contains("ExamCountdownCard(")
        )
    }

    @Test
    fun `复习横幅在列表最前`() {
        val bannerAt = listBody.indexOf("review-banner")
        assertTrue("列表里必须有复习横幅条目", bannerAt >= 0)
        val countdownAt = listBody.indexOf("exam-countdown")
        assertTrue(
            "复习横幅必须排在倒计时卡前面（复习第二、倒计时第三）",
            countdownAt < 0 || bannerAt < countdownAt
        )
    }

    @Test
    fun `倒计时卡是列表条目且与题目卡对齐`() {
        assertTrue(
            "倒计时卡必须搬进 LazyColumn 成为条目",
            listBody.contains("ExamCountdownCard(")
        )
        val card = readSource("src/main/java/com/mistakebook/ui/home/ExamCountdownCard.kt")
        assertTrue(
            "卡片的横向留白交给列表 contentPadding，自己不再加 14dp（否则比题目卡缩一圈）",
            !card.contains("padding(horizontal = 14.dp")
        )
    }

    @Test
    fun `空态在列表里撑满一屏而不是缩成一条`() {
        assertTrue(
            "空态必须包在 fillParentMaxSize 里：LazyColumn 纵向约束无限，fillMaxSize 会缩成内容高",
            listBody.contains("fillParentMaxSize()")
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

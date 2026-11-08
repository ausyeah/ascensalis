package com.mistakebook.data.prefs

import com.mistakebook.ui.home.upcomingCustomEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.encodeToString
import java.time.LocalDate

/**
 * 自定义未来事件（v0.0.134，issue #56）的纯逻辑测试：
 * 展示过滤（只留今天及以后）与排序（日期升序）。
 *
 * DataStore JSON 往返由 kotlinx.serialization 保证（@Serializable data class
 * 全默认值字段），这里钉住会被改坏的行为语义。
 */
class CustomEventsTest {

    private val today: LocalDate = LocalDate.of(2026, 11, 8)

    @Test
    fun `过去的事件被过滤掉`() {
        val events = listOf(
            CustomEvent("昨天", today.minusDays(1).toEpochDay()),
            CustomEvent("今天", today.toEpochDay()),
            CustomEvent("明天", today.plusDays(1).toEpochDay())
        )
        val upcoming = upcomingCustomEvents(events, today)
        // 今天（dateEpochDay >= today）保留；昨天（严格早于今天）淘汰
        assertEquals(listOf("今天", "明天"), upcoming.map { it.name })
    }

    @Test
    fun `按日期升序排列——最近的最先看到`() {
        val events = listOf(
            CustomEvent("远", today.plusDays(30).toEpochDay()),
            CustomEvent("近", today.plusDays(2).toEpochDay()),
            CustomEvent("中", today.plusDays(7).toEpochDay())
        )
        val upcoming = upcomingCustomEvents(events, today)
        assertEquals(listOf("近", "中", "远"), upcoming.map { it.name })
    }

    @Test
    fun `全部过期时返回空列表`() {
        val events = listOf(
            CustomEvent("旧1", today.minusDays(10).toEpochDay()),
            CustomEvent("旧2", today.minusDays(1).toEpochDay())
        )
        assertTrue(upcomingCustomEvents(events, today).isEmpty())
    }

    @Test
    fun `空列表原样返回`() {
        assertTrue(upcomingCustomEvents(emptyList(), today).isEmpty())
    }

    @Test
    fun `JSON 往返保真——名称与日期字段都不丢`() {
        val events = listOf(
            CustomEvent("月考", LocalDate.of(2026, 12, 1).toEpochDay()),
            CustomEvent("竞赛", LocalDate.of(2027, 3, 15).toEpochDay())
        )
        val raw = kotlinx.serialization.json.Json.encodeToString(events)
        val back = kotlinx.serialization.json.Json.decodeFromString<List<CustomEvent>>(raw)
        assertEquals(events, back)
    }
}

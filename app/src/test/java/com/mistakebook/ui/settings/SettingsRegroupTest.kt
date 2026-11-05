package com.mistakebook.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 设置页四卡重组（v0.0.124）。
 *
 * 用户：「目前设置功能很多但是不好找，你整理一下」＋「番茄钟怎么没有
 * 重置数据这种设置选项呢，模仿单词书数据等做一个」。
 *
 * 重组后的口径钉在这里：
 * - **数据**独立成第四张卡，四类数据入口一张卡找齐（备份 / 归档 /
 *   单词书数据 / 番茄钟数据）；
 * - **学习**卡里背单词、番茄钟从「学习工具」拆出独立分组——
 *   一级分组名直接可见，不用挨个展开猜；
 * - 番茄钟数据重置与单词书数据同款：重置今日 + 全部重置、两步确认，
 *   落库走 SettingsViewModel（IO + NonCancellable），DAO 有按日清除。
 */
class SettingsRegroupTest {

    private val screen = readSource("src/main/java/com/mistakebook/ui/settings/SettingsScreen.kt")
    private val storage = readSource("src/main/java/com/mistakebook/ui/settings/SettingsStorageSection.kt")
    private val viewModel = readSource("src/main/java/com/mistakebook/ui/settings/SettingsViewModel.kt")
    private val dao = readSource("src/main/java/com/mistakebook/data/local/PomodoroDao.kt")

    /** 「数据」分类卡整段（从卡声明到「通用」卡声明之前）。 */
    private val dataCard: String = screen
        .substringAfter("settings_group_data")
        .substringBefore("settings_group_general")

    @Test
    fun `数据卡收拢四类数据入口`() {
        assertTrue("必须存在独立的「数据」分类卡", screen.contains("R.string.settings_group_data"))
        listOf(
            "settings_group_data_backup",
            "settings_group_archived",
            "settings_group_wordbook_data",
            "settings_group_pomodoro_data"
        ).forEach { res ->
            assertTrue(
                "数据卡缺分组：$res",
                dataCard.contains("R.string.$res")
            )
        }
    }

    @Test
    fun `通用卡瘦身只剩外观用量网络关于`() {
        val general = screen.substringAfter("settings_group_general")
        listOf(
            "settings_group_appearance",
            "settings_group_usage",
            "settings_group_network",
            "settings_group_about"
        ).forEach { res ->
            assertTrue("通用卡缺分组：$res", general.contains("R.string.$res"))
        }
        assertFalse(
            "数据类分组不许留在通用卡里（重组前散在三处，正是「不好找」的根源）",
            general.contains("settings_group_data_backup") ||
                general.contains("settings_group_wordbook_data") ||
                general.contains("settings_group_pomodoro_data") ||
                general.contains("settings_group_archived")
        )
    }

    @Test
    fun `背单词与番茄钟在学习卡独立成组`() {
        assertTrue(
            "学习卡必须有独立的「背单词」分组",
            screen.contains("R.string.settings_group_wordbook")
        )
        assertTrue(
            "学习卡必须有独立的「番茄钟」分组",
            screen.contains("R.string.settings_group_pomodoro")
        )
        val tools = screen.substringAfter("settings_group_learning_tools")
            .substringBefore("settings_group_wordbook")
        assertFalse(
            "背单词设置不许再折进「学习工具」组",
            tools.contains("WordbookSection(")
        )
    }

    @Test
    fun `番茄钟数据重置与单词书同款且两步确认`() {
        val pom = storage.substringAfter("PomodoroDataSection")
        assertTrue(
            "必须有重置今日记录按钮", pom.contains("settings_pomodoro_reset_today")
        )
        assertTrue(
            "必须有全部重置按钮", pom.contains("settings_pomodoro_reset_all")
        )
        assertTrue(
            "确认必须走两步确认组件",
            pom.contains("TwoStepConfirmTextButton")
        )
    }

    @Test
    fun `重置落库在 ViewModel 且按日清除有 DAO 支撑`() {
        assertTrue(
            "ViewModel 必须有 resetPomodoroToday",
            viewModel.contains("fun resetPomodoroToday()")
        )
        assertTrue(
            "ViewModel 必须有 resetPomodoroAll",
            viewModel.contains("fun resetPomodoroAll()")
        )
        assertTrue(
            "今日重置必须按 day 字段删（与实体同口径）",
            dao.contains("DELETE FROM pomodoro_sessions WHERE day = :day")
        )
        assertTrue(
            "今日重置必须走 clearSessionsOnDay",
            viewModel.contains("pomodoroDao.clearSessionsOnDay(today)")
        )
        assertTrue(
            "全部重置必须走 clearAllSessions",
            viewModel.contains("pomodoroDao.clearAllSessions()")
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

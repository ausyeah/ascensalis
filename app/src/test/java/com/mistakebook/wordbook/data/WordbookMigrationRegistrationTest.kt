package com.mistakebook.wordbook.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 扫描 [WordbookDatabase] 源码，检查单词库的 Room 迁移**没有漏注册**、路径完整、且开启了 Schema 导出。
 *
 * 与主库 [MigrationRegistrationTest] 保持同一防护基准。
 */
class WordbookMigrationRegistrationTest {

    private val databaseSource: String by lazy {
        val candidates = listOf(
            File("src/main/java/com/mistakebook/wordbook/data/WordbookDatabase.kt"),
            File("app/src/main/java/com/mistakebook/wordbook/data/WordbookDatabase.kt")
        )
        candidates.firstOrNull { it.exists() }?.readText()
            ?: error("找不到 WordbookDatabase.kt，已尝试：${candidates.map { it.path }}")
    }

    private fun definedMigrations(): List<String> =
        Regex("""val\s+(MIGRATION_\d+_\d+)\s*=""")
            .findAll(databaseSource)
            .map { it.groupValues[1] }
            .toList()

    private fun registeredMigrations(): List<String> {
        val call = Regex("""\.addMigrations\(([^)]*)\)""")
            .find(databaseSource)
            ?: error("找不到 addMigrations 调用")
        return Regex("""MIGRATION_\d+_\d+""")
            .findAll(call.groupValues[1])
            .map { it.value }
            .toList()
    }

    private fun declaredVersion(): Int {
        val constant = Regex("""WORDBOOK_DB_VERSION\s*=\s*(\d+)""").find(databaseSource)
        if (constant != null) return constant.groupValues[1].toInt()
        val inline = Regex("""version\s*=\s*(\d+)""").find(databaseSource)
            ?: error("找不到版本声明")
        return inline.groupValues[1].toInt()
    }

    @Test
    fun `定义过的迁移全部注册`() {
        val defined = definedMigrations()
        assertTrue("至少应定义一条迁移（1→2），实际解析到 $defined", defined.isNotEmpty())

        val registered = registeredMigrations()
        val missing = defined.filterNot { it in registered }
        assertEquals(
            "单词库迁移已定义但未在 addMigrations(...) 中注册：$missing",
            emptyList<String>(),
            missing
        )
    }

    @Test
    fun `从版本 1 到当前版本路径完整`() {
        val defined = definedMigrations().toSet()
        val target = declaredVersion()

        var version = 1
        while (version < target) {
            val step = "MIGRATION_${version}_${version + 1}"
            assertTrue(
                "缺少从版本 $version 到 ${version + 1} 的连续迁移（期望 $step）",
                step in defined
            )
            version++
        }
    }

    @Test
    fun `exportSchema 必须显式开启`() {
        val match = Regex("""exportSchema\s*=\s*(true|false)""").find(databaseSource)
        assertTrue("必须显式声明 exportSchema", match != null)
        assertEquals(
            "WordbookDatabase 必须开启 exportSchema = true，对齐 Schema 版本化契约防线",
            "true",
            match!!.groupValues[1]
        )
    }

    @Test
    fun `绝不开 fallbackToDestructiveMigration`() {
        assertFalse(
            "WordbookDatabase 绝不可调用 fallbackToDestructiveMigration()，防止升级静默清空学习进度与错词本",
            Regex("""\.\s*fallbackToDestructiveMigration\s*\(""").containsMatchIn(databaseSource)
        )
    }
}

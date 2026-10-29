package com.mistakebook.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 扫描 [MistakeBookDatabase] 源码，检查 Room 迁移**没有漏注册**。
 *
 * ## 为什么扫源码而不是跑迁移
 *
 * 真正验证迁移要 `MigrationTestHelper` + 仪器测试（本项目零仪器测试基建）。
 * 而这里要防的**不是迁移写错**，是**迁移忘了注册**——
 * 那个错误在 JVM 单测里根本不出现，因为没人会去打开一个旧版本数据库。
 *
 * 扫描源码能覆盖的正是这一类：文本里有 `MIGRATION_2_3` 的定义，
 * 却没有出现在 `addMigrations(...)` 里。
 *
 * ## 这个 bug 的实际后果
 *
 * v0.0.6~v0.0.11 全部漏了 `MIGRATION_2_3`：`version = 3` 已在，
 * 迁移对象也写好了，但 `addMigrations(MIGRATION_1_2)` 没带上它。
 * 老用户从 v2 升级时 Room 找不到路径 → `IllegalStateException` → **启动即闪退**。
 * 新装用户不受影响（直接建 v3），所以这个缺陷能一路发布出去。
 */
class MigrationRegistrationTest {

    private val databaseSource: String by lazy {
        // 测试运行时的工作目录是模块根目录
        val candidates = listOf(
            File("src/main/java/com/mistakebook/data/local/MistakeBookDatabase.kt"),
            File("app/src/main/java/com/mistakebook/data/local/MistakeBookDatabase.kt")
        )
        candidates.firstOrNull { it.exists() }?.readText()
            ?: error("找不到 MistakeBookDatabase.kt，已尝试：${candidates.map { it.path }}")
    }

    /** 所有在源码里定义过的迁移对象名，形如 `MIGRATION_1_2`。 */
    private fun definedMigrations(): List<String> =
        Regex("""val\s+(MIGRATION_\d+_\d+)\s*=""")
            .findAll(databaseSource)
            .map { it.groupValues[1] }
            .toList()

    /** `addMigrations(...)` 调用里实际传进去的名字。 */
    private fun registeredMigrations(): List<String> {
        val call = Regex("""\.addMigrations\(([^)]*)\)""")
            .find(databaseSource)
            ?: error("找不到 addMigrations 调用——如果改成了别的方式，请同步更新这条测试")
        return Regex("""MIGRATION_\d+_\d+""")
            .findAll(call.groupValues[1])
            .map { it.value }
            .toList()
    }

    private fun declaredVersion(): Int {
        // v0.0.60 起版本号提成顶层常量 MISTAKE_BOOK_DB_VERSION（annotation 引用它），
        // 优先解析常量；兼容旧的字面量写法 version = 7。
        val constant = Regex("""MISTAKE_BOOK_DB_VERSION\s*=\s*(\d+)""").find(databaseSource)
        if (constant != null) return constant.groupValues[1].toInt()
        val inline = Regex("""version\s*=\s*(\d+)""").find(databaseSource)
            ?: error("找不到版本声明——若版本改成其他常量形态，请同步更新这条测试")
        return inline.groupValues[1].toInt()
    }

    // ------------------------------------------------------- 核心断言

    /**
     * 每一条定义过的迁移都必须注册。
     *
     * 这是本文件存在的主要理由。
     */
    @Test
    fun `定义过的迁移全部注册`() {
        val defined = definedMigrations()
        assertTrue("至少应定义一条迁移（1→2），实际解析到 $defined", defined.isNotEmpty())

        val registered = registeredMigrations()
        val missing = defined.filterNot { it in registered }
        assertEquals(
            "这些迁移定义了但没出现在 addMigrations(...) 里——" +
                "老用户升级时会抛 IllegalStateException 直接闪退：$missing",
            emptyList<String>(),
            missing
        )
    }

    /**
     * 从 1 升到当前版本必须有**完整路径**。
     *
     * 只查「定义了没注册」还不够：万一两条迁移之间断了链
     * （比如定义了 1→2 和 3→4，但没有 2→3），老用户同样升不动。
     */
    @Test
    fun `从版本 1 到当前版本路径完整`() {
        val defined = definedMigrations().toSet()
        val target = declaredVersion()

        var version = 1
        val path = mutableListOf<String>()
        while (version < target) {
            val next = version + 1
            val migration = "MIGRATION_${version}_$next"
            assertTrue(
                "缺少 $version → $next 的迁移路径。" +
                    "已定义的：${defined.sorted()}。" +
                    "当前只能走到 ${path.joinToString(" → ") ?: "1"}，到不了 $target",
                migration in defined
            )
            path += "$version→$next"
            version = next
        }
        assertEquals("应当每一步都有迁移", target - 1, path.size)
    }

    // ------------------------------------------------------- 约束

    /**
     * 不能开 `fallbackToDestructiveMigration`：那会静默清空用户的错题。
     *
     * 只扫**代码**不扫注释——否则本文件的 KDoc 里就写着这个词
     * （正是在说明为什么不能开），扫到它会永远报红：
     * 提醒功能很可靠，但是假的。
     */
    @Test
    fun `禁止破坏性迁移兜底`() {
        val codeOnly = stripComments(databaseSource)
        assertTrue(
            "绝不能开 fallbackToDestructiveMigration——" +
                "迁移缺失时它会静默清空用户攒下的全部错题，" +
                "宁可在启动时崩溃也不能丢数据",
            !codeOnly.contains("fallbackToDestructiveMigration")
        )
    }

    /** 去掉行注释、块注释与原始字符串，只留可执行代码。 */
    private fun stripComments(source: String): String = source
        .replace(Regex("""//[^\n]*"""), "")
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("\"\"\".*?\"\"\"", RegexOption.DOT_MATCHES_ALL), "")

    /** 没有 `exportSchema` 就没法用 `MigrationTestHelper` 验证，将来补迁移会缺工具。 */
    @Test
    fun `开启 schema 导出`() {
        assertTrue(
            "Room 的 exportSchema 必须开启，否则没有 schema json，" +
                "将来补 MigrationTestHelper 用例时无从对照",
            Regex("""exportSchema\s*=\s*true""").containsMatchIn(databaseSource)
        )
    }
}
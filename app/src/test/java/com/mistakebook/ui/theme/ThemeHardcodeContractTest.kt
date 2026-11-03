package com.mistakebook.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「界面层不写裸色值」的防回归契约（premium-ui 铁律：每个颜色有槽位）。
 *
 * 背景：v0.0.88 UI 重构前，theme 包外散落 92 处 `Color(0xFF…)`（按出现
 * 次数计，一行多匹配分别计数），大多配合 `LocalDarkTheme` 手写明暗分支
 * ——主题换血后这些值不再与新色板同族，是质感崩坏的主要来源。迁移策略
 * 是逐屏收敛进 colorScheme 槽位，本契约把**当前存量 92 钉死**：
 * - 每迁移一处，基线就该跟着调低；
 * - 任何新增裸色值（新界面、新功能）都会让测试变红——新增颜色必须
 *   先进色板槽位，再在界面里引用。
 *
 * v0.0.89 又迁走一处：对话历史删除的「确认态」固定深红 #8B0000 换成
 * colorScheme 的 error / errorContainer 两档（深浅语义在亮暗主题下
 * 方向一致，且删除块底色同步从裸 #D32F2F 迁到 errorContainer），
 * 基线 92 → 91。
 *
 * SubjectPalette（学科色表）刻意豁免：学科色相是固定语义（数学=蓝、
 * 语文=红…），不随主题变化。theme 包自身是色板的定义处，不在此列。
 */
class ThemeHardcodeContractTest {

    private val hardcodedColorLimit = 91

    @Test
    fun `界面层裸色值存量只减不增`() {
        val root = sequenceOf(
            File("src/main/java/com/mistakebook"),
            File("app/src/main/java/com/mistakebook"),
            File("../app/src/main/java/com/mistakebook")
        ).firstOrNull { it.isDirectory } ?: error("找不到源码目录")

        val excluded = setOf("theme", "SubjectPalette.kt")
        var count = 0
        val offenders = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val rel = file.relativeTo(root).path.replace('\\', '/')
                if (rel.startsWith("ui/theme/") || file.name == "SubjectPalette.kt") return@forEach
                val hits = file.readText().split("Color(0x").size - 1
                if (hits > 0) {
                    count += hits
                    offenders += "$rel: $hits"
                }
            }

        assertTrue(
            "界面层裸 Color(0x…) 超过基线 $hardcodedColorLimit（当前 $count）。\n" +
                "新增颜色必须走 colorScheme 槽位；迁移一处请同步调低基线。\n" +
                offenders.joinToString("\n"),
            count <= hardcodedColorLimit
        )
    }
}

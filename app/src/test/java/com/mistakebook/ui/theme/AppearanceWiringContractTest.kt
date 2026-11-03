package com.mistakebook.ui.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 外观旋钮（字号 / 字体 / 圆角）的**接线契约**守护（v0.0.94）。
 *
 * 缩放与圆角的数值在 [AppearanceTest] 里测，但数值再对，只要哪一环没接上，
 * 用户就一点变化都看不到，而所有单测照样全绿。这种退化在 JVM 单测里看不见，
 * 只能钉源码。钉的都是「一退就回到旧样子」的点：
 *
 * 1. 主题必须真的把缩放的 Typography / Shapes 喂给 MaterialTheme；
 * 2. MainActivity 必须把设置里的三个值传进主题（漏一个 = 那个旋钮失效）；
 * 3. 存储侧必须三个键都读都写，缺一个 = 改了不生效或重启后丢；
 * 4. 设置页必须真的挂上这个分组（写了设置项却不在界面上 = 用户找不到）；
 * 5. 界面文案必须走 strings.xml（仓库规则），不许在 Kotlin 里硬编码绕开。
 */
class AppearanceWiringContractTest {

    @Test
    fun `主题必须把缩放后的排版与形状喂给 MaterialTheme`() {
        val theme = readSource("app/src/main/java/com/mistakebook/ui/theme/Theme.kt")

        assertTrue(
            "MistakeBookTheme 必须收这三个旋钮，否则设置页无处可传",
            theme.contains("fontScale: com.mistakebook.data.prefs.FontScale = com.mistakebook.data.prefs.FontScale.DEFAULT") &&
                theme.contains("font: com.mistakebook.data.prefs.AppFont = com.mistakebook.data.prefs.AppFont.DEFAULT") &&
                theme.contains("corner: com.mistakebook.data.prefs.CornerStyle = com.mistakebook.data.prefs.CornerStyle.DEFAULT")
        )
        assertTrue(
            "typography 必须走 appTypography——直接写 ZincTypography 等于字号旋钮失效",
            theme.contains("typography = appTypography(fontScale.scale, fontFamilyOf(font))")
        )
        assertTrue(
            "shapes 必须走 zincShapes——直接写死 Shapes 等于圆角旋钮失效",
            theme.contains("shapes = zincShapes(corner)")
        )
        assertFalse(
            "主题里不许再出现写死的 ZincShapes，否则缩放没有唯一入口",
            theme.contains("= Shapes(")
        )
    }

    @Test
    fun `MainActivity 必须把三个设置值传进主题`() {
        val main = readSource("app/src/main/java/com/mistakebook/MainActivity.kt")

        assertTrue(
            "漏传 fontScale = 字号旋钮整个失效",
            main.contains("fontScale = settings.fontScale")
        )
        assertTrue(
            "漏传 font = 字体旋钮整个失效",
            main.contains("font = settings.font")
        )
        assertTrue(
            "漏传 corner = 圆角旋钮整个失效",
            main.contains("corner = settings.cornerStyle")
        )
    }

    @Test
    fun `存储侧三个键都要读都要写`() {
        val store = readSource("app/src/main/java/com/mistakebook/data/prefs/SettingsStore.kt")

        // 读：新增键老用户库里没有，fromKey 一律回默认，升级后界面一个字都不变
        assertTrue(store.contains("fontScale = FontScale.fromKey(prefs[KEY_FONT_SCALE])"))
        assertTrue(store.contains("font = AppFont.fromKey(prefs[KEY_FONT])"))
        assertTrue(store.contains("cornerStyle = CornerStyle.fromKey(prefs[KEY_CORNER_STYLE])"))

        // 写：三个键都要存在
        assertTrue(store.contains("val KEY_FONT_SCALE = stringPreferencesKey(\"font_scale\")"))
        assertTrue(store.contains("val KEY_FONT = stringPreferencesKey(\"app_font\")"))
        assertTrue(store.contains("val KEY_CORNER_STYLE = stringPreferencesKey(\"corner_style\")"))

        // 三个 setter
        assertTrue(store.contains("suspend fun setFontScale(scale: FontScale) = editString(KEY_FONT_SCALE, scale.name)"))
        assertTrue(store.contains("suspend fun setFont(font: AppFont) = editString(KEY_FONT, font.name)"))
        assertTrue(store.contains("suspend fun setCornerStyle(corner: CornerStyle) = editString(KEY_CORNER_STYLE, corner.name)"))
    }

    @Test
    fun `快照字段必须是可缺省的新增字段`() {
        val snapshot = readSource("app/src/main/java/com/mistakebook/data/prefs/SettingsSnapshot.kt")

        // 默认值给错 = 老用户升级后界面被偷偷改掉
        assertTrue(snapshot.contains("val fontScale: FontScale = FontScale.DEFAULT"))
        assertTrue(snapshot.contains("val font: AppFont = AppFont.DEFAULT"))
        assertTrue(snapshot.contains("val cornerStyle: CornerStyle = CornerStyle.DEFAULT"))
    }

    @Test
    fun `ViewModel 三个 setter 都要通到存储`() {
        val vm = readSource("app/src/main/java/com/mistakebook/ui/settings/SettingsViewModel.kt")

        assertTrue(vm.contains("fun setFontScale(scale: FontScale) = viewModelScope.launch {"))
        assertTrue(vm.contains("store.setFontScale(scale)"))
        assertTrue(vm.contains("fun setFont(font: AppFont) = viewModelScope.launch {"))
        assertTrue(vm.contains("store.setFont(font)"))
        assertTrue(vm.contains("fun setCornerStyle(corner: CornerStyle) = viewModelScope.launch {"))
        assertTrue(vm.contains("store.setCornerStyle(corner)"))
    }

    @Test
    fun `设置页必须真的挂上文字与形状分组`() {
        val screen = readSource("app/src/main/java/com/mistakebook/ui/settings/SettingsScreen.kt")

        assertTrue(
            "写了设置项却不在界面上 = 用户根本找不到这个功能",
            screen.contains("TextStyleSection(state = state, viewModel = viewModel)")
        )
        val sections = readSource("app/src/main/java/com/mistakebook/ui/settings/SettingsAppearanceSections.kt")
        assertTrue(
            "分组必须是 SettingsGroup，与外观分组同一套折叠结构",
            sections.contains("internal fun TextStyleSection(") &&
                sections.contains("SettingsGroup(title = stringResource(R.string.settings_text_shape_title))")
        )
    }

    @Test
    fun `三个旋钮的选项都必须来自同一个枚举`() {
        val sections = readSource("app/src/main/java/com/mistakebook/ui/settings/SettingsAppearanceSections.kt")

        // 手抄一份选项列表 = 将来加档位时设置页少一个，用户看不到新档
        assertTrue(sections.contains("FontScale.entries.forEach { scale ->"))
        assertTrue(sections.contains("AppFont.entries.forEach { font ->"))
        assertTrue(sections.contains("CornerStyle.entries.forEach { corner ->"))
    }

    @Test
    fun `界面文案全部走 stringsxml`() {
        val strings = readSource("app/src/main/res/values/strings.xml")
        val sections = readSource("app/src/main/java/com/mistakebook/ui/settings/SettingsAppearanceSections.kt")

        listOf(
            "settings_text_shape_title",
            "settings_font_size_small",
            "settings_font_size_extra_large",
            "settings_font_family_system",
            "settings_font_family_serif",
            "settings_font_family_monospace",
            "settings_corner_sharp",
            "settings_corner_standard",
            "settings_corner_rounded",
            "settings_font_size_preview",
            "settings_font_family_preview"
        ).forEach { name ->
            assertTrue("strings.xml 少了 $name", strings.contains("name=\"$name\""))
        }

        // 预览 glyph 也算界面文案，一并进了 strings.xml；这里钉住没有绕回去
        listOf("特大", "无衬线", "等宽", "方正", "圆润").forEach { text ->
            assertFalse(
                "「$text」不许硬编码在 Kotlin 里（仓库规则：界面文案全部进 strings.xml）",
                sections.contains("\"$text\"")
            )
        }
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

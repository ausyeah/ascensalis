package com.mistakebook.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.cash.paparazzi.detectEnvironment
import com.mistakebook.ui.theme.MistakeBookTheme
import org.junit.Rule
import org.junit.Test

/**
 * v0.0.54 后续优化 · Task D：设置页三层目录的窄屏截图基线。
 *
 * 用固定宽度 Box（320 / 360dp）把组件约束到窄宽，复现窄屏下的标题换行、
 * 层级缩进与点击区表现。这里只渲染**结构组件**（分类卡 / 二级分组），
 * 不拉真实 SettingsViewModel / AppContainer，故无需 mock 重依赖。
 *
 * 覆盖任务书要求的三类画面：设置首页（分类卡收起）、分类卡展开（露出类内分组）、
 * 一个展开的二级分组（看缩进与标题换行）。
 *
 * 局限（如实说明）：Paparazzi 无法直接设 DeviceConfig 宽度（仓库内无自定义构造先例，
 * 乱猜签名会拖垮整个测试编译），故用宽度约束代替；Paparazzi 也不模拟系统 fontScale，
 * 大字体（fontScale 1.3 / 2.0）需真机验。因此本基线覆盖窄**宽度** + 默认字号，
 * 「320dp + fontScale 2.0 无截断/重叠」的最终确认需在真机 / 有渲染环境时看金标图。
 */
class SettingsLayoutScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5,
        environment = detectEnvironment().copy(compileSdkVersion = 34)
    )

    /** 把内容压到 [width] 宽的竖列，复现窄屏宽度约束。 */
    @Composable
    private fun NarrowColumn(width: Dp, content: @Composable () -> Unit) {
        Box(
            modifier = Modifier
                .width(width)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.background)
        ) { content() }
    }

    private fun category(content: @Composable () -> Unit) = SettingsCategoryCard(
        title = "AI 与识别",
        summary = "大模型、识图、生图、搜索的 Key 与参数",
        icon = Icons.Default.Settings,
        content = content
    )

    @Test
    fun settingsHome_collapsed_320dp() {
        expandedCategoryTitle = null
        expandedGroupTitle = null
        paparazzi.snapshot(name = "settings-home-collapsed-320dp") {
            NarrowColumn(320.dp) { category { Text("（分类内容）") } }
        }
    }

    @Test
    fun settingsHome_collapsed_360dp() {
        expandedCategoryTitle = null
        expandedGroupTitle = null
        paparazzi.snapshot(name = "settings-home-collapsed-360dp") {
            NarrowColumn(360.dp) { category { Text("（分类内容）") } }
        }
    }

    @Test
    fun settingsCategory_expanded_320dp() {
        // 分类展开（露出类内分组头），类内分组全收起
        expandedCategoryTitle = "AI 与识别"
        expandedGroupTitle = null
        paparazzi.snapshot(name = "settings-category-expanded-320dp") {
            NarrowColumn(320.dp) {
                category {
                    CollapsibleSettingsGroup(title = "识别方式", nested = true) { Text("识别方式内容") }
                    CollapsibleSettingsGroup(title = "MinerU 配置", nested = true) { Text("MinerU 内容") }
                    CollapsibleSettingsGroup(title = "网络与代理（高级）", nested = true) { Text("网络代理内容") }
                }
            }
        }
        expandedCategoryTitle = null
    }

    @Test
    fun settingsGroup_expanded_320dp() {
        // 一个展开的二级分组：看层级缩进、标题换行、内容是否被挤压或横向溢出
        expandedGroupTitle = "识别方式"
        paparazzi.snapshot(name = "settings-group-expanded-320dp") {
            NarrowColumn(320.dp) {
                CollapsibleSettingsGroup(title = "识别方式", nested = true) {
                    Text("这是一个展开的二级分组，用来观察窄屏下的层级缩进、标题换行，以及内容是否被挤压或出现横向溢出。")
                }
            }
        }
        expandedGroupTitle = null
    }
}

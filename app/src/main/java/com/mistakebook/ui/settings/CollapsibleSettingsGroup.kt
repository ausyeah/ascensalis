package com.mistakebook.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 可收起的设置分组卡片（v0.0.51 T8，v0.0.52 全局互斥）。
 *
 * ## 为什么改版
 *
 * 旧版所有分组默认全展开，一张长页从主题堆到数据管理，找一项要翻很久；
 * 还有多个「特立独行的单条设置」各占一行。重构后：
 *
 * - 所有分组默认**收起**，只显示组名 + 展开箭头，一屏能看全目录；
 * - **全局手风琴**：整个设置页同一时刻只允许一个分组展开，展开一个，
 *   其他组自动收起。设置页是「找一项 → 调一项 → 走」的页面，同时摊开
 *   三四组只会让页面更长、更找不到北；互斥后视口里永远只有「目录 +
 *   当前一组内容」，最长的一组（AI 服务配置卡）也不会把后续组顶出屏幕。
 * - 各分组**内部内容原样复用**既有 Section 组件，功能逻辑零改动。
 *
 * 展开态用页级单例 [expandedGroupTitle]（当前展开的组名，null = 全收起）
 * 承载：设置页销毁重建后旧组名匹配不上新组实例，等效全收起，行为安全。
 *
 * @param title 组名（收起态显示的全部内容）。
 * @param initiallyExpanded 初始是否展开（默认收起）。
 */
@Composable
internal fun CollapsibleSettingsGroup(
    title: String,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit
) {
    val isExpanded = expandedGroupTitle == title

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(
            containerColor = if (isExpanded) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    // 全局互斥：点已展开的组 = 收起；点其他组 = 展开它并顶掉上一个
                    expandedGroupTitle = if (isExpanded) null else title
                }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isExpanded) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (isExpanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AnimatedVisibility(visible = isExpanded) {
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                content()
            }
        }
    }
}

/** 全局唯一展开态：当前展开的组名（null = 全收起）。 */
private var expandedGroupTitle by androidx.compose.runtime.mutableStateOf<String?>(null)

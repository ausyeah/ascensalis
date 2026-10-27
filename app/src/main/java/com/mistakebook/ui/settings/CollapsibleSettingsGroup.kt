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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 可收起的设置分组卡片（v0.0.51 T8）。
 *
 * ## 为什么改版
 *
 * 旧版所有分组默认全展开，一张长页从主题堆到数据管理，找一项要翻很久；
 * 还有多个「特立独行的单条设置」各占一行。重构后：
 *
 * - 所有分组默认**收起**，只显示组名 + 展开箭头，一屏能看全目录；
 * - `exclusiveScope` 相同的分组共享互斥状态（手风琴）：展开一个，
 *   同 scope 的其他组自动收起——大模型配置/识图/生图几张卡轮流看；
 * - 各分组**内部内容原样复用**既有 Section 组件，功能逻辑零改动。
 *
 * 互斥状态用页级单例 [expandedScopes]（scope 名 → 当前展开的组名）承载：
 * 设置页销毁重建后旧组名匹配不上新组实例，等效全收起，行为安全。
 *
 * @param title 组名（收起态显示的全部内容）。
 * @param exclusiveScope 互斥域：非空时同域组手风琴互斥；null 表示独立开关。
 * @param initiallyExpanded 初始是否展开（默认收起）。
 */
@Composable
internal fun CollapsibleSettingsGroup(
    title: String,
    exclusiveScope: String? = null,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit
) {
    var ownState by remember(title) { mutableStateOf(initiallyExpanded) }

    val isExpanded: Boolean
    if (exclusiveScope == null) {
        isExpanded = ownState
    } else {
        isExpanded = expandedScopes[exclusiveScope] == title
    }

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
                    if (exclusiveScope != null) {
                        expandedScopes[exclusiveScope] = if (isExpanded) null else title
                    } else {
                        ownState = !ownState
                    }
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

/** 互斥域共享状态：scope 名 → 当前展开的组名（null = 全收起）。 */
private val expandedScopes = androidx.compose.runtime.mutableStateMapOf<String, String?>()

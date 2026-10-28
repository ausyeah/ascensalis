package com.mistakebook.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
 * @param nested 是否为分类卡内的二级组：去掉外层 Card 与阴影，改用
 *   细分割线区隔。二级组再套卡片会出现「卡中卡」的双层阴影，
 *   层级关系反而看不清（v0.0.54 设置页三层目录）。
 */
@Composable
internal fun CollapsibleSettingsGroup(
    title: String,
    initiallyExpanded: Boolean = false,
    nested: Boolean = false,
    content: @Composable () -> Unit
) {
    val isExpanded = expandedGroupTitle == title

    val header: @Composable () -> Unit = {
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
                style = if (nested) {
                    MaterialTheme.typography.titleSmall
                } else {
                    MaterialTheme.typography.titleMedium
                },
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
    }

    if (nested) {
        Column(modifier = Modifier.fillMaxWidth()) {
            header()
            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                ) {
                    content()
                }
            }
        }
    } else {
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
            header()
            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                ) {
                    content()
                }
            }
        }
    }
}

/**
 * 设置页的**顶层分类卡**（v0.0.54）。
 *
 * ## 为什么在分组之上再套一层
 *
 * 平铺的分组卡再多也只是「一排等价的卡片」：用户面对 12 个平级入口，
 * 仍然要先读完每个标题才能判断该点哪个——这正是「臃肿、难理解」的来源。
 * 三个语义分类（AI 与识别 / 学习 / 通用）把「找设置」变成两步：
 * 先选大类（3 选 1，标题就带一句摘要说明这类里有什么），
 * 再在类内选具体分组。分类之间用图标 + 摘要区分，不需要读全文。
 *
 * 与分组手风琴**各用各的展开态**（[expandedCategoryTitle] / [expandedGroupTitle]）：
 * 分类是全局互斥，类内的分组也是全局互斥，两者叠加就是
 * 「一个大类 + 一个大类里的一个分组」同时展开，页面长度恒定。
 *
 * 切换分类时清掉分组展开态：否则旧分类里展开的那个组名仍然命中，
 * 用户切回来会看到「我没展开过却开着」的困惑状态。
 *
 * @param summary 标题下的一行摘要：这类设置是干什么的、已配置多少。
 */
@Composable
internal fun SettingsCategoryCard(
    title: String,
    summary: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable () -> Unit
) {
    val isExpanded = expandedCategoryTitle == title

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (isExpanded) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    expandedCategoryTitle = if (isExpanded) null else title
                    // 换分类 = 换上下文，类内展开态从头开始
                    expandedGroupTitle = null
                }
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isExpanded) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(26.dp)
            )
            Spacer(modifier = Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isExpanded) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (isExpanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AnimatedVisibility(visible = isExpanded) {
            Column(modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 12.dp)) {
                content()
            }
        }
    }
}

/** 全局唯一展开的分类名（null = 全收起）。 */
private var expandedCategoryTitle by androidx.compose.runtime.mutableStateOf<String?>(null)

/** 全局唯一展开态：当前展开的组名（null = 全收起）。 */
private var expandedGroupTitle by androidx.compose.runtime.mutableStateOf<String?>(null)

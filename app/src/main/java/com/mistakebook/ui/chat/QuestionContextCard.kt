package com.mistakebook.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mistakebook.R
import com.mistakebook.ui.common.RichText

/**
 * 题目上下文卡片：把注入给模型的那道题，从一堵灰色小字变成一张能认出来的卡。
 *
 * ## 与普通消息的区别（为什么值得单独一张卡）
 *
 * 1. **底色与描边**：`surfaceContainerLow` + 细描边，既不抢对话气泡的风头，
 *    也不会再被看成「AI 先说了一段莫名其妙的话」。
 * 2. **标题与芯片**：标题/学科/难度/错因提到最上面，用户一眼知道这是哪道题；
 *    题干/选项/答案/解析收进折叠区，要看时再点开。
 * 3. **公式照常渲染**：正文走 [RichText]，题干里的 LaTeX 与对话里一样显示。
 *
 * ## 为什么默认收起
 *
 * 题目上下文是**给模型的背景**，不是每轮都要重读的内容。整块铺开的代价是
 * 用户每次进会话都要先滑过几十行才看到对话——那正是这张卡要治的病。
 * 默认收起后标题仍常驻，看不出「题目不见了」。折叠规则与思考过程一致
 * （见 [ThinkingBlock]），用户不需要学第二套交互。
 */
@Composable
internal fun QuestionContextCard(
    content: String,
    mathRenderer: com.mistakebook.math.MathRenderer,
    modifier: Modifier = Modifier
) {
    val parsed = remember(content) { QuestionContext.parse(content) }
    // 收起态按内容记：同一会话里题目上下文只会注入一次，key 基本不会变，
    // 但万一重新注入（换题重开），卡片要回到收起而不是停在展开。
    var expanded by remember(content) { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .animateContentSize()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 2.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.MenuBook,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.chat_context_header),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                // 芯片靠右：标签在左、元信息在右，一眼扫完不占第二行
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf(parsed.subject, parsed.difficulty, parsed.errorReason)
                        .filter { it.isNotBlank() }
                        .forEach { MetaChip(it) }
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = stringResource(
                        if (expanded) R.string.chat_question_card_collapse
                        else R.string.chat_question_card_expand
                    ),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (parsed.title.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = parsed.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (parsed.knowledge.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.chat_question_card_knowledge, parsed.knowledge),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (expanded && parsed.body.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(Modifier.height(8.dp))
                RichText(
                    text = parsed.body,
                    mathRenderer = mathRenderer,
                    style = MaterialTheme.typography.bodySmall,
                    // 题干用 onSurface 而不是 onSurfaceVariant：卡片底是 surfaceContainerLow，
                    // 再用次级色在浅色主题下对比度不够，用户实测「字太淡看不清」
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/** 元信息小芯片（学科 / 难度 / 错因）。 */
@Composable
private fun MetaChip(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

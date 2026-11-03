package com.mistakebook.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mistakebook.R

/**
 * 对话里已经能调用的能力清单（v0.0.96），摆在历史页顶部。
 *
 * ## 为什么是「预填提示词」而不是六个新功能
 * 这六件事本来就是同一套对话能力的不同起手式：翻译、批改、绘图、讲解、
 * 生成图片全部靠模型本身，拍题靠识图。把它们做成独立页面或独立接口，
 * 等于把一棵树砍成六根棍子。这里只负责**把用户送到那条对话里，并替他把
 * 第一句话写好**——不自动发送，用户还能改。
 *
 * ## 为什么每个入口要显示可用状态
 * 用户配好模型之前点这些入口，看到的会是「发送失败 / 没有可用模型」。
 * 与其让他点进去撞一次墙，不如在这里就说明白缺哪一样：模型还是识别服务。
 */
internal enum class ChatTool(
    val labelRes: Int,
    val icon: ImageVector,
    /** 点开后预填进输入框的第一句话；null = 走别的入口（拍题直接开相机）。 */
    val promptRes: Int?,
    val needsLlm: Boolean,
    val needsOcr: Boolean
) {
    PHOTO(
        labelRes = R.string.chat_tool_photo,
        icon = Icons.Default.PhotoCamera,
        promptRes = null,
        needsLlm = true,
        needsOcr = true
    ),
    TRANSLATE(
        labelRes = R.string.chat_tool_translate,
        icon = Icons.Default.Translate,
        promptRes = R.string.chat_tool_translate_prompt,
        needsLlm = true,
        needsOcr = false
    ),
    ESSAY(
        labelRes = R.string.chat_tool_essay,
        icon = Icons.Default.RateReview,
        promptRes = R.string.chat_tool_essay_prompt,
        needsLlm = true,
        needsOcr = false
    ),
    DIAGRAM(
        labelRes = R.string.chat_tool_diagram,
        icon = Icons.Default.Insights,
        promptRes = R.string.chat_tool_diagram_prompt,
        needsLlm = true,
        needsOcr = false
    ),
    EXPLAIN(
        labelRes = R.string.chat_tool_explain,
        icon = Icons.Default.School,
        promptRes = R.string.chat_tool_explain_prompt,
        needsLlm = true,
        needsOcr = false
    ),
    IMAGE(
        labelRes = R.string.chat_tool_image,
        icon = Icons.Default.Image,
        promptRes = R.string.chat_tool_image_prompt,
        needsLlm = true,
        needsOcr = false
    )
}

/**
 * 这个入口现在点得动吗。纯函数，单测直接钉（见 ChatToolsTest）。
 *
 * 缺的是**模型**还是**识别**要分开说：两者的修法完全不同，
 * 一句笼统的「服务未配置」只会让用户去设置页翻半天。
 */
internal fun ChatTool.blockReason(
    llmConfigured: Boolean,
    ocrConfigured: Boolean
): ChatToolBlock = when {
    needsLlm && !llmConfigured -> ChatToolBlock.NEEDS_LLM
    needsOcr && !ocrConfigured -> ChatToolBlock.NEEDS_OCR
    else -> ChatToolBlock.READY
}

internal enum class ChatToolBlock { READY, NEEDS_LLM, NEEDS_OCR }

/** 两行三列的能力入口。不可用的档位只降透明度，仍可点击——点进去会给出配置引导。 */
@Composable
internal fun ChatToolGrid(
    llmConfigured: Boolean,
    ocrConfigured: Boolean,
    onPick: (ChatTool) -> Unit,
    modifier: Modifier = Modifier
) {
    // 固定两行三列：六个入口正好铺满，不必用 FlowRow 那种随宽度回流的布局——
    // 入口位置一旦随屏幕宽度变化，用户就记不住「翻译在最右边」。
    val rows = ChatTool.entries.chunked(3)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { tool ->
                    ChatToolTile(
                        tool = tool,
                        block = tool.blockReason(llmConfigured, ocrConfigured),
                        onClick = { onPick(tool) },
                        modifier = Modifier.weight(1f)
                    )
                }
                // 最后一行不满三列时补空位，保持列宽一致
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ChatToolTile(
    tool: ChatTool,
    block: ChatToolBlock,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ready = block == ChatToolBlock.READY
    val contentColor = if (ready) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                shape = MaterialTheme.shapes.medium
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = tool.icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(tool.labelRes),
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(
                when (block) {
                    ChatToolBlock.READY -> R.string.chat_tool_ready
                    ChatToolBlock.NEEDS_LLM -> R.string.chat_tool_needs_llm
                    ChatToolBlock.NEEDS_OCR -> R.string.chat_tool_needs_ocr
                }
            ),
            style = MaterialTheme.typography.labelSmall,
            color = if (ready) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
            maxLines = 1
        )
    }
}
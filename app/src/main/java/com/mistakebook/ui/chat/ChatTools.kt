package com.mistakebook.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.mistakebook.R
import com.mistakebook.data.chat.ChatToolPrompts

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
    /** 发给模型的隐藏指令（[ChatToolPrompts]），以 SYSTEM 消息注入会话，不进输入框。 */
    val instruction: String,
    /** 拍题 / 翻译 / 作文批改都从一张照片开始：进对话页后自动拉起相机，拍完裁切自动发送。 */
    val needsCamera: Boolean,
    val needsLlm: Boolean,
    val needsOcr: Boolean,
    /**
     * 是否是「手动入口」：点它打开自己的输入框，而不是预填一句提示词。
     * 目前只有函数画图（v0.0.144）是这种。
     */
    val manual: Boolean = false
) {
    PHOTO(
        labelRes = R.string.chat_tool_photo,
        icon = Icons.Default.PhotoCamera,
        instruction = ChatToolPrompts.ASK,
        needsCamera = true,
        needsLlm = true,
        needsOcr = true
    ),
    TRANSLATE(
        labelRes = R.string.chat_tool_translate,
        icon = Icons.Default.Translate,
        instruction = ChatToolPrompts.TRANSLATE,
        // v0.0.106 用户改口：翻译也是「拍一段英文再讲」，所以要先进相机。
        // 手持一段英文却要先打字把原文敲进去，等于让工具替用户做了两遍活。
        needsCamera = true,
        needsLlm = true,
        needsOcr = false
    ),
    ESSAY(
        labelRes = R.string.chat_tool_essay,
        icon = Icons.Default.RateReview,
        instruction = ChatToolPrompts.ESSAY,
        needsCamera = true,
        needsLlm = true,
        needsOcr = false
    ),
    GRADE(
        labelRes = R.string.chat_tool_grade,
        icon = Icons.Default.TaskAlt,
        instruction = ChatToolPrompts.GRADE,
        // 与作文批改同款的两段式拍摄：先题目，再手写答案。
        needsCamera = true,
        needsLlm = true,
        // 发送路径会对照片跑 OCR（纯文本模型也能批），识别没配就是真的用不了
        needsOcr = true
    ),
    DIAGRAM(
        labelRes = R.string.chat_tool_diagram,
        icon = Icons.Default.Insights,
        instruction = ChatToolPrompts.DIAGRAM,
        needsCamera = false,
        needsLlm = true,
        needsOcr = false
    ),

    /**
     * 函数画图（v0.0.144）：六个入口里**唯一不是预填提示词**的一个。
     *
     * 其余五个都是「把用户送到那条对话里，并替他把第一句话写好」；函数图像
     * 却是**本机算出来的**：填表达式 → 直接出图，不经过模型、不需要任何 Key。
     * 所以它的 [instruction] 是空的，[needsLlm] 也是 false——没配模型时它照样
     * 能用（这正是手动入口存在的意义：模型限流或没配 Key 时，图照样画得出来）。
     */
    PLOT(
        labelRes = R.string.chat_tool_plot,
        icon = Icons.Default.ShowChart,
        instruction = "",
        needsCamera = false,
        needsLlm = false,
        needsOcr = false,
        manual = true
    ),
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

/**
 * 一行行的功能入口（v0.0.96 改为抽屉布局）：图标 + 文字，右侧标可用状态。
 *
 * 抽屉是竖向空间，行比九宫格读得快——九宫格版本用了一天就换掉了。
 * 不可用的入口**仍可点击**：点进去会给出具体缺什么，而不是把按钮锁死
 * 让用户猜。状态只在不可用时显示，六个「可用」会稀释成噪音。
 */
@Composable
internal fun ChatToolRows(
    llmConfigured: Boolean,
    ocrConfigured: Boolean,
    onPick: (ChatTool) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        ChatTool.entries.forEach { tool ->
            val block = tool.blockReason(llmConfigured, ocrConfigured)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = { onPick(tool) })
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = tool.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = stringResource(tool.labelRes),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                if (block != ChatToolBlock.READY) {
                    Text(
                        text = stringResource(
                            when (block) {
                                ChatToolBlock.NEEDS_LLM -> R.string.chat_tool_needs_llm
                                else -> R.string.chat_tool_needs_ocr
                            }
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
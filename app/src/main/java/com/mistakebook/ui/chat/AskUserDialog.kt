package com.mistakebook.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mistakebook.R
import com.mistakebook.data.chat.ChatAskBridge
import kotlinx.coroutines.delay

/**
 * 模型反问的选项卡片（v0.0.128，issue #46）。
 *
 * 用户给的是电脑端 ask user question 的截图，四条机制都照做：
 * - **单选 / 多选**：`multiSelect` 决定 RadioButton 还是 Checkbox；
 * - **推荐项**：带「推荐」角标，文案后面顺手说明为什么推荐；
 * - **默认预选**：默认选中的项，用户不点也能直接确认；
 * - **超时跳过**：卡片顶部有倒计时，到点自动按「跳过」处理，
 *   模型拿到「用户未回答」后自行继续（服务侧也有兜底超时）。
 *
 * 为什么是 AlertDialog 而不是气泡：卡片必须压在最上层、且在输入框之上，
 * 否则用户在滚动列表时根本看不到「它在等你」。回答完卡片即消失。
 */
@Composable
internal fun AskUserDialog(
    pending: ChatAskBridge.Pending,
    onAnswer: (askId: String, indexes: Set<Int>, skipped: Boolean) -> Unit
) {
    // 换一道题就重置选择（askId 作 key）。
    var selected by remember(pending.askId) {
        mutableStateOf(pending.defaultIndexes)
    }
    var remainingSec by remember(pending.askId) {
        mutableIntStateOf((pending.timeoutMs / 1000L).toInt())
    }
    // 倒计时：每秒减一，到 0 自动跳过。服务侧还有 withTimeoutOrNull 兜底，
    // 两处都写是因为界面可能被销毁（进程内桥还在等）。
    LaunchedEffect(pending.askId) {
        while (remainingSec > 0) {
            delay(1000)
            remainingSec -= 1
        }
        onAnswer(pending.askId, emptySet(), true)
    }

    AlertDialog(
        onDismissRequest = { onAnswer(pending.askId, emptySet(), true) },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.chat_ask_title),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(R.string.chat_ask_countdown, remainingSec),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column {
                Text(
                    text = pending.question,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(10.dp))
                pending.options.forEach { option ->
                    val checked = option.index in selected
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (checked) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clickable {
                                selected = if (pending.multiSelect) {
                                    if (checked) selected - option.index else selected + option.index
                                } else {
                                    setOf(option.index)
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (pending.multiSelect) {
                                Checkbox(checked = checked, onCheckedChange = null)
                            } else {
                                RadioButton(selected = checked, onClick = null)
                            }
                            Spacer(Modifier.width(6.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = option.label,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    if (option.index == pending.recommendedIndex) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = stringResource(R.string.chat_ask_recommended),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .background(
                                                    MaterialTheme.colorScheme.primaryContainer,
                                                    RoundedCornerShape(4.dp)
                                                )
                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                                option.description?.let { desc ->
                                    Text(
                                        text = desc,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onAnswer(pending.askId, selected, false) },
                enabled = selected.isNotEmpty()
            ) {
                Text(stringResource(R.string.chat_ask_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = { onAnswer(pending.askId, emptySet(), true) }) {
                Text(stringResource(R.string.chat_ask_skip))
            }
        }
    )
}
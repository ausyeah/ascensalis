package com.mistakebook.ui.common

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 不可恢复操作的两步确认按钮。
 *
 * ## 为什么要有
 *
 * 用户原话：「清零重算等标红的重点的不可恢复的删除行为都要点击两次确定哦，
 * 就是原来按键变全红，展示确认删除或者确认清理这种，再点一次才删除清空，
 * 防止误触」。
 *
 * 之前的两种保护都不够：
 * - **零保护**（清零重算 / 清空回收站）：点一下整库数据就没，误触即事故；
 * - **AlertDialog**：多一次弹窗，但确认键依然是一击即执行，
 *   而且「不可恢复」的警告只在弹窗里出现过一次。
 *
 * 两步确认把保险丝做在按钮本身：
 * 1. 第一击：按钮**整体变全红**（error 容器 + onError 文字），
 *    文字换成「确认清零 / 确认清空 / 确认删除」这类明确指令；
 * 2. 第二击（红态）：才真正执行。
 *
 * ## 防呆：确认态自动收起
 *
 * 红态停留 [autoResetMillis] 无操作就自己退回常态。用户点完第一下
 * 走开、回来手一抖点到的是常态按钮，不会把数据删了。
 *
 * @param text 常态文字（如「清零重算」）
 * @param confirmText 确认态文字（如「确认清零」）
 * @param style 按钮形态：设置页用 [DangerButtonStyle.Outlined]，
 *   对话框里的确认键用 [DangerButtonStyle.Text]
 * @param icon 常态与确认态共用的前置图标；null 则不加
 */
enum class DangerButtonStyle { Outlined, Text }

@Composable
fun TwoStepConfirmButton(
    text: String,
    confirmText: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    style: DangerButtonStyle = DangerButtonStyle.Outlined,
    icon: ImageVector? = null,
    autoResetMillis: Long = 4_000
) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(autoResetMillis)
            armed = false
        }
    }

    val label = if (armed) confirmText else text
    val onClick = {
        if (armed) {
            armed = false
            onConfirm()
        } else {
            armed = true
        }
    }

    when (style) {
        DangerButtonStyle.Outlined -> OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
                containerColor = if (armed) MaterialTheme.colorScheme.error
                else androidx.compose.ui.graphics.Color.Transparent
            )
        ) {
            // 红态下文字/图标用 onError，与 error 容器对比度达标
            val contentColor =
                if (armed) MaterialTheme.colorScheme.onError
                else MaterialTheme.colorScheme.error
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
            }
            Text(label, color = contentColor, maxLines = 1)
        }

        DangerButtonStyle.Text -> TextButton(
            onClick = onClick,
            modifier = modifier,
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
                containerColor = if (armed) MaterialTheme.colorScheme.error
                else androidx.compose.ui.graphics.Color.Transparent
            )
        ) {
            val contentColor =
                if (armed) MaterialTheme.colorScheme.onError
                else MaterialTheme.colorScheme.error
            Text(label, color = contentColor, maxLines = 1)
        }
    }
}

/** 对话框内确认键的两步确认（[TwoStepConfirmButton] 的 Text 形态简写）。 */
@Composable
fun TwoStepConfirmTextButton(
    text: String,
    confirmText: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier
) {
    TwoStepConfirmButton(
        text = text,
        confirmText = confirmText,
        onConfirm = onConfirm,
        modifier = modifier,
        style = DangerButtonStyle.Text
    )
}

/** 实底两步确认按钮（Red filled），暂留作未来设置页主操作位使用。 */
@Composable
fun TwoStepConfirmFilledButton(
    text: String,
    confirmText: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null
) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(4_000)
            armed = false
        }
    }
    Button(
        onClick = {
            if (armed) {
                armed = false
                onConfirm()
            } else {
                armed = true
            }
        },
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (armed) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.secondaryContainer,
            contentColor = if (armed) MaterialTheme.colorScheme.onError
            else MaterialTheme.colorScheme.onSecondaryContainer
        )
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.size(8.dp))
        }
        Text(if (armed) confirmText else text, maxLines = 1)
    }
}

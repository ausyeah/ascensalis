package com.mistakebook.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.view.WindowManager
import androidx.compose.ui.platform.LocalView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Note
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import android.content.ClipData
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import com.mistakebook.R
import com.mistakebook.data.chat.ChatContextAssembler
import com.mistakebook.data.chat.ChatSearchProvider
import com.mistakebook.data.local.entities.ChatMessage
import com.mistakebook.data.prefs.ReasoningEffort
import com.mistakebook.di.AppContainer
import com.mistakebook.domain.ChatRole
import com.mistakebook.domain.MessageStatus
import com.mistakebook.ui.common.ApiKeyRequiredDialog
import com.mistakebook.ui.common.RichText
import com.mistakebook.ui.common.rememberStreamTextDebounced
import com.mistakebook.ui.common.containerViewModel
import kotlinx.coroutines.Dispatchers
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.ExpandLess
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File
import android.net.Uri
import android.widget.Toast

/**
 * 会话相册一次最多选几张（v0.0.52 多选）。
 *
 * 取 9 与旧 CHAT 连拍上限一致：附件要逐张压缩/转 data URL，
 * 一次十几张既慢也没意义，真需要更多分几次选。
 */
private const val CHAT_GALLERY_MAX = 9

/** 选文档。PDF 单独列出，因为部分文件管理应用不会把 .pdf 归到 text 类型。 */

@Composable
internal fun ChatInputBar(
    text: String,
    streaming: Boolean,
    attachments: List<PendingAttachment>,
    modelName: String,
    /** T6 降级：待注入的资料库文件名与文件列表（chips 移除用）。 */
    libraryInjectionNames: List<String> = emptyList(),
    libraryFiles: List<java.io.File> = emptyList(),
    onRemoveLibraryInjection: (java.io.File) -> Unit = {},
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    onPickModel: () -> Unit,
    onRemoveAttachment: (Long) -> Unit,
    /** 批改模式开闭（T13）。 */
    gradingMode: Boolean = false,
    /** 是否展示批改开关：仅题目会话。 */
    gradingVisible: Boolean = false,
    onToggleGrading: () -> Unit = {}
) {
    // 输入栏与画布同色、零阴影，层次靠顶部 1dp 描边（与门户顶栏同一手法）：
    // 键盘弹出/收起时表面不再明暗跳变，只有一条静默的界线。
    val inputBarBorder = MaterialTheme.colorScheme.outlineVariant
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.drawBehind {
            drawRect(
                color = inputBarBorder,
                topLeft = Offset(0f, 0f),
                size = Size(size.width, 1.dp.toPx())
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 键盘弹出时输入栏坐到键盘上；键盘收起时贴齐手势导航条。
                // 用 union 而不是两个 padding 叠加：ime 的底边距本身就
                // 含导航栏区域，再叠一层 navigationBarsPadding 会让输入栏
                // 浮在键盘上方一条导航栏的高度。
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (libraryInjectionNames.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    libraryInjectionNames.forEach { name ->
                        LibraryInjectionChip(
                            fileName = name,
                            onRemove = {
                                libraryFiles.firstOrNull { it.name == name }
                                    ?.let(onRemoveLibraryInjection)
                            }
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            if (attachments.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    attachments.forEach { attachment ->
                        PendingAttachmentChip(attachment = attachment, onRemove = { onRemoveAttachment(attachment.id) })
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            // 上段：输入区。去掉了描边——两段式布局里容器本身就是边界，
            // 再画一圈边框就是双重框，又挤又旧。
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        stringResource(
                            if (gradingMode) R.string.chat_input_hint_grading
                            else R.string.chat_input_hint
                        )
                    )
                },
                maxLines = 5,
                shape = MaterialTheme.shapes.large,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    disabledBorderColor = Color.Transparent,
                    errorBorderColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                )
            )

            // 下段：操作条
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 附件在最左（用户点名：最左边是 +，右边那个加号不要了）
                // v0.0.58：生成中也能加附件（用户反馈「可以打字但不能添加图片」）。
                // 附件只是排队成 pendingAttachments，等这一轮结束后随下一条消息发出，
                // 与「打字」完全同权——没有理由只禁这一项。
                IconButton(onClick = onAttach, modifier = Modifier.size(44.dp)) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = stringResource(R.string.chat_attach)
                    )
                }

                Spacer(Modifier.width(6.dp))

                // 批改开关（T13）：选中态高亮 + contentDescription 说明。
                // 只对题目会话显示——自由会话没有题干/答案可对照，批改无从谈起。
                if (gradingVisible) {
                    val gradingTint =
                        if (gradingMode) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    IconButton(
                        onClick = onToggleGrading,
                        enabled = !streaming,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            Icons.Filled.RateReview,
                            contentDescription = stringResource(R.string.chat_grading_toggle),
                            tint = gradingTint
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                }

                // 模型选择入口：圆形图标 + 当前模型名 + 下拉箭头。
                // 一个 provider 常供应几十个模型，点这里弹出完整列表。
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(MaterialTheme.shapes.large)
                        .clickable(enabled = !streaming, onClick = onPickModel)
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = modelName.firstOrNull()?.uppercase() ?: "?",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = modelName.ifBlank { stringResource(R.string.chat_model_unset) },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Icon(
                        Icons.Default.ExpandMore,
                        contentDescription = stringResource(R.string.chat_pick_model),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                FilledIconButton(
                    onClick = { if (streaming) onStop() else onSend() },
                    enabled = streaming ||
                        text.isNotBlank() ||
                        attachments.any { it.status == com.mistakebook.domain.AttachmentStatus.READY },
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = if (streaming) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(if (streaming) R.string.chat_stop else R.string.chat_send)
                    )
                }
            }
        }
    }
}


@Composable
internal fun PendingAttachmentChip(
    attachment: PendingAttachment,
    onRemove: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = if (attachment.status == com.mistakebook.domain.AttachmentStatus.FAILED) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
        ) {
            if (attachment.status == com.mistakebook.domain.AttachmentStatus.PREPARING) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.chat_attachment_preparing),
                    style = MaterialTheme.typography.labelSmall
                )
            } else {
                Icon(
                    imageVector = when (attachment.kind) {
                        com.mistakebook.domain.AttachmentKind.IMAGE -> Icons.Default.Image
                        com.mistakebook.domain.AttachmentKind.PDF -> Icons.Default.PictureAsPdf
                        else -> Icons.Default.Description
                    },
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
                Column {
                    Text(
                        text = attachment.fileName.ifBlank { stringResource(R.string.chat_attach_file) },
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 130.dp)
                    )
                    val subtitle = when (attachment.status) {
                        com.mistakebook.domain.AttachmentStatus.FAILED ->
                            attachment.errorMessage ?: stringResource(R.string.chat_attachment_failed)

                        else -> when (attachment.kind) {
                            // 资料库文件不走「提取字数」口径：原文由服务整段注入上下文。
                            com.mistakebook.domain.AttachmentKind.LIBRARY ->
                                stringResource(R.string.chat_attachment_library)

                            else -> if (attachment.extractedChars > 0) {
                                stringResource(R.string.chat_attachment_extracted, attachment.extractedChars)
                            } else {
                                com.mistakebook.ui.common.Format.fileSize(attachment.sizeBytes)
                            }
                        }
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (attachment.status == com.mistakebook.domain.AttachmentStatus.FAILED) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 130.dp)
                    )
                }
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(24.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.chat_attachment_remove),
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

/** 字节数 -> 人类可读。 */

package com.mistakebook.ui.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.mistakebook.R
import com.mistakebook.pipeline.TextAudit
import com.mistakebook.ui.theme.DeleteReveal
import com.mistakebook.ui.theme.WarningAmber

/**
 * 文本体检结果面板。
 *
 * ## 交互原则：**先看，再决定**
 *
 * 任何修正都以「原文 → 改后」的形式摆在用户面前，由他点「修复」。
 * 不做「一键全部静默改掉」——那是替用户做数学判断，越权。
 * 同样也不做「只报问题不给修法」——那等于把活推回给用户。
 *
 * ## 为什么必须能撤销
 *
 * 体检是启发式的，它可能把一段**本来就该那样**的公式判成有问题。
 * 万一改错，没有撤销就等于逼用户手工逐字改回来——
 * 那这个功能就是负收益，不如不做。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditSheet(
    issues: List<TextAudit.Issue>,
    onDismiss: () -> Unit,
    onApply: (List<TextAudit.Issue>) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val fixable = issues.filter { it.autoFixable }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = if (issues.isEmpty()) stringResource(R.string.edit_audit_title) else stringResource(R.string.edit_audit_issue_count_format, issues.size),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (issues.isEmpty()) {
                    stringResource(R.string.edit_audit_empty_description)
                } else {
                    stringResource(R.string.edit_audit_summary_format, fixable.size, issues.size - fixable.size)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            if (issues.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.edit_audit_no_issues), style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(issues) { issue -> AuditIssueRow(issue) }
                }
                if (fixable.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = { onApply(fixable) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.edit_audit_apply_fixes_format, fixable.size))
                    }
                }
            }
        }
    }
}

@Composable
private fun AuditIssueRow(issue: TextAudit.Issue) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = if (issue.kind == TextAudit.Kind.BROKEN) DeleteReveal else WarningAmber,
                            shape = CircleShape
                        )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = issue.field.label +
                        if (issue.optionIndex >= 0) " ${issue.optionIndex + 1}" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = if (issue.kind == TextAudit.Kind.BROKEN) {
                        Icons.Default.ErrorOutline
                    } else {
                        Icons.Default.WarningAmber
                    },
                    contentDescription = null,
                    tint = if (issue.kind == TextAudit.Kind.BROKEN) DeleteReveal else WarningAmber,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(issue.summary, style = MaterialTheme.typography.bodyMedium)
            if (issue.note.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = issue.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (issue.autoFixable) {
                Spacer(Modifier.height(8.dp))
                DiffView(before = issue.original, after = issue.suggestion!!)
            }
        }
    }
}

/** 「原来 → 改成」对照。字体用等宽，LaTeX 里空格有意义。 */
@Composable
private fun DiffView(before: String, after: String) {
    Column {
        Text(
            text = stringResource(R.string.edit_audit_current_format, before),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = DeleteReveal
        )
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.edit_audit_replacement_format, after),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

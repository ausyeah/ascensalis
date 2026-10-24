package com.mistakebook.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mistakebook.R
import com.mistakebook.data.local.figurePaths
import com.mistakebook.data.local.options
import com.mistakebook.di.AppContainer
import com.mistakebook.domain.ReviewResult
import com.mistakebook.ui.common.QuestionThumb
import com.mistakebook.ui.common.RichText
import com.mistakebook.ui.common.containerViewModel
import com.mistakebook.ui.theme.Danger
import com.mistakebook.ui.theme.PaperLv1
import com.mistakebook.ui.theme.SuccessGreen

/**
 * 独立复习做题页（T1 复习闭环的前端半边）。
 *
 * 页面形态是「回忆自测」而非做题判分：题目与选项展示出来，答案默认被
 * 浅色遮罩盖住，用户回忆后手动展开，再按 会/模糊/不会 三键自评。
 * 三键在答案展开前不可点（半透明）——没看答案就评分是无效数据。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onGoNotebook: () -> Unit
) {
    val viewModel: ReviewViewModel = containerViewModel(container) { ReviewViewModel(it) }
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.review_screen_title))
                        if (!state.finished && state.queue.isNotEmpty()) {
                            Text(
                                text = stringResource(
                                    R.string.review_progress_format,
                                    state.index + 1,
                                    state.queue.size
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            when {
                // 空队列：今天没有到期题
                state.isEmpty -> EmptyReviewState(onGoNotebook = onGoNotebook)

                // 队列走完：结算页
                state.finished -> ReviewFinishState(
                    state = state,
                    onDone = onBack,
                    onRetry = viewModel::restartUnmastered,
                    onContinue = viewModel::continueMore
                )

                // 过题
                else -> ReviewQuestionBody(
                    state = state,
                    mathRenderer = container.mathRenderer,
                    onReveal = viewModel::reveal,
                    onRate = viewModel::rate
                )
            }
        }
    }
}

/** 过题主体：题干卡 + 附图 + 选项 + 答案遮罩 + 三键自评条。 */
@Composable
private fun ReviewQuestionBody(
    state: ReviewUiState,
    mathRenderer: com.mistakebook.math.MathRenderer,
    onReveal: () -> Unit,
    onRate: (ReviewResult) -> Unit
) {
    val question = state.queue[state.index]
    val options = remember(question.id) { question.options }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    RichText(
                        text = question.stem,
                        mathRenderer = mathRenderer,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    // 附图：与详情页一致，横向缩略图可点开看大图
                    val figures = remember(question.id) { question.figurePaths }
                    if (figures.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(figures) { ref ->
                                QuestionThumb(
                                    imagePath = ref,
                                    contentDescription = stringResource(R.string.edit_figures),
                                    modifier = Modifier
                                        .height(120.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                )
                            }
                        }
                    }
                    // 选项只展示不可点：本页是回忆自测，不是做题判分
                    if (options.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        options.forEach { option ->
                            RichText(
                                text = "${option.label}. ${option.text}",
                                mathRenderer = mathRenderer,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }

        // 答案区：未展开时盖浅色遮罩 + 居中「显示答案」按钮
        item {
            if (state.revealed) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        if (question.answer.isNotBlank()) {
                            Text(
                                text = stringResource(R.string.review_answer_label),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(4.dp))
                            RichText(
                                text = question.answer,
                                mathRenderer = mathRenderer,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        if (question.analysis.isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.review_analysis_label),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(4.dp))
                            RichText(
                                text = question.analysis,
                                mathRenderer = mathRenderer,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            } else {
                // 遮罩：浅色块 + 居中按钮。不透亮背景，防止答案从视觉缝隙里「透」出来
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Button(
                            onClick = onReveal,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text(
                                stringResource(R.string.review_show_answer),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // 三键自评条：必须先显示答案才可评
        item {
            RateBar(
                enabled = state.revealed,
                onRate = onRate
            )
            if (!state.revealed) {
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.review_rate_locked_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** 三键自评：会（绿）/ 模糊（橙）/ 不会（红），观感对齐背单词页 RateButton。 */
@Composable
private fun RateBar(enabled: Boolean, onRate: (ReviewResult) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ReviewRateButton(
            title = stringResource(R.string.review_rate_correct),
            color = SuccessGreen,
            enabled = enabled,
            modifier = Modifier.weight(1f),
            onClick = { onRate(ReviewResult.CORRECT) }
        )
        ReviewRateButton(
            title = stringResource(R.string.review_rate_vague),
            color = PaperLv1,
            enabled = enabled,
            modifier = Modifier.weight(1f),
            onClick = { onRate(ReviewResult.VAGUE) }
        )
        ReviewRateButton(
            title = stringResource(R.string.review_rate_wrong),
            color = Danger,
            enabled = enabled,
            modifier = Modifier.weight(1f),
            onClick = { onRate(ReviewResult.WRONG) }
        )
    }
}

@Composable
private fun ReviewRateButton(
    title: String,
    color: androidx.compose.ui.graphics.Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(46.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            disabledContainerColor = color.copy(alpha = 0.35f)
        )
    ) {
        Text(title, color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold)
    }
}

/** 空状态：今天没有到期题。 */
@Composable
private fun EmptyReviewState(onGoNotebook: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.review_empty_title),
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onGoNotebook) {
            Text(stringResource(R.string.review_go_notebook))
        }
    }
}

/**
 * 结算页。
 *
 * 「立即重刷未掌握」的数据源在 ViewModel 里是内存快照（评了模糊/不会的 id），
 * 不能重查 dueQuestions——那些题的 nextReviewAt 已推到明天，重查必空。
 */
@Composable
private fun ReviewFinishState(
    state: ReviewUiState,
    onDone: () -> Unit,
    onRetry: () -> Unit,
    onContinue: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(
                        R.string.review_finish_title,
                        state.correctCount,
                        state.vagueCount,
                        state.wrongCount
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.review_finish_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        // 队列上限（200）没轮完时：主按钮变成「继续复习」
        val moreDue = state.remainingDueCount > 0
        Button(
            onClick = if (moreDue) onContinue else onDone,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (moreDue) {
                    stringResource(R.string.review_finish_continue, state.remainingDueCount)
                } else {
                    stringResource(R.string.review_finish_done)
                }
            )
        }
        if (state.retryIds.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.review_finish_retry))
            }
        }
    }
}

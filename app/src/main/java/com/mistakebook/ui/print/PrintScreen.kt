package com.mistakebook.ui.print

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.mistakebook.R
import com.mistakebook.di.AppContainer
import com.mistakebook.ui.common.containerViewModel

/**
 * 打印页（PRD 7.7）：筛选 -> 勾选 -> 打印选项 -> 生成 PDF -> 分享。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintScreen(container: AppContainer, onBack: () -> Unit) {
    val viewModel: PrintViewModel = containerViewModel(container) { PrintViewModel(it) }
    val state by viewModel.uiState.collectAsState()
    val formatName = stringResource(state.format.shortLabelRes)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.print_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                }
            )
        },
        bottomBar = {
            PrintBottomBar(
                generating = state.generating,
                selectedCount = state.selected.size,
                formatName = formatName,
                onGenerate = viewModel::generate
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            PrintFilters(
                state = state,
                onSubjectChange = viewModel::setSubject,
                onStatusChange = viewModel::setStatus,
                onKeywordChange = viewModel::setKeyword,
                onSetAll = { checked ->
                    val ids = state.questions.map { it.id }
                    if (checked) viewModel.selectAll(ids) else viewModel.deselectAll(ids)
                },
                onInvert = { viewModel.invertSelection(state.questions.map { it.id }) }
            )

            PrintOptionsPanel(
                includeImage = state.includeImage,
                showAnswer = state.showAnswer,
                blankRedo = state.blankRedo,
                blankHeight = state.blankHeight,
                format = state.format,
                onIncludeImage = viewModel::setIncludeImage,
                onShowAnswer = viewModel::setShowAnswer,
                onBlankRedo = viewModel::setBlankRedo,
                onBlankHeight = viewModel::setBlankHeight,
                onFormatChange = viewModel::setFormat,
                fontScale = state.fontScale,
                fontFamily = state.fontFamily,
                onFontScaleChange = viewModel::setFontScale,
                onFontFamilyChange = viewModel::setFontFamily
            )

            PrintQuestionList(
                questions = state.questions,
                selectedIds = state.selected,
                subjectNames = state.subjectNames,
                onToggleSelect = viewModel::toggleSelect,
                onReorderFinished = viewModel::setQuestionOrder
            )
        }
    }

    state.result?.let { output ->
        PrintResultDialog(
            result = output,
            formatName = formatName,
            skipped = state.skipped,
            onDismiss = viewModel::consumeResult
        )
    }

    state.error?.let { errorMessage ->
        PrintErrorDialog(
            error = errorMessage,
            onDismiss = viewModel::consumeError
        )
    }
}

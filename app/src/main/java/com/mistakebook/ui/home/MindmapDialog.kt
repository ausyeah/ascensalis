package com.mistakebook.ui.home

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mistakebook.R
import com.mistakebook.data.local.entities.Subject
import com.mistakebook.di.AppContainer
import com.mistakebook.mindmap.MindmapNode
import com.mistakebook.ui.common.containerViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 思维导图弹层（F4）：选学科 → AI 生成知识体系 → 本地 markmap 离线渲染。
 *
 * 薄弱知识点带 🔴/🟠 标记与出错题数，一眼看出体系里的薄弱分支。
 * 不落库：导图是"随错题数据生长"的视图，每次按最新薄弱点现生成，
 * 存下来反而会过时。
 */
@Composable
fun MindmapDialog(
    container: AppContainer,
    onDismiss: () -> Unit
) {
    val viewModel: MindmapViewModel = containerViewModel(container) { MindmapViewModel(it) }
    val state by viewModel.uiState.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mindmap_title)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 480.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(state.subjects, key = { it.id ?: -it.name.hashCode() }) { subject ->
                        FilterChip(
                            selected = subject.id == state.selectedSubjectId,
                            onClick = { viewModel.select(subject) },
                            label = { Text(subject.name) }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                when {
                    state.generating -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.mindmap_generating))
                    }

                    state.error != null -> Column {
                        Text(
                            text = state.error.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { viewModel.generate() }) {
                            Text(stringResource(R.string.mindmap_retry))
                        }
                    }

                    state.root == null -> Text(
                        text = stringResource(R.string.mindmap_empty_hint),
                        style = MaterialTheme.typography.bodyMedium
                    )

                    else -> MindmapWebView(root = state.root!!)
                }
            }
        },
        confirmButton = {
            if (!state.generating) {
                Button(onClick = { viewModel.generate() }) {
                    Text(
                        if (state.root == null) stringResource(R.string.mindmap_generate)
                        else stringResource(R.string.mindmap_regenerate)
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        }
    )
}

/**
 * 本地 assets 里的 markmap 页面离线渲染（d3 + markmap-view 都在 assets，无 CDN）。
 * 树以 JSON 注入占位符——解析在 Kotlin（可单测），WebView 保持哑渲染。
 */
@Composable
private fun MindmapWebView(root: MindmapNode) {
    val json = remember { Json { encodeDefaults = true } }
    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .height(360.dp),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                webViewClient = WebViewClient()
            }
        },
        update = { webView ->
            val html = runCatching {
                contextAsset(webView, "markmap/mindmap.html")
                    .replace("__TREE_JSON__", json.encodeToString(root))
            }.getOrNull() ?: return@AndroidView
            // base URL 指向 assets 目录，页面里的相对路径 ./d3.min.js 才能解析
            webView.loadDataWithBaseURL(
                "file:///android_asset/markmap/", html, "text/html", "utf-8", null
            )
        }
    )
}

private fun contextAsset(webView: WebView, path: String): String =
    webView.context.assets.open(path).bufferedReader().use { it.readText() }

data class MindmapUiState(
    val subjects: List<Subject> = emptyList(),
    val selectedSubjectId: Long? = null,
    val selectedSubjectName: String = "",
    val generating: Boolean = false,
    val error: String? = null,
    val root: MindmapNode? = null
)

class MindmapViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(MindmapUiState())
    val uiState: StateFlow<MindmapUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            container.subjectRepository.observeAll().collect { subjects ->
                val current = _uiState.value
                _uiState.value = current.copy(
                    subjects = subjects,
                    // 学科列表加载后默认选中第一个；已手动选过的不动
                    selectedSubjectId = current.selectedSubjectId ?: subjects.firstOrNull()?.id,
                    selectedSubjectName = current.selectedSubjectName.ifEmpty {
                        subjects.firstOrNull()?.name.orEmpty()
                    }
                )
            }
        }
    }

    fun select(subject: Subject) {
        _uiState.value = _uiState.value.copy(
            selectedSubjectId = subject.id,
            selectedSubjectName = subject.name,
            root = null,
            error = null
        )
    }

    fun generate() {
        val state = _uiState.value
        if (state.generating || state.selectedSubjectName.isBlank()) return
        viewModelScope.launch {
            _uiState.value = state.copy(generating = true, error = null)
            when (
                val result = container.mindmapGenerator.generate(
                    subjectId = state.selectedSubjectId,
                    subjectName = state.selectedSubjectName
                )
            ) {
                is com.mistakebook.mindmap.MindmapGenerator.Result.Done -> {
                    _uiState.value = _uiState.value.copy(
                        generating = false,
                        root = result.root
                    )
                }
                is com.mistakebook.mindmap.MindmapGenerator.Result.Failed -> {
                    _uiState.value = _uiState.value.copy(generating = false, error = result.reason)
                }
            }
        }
    }
}

package com.mistakebook.wordbook.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mistakebook.di.AppContainer
import com.mistakebook.data.prefs.SettingsStore
import com.mistakebook.ui.common.containerViewModel
import com.mistakebook.ui.theme.LocalDarkTheme
import com.mistakebook.ui.theme.PaperBorderLight
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Share
import com.mistakebook.ui.theme.PaperLv0
import com.mistakebook.ui.theme.PaperLv1
import com.mistakebook.ui.theme.PaperLv2
import com.mistakebook.ui.theme.PaperLv3
import com.mistakebook.ui.theme.PaperOk
import com.mistakebook.ui.theme.PaperOkDark
import com.mistakebook.ui.theme.PaperPrimary
import com.mistakebook.ui.theme.PaperPrimaryTint
import com.mistakebook.wordbook.data.Word
import com.mistakebook.wordbook.data.WordProgress
import com.mistakebook.wordbook.algo.WordbookRules
import androidx.compose.ui.res.stringResource
import com.mistakebook.R

@Composable
internal fun WrongBookView(
    state: WordbookUiState,
    viewModel: WordbookViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // 四级子标签。数量角标（用户反馈）会随记词进度变化宽度，把整行
        // chip 挤得左右挪位——已去掉；四个 chip **等宽均分**整行且去掉
        // 横向滚动，位置在任何状态下都纹丝不动。「曾错毕业本」改名
        // 「曾错本」（用户口径）。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = state.wrongSubTab == WrongSubTab.WRONG_BOOK,
                onClick = { viewModel.setWrongSubTab(WrongSubTab.WRONG_BOOK) },
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.wordbook_quiz_tab_wrong), maxLines = 1) }
            )
            FilterChip(
                selected = state.wrongSubTab == WrongSubTab.EVER_WRONG,
                onClick = { viewModel.setWrongSubTab(WrongSubTab.EVER_WRONG) },
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.wordbook_quiz_tab_ever), maxLines = 1) }
            )
            FilterChip(
                selected = state.wrongSubTab == WrongSubTab.MASTERED,
                onClick = { viewModel.setWrongSubTab(WrongSubTab.MASTERED) },
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.wordbook_quiz_tab_familiar), maxLines = 1) }
            )
            FilterChip(
                selected = state.wrongSubTab == WrongSubTab.FAVORITE,
                onClick = { viewModel.setWrongSubTab(WrongSubTab.FAVORITE) },
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.wordbook_quiz_tab_new), maxLines = 1) }
            )
        }

        if (state.wrongBookList.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when (state.wrongSubTab) {
                        WrongSubTab.WRONG_BOOK -> "错词本干干净净，暂无待复习错词 🎉"
                        WrongSubTab.EVER_WRONG -> "暂无曾错毕业词汇"
                        WrongSubTab.MASTERED -> "暂无手动标记的熟词"
                        WrongSubTab.FAVORITE -> "生词本空着——刷题或词库检索时点书签图标即可收藏"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.wrongBookList, key = { it.first.word }) { (word, progress) ->
                    WordItemCard(
                        word = word,
                        progress = progress,
                        showRemove = state.wrongSubTab == WrongSubTab.WRONG_BOOK,
                        onRemove = { viewModel.removeFromWrongBook(word.word) },
                        onSpeak = { viewModel.speakWord(word.word) },
                        onToggleFavorite = { viewModel.toggleFavorite(word.word) },
                        scenePath = viewModel.scenePathFor(word.word, word.meaning)
                    )
                }
            }
        }
    }
}

// =========================================================================
// 3. 词库检索 (Library View)
// =========================================================================


@Composable
internal fun LibraryView(
    state: WordbookUiState,
    viewModel: WordbookViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        OutlinedTextField(
            value = state.libraryKeyword,
            onValueChange = viewModel::setLibraryKeyword,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.wordbook_library_search_hint)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface
            )
        )

        Spacer(Modifier.height(8.dp))

        // 等级筛选 Chip
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                null to "全部",
                0 to "生词",
                1 to "模糊",
                2 to "认识",
                3 to "掌握"
            ).forEach { (lv, label) ->
                FilterChip(
                    selected = state.libraryFilterLevel == lv,
                    onClick = { viewModel.setLibraryFilterLevel(lv) },
                    label = { Text(label) }
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(state.libraryWords, key = { it.first.word }) { (word, progress) ->
                WordItemCard(
                    word = word,
                    progress = progress ?: WordProgress(word = word.word),
                    showRemove = false,
                    onRemove = {},
                    onSpeak = { viewModel.speakWord(word.word) },
                    onToggleFavorite = { viewModel.toggleFavorite(word.word) }
                )
            }
        }
    }
}

// =========================================================================
// 4. 统计看板 (Stats View)
// =========================================================================


@Composable
internal fun WordItemCard(
    word: Word,
    progress: WordProgress,
    showRemove: Boolean,
    onRemove: () -> Unit,
    onSpeak: () -> Unit = {},
    onToggleFavorite: () -> Unit = {},
    /**
     * 场景图缩略图路径（已生成过才有值）。
     *
     * 错词本列表用它做**视觉锚点**：背单词时那张图比文字更容易被记起来，
     * 列表里露出 40dp 小图，扫一眼就能把词和场景对上。
     */
    scenePath: String? = null
) {
    val levelColor = when (progress.level) {
        1 -> PaperLv1
        2 -> PaperLv2
        3 -> PaperLv3
        else -> PaperLv0
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.outline))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 掌握度竖条
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(36.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(levelColor)
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = word.word,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Serif
                    )
                    if (progress.isMastered) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Default.Star, contentDescription = null, tint = PaperLv1, modifier = Modifier.size(14.dp))
                    }
                    if (progress.isWrongBook) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            // 毕业线抽成了 WordbookRules.GRADUATION_REPS：
                            // UI 文案与打分逻辑共用同一个常量，不会出现
                            // 「显示 x/2 实际要对 3 次」的割裂。
                            text = stringResource(R.string.wordbook_streak_reps, progress.reps, WordbookRules.GRADUATION_REPS),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = word.meaning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 场景图缩略图：出过图才占位置（没出过图的词布局不变）。
            if (scenePath != null) {
                AsyncImage(
                    model = scenePath,
                    contentDescription = stringResource(R.string.wordbook_cd_scene),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Spacer(Modifier.width(6.dp))
            }

            // 列表里的发音与收藏：单词旁的即点即读/即收藏，
            // 不用进详情页（本项目不做单词详情页）。
            IconButton(onClick = onSpeak, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = stringResource(R.string.wordbook_cd_speak),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = onToggleFavorite, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = if (progress.isFavorite) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    contentDescription = if (progress.isFavorite) "取消收藏" else "收进生词本",
                    tint = if (progress.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            if (showRemove) {
                TextButton(onClick = onRemove) {
                    Text(stringResource(R.string.wordbook_remove), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
            }
        }
    }
}


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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.platform.LocalDensity
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

/**
 * 单词场景图（F-IMG-1）：三态渲染——生成中 / 已有图 / 未生成。
 *
 * ## 为什么按钮常驻而不是藏在菜单里
 *
 * 场景图是「想看就看」的辅助，不是答题的一部分。常驻一颗按钮，
 * 出过图的词切回来直接显示（磁盘缓存命中），没出过图的给入口。
 *
 * ## 反馈纪律
 *
 * 生成中按钮转 spinner + 「生成中…」：生图是 5~15 秒的操作，
 * 没有任何反馈用户会以为按钮坏了而连点（连点会真的多烧几次额度）。
 */
@Composable
internal fun WordSceneImage(
    state: WordbookUiState,
    viewModel: WordbookViewModel
) {
    Spacer(Modifier.height(8.dp))
    if (state.sceneImageLoading) {
        OutlinedButton(
            onClick = {},
            enabled = false,
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.wordbook_scene_generating))
        }
        return
    }
    val path = state.sceneImagePath
    if (path != null) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column {
                AsyncImage(
                    model = path,
                    contentDescription = stringResource(R.string.wordbook_cd_scene),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(16.dp))
                )
                // 展示模型写的场景描述：让用户知道这张图是怎么来的，
                // 也便于判断「图不对」是 prompt 的问题还是生图模型的问题。
                if (!state.sceneImagePrompt.isNullOrBlank()) {
                    Text(
                        text = state.sceneImagePrompt,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }
        return
    }
    OutlinedButton(
        onClick = viewModel::generateSceneImage,
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Icon(
            Icons.Default.Landscape,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.wordbook_scene_chip))
    }
}


@Composable
internal fun StudyView(
    state: WordbookUiState,
    viewModel: WordbookViewModel
) {
    val isDark = LocalDarkTheme.current
    val currentWord = state.currentWord

    if (currentWord == null && !state.isLoading) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.wordbook_all_done_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.wordbook_all_done_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = viewModel::prepareNextQuestion) {
                    Text(stringResource(R.string.wordbook_extract_practice))
                }
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 每日目标进度条已从刷题页撤下：它把首屏占掉一截，而同样的信息
        // （今日已学 / 目标 / 打卡）在「学习统计」页都有完整呈现——
        // 刷题页每一寸都留给题目本身。

        // 模式切换 + 上一题操作栏（熟词/生词标记已并入卡片右上角工具位）
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 「上一题」：常驻圆形撤回按钮。
                //
                // 三个实测反馈一次解决：
                // (a) 形态：原来是带箭头的文字 TextButton，太宽。现在 36dp
                //     正圆 + 一枚撤回（Undo）图标，窄而不突兀；
                // (b) 布局：**永远占位**。过去只有存在历史时才渲染，
                //     它一出现右侧四个模式 chip 就被推着位移；现在灰态
                //     也占住同一个位置，整行布局纹丝不动；
                // (c) 状态：没有上一个词时置灰（底色与图标同时降透明度），
                //     且点击禁用——看得见「有这个东西」，也看得出「现在
                //     还不能用」。
                val hasPrev = state.hasPrevious
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            if (hasPrev) MaterialTheme.colorScheme.surfaceContainerHighest
                            else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.35f)
                        )
                        .clickable(enabled = hasPrev, onClick = viewModel::previousQuestion),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Undo,
                        contentDescription = stringResource(R.string.wordbook_cd_prev_question),
                        tint = if (hasPrev) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                        modifier = Modifier.size(18.dp)
                    )
                }

                // 四个模式 chip 等宽铺满整行剩余宽度。
                // 熟词星标已移到卡片右上角工具位（与发音/收藏并排），
                // 不再占这一行的固定右侧位——用户反馈「别占一排的固定位置」。
                //
                // 「不是均衡位置」的根因：原来用 horizontalScroll + 自然宽度，
                // 「四选一」（3 字）比「卡片」「义项」「拼写」（2 字）宽，
                // 四个 chip 挤在左边、右边留一大块空白，看起来就歪。
                // 改成 weight(1f) 等宽 + 文字居中，四个格子严丝合缝，
                // 谁也不比谁宽——切换模式时选中态的移动也更好看。
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        modifier = Modifier.weight(1f),
                        selected = state.studyMode == StudyMode.QUIZ,
                        onClick = { viewModel.setStudyMode(StudyMode.QUIZ) },
                        label = { ChipLabel("四选一") }
                    )
                    FilterChip(
                        modifier = Modifier.weight(1f),
                        selected = state.studyMode == StudyMode.CARD,
                        onClick = { viewModel.setStudyMode(StudyMode.CARD) },
                        label = { ChipLabel("卡片") }
                    )
                    FilterChip(
                        modifier = Modifier.weight(1f),
                        selected = state.studyMode == StudyMode.SENSE,
                        onClick = { viewModel.setStudyMode(StudyMode.SENSE) },
                        label = { ChipLabel("义项") }
                    )
                    FilterChip(
                        modifier = Modifier.weight(1f),
                        selected = state.studyMode == StudyMode.SPELL,
                        onClick = { viewModel.setStudyMode(StudyMode.SPELL) },
                        label = { ChipLabel("拼写") }
                    )
                }
            }
        }

        // 单词主纸质卡片
        if (currentWord != null) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    // 圆角按「外大内小」的层级：容器 28 / 卡片 16 / 卡内元素 8~12。
                    // 原来是 20，与内部 chip 的 12 太接近，两层边界糊在一起。
                    shape = RoundedCornerShape(16.dp),
                    // 不用阴影：深色下阴影几乎不可见，浅色下这点 2dp 只会让卡片
                    // 边缘发灰。层级交给容器色阶与 1dp 描边表达。
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.outline))
                ) {
                    // 发音 + 收藏固定在卡片右上角：原来单独占一行把卡片
                    // 撑高了一截，而这两个图标一年到头都长在同一个位置，
                    // 右上角是「工具位」的常识摆放，不占正文空间。
                    Box(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (state.studyMode == StudyMode.SENSE) {
                            // 义项辨析专属题干
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isDark) Color(0xFF232A46) else PaperPrimaryTint,
                                modifier = Modifier.padding(bottom = 12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.MenuBook,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = if (isDark) Color(0xFF91A7FF) else PaperPrimary
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = stringResource(R.string.wordbook_sense_quiz_title),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isDark) Color(0xFF91A7FF) else PaperPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Text(
                                text = state.currentSense ?: currentWord.meaning,
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Serif,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center,
                                lineHeight = 34.sp
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.wordbook_sense_quiz_prompt),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            // 义项辨析模式下**不给发音入口**：发音等于直接
                            // 报答案（听到读音就能选出对应单词），练义项
                            // 辨析的意义就没了。收藏与对错无关，右上角照常给。
                            // 答错后展示原词时再补一个发音按钮（见下方
                            // 揭晓区），让用户对答案建立「音-义」关联。

                            // 作答后展示原词与全部释义。
                            //
                            // **只在答错时展示**：答对后 380ms 就自动切题，
                            // 释义闪现一下没有意义；答错时用户正需要
                            // 「正确答案 + 完整释义」来理解错因。
                            // （学习 github.com/ausyeah/wordbook：答对只出
                            // 「✓ 回答正确！」反馈，答错才给正确答案。）
                            //
                            // 不用 AnimatedVisibility：它的 exit 渐隐期间 content
                            // 仍会组合渲染——切题时新词的释义会先闪一下再消失
                            // （用户实测「还没选就出现了」）。改为显式 alpha：
                            // 隐藏时 snap() 瞬间归零立即停止渲染，显示时才渐显。
                            val senseSelected = state.options.getOrNull(state.selectedOptionIndex ?: -1)
                            val senseAnsweredWrong = state.isAnswered && senseSelected?.isCorrect == false
                            val senseAlpha by animateFloatAsState(
                                targetValue = if (senseAnsweredWrong) 1f else 0f,
                                animationSpec = if (senseAnsweredWrong) tween(220) else snap(),
                                label = "senseAnswerAlpha"
                            )
                            if (senseAlpha > 0.01f) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .alpha(senseAlpha),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Spacer(Modifier.height(18.dp))
                                    HorizontalDivider(
                                        modifier = Modifier.fillMaxWidth(),
                                        thickness = 1.dp,
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                                    )
                                    Spacer(Modifier.height(14.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = currentWord.word,
                                            fontSize = 26.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Serif,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        // 答错后才出现发音按钮：此时答案已揭晓，
                                        // 听一遍建立「音-义-形」关联不再泄露答案。
                                        IconButton(
                                            onClick = viewModel::speakCurrentWord,
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                                contentDescription = stringResource(R.string.wordbook_cd_speak),
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    if (currentWord.pos.isNotBlank()) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = currentWord.pos,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = currentWord.full.ifBlank { currentWord.meaning },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        textAlign = TextAlign.Center,
                                        lineHeight = 22.sp
                                    )
                                }
                            }
                        } else if (state.studyMode == StudyMode.SPELL) {
                            // 拼写模式题干：只给中文释义，不给单词。
                            //
                            // 释义用 full（多义项全文）而不是 meaning（短释义）：
                            // meaning 只有一两个词，猜出英文很容易（一个中文
                            // 词对应一串英文候选）；full 里有词性、多义项、
                            // 近反义，猜的难度才够。
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isDark) Color(0xFF232A46) else PaperPrimaryTint,
                                modifier = Modifier.padding(bottom = 12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.MenuBook,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = if (isDark) Color(0xFF91A7FF) else PaperPrimary
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = stringResource(R.string.wordbook_spell_mode_title),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isDark) Color(0xFF91A7FF) else PaperPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Text(
                                text = currentWord.full.ifBlank { currentWord.meaning },
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Serif,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center,
                                lineHeight = 34.sp
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.wordbook_spell_prompt),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            // 答错后揭晓正确拼写。
                            // 答对时不展示（600ms 后自动切题，闪现没意义），
                            // 答错时用户正需要「正确拼写 + 释义」来记牢。
                            if (state.spellSubmitted && !state.spellCorrect) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Spacer(Modifier.height(18.dp))
                                    HorizontalDivider(
                                        modifier = Modifier.fillMaxWidth(),
                                        thickness = 1.dp,
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                                    )
                                    Spacer(Modifier.height(14.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = currentWord.word,
                                            fontSize = 26.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Serif,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        IconButton(
                                            onClick = viewModel::speakCurrentWord,
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                                contentDescription = stringResource(R.string.wordbook_cd_speak),
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    if (currentWord.pos.isNotBlank()) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = currentWord.pos,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            // 发音/收藏在卡片右上角。这里的喇叭是「听一遍」：
                            // **不自动播放**，由用户自己决定听不听——自动播等于
                            // 直接把答案念出来，拼写练习就退化成听写；用户主动点
                            // 才是听写练习。
                        } else {
                            // 常规四选一 / 卡片模式题干
                            //
                            // 为什么不能再「36sp + 居中 + 不管多宽」：词库最长
                            // 28 字母（antidisestablishmentarianism），36sp 衬线
                            // 粗体实测约 0.54em/字，13 字的 sophisticated 就要
                            // 252dp——而卡片内容区在 PIXEL_5 上只有 320dp，
                            // 右端直接压住右上角的星标/喇叭/收藏。用户截图报的
                            // 「长单词显示和上方UI冲突」就是这一处。
                            //
                            // 修法：单词仍然居中，但可用宽度左右各扣掉一个右上角
                            // 工具带（左侧一并让出是为了保持居中，只让右边的话
                            // 长单词会整体歪向左边），字号「塞得下就 36sp、塞不下
                            // 按比例缩」，外面再叠一层 widthIn 硬钳——字体度量
                            // 估算有偏差、或用户把系统字号调到 1.3x 时，宁可折行
                            // 也绝不压到图标（双保险，同 RichText 的取宽思路）。
                            BoxWithConstraints(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                val safeWidthDp = wordHeadlineMaxWidthDp(maxWidth.value)
                                Text(
                                    text = currentWord.word,
                                    fontSize = wordHeadlineSizeSp(
                                        word = currentWord.word,
                                        availableWidthDp = safeWidthDp,
                                        fontScale = LocalDensity.current.fontScale
                                    ).sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Serif,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    modifier = Modifier.widthIn(max = safeWidthDp.dp)
                                )
                            }

                            if (currentWord.pos.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = currentWord.pos,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            // 发音 + 收藏已移到卡片右上角，不再占据单词正下方的空间。

                            // 作答后或卡片揭晓后展示完整中文释义。
                            // **答对不展示**：答对即退出本轮（380ms 自动切题），
                            // 释义留给答错时理解错因；卡片模式是主动翻面，
                            // isCardRevealed 照常展示。
                            // 同 SENSE 分支：不用 AnimatedVisibility，避免 exit
                            // 渐隐期间新词释义闪现；隐藏即时、显示渐显。
                            val selectedOption = state.options.getOrNull(state.selectedOptionIndex ?: -1)
                            val answeredWrong = state.isAnswered && selectedOption?.isCorrect == false
                            val meaningAlpha by animateFloatAsState(
                                targetValue = if (answeredWrong || state.isCardRevealed) 1f else 0f,
                                animationSpec = if (answeredWrong || state.isCardRevealed) tween(220) else snap(),
                                label = "meaningAlpha"
                            )
                            if (meaningAlpha > 0.01f) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .alpha(meaningAlpha)
                                ) {
                                    Spacer(Modifier.height(18.dp))
                                    HorizontalDivider(
                                        modifier = Modifier.fillMaxWidth(),
                                        thickness = 1.dp,
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                                    )
                                    Spacer(Modifier.height(16.dp))
                                    Text(
                                        text = currentWord.full.ifBlank { currentWord.meaning },
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        lineHeight = 24.sp
                                    )
                                }
                            }
                        }
                    }

                    // 右上角工具位：熟词星标 + 发音（义项辨析模式不给，发音=报答案）+ 收藏。
                    // 三个标记/工具按钮并排在一处，不各占一行的固定位。
                    Row(
                        modifier = Modifier.align(Alignment.TopEnd),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val isMastered = state.currentProgress?.isMastered == true
                        IconButton(
                            onClick = viewModel::toggleMasterCurrentWord,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = if (isMastered) Icons.Default.Star else Icons.Default.StarBorder,
                                contentDescription = if (isMastered) "取消熟词标记" else "标记熟词",
                                tint = if (isMastered) PaperLv1 else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                        if (state.studyMode != StudyMode.SENSE) {
                            IconButton(
                                onClick = viewModel::speakCurrentWord,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = stringResource(R.string.wordbook_cd_speak),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }
                        IconButton(
                            onClick = viewModel::toggleFavoriteCurrentWord,
                            modifier = Modifier.size(32.dp)
                        ) {
                            val fav = state.currentProgress?.isFavorite == true
                            Icon(
                                imageVector = if (fav) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                contentDescription = if (fav) "取消收藏" else "收进生词本",
                                tint = if (fav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                    }
                }
            }


            // 模式分支交互
            if (state.studyMode == StudyMode.QUIZ || state.studyMode == StudyMode.SENSE) {
                // 单选题列表（QUIZ 或 SENSE）
                // key 用单词本身：选项集合变化时 Compose 才能正确复用/重排
                // item，而不是把四个选项整组扔掉重建（滚动卡顿的来源之一）。
                items(state.options, key = { it.word.word }) { option ->
                    QuizOptionRow(
                        option = option,
                        isSenseMode = state.studyMode == StudyMode.SENSE,
                        isAnswered = state.isAnswered,
                        // 回退/切模式重放回来的题允许改选：
                        // 保留刚才的选中状态（isAnswered 仍为 true，
                        // 于是对错颜色照旧显示），但选项不再被锁死。
                        allowReselect = state.allowReselect,
                        onSelect = {
                            val idx = state.options.indexOf(option)
                            if (idx >= 0) viewModel.selectOption(idx)
                        }
                    )
                }

                // 答错时展示「下一题」大按钮供看清错因并手动推进；
                // 答对时由系统 380ms 跟手自动切题，无需手动点击。
                val selectedOption = state.options.getOrNull(state.selectedOptionIndex ?: -1)
                val isWrong = state.isAnswered && (selectedOption?.isCorrect == false)
                if (isWrong) {
                    item {
                        Spacer(Modifier.height(14.dp))
                        val correctOption = state.options.firstOrNull { it.isCorrect }
                        val wrongExplanation = if (state.studyMode == StudyMode.SENSE && correctOption != null) {
                            "正确答案：${correctOption.word.word}（${correctOption.word.meaning}）"
                        } else {
                            "正确答案：${correctOption?.word?.meaning ?: ""}"
                        }

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isDark) Color(0xFF381518) else Color(0xFFFFF0F0)
                            ),
                            border = CardDefaults.outlinedCardBorder().copy(
                                brush = androidx.compose.ui.graphics.SolidColor(
                                    if (isDark) Color(0xFFFF6B6B) else Color(0xFFC92A2A)
                                )
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = null,
                                    tint = if (isDark) Color(0xFFFF6B6B) else Color(0xFFC92A2A),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = stringResource(R.string.wordbook_quiz_wrong_added),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isDark) Color(0xFFFFA8A8) else Color(0xFFC92A2A)
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = wrongExplanation,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isDark) Color(0xFFFFE8E8) else Color(0xFF821818)
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = viewModel::prepareNextQuestion,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text(stringResource(R.string.wordbook_next_question), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                } else {
                    // **常驻的前进按钮**：答过了叫「下一个词」（抽新题），
                    // 没答过叫「换批释义」（不换词，只重抽当前词的选项）。
                    //
                    // 答错已经有上面那颗「下一题」大按钮，这里就不重复了；
                    // 其余状态一律给出这颗次要按钮，原因是「回退」这条路径
                    // 之前是**死路**：点撤回回到上一个词后，答对的会自动
                    // 跳走，但改选答对、以及回退后重新看一眼的场景不会跳——
                    // 界面上没有任何能往前走的东西，只能去切模式或者杀进程。
                    // 有进有退才是完整的导航：撤得回来，也得走得出去。
                    item {
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = {
                                // 已答错的大按钮是「下一题」（抽新词）；
                                // 未作答时这颗次要按钮是「换批释义」——
                                // 不换词，只把当前词的选项重抽一批
                                // （用户原话：要的是换释义，不是换词）。
                                if (state.isAnswered) viewModel.prepareNextQuestion()
                                else viewModel.reshuffleOptions()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                if (state.isAnswered) "下一个词" else "换批释义",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            } else if (state.studyMode == StudyMode.SPELL) {
                // 拼写模式：输入框 + 提交 + 结果反馈
                item {
                    SpellAnswerArea(state = state, viewModel = viewModel)
                }
            } else {
                // 卡片模式：翻面揭晓与四档打分
                item {
                    if (!state.isCardRevealed) {
                        Button(
                            onClick = viewModel::revealCard,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text(stringResource(R.string.wordbook_reveal_meaning), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                        }
                    } else {
                        // 四档掌握度打分按钮
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                RateButton("忘记了", MaterialTheme.colorScheme.error, Modifier.weight(1f)) { viewModel.rateCard(0) }
                                RateButton("模糊", PaperLv1, Modifier.weight(1f)) { viewModel.rateCard(1) }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                RateButton("认识", PaperLv2, Modifier.weight(1f)) { viewModel.rateCard(2) }
                                RateButton("熟练", PaperLv3, Modifier.weight(1f)) { viewModel.rateCard(3) }
                            }
                        }
                    }
                }
            }
            // 场景图（F-IMG-1）：v0.0.58 反馈「把场景图功能放到最下面啊」。
            //
            // 历史来回搬了两次，结论记在这里免得再改：
            // - 最初在最底部 → 用户说找不到（要滚过题目卡+选项+打分按钮）；
            // - v0.0.55 上移到题目卡正下方 → 用户说挡住刷题，要求放回最下面。
            // 现在固定在最底部：题目卡 → 选项 → 打分/前进 → 场景图。
            // 未生成时只是一颗 46dp 的按钮，滚到底才看到，不占首屏。
            if (currentWord != null) {
                item {
                    WordSceneImage(state = state, viewModel = viewModel)
                }
            }
        }
    }
}


/**
 * 模式 chip 里的文字：撑满 chip 宽度并居中。
 *
 * 四个 chip 等宽（weight(1f)）之后，Material3 的 FilterChip 会把
 * label 靠左排——「四选一」三个字顶着左边，「拼写」两个字也顶着左边，
 * 视觉上又变成参差不齐。撑满 + 居中才是真正的均衡。
 */
@Composable
internal fun ChipLabel(text: String) {
    Text(
        text = text,
        maxLines = 1,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}


@Composable
internal fun RateButton(
    title: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(46.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color)
    ) {
        Text(title, color = Color.White, fontWeight = FontWeight.Bold)
    }
}


/**
 * 拼写模式的作答区：输入框 + 提交 + 对错反馈。
 *
 * 三种状态：
 * - 未提交：输入框可编辑，下面一个「提交答案」大按钮；
 * - 答对：绿色对勾一行，600ms 后自动进入下一题（拼写比四选一多看一眼的时间）；
 * - 答错：红色卡片给出正确拼写（题干卡里也会揭晓），并给「下一题」按钮。
 *
 * 输入框用 IME「完成」动作提交：手机上单手打字时，键盘上的
 * 「完成/换行」键比伸手点屏幕上的按钮顺手得多。
 */
@Composable
internal fun SpellAnswerArea(
    state: WordbookUiState,
    viewModel: WordbookViewModel
) {
    val isDark = LocalDarkTheme.current
    val okColor = if (isDark) com.mistakebook.ui.theme.PaperOkDark else PaperOk

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = state.spellInput,
            onValueChange = viewModel::setSpellInput,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.wordbook_spell_hint)) },
            singleLine = true,
            enabled = !state.spellSubmitted,
            isError = state.spellSubmitted && !state.spellCorrect,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
                keyboardType = KeyboardType.Text
            ),
            keyboardActions = KeyboardActions(onDone = { viewModel.submitSpell() }),
            trailingIcon = {
                if (!state.spellSubmitted) {
                    IconButton(
                        onClick = viewModel::submitSpell,
                        enabled = state.spellInput.isNotBlank()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.wordbook_cd_submit))
                    }
                }
            },
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface
            )
        )

        Spacer(Modifier.height(12.dp))

        when {
            !state.spellSubmitted -> {
                Button(
                    onClick = viewModel::submitSpell,
                    enabled = state.spellInput.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(
                        "提交答案",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }

            state.spellCorrect -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = okColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.wordbook_spell_correct),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = okColor
                    )
                }
            }

            else -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isDark) Color(0xFF381518) else Color(0xFFFFF0F0)
                    ),
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = androidx.compose.ui.graphics.SolidColor(
                            if (isDark) Color(0xFFFF6B6B) else Color(0xFFC92A2A)
                        )
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                            tint = if (isDark) Color(0xFFFF6B6B) else Color(0xFFC92A2A),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.wordbook_spell_wrong_added),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isDark) Color(0xFFFFA8A8) else Color(0xFFC92A2A)
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.wordbook_spell_answer, state.currentWord?.word ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isDark) Color(0xFFFFE8E8) else Color(0xFF821818)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = viewModel::prepareNextQuestion,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(stringResource(R.string.wordbook_next_question), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}


@Composable
internal fun QuizOptionRow(
    option: QuizOption,
    isSenseMode: Boolean = false,
    isAnswered: Boolean,
    /** 已作答但允许改选（回退/切模式重放回来的题）。 */
    allowReselect: Boolean = false,
    onSelect: () -> Unit
) {
    val isDark = LocalDarkTheme.current

    // 背景色搭配：深浅色自适应，彻底解决深色模式下冒出惨白底与白字冲突的问题
    val bgColor = when {
        !isAnswered -> MaterialTheme.colorScheme.surface
        option.isCorrect -> {
            if (isDark) Color(0xFF133221) else Color(0xFFEDFBF1)
        }
        option.isSelected && !option.isCorrect -> {
            if (isDark) Color(0xFF381518) else Color(0xFFFFF0F0)
        }
        else -> {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
        }
    }

    // 边框色
    val borderColor = when {
        !isAnswered -> MaterialTheme.colorScheme.outline
        option.isCorrect -> {
            if (isDark) Color(0xFF40C057) else Color(0xFF2B8A3E)
        }
        option.isSelected && !option.isCorrect -> {
            if (isDark) Color(0xFFFF6B6B) else Color(0xFFC92A2A)
        }
        else -> {
            MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
        }
    }

    // 选项字母圆形徽标背景色与文字色
    val badgeBgColor = when {
        isAnswered && option.isCorrect -> {
            if (isDark) Color(0xFF2B8A3E) else Color(0xFF2B8A3E)
        }
        isAnswered && option.isSelected -> {
            if (isDark) Color(0xFFC92A2A) else Color(0xFFC92A2A)
        }
        else -> {
            if (isDark) Color(0xFF2B3356) else PaperPrimaryTint
        }
    }

    val badgeTextColor = when {
        isAnswered && (option.isCorrect || option.isSelected) -> Color.White
        else -> {
            if (isDark) Color(0xFF91A7FF) else PaperPrimary
        }
    }

    // 词性标签颜色
    val posColor = when {
        isAnswered && option.isCorrect -> {
            if (isDark) Color(0xFF69DB7C) else Color(0xFF2B8A3E)
        }
        isAnswered && option.isSelected && !option.isCorrect -> {
            if (isDark) Color(0xFFFFA8A8) else Color(0xFFC92A2A)
        }
        isAnswered -> {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        }
        else -> {
            MaterialTheme.colorScheme.primary
        }
    }

    // 释义正文颜色：确保在深色或浅色卡片上对比度均在 10:1 以上清晰可读
    val meaningColor = when {
        isAnswered && option.isCorrect -> {
            if (isDark) Color(0xFFE6FCED) else Color(0xFF144D29)
        }
        isAnswered && option.isSelected && !option.isCorrect -> {
            if (isDark) Color(0xFFFFE8E8) else Color(0xFF821818)
        }
        isAnswered -> {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        }
        else -> {
            MaterialTheme.colorScheme.onSurface
        }
    }

    // 反馈图标颜色
    val iconColor = when {
        option.isCorrect -> if (isDark) Color(0xFF51CF66) else Color(0xFF2B8A3E)
        else -> if (isDark) Color(0xFFFF6B6B) else Color(0xFFC92A2A)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            // 已作答默认锁死；但 allowReselect 时（回退后重放）仍然可点，
            // 用户能改主意——「保留状态但可以修改」。
            .clickable(enabled = !isAnswered || allowReselect, onClick = onSelect),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(borderColor))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(badgeBgColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = option.label,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = badgeTextColor
                )
            }

            Spacer(Modifier.width(12.dp))

            // 选项文本区。
            //
            // **固定高度是这个区的核心目的**：实测词库 4356 词——
            // 100% 有词性（最长 34 字符，独立一行放得下）、释义长度
            // p50=13 / p90=25 / p99=33 字符，中文 bodyMedium 一行约
            // 18 字，两行空间（48dp）覆盖 99% 的词。所以：
            //
            // - 释义区写死 48dp（QUIZ）/ 40dp（SENSE 作答后）：
            //   单行释义不塌高度、双行不撑高，四个选项从始至终一样高；
            // - 不写死 Card 总高度：ROM 行高有差异，写死会溢出裁剪，
            //   固定文本区高度则无论行高多少总高都恒定；
            // - 词性独立成行（QUIZ）：有没有词性、词性长短都不影响高度。
            //
            // 改之前的现象：词性有无（有的词没有）、释义一两行、
            // 作答后多一行释义——三种因素叠加，四个选项高矮不齐、
            // 切题时整组跳动，用户答题时要点的位置一直在飘。
            Column(modifier = Modifier.weight(1f)) {
                if (isSenseMode) {
                    // 义项辨析模式：选项呈现英文单词，词性内联小标同行
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = option.word.word,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Serif,
                            color = meaningColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (option.word.pos.isNotBlank()) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = option.word.pos,
                                style = MaterialTheme.typography.labelSmall,
                                color = posColor,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    // 释义区**恒定占位**：未作答时也留着这 40dp。
                    // 四个选项在作答瞬间一起从 52dp 长到 96dp 也算「位置跳」，
                    // 未作答就预留空间，全程等高，切题/作答都不动。
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (isAnswered) option.word.meaning else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = meaningColor.copy(alpha = 0.85f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.height(OPTION_MEANING_SLOT_SENSE.dp)
                    )
                } else {
                    // 常规四选一模式：词性内联在释义行首（「n. 金属板」），
                    // 不再独立成行。整个文本区固定 40dp 高、内容垂直居中——
                    // 单行不偏上、双行放得下，四个选项从始至终一样高。
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.height(OPTION_MEANING_SLOT_QUIZ.dp)
                    ) {
                        if (option.word.pos.isNotBlank()) {
                            Text(
                                text = option.word.pos,
                                style = MaterialTheme.typography.labelSmall,
                                color = posColor,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                modifier = Modifier.padding(end = 6.dp)
                            )
                        }
                        Text(
                            text = option.word.meaning,
                            style = MaterialTheme.typography.bodyMedium,
                            color = meaningColor,
                            fontWeight = if (isAnswered && (option.isCorrect || option.isSelected)) FontWeight.Medium else FontWeight.Normal,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            if (isAnswered) {
                if (option.isCorrect) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = iconColor)
                } else if (option.isSelected) {
                    Icon(Icons.Default.Close, contentDescription = null, tint = iconColor)
                }
            }
        }
    }
}

// =========================================================================
// 2. 错题本体系 (WrongBook View)
// =========================================================================


/**
 * 四选一选项的释义区高度（dp）。
 *
 * 写死文本区高度而不是 Card 总高度：词库实测释义 p99=33 字符，
 * 中文 bodyMedium 一行约 18 字，48dp（两行空间）覆盖 99% 的词；
 * 单行释义不塌、双行不撑，四个选项从始至终等高。
 * 详见 [QuizOptionRow] 里的注释。
 */
internal const val OPTION_MEANING_SLOT_QUIZ = 40



/**
 * 义项辨析选项的释义区高度（dp）。
 *
 * bodySmall 一行约 22 字，40dp 两行覆盖 44 字——词库最长释义
 * 43 字符，刚好全覆盖。未作答时也恒定占位，作答不改变选项高度。
 */
internal const val OPTION_MEANING_SLOT_SENSE = 36

/**
 * 单词卡右上角工具带的宽度（dp）：星标 + 发音 + 收藏三个 32dp IconButton。
 *
 * 单独拎成常量，是因为它是题干可用宽度的**扣减项**——三颗按钮改尺寸或
 * 增删一颗，题干的 safe 宽度必须跟着变；写死在 UI 里就会漏改，
 * 于是长单词又开始压图标。
 */
internal const val WORD_CARD_TOOL_BAND_DP = 96f

/** 题干（单词本身）的最大字号：设计稿基准值，短单词一律用满。 */
internal const val WORD_HEADLINE_MAX_SP = 36f

/**
 * 题干的最小字号。
 *
 * 为什么必须有下限：字数继续涨时按比例缩会小到看不清，而 widthIn 硬钳
 * 已经保证不会压图标——所以缩到 18sp（正文 bodyLarge 量级）就停，
 * 剩下的交给折行，折两行仍读得清。
 */
internal const val WORD_HEADLINE_MIN_SP = 18f

/**
 * Noto Serif Bold 下每个字符约占多少个 em。
 *
 * 实测：PIXEL_5 渲染 ubiquitous（10 字母）36sp 宽 193.9dp → 0.539em/字。
 * 取 0.58 是**故意留 7% 余量**，不是四舍五入——选字号用的是
 * `可用宽度 / (字宽 × 字数)`，估算偏小的结果就是「算出来的字号刚够塞，
 * 实际排版宽了几个百分点」，于是 maxLines=2 生效、单词被折行。
 * 0.55 那版就是这么翻车的：ubiquitous 折成两行，用户以为显示坏了。
 *
 * 它只用来**选字号**；真正防压图标靠 widthIn 硬钳，所以估算偏大了
 * 顶多字号小一两号，不可能让单词越过工具带。
 */
internal const val WORD_HEADLINE_EM_PER_CHAR = 0.58f

/**
 * 题干可以占用的最大宽度（dp）。
 *
 * 左右各扣一个工具带：右边是真实占用（右上角三颗按钮），左边让出来是为了
 * 单词保持居中。兜底 96dp 对应 360dp 宽的设计下限屏——再窄卡片内容区只剩
 * 96dp，已超出本项目的设计包络（内边距写死 16/20dp、题干写死 36sp）。
 */
internal fun wordHeadlineMaxWidthDp(cardContentWidthDp: Float): Float =
    (cardContentWidthDp - 2 * WORD_CARD_TOOL_BAND_DP).coerceAtLeast(96f)

/**
 * 按可用宽度给题干选字号（sp）。
 *
 * 三个入参都是「当前实际约束」而不是设备型号：可用宽度由 BoxWithConstraints
 * 量出来，fontScale 取系统的（用户在设置里放大字号时，单词占的 dp 会同比例
 * 变大，必须一起算进去，否则放大模式下又会压图标）。
 */
internal fun wordHeadlineSizeSp(word: String, availableWidthDp: Float, fontScale: Float): Float {
    if (word.isEmpty()) return WORD_HEADLINE_MAX_SP
    val availableEm = availableWidthDp / (fontScale * WORD_HEADLINE_EM_PER_CHAR)
    return (availableEm / word.length).coerceIn(WORD_HEADLINE_MIN_SP, WORD_HEADLINE_MAX_SP)
}


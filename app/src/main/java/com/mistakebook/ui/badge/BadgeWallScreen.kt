package com.mistakebook.ui.badge

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mistakebook.R
import com.mistakebook.di.AppContainer
import com.mistakebook.domain.Badge
import com.mistakebook.domain.BadgeEngine
import com.mistakebook.domain.BadgeStats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

/** 每页徽章数：3 列 × 3 行，与参考样式的呼吸感一致。 */
private const val PAGE_SIZE = 9
private const val GRID_COLUMNS = 3

/**
 * 成就墙（v0.0.54 重设计）。
 *
 * 参考主流打卡 App 的徽章墙样式：顶部聚光灯 + 柔和渐变背景 +
 * 分页徽章网格；徽章用专属手绘风格素材（drawable-nodpi/badge_*.png），
 * 未解锁置灰压暗并挂锁角标。点任意徽章弹出详情（达成条件 / 进度 /
 * 解锁日期）。判定逻辑不变，仍由 [BadgeEngine] 纯函数计算。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BadgeWallScreen(onBack: () -> Unit) {
    val container = run {
        val app = com.mistakebook.MistakeBookApp.instanceOrNull()
        app?.container ?: com.mistakebook.di.AppContainer(
            LocalContext.current.applicationContext as android.app.Application
        )
    }
    val unlocked by container.badgeDao.observeAll()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val unlockedMap = unlocked.associateBy { it.key }

    val stats by produceState<BadgeStats?>(initialValue = null) {
        value = runCatching { container.badgeStatsProvider.currentStats() }.getOrNull()
    }

    var page by rememberSaveable { mutableStateOf(0) }
    var selected by remember { mutableStateOf<Badge?>(null) }

    val progressList = stats?.let { s ->
        BadgeEngine.evaluate(s, java.time.LocalDate.now())
    }

    Scaffold(
        containerColor = Color(0xFFE9F1FC),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.badge_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (progressList == null) {
            Box(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.badge_loading),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            return@Scaffold
        }

        val pageCount = ceil(progressList.size.toDouble() / PAGE_SIZE).toInt().coerceAtLeast(1)
        val safePage = page.coerceIn(0, pageCount - 1)
        val pageItems = progressList.drop(safePage * PAGE_SIZE).take(PAGE_SIZE)
        val rows = pageItems.chunked(GRID_COLUMNS)
        val unlockedCount = progressList.count { unlockedMap.containsKey(it.badge.key) }

        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFFE9F1FC), Color(0xFFF6FAFF), Color(0xFFFFFFFF))
                    )
                )
        ) {
            Spotlights(Modifier.fillMaxWidth().height(120.dp))
            Text(
                text = stringResource(R.string.badge_unlocked_count, unlockedCount, progressList.size),
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFF5B7DA8),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                textAlign = TextAlign.Center
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(22.dp)
                ) {
                    rows.forEach { rowBadges ->
                        Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                            rowBadges.forEach { progress ->
                                BadgeGridItem(
                                    badge = progress.badge,
                                    unlocked = unlockedMap.containsKey(progress.badge.key),
                                    onClick = { selected = progress.badge }
                                )
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 18.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { if (safePage > 0) page = safePage - 1 },
                    enabled = safePage > 0
                ) {
                    Text(stringResource(R.string.badge_prev_page))
                }
                Text(
                    text = stringResource(R.string.badge_page_indicator, safePage + 1, pageCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF5B7DA8),
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                TextButton(
                    onClick = { if (safePage < pageCount - 1) page = safePage + 1 },
                    enabled = safePage < pageCount - 1
                ) {
                    Text(stringResource(R.string.badge_next_page))
                }
            }
        }
    }

    selected?.let { badge ->
        val record = unlockedMap[badge.key]
        val progress = progressList?.firstOrNull { it.badge.key == badge.key }
        BadgeDetailDialog(
            badge = badge,
            current = progress?.current ?: 0,
            unlocked = record != null,
            unlockedAt = record?.unlockedAt,
            onDismiss = { selected = null }
        )
    }
}

/** 顶部三道聚光灯：梯形光束从上向下渐隐，营造「展台」氛围。 */
@Composable
private fun Spotlights(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        listOf(0.24f, 0.50f, 0.76f).forEach { center ->
            val topHalf = w * 0.03f
            val bottomHalf = w * 0.15f
            val beam = Path().apply {
                moveTo(w * center - topHalf, 0f)
                lineTo(w * center + topHalf, 0f)
                lineTo(w * center + bottomHalf, h)
                lineTo(w * center - bottomHalf, h)
                close()
            }
            drawPath(
                beam,
                brush = Brush.verticalGradient(
                    listOf(Color(0xFFBBd4F5).copy(alpha = 0.9f), Color.Transparent)
                )
            )
        }
    }
}

@Composable
private fun BadgeGridItem(
    badge: Badge,
    unlocked: Boolean,
    onClick: () -> Unit
) {
    Box(modifier = Modifier.clickable(onClick = onClick)) {
        BadgeImage(badge = badge, unlocked = unlocked, size = 84.dp)
        if (!unlocked) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF3D5A80).copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

/**
 * 徽章图。未解锁：灰度 + 半透明 + 浅阴影；已解锁：全彩 + 投影。
 * 素材是圆形透明底 PNG，阴影走 CircleShape 轮廓。
 */
@Composable
private fun BadgeImage(badge: Badge, unlocked: Boolean, size: Dp) {
    val res = BADGE_IMAGES.getValue(badge)
    Image(
        painter = painterResource(res),
        contentDescription = badge.title,
        modifier = Modifier
            .size(size)
            .shadow(
                elevation = if (unlocked) 10.dp else 3.dp,
                shape = CircleShape,
                clip = false,
                ambientColor = Color(0x33446688),
                spotColor = Color(0x55446688)
            ),
        colorFilter = if (unlocked) {
            null
        } else {
            // Compose 的 ColorMatrix 方法是 setToSaturation（android.graphics 那个才叫
            // setSaturation）——WIP 里写的是 framework 版名字，从未编译所以没暴露。
            ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
        },
        alpha = if (unlocked) 1f else 0.45f
    )
}

@Composable
private fun BadgeDetailDialog(
    badge: Badge,
    current: Int,
    unlocked: Boolean,
    unlockedAt: Long?,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = badge.title,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                BadgeImage(badge = badge, unlocked = unlocked, size = 132.dp)
                Spacer(Modifier.height(12.dp))
                Text(
                    text = badge.conditionText,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(10.dp))
                if (unlocked) {
                    val stamp = unlockedAt?.let {
                        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(it))
                    }.orEmpty()
                    Text(
                        text = stringResource(R.string.badge_unlocked_on, stamp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else if (badge.target > 0) {
                    LinearProgressIndicator(
                        progress = { (current.toFloat() / badge.target).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.badge_progress, current, badge.target),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = stringResource(R.string.badge_locked),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_confirm))
            }
        }
    )
}

/** 徽章 key → 素材资源。新增徽章时同步补图。 */
private val BADGE_IMAGES: Map<Badge, Int> = mapOf(
    Badge.FIRST_QUESTION to R.drawable.badge_first_question,
    Badge.HUNDRED_QUESTIONS to R.drawable.badge_hundred_questions,
    Badge.TEN_MASTERED to R.drawable.badge_ten_mastered,
    Badge.HUNDRED_REVIEWS to R.drawable.badge_hundred_reviews,
    Badge.SEVEN_DAY_STREAK to R.drawable.badge_seven_day_streak,
    Badge.THIRTY_DAY_STREAK to R.drawable.badge_thirty_day_streak,
    Badge.KNOWLEDGE_CLEARED to R.drawable.badge_knowledge_cleared,
    Badge.TEN_IN_ONE_DAY to R.drawable.badge_ten_in_one_day,
    Badge.FIRST_WORD to R.drawable.badge_first_word,
    Badge.HUNDRED_WORDS to R.drawable.badge_hundred_words
)

package com.mistakebook.ui.badge

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mistakebook.R
import com.mistakebook.ui.common.AppLinearProgressIndicator
import com.mistakebook.ui.theme.categoryGradientOf
import com.mistakebook.domain.Badge
import com.mistakebook.domain.BadgeCategory
import com.mistakebook.domain.BadgeEngine
import com.mistakebook.domain.BadgeStats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 成就墙（v0.0.54 首版，v0.0.86 重排版）。
 *
 * 旧版是「2 列 × 3 行 + 8 页翻页」：48 枚徽章一页只露 6 枚，
 * 绝大多数时间是灰的，用户翻两页就累了，整面墙看着空空荡荡。
 *
 * 现在改成一面**一整幅能滚动的墙**：顶部战绩卡（已解锁数 + 完成度进度条），
 * 下面自适应网格一屏铺开 6~9 枚，按「已解锁在前、未解锁在后，两组都按难度
 * 越难越靠后」排（排序规则见 [BadgeEngine.sortForWall]，纯函数可测）。
 * 每枚章下面带名字，未解锁的进度型徽章还带「3 / 10」——灰墙上有进度
 * 才看得出在往前走。判定逻辑不变，仍由 [BadgeEngine] 纯函数计算。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BadgeWallScreen(onBack: () -> Unit) {
    // Application 未初始化时给占位而不是现场 new 一整套 AppContainer
    // （v0.0.67 修复）：那等于为读一次徽章数据建起网络客户端 + 两个
    // Room 连接，纯浪费且有连接泄漏风险。理论不可达路径，防御即可。
    val container = com.mistakebook.MistakeBookApp.instanceOrNull()?.container
    if (container == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(stringResource(R.string.badge_not_initialized))
        }
        return
    }
    val unlocked by container.badgeDao.observeAll()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val unlockedMap = unlocked.associateBy { it.key }

    val stats by produceState<BadgeStats?>(initialValue = null) {
        value = runCatching { container.badgeStatsProvider.currentStats() }.getOrNull()
    }

    var selected by remember { mutableStateOf<Badge?>(null) }

    val progressList = stats?.let { s ->
        BadgeEngine.evaluate(s, java.time.LocalDate.now())
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
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

        // 墙上「有没有这枚章」只认库里的解锁记录，引擎算出的应解锁集合
        // 只用来取进度数字——两者差一瞬间（算完还没落库）时，按库排才
        // 不会出现「排序说已解锁、章却是灰的」这种自相矛盾。
        val unlockedCount = progressList.count { unlockedMap.containsKey(it.badge.key) }
        val currentByKey = progressList.associate { it.badge.key to it.current }
        val ordered = BadgeEngine.sortForWall(progressList.map { it.badge }, unlockedMap.keys)
        val unlockedBadges = ordered.filter { unlockedMap.containsKey(it.key) }
        val lockedBadges = ordered.filterNot { unlockedMap.containsKey(it.key) }

        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            MaterialTheme.colorScheme.surface
                        )
                    )
                )
        ) {
            WallSummaryCard(
                unlockedCount = unlockedCount,
                total = progressList.size,
                // v0.0.117：撤掉顶部的三道聚光灯（用户：「丑死了」）。
                // 原先靠一段 120dp 的梯形渐变光束撑「展台」氛围，实际效果是
                // 页头糊了三团半透明黄，还把战绩卡往下推了一屏。首屏该留给
                // 进度本身，所以直接把它删了，卡片上移到与顶栏相接。
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(96.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = 4.dp,
                    bottom = 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (unlockedBadges.isNotEmpty()) {
                    item(
                        span = { GridItemSpan(maxLineSpan) },
                        key = "header_unlocked"
                    ) {
                        WallSectionHeader(
                            title = stringResource(R.string.badge_wall_section_unlocked),
                            count = unlockedBadges.size
                        )
                    }
                    items(
                        items = unlockedBadges,
                        key = { "unlocked_${it.key}" }
                    ) { badge ->
                        BadgeCell(
                            badge = badge,
                            unlocked = true,
                            current = currentByKey[badge.key] ?: 0,
                            onClick = { selected = badge }
                        )
                    }
                }
                if (lockedBadges.isNotEmpty()) {
                    item(
                        span = { GridItemSpan(maxLineSpan) },
                        key = "header_locked"
                    ) {
                        WallSectionHeader(
                            title = stringResource(R.string.badge_wall_section_locked),
                            count = lockedBadges.size
                        )
                    }
                    items(
                        items = lockedBadges,
                        key = { "locked_${it.key}" }
                    ) { badge ->
                        BadgeCell(
                            badge = badge,
                            unlocked = false,
                            current = currentByKey[badge.key] ?: 0,
                            onClick = { selected = badge }
                        )
                    }
                }
            }
        }
    }

    selected?.let { badge ->
        val record = unlockedMap[badge.key]
        // 只有墙渲染出来（stats 已就位）才可能点中徽章，但编译器不知道，
        // 这里仍按可空取——真取不到就当成 0 进度，弹窗照常能开。
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

/**
 * 战绩卡：已解锁数 / 总数 + 完成度进度条。
 *
 * 旧版只有一行「已解锁 3 / 48」小字，数字之外读不出任何信息。
 * 进度条是这面墙上唯一「在往前走」的东西：哪怕今天只多解锁一枚，
 * 那条线也会动，比一句静态数字更能留住人。
 */
@Composable
private fun WallSummaryCard(
    unlockedCount: Int,
    total: Int,
    modifier: Modifier = Modifier
) {
    val percent = if (total <= 0) 0 else unlockedCount * 100 / total
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.EmojiEvents,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.badge_unlocked_count, unlockedCount, total),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(R.string.badge_wall_percent, percent),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(10.dp))
            AppLinearProgressIndicator(
                progress = { unlockedCount.toFloat() / total.coerceAtLeast(1) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** 分区标题：「已解锁 · 3 枚」/「未解锁 · 45 枚」，右侧一条渐隐的分隔线。 */
@Composable
private fun WallSectionHeader(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.badge_wall_section_count, title, count),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(10.dp))
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        )
    }
}

/**
 * 单枚徽章格：章 + 名字 +（未解锁且进度型时）进度。
 *
 * 名字必须露出来：48 枚章的图是有，但用户记不住哪张图对应哪件事，
 * 点开弹窗才知道太慢。进度「3 / 10」只给未解锁的——已解锁的章
 * 彩色+投影已经足够抢眼，再挂数字反而吵。
 */
@Composable
private fun BadgeCell(
    badge: Badge,
    unlocked: Boolean,
    current: Int,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box {
            BadgeImage(badge = badge, unlocked = unlocked, size = 74.dp)
            if (!unlocked) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF6B4A2F).copy(alpha = 0.85f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(11.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = badge.title,
            style = MaterialTheme.typography.labelMedium,
            color = if (unlocked) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (!unlocked && badge.target > 0) {
            Text(
                text = stringResource(R.string.badge_progress, current, badge.target),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}

/**
 * 徽章图。未解锁：灰度 + 半透明 + 浅阴影；已解锁：全彩 + 投影。
 * 素材是圆形透明底 PNG，阴影走 CircleShape 轮廓。
 */
@Composable
private fun BadgeImage(badge: Badge, unlocked: Boolean, size: Dp) {
    val res = BADGE_IMAGES[badge]
    if (res == null) {
        // 新增徽章暂时没有美术素材（v0.0.57 一次扩到 48 枚）：用分类配色 +
        // 首字做一枚程序化徽章，保证「有章可看」，而不是因为缺图直接崩。
        // `BADGE_IMAGES.getValue` 在缺键时会抛异常——旧代码只覆盖了 10 枚。
        ProceduralBadge(badge = badge, unlocked = unlocked, size = size)
        return
    }
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

/**
 * 程序化徽章：没有美术素材时按分类配色 + 标题首字生成一枚圆形章。
 *
 * 与美术素材同一套尺寸 / 阴影 / 未解锁置灰口径，看起来是同一面墙。
 */
@Composable
private fun ProceduralBadge(badge: Badge, unlocked: Boolean, size: Dp) {
    val (start, end) = categoryGradient(badge.category)
    val brush = if (unlocked) {
        Brush.linearGradient(listOf(start, end))
    } else {
        Brush.linearGradient(listOf(Color(0xFFB9B4AC), Color(0xFF8F8A82)))
    }
    Box(
        modifier = Modifier
            .size(size)
            .shadow(
                elevation = if (unlocked) 10.dp else 3.dp,
                shape = CircleShape,
                clip = false,
                ambientColor = Color(0x33446688),
                spotColor = Color(0x55446688)
            )
            .clip(CircleShape)
            .background(brush),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = badge.title.take(1),
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(4.dp)
        )
    }
}

// 分类配色（与全局暖色主题一致，v0.0.57 去掉蓝/紫）已迁入主题包
// BadgeCategoryColors.kt——程序化兜底徽章是唯一成体系用裸色值的地方，
// 放进 theme 豁免区，「界面层不写裸色值」契约才能守得住。
private fun categoryGradient(category: BadgeCategory): Pair<Color, Color> =
    categoryGradientOf(category)

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
                // 分类标注：墙面已不按类分区，这里补一句「这枚章属于哪一类」，
                // 用户对着 48 枚章才知道该往哪个方向攒。
                Text(
                    text = stringResource(R.string.badge_category_label, badge.category.label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
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
                    AppLinearProgressIndicator(
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

/**
 * 徽章 key → 素材资源。新增徽章时同步补图。
 * v0.0.84 补齐全部 48 枚（此前只有 10 枚，缺图走程序化渐变章）：
 * 新素材用与旧素材同一套圆形贴纸风格生成（蓝盘 + 藏青描边 + 白环 + 暖金图案），
 * 未解锁的置灰逻辑不变（灰度 + 半透明），成就墙不再出现「一半图片一半色块」。
 */
private val BADGE_IMAGES: Map<Badge, Int> = mapOf(
    // 录入错题（10）
    Badge.FIRST_QUESTION to R.drawable.badge_first_question,
    Badge.TEN_QUESTIONS to R.drawable.badge_ten_questions,
    Badge.Q25 to R.drawable.badge_q25,
    Badge.Q50 to R.drawable.badge_q50,
    Badge.HUNDRED_QUESTIONS to R.drawable.badge_hundred_questions,
    Badge.Q150 to R.drawable.badge_q150,
    Badge.Q200 to R.drawable.badge_q200,
    Badge.Q300 to R.drawable.badge_q300,
    Badge.Q500 to R.drawable.badge_q500,
    Badge.Q1000 to R.drawable.badge_q1000,
    // 掌握错题（8）
    Badge.M1 to R.drawable.badge_m1,
    Badge.M5 to R.drawable.badge_m5,
    Badge.TEN_MASTERED to R.drawable.badge_ten_mastered,
    Badge.M25 to R.drawable.badge_m25,
    Badge.M50 to R.drawable.badge_m50,
    Badge.M100 to R.drawable.badge_m100,
    Badge.M200 to R.drawable.badge_m200,
    Badge.M500 to R.drawable.badge_m500,
    // 复习次数（8）
    Badge.R10 to R.drawable.badge_r10,
    Badge.R50 to R.drawable.badge_r50,
    Badge.HUNDRED_REVIEWS to R.drawable.badge_hundred_reviews,
    Badge.R250 to R.drawable.badge_r250,
    Badge.R500 to R.drawable.badge_r500,
    Badge.R1000 to R.drawable.badge_r1000,
    Badge.R2000 to R.drawable.badge_r2000,
    Badge.R5000 to R.drawable.badge_r5000,
    // 连续学习（8）
    Badge.S3 to R.drawable.badge_s3,
    Badge.SEVEN_DAY_STREAK to R.drawable.badge_seven_day_streak,
    Badge.S14 to R.drawable.badge_s14,
    Badge.S21 to R.drawable.badge_s21,
    Badge.THIRTY_DAY_STREAK to R.drawable.badge_thirty_day_streak,
    Badge.S60 to R.drawable.badge_s60,
    Badge.S100 to R.drawable.badge_s100,
    Badge.S365 to R.drawable.badge_s365,
    // 单日爆发（5）
    Badge.D3 to R.drawable.badge_d3,
    Badge.D5 to R.drawable.badge_d5,
    Badge.TEN_IN_ONE_DAY to R.drawable.badge_ten_in_one_day,
    Badge.D20 to R.drawable.badge_d20,
    Badge.D30 to R.drawable.badge_d30,
    // 知识点清零（4）
    Badge.KNOWLEDGE_CLEARED to R.drawable.badge_knowledge_cleared,
    Badge.K3 to R.drawable.badge_k3,
    Badge.K5 to R.drawable.badge_k5,
    Badge.K10 to R.drawable.badge_k10,
    // 单词（5 + 7 = 12）
    Badge.FIRST_WORD to R.drawable.badge_first_word,
    Badge.W50 to R.drawable.badge_w50,
    Badge.HUNDRED_WORDS to R.drawable.badge_hundred_words,
    Badge.W200 to R.drawable.badge_w200,
    Badge.W500 to R.drawable.badge_w500,
    Badge.W1000 to R.drawable.badge_w1000,
    Badge.W2000 to R.drawable.badge_w2000,
    Badge.WD7 to R.drawable.badge_wd7,
    Badge.WD30 to R.drawable.badge_wd30,
    Badge.WD100 to R.drawable.badge_wd100,
    Badge.WA100 to R.drawable.badge_wa100,
    Badge.WA1000 to R.drawable.badge_wa1000,
    // 番茄专注（15，v0.0.125）
    Badge.FIRST_FOCUS to R.drawable.badge_first_focus,
    Badge.FOCUS_TEN to R.drawable.badge_focus_ten,
    Badge.FOCUS_FIFTY to R.drawable.badge_focus_fifty,
    Badge.FOCUS_HUNDRED to R.drawable.badge_focus_hundred,
    Badge.FOCUS_FIVE_HUNDRED to R.drawable.badge_focus_five_hundred,
    Badge.FOCUS_THOUSAND to R.drawable.badge_focus_thousand,
    Badge.FOCUS_HALF_HOUR to R.drawable.badge_focus_half_hour,
    Badge.FOCUS_HOUR to R.drawable.badge_focus_hour,
    Badge.FOCUS_TEN_HOURS to R.drawable.badge_focus_ten_hours,
    Badge.FIFTY_HOURS to R.drawable.badge_fifty_hours,
    Badge.HUNDRED_HOURS to R.drawable.badge_hundred_hours,
    Badge.FOCUS_DAYS_3 to R.drawable.badge_focus_days_3,
    Badge.FOCUS_DAYS_10 to R.drawable.badge_focus_days_10,
    Badge.FOCUS_DAYS_30 to R.drawable.badge_focus_days_30,
    Badge.FOCUS_DAYS_100 to R.drawable.badge_focus_days_100,
    // AI 问答（5，v0.0.125）
    Badge.FIRST_CHAT to R.drawable.badge_first_chat,
    Badge.CHAT_TEN to R.drawable.badge_chat_ten,
    Badge.CHAT_FIFTY to R.drawable.badge_chat_fifty,
    Badge.CHAT_HUNDRED to R.drawable.badge_chat_hundred,
    Badge.CHAT_TWO_HUNDRED to R.drawable.badge_chat_two_hundred,
    // 资料库（5，v0.0.125）
    Badge.FIRST_DOC to R.drawable.badge_first_doc,
    Badge.DOCS_FIVE to R.drawable.badge_docs_five,
    Badge.DOCS_TWENTY to R.drawable.badge_docs_twenty,
    Badge.DOCS_FIFTY to R.drawable.badge_docs_fifty,
    Badge.DOCS_HUNDRED to R.drawable.badge_docs_hundred,
    // 徽章收藏（5，v0.0.125）
    Badge.BADGE_ROOKIE to R.drawable.badge_badge_rookie,
    Badge.BADGE_COLLECTOR to R.drawable.badge_badge_collector,
    Badge.BADGE_MASTER to R.drawable.badge_badge_master,
    Badge.BADGE_ELDER to R.drawable.badge_badge_elder,
    Badge.BADGE_GALLERY to R.drawable.badge_badge_gallery,
    // 旧阶梯补长（9，v0.0.125）
    Badge.Q2000 to R.drawable.badge_q2000,
    Badge.Q3000 to R.drawable.badge_q3000,
    Badge.Q5000 to R.drawable.badge_q5000,
    Badge.M1000 to R.drawable.badge_m1000,
    Badge.M2000 to R.drawable.badge_m2000,
    Badge.M5000 to R.drawable.badge_m5000,
    Badge.R10000 to R.drawable.badge_r10000,
    Badge.R20000 to R.drawable.badge_r20000,
    Badge.S200 to R.drawable.badge_s200,
    Badge.S500 to R.drawable.badge_s500,
    Badge.S1000 to R.drawable.badge_s1000,
    Badge.D50 to R.drawable.badge_d50,
    Badge.D100 to R.drawable.badge_d100,
    Badge.K25 to R.drawable.badge_k25,
    Badge.K50 to R.drawable.badge_k50
)

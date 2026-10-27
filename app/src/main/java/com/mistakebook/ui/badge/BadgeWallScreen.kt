package com.mistakebook.ui.badge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
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

/**
 * 成就墙（v0.0.51 T10）。
 *
 * 网格卡片：已解锁亮色 + 解锁日期；未解锁置灰 + 当前进度 + 达成条件。
 * 未解锁态的进度由 [BadgeEngine] 用**当前统计**实时算（只读一次，
 * 不进数据库）；已解锁态显示库里的解锁时间。
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

    Scaffold(
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
        val progressList = stats?.let { s ->
            BadgeEngine.evaluate(s, java.time.LocalDate.now())
        }
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
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(progressList, key = { it.badge.key }) { progress ->
                val record = unlockedMap[progress.badge.key]
                BadgeCard(
                    badge = progress.badge,
                    current = progress.current,
                    unlocked = record != null,
                    unlockedAt = record?.unlockedAt
                )
            }
        }
    }
}

@Composable
private fun BadgeCard(
    badge: Badge,
    current: Int,
    unlocked: Boolean,
    unlockedAt: Long?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (unlocked) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.EmojiEvents,
                contentDescription = null,
                tint = if (unlocked) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                },
                modifier = Modifier.height(34.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = badge.title,
                style = MaterialTheme.typography.titleSmall,
                color = if (unlocked) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = badge.conditionText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            if (unlocked) {
                val stamp = unlockedAt?.let {
                    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(it))
                }.orEmpty()
                Text(
                    text = stringResource(R.string.badge_unlocked_on, stamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            } else if (badge.target > 0) {
                val fraction = (current.toFloat() / badge.target).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
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
    }
}

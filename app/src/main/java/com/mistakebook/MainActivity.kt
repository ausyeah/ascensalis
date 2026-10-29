package com.mistakebook

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mistakebook.data.prefs.SettingsSnapshot
import com.mistakebook.data.prefs.ThemeMode
import com.mistakebook.review.ReviewScheduler
import com.mistakebook.ui.common.RichText
import com.mistakebook.ui.theme.MistakeBookTheme
import kotlinx.coroutines.launch

/** 公式渲染探针页：只显示一段带公式的正文，用于真机肉眼确认渲染效果。 */
@androidx.compose.runtime.Composable
private fun ProbeRichTextScreen(mathRenderer: com.mistakebook.math.MathRenderer) {
    androidx.compose.foundation.layout.Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(stringResource(R.string.probe_math_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        RichText(
            text = "设 \$I_R = \\oint_L \\frac{y\\,dx - x\\,dy}{(x^2 + y^2)^2}\$，其中 \$L\$ 为 " +
                "\$x^2 + y^2 = \\frac{1}{R^2}\$，方向为逆时针方向，则 \$R \\to 0^+\$ 时，",
            mathRenderer = mathRenderer,
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.probe_math_display_label), style = MaterialTheme.typography.bodyMedium)
        RichText(
            text = "\$\$ \\int_0^1 x^2 \\, dx = \\frac{1}{3} \$\$",
            mathRenderer = mathRenderer,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

// 单 Activity 宿主：Navigation-Compose 驱动全部页面。
class MainActivity : ComponentActivity() {

    private val dueFilterRequest = androidx.compose.runtime.mutableIntStateOf(0)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        val container = (application as MistakeBookApp).container
        // 公式渲染自检：adb shell am start -n com.mistakebook/.MainActivity --ez probe_math true
        // 把整条链路的真实值打进 logcat，不用点界面就能定位「空白」到底卡在哪一步。
        if (BuildConfig.DEBUG && intent?.getBooleanExtra(EXTRA_PROBE_MATH, false) == true) {
            container.appScope.launch {
                val report = container.mathRenderer.diagnose()
                Log.e("MathProbe", "===== BEGIN =====\n$report===== END =====")
            }
        }
        if (BuildConfig.DEBUG && intent?.getBooleanExtra(EXTRA_PROBE_RENDER, false) == true) {
            container.appScope.launch {
                // 走真实的 RichText 渲染路径，把每一步的结果打出来
                val sample = "设 \$I_R = \\oint_L \\frac{y\\,dx - x\\,dy}{(x^2 + y^2)^2}\$，其中 \$L$ 为 \$x^2 + y^2 = \\frac{1}{R^2}\$，则"
                val inline = Regex("\\$([^$]+)\\$").findAll(sample).map { it.groupValues[1] }.toList()
                Log.e("MathProbe", "===== RENDER BEGIN =====")
                inline.forEach { latex ->
                    val rendered = container.mathRenderer.render(latex, false)
                    Log.e(
                        "MathProbe",
                        "  '$latex' -> ${if (rendered == null) "null(回退源码)" else "${rendered.bitmap.width}x${rendered.bitmap.height} @${rendered.fontPx}px"}"
                    )
                }
                Log.e("MathProbe", "===== RENDER END =====")
            }
        }
        container.appScope.launch {
            val snapshot = container.settingsStore.snapshotNow()
            // 启动路径必须 KEEP：CANCEL_AND_REENQUEUE 会把首次触发时间无限推后，
            // 每天在提醒时刻前打开 App 的用户永远收不到提醒（v0.0.60 修复）。
            ReviewScheduler.schedule(
                this@MainActivity,
                snapshot.reminderHour,
                snapshot.reminderMinute,
                androidx.work.ExistingPeriodicWorkPolicy.KEEP
            )
            // 单词书的每日目标提醒：与错题复习提醒同一时刻，
            // 少设一个时间开关，用户少困惑一次。
            com.mistakebook.wordbook.notify.WordbookReminderScheduler.schedule(
                this@MainActivity,
                snapshot.reminderHour,
                snapshot.reminderMinute,
                androidx.work.ExistingPeriodicWorkPolicy.KEEP
            )
        }
        if (intent?.getBooleanExtra(EXTRA_FILTER_DUE, false) == true) {
            dueFilterRequest.intValue += 1
        }
        val probeRender = BuildConfig.DEBUG && intent?.getBooleanExtra(EXTRA_PROBE_RENDER, false) == true
        setContent {
            val settings by container.settingsStore.settings.collectAsState(initial = SettingsSnapshot())
            val systemInDark = isSystemInDarkTheme()
            val isDark = when (settings.themeMode) {
                ThemeMode.SYSTEM -> systemInDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // 公式位图是透明背景的，前景色必须跟主题走：
            // 深色主题还用墨黑，公式贴到近黑背景上就是一片空白（v0.0.12 反馈）。
            LaunchedEffect(isDark) {
                container.mathRenderer.setForegroundColor(
                    if (isDark) com.mistakebook.math.MathRenderer.FG_DARK
                    else com.mistakebook.math.MathRenderer.FG_LIGHT
                )
            }
            MistakeBookTheme(darkTheme = isDark) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    if (probeRender) {
                        ProbeRichTextScreen(container.mathRenderer)
                    } else {
                        MistakeBookNavHost(container = container, filterDueRequest = dueFilterRequest.intValue)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_FILTER_DUE, false)) {
            dueFilterRequest.intValue += 1
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    companion object {
        const val EXTRA_FILTER_DUE = "extra_filter_due"
        const val EXTRA_PROBE_MATH = "probe_math"
        const val EXTRA_PROBE_RENDER = "probe_render"
    }
}

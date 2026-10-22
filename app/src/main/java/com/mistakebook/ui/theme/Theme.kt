package com.mistakebook.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// 经典浅色暖纸模式（Warm Paper）
private val PaperLightColors = lightColorScheme(
    primary = PaperPrimary,
    onPrimary = Color.White,
    primaryContainer = PaperPrimaryTint,
    onPrimaryContainer = PaperPrimaryDark,
    secondary = PaperTextSecondaryLight,
    onSecondary = Color.White,
    background = PaperBgLight,
    onBackground = PaperTextPrimaryLight,
    surface = PaperCardLight,
    onSurface = PaperTextPrimaryLight,
    surfaceVariant = Color(0xFFEFECE3),
    onSurfaceVariant = PaperTextMutedLight,
    outline = PaperBorderLight,
    error = PaperBad,
    onError = Color.White
)

// 深色墨砚夜读模式（Dark Ink Paper）
private val PaperDarkColors = darkColorScheme(
    primary = PaperPrimaryDarkTheme,
    // 琥珀是高亮色，上面的字必须压到近黑的暖棕才够对比度
    onPrimary = Color(0xFF2A1F06),
    primaryContainer = PaperPrimaryTintDark,
    onPrimaryContainer = Color(0xFFF0DDB4),
    secondary = PaperTextSecondaryDark,
    onSecondary = Color(0xFF2A1F06),
    background = PaperBgDark,
    onBackground = PaperTextPrimaryDark,
    surface = PaperCardDark,
    onSurface = PaperTextPrimaryDark,
    // 容器色阶按 M3 的 tone 递进（12 / 16 / 22 / 28），
    // 制造层次而不是靠阴影——深色下阴影几乎看不见
    surfaceContainerLowest = Color(0xFF0C0C0C),
    surfaceContainerLow = Color(0xFF161616),
    surfaceContainer = Color(0xFF1C1C1C),
    surfaceContainerHigh = Color(0xFF262523),
    surfaceContainerHighest = Color(0xFF31302D),
    surfaceVariant = Color(0xFF262523),
    onSurfaceVariant = PaperTextMutedDark,
    outline = PaperBorderDark,
    outlineVariant = Color(0xFF2A2825),
    error = PaperBadDark,
    onError = Color(0xFF3A211C)
)

/**
 * 排版。
 *
 * 两条硬规则：
 * 1. **中文行高要比英文再松一档**。M3 把中/日/韩归为「较高文字」，
 *    正文行高至少 1.4 倍字号才不挤——中文没有西文的下伸部留白，
 *    按英文规范排会显得糊成一片。
 * 2. **字号越大，字距越紧**。大标题若沿用正文的字距会松散得像没排过版，
 *    display 级收 -0.25~-0.5sp 是排版的常识，不是风格选择。
 */
private val MistakeBookTypography = Typography(
    titleLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.2).sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.01.sp
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 22.sp
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 18.sp
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        lineHeight = 16.sp
    ),
    // 番茄钟与统计里的大数字：等宽字形（tnum）让每秒跳动的数字不左右抖
    displayLarge = TextStyle(
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp,
        fontFeatureSettings = "tnum"
    )
)

val LocalDarkTheme = compositionLocalOf { false }

/**
 * 全局纸质化主题系统（支持浅色暖纸与深色墨砚自适应）。
 */
@Composable
fun MistakeBookTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val activity = LocalContextActivity()
    val view = LocalView.current
    val colorScheme = if (darkTheme) PaperDarkColors else PaperLightColors

    if (!view.isInEditMode) {
        SideEffect {
            val window = activity?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = MistakeBookTypography,
            content = content
        )
    }
}

@Composable
private fun LocalContextActivity(): Activity? {
    var context: android.content.Context = LocalContext.current
    while (context is android.content.ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

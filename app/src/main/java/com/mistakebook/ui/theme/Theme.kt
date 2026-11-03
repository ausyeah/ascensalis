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

// ==========================================================
// 色槽映射：语义槽位只有一套，明暗两份值。
// 哲学（逆向自 T3 Code）：单强调色、中性灰阶、层次靠描边不靠阴影。
// 槽位填得越全，界面越没有借口写裸色值。
// ==========================================================

internal val ZincLight = lightColorScheme(
    primary = PaperPrimary,
    onPrimary = Color.White,
    primaryContainer = PaperPrimaryTint,
    onPrimaryContainer = PaperPrimaryDark,
    // secondary/tertiary 收敛为中性面：单强调色体系里它们只做「次级灰」，
    // M3 的 FilledTonalButton 之类组件吃这两个槽，给彩色反而破坏克制
    secondary = PaperVariantLight,
    onSecondary = PaperTextPrimaryLight,
    secondaryContainer = PaperVariantLight,
    onSecondaryContainer = PaperTextPrimaryLight,
    tertiary = PaperVariantLight,
    onTertiary = PaperTextPrimaryLight,
    tertiaryContainer = PaperVariantLight,
    onTertiaryContainer = PaperTextPrimaryLight,
    background = PaperBgLight,
    onBackground = PaperTextPrimaryLight,
    surface = PaperBgLight,
    onSurface = PaperTextPrimaryLight,
    surfaceVariant = PaperVariantLight,
    onSurfaceVariant = PaperTextMutedLight,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    // 卡槽：浮在 #FCFCFC 底上的纯白卡（T3 浅色结构）。卡片统一用 low，
    // 深色对应的卡槽是 #111111（low）——两种明暗同一槽位语义
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF4F4F5),
    surfaceContainerHigh = Color(0xFFF4F4F5),
    surfaceContainerHighest = Color(0xFFEBEBEE),
    surfaceTint = Color.Transparent,
    outline = PaperBorderLight,
    outlineVariant = PaperBorderLight,
    inverseSurface = PaperTextPrimaryLight,
    inverseOnSurface = PaperBgLight,
    error = PaperBad,
    onError = Color.White,
    errorContainer = PaperBadBg,
    onErrorContainer = Color(0xFF9B0E17)
)

internal val ZincDark = darkColorScheme(
    primary = PaperPrimaryDarkTheme,
    onPrimary = Color.White,
    primaryContainer = PaperPrimaryTintDark,
    onPrimaryContainer = Color(0xFFBFD3FF),
    secondary = PaperVariantDark,
    onSecondary = PaperTextPrimaryDark,
    secondaryContainer = PaperVariantDark,
    onSecondaryContainer = PaperTextPrimaryDark,
    tertiary = PaperVariantDark,
    onTertiary = PaperTextPrimaryDark,
    tertiaryContainer = PaperVariantDark,
    onTertiaryContainer = PaperTextPrimaryDark,
    background = PaperBgDark,
    onBackground = PaperTextPrimaryDark,
    surface = PaperBgDark,
    onSurface = PaperTextPrimaryDark,
    // 表面阶梯从底到浮：0A(底) → 11(卡) → 14(控件) → 1C → 24，
    // 每级只差一线，「面浮在底上」的感觉全靠这条极窄的明度带
    surfaceContainerLowest = Color(0xFF0A0A0A),
    surfaceContainerLow = Color(0xFF111111),
    surfaceContainer = Color(0xFF141414),
    surfaceContainerHigh = Color(0xFF1C1C1C),
    surfaceContainerHighest = Color(0xFF242424),
    surfaceVariant = PaperVariantDark,
    onSurfaceVariant = PaperTextMutedDark,
    surfaceTint = Color.Transparent,
    outline = Color(0xFF1E1E1E),
    outlineVariant = PaperBorderDark,
    inverseSurface = PaperTextPrimaryDark,
    inverseOnSurface = PaperBgDark,
    error = PaperBadDark,
    onError = Color.White,
    errorContainer = PaperBadBgDark,
    onErrorContainer = Color(0xFFFFB3B6)
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
 *
 * 档位补齐到 M3 全集：组件（TopAppBar/ListItem/Badge…）默认吃
 * headlineSmall/titleSmall/labelMedium 等槽，缺省会让没显式指定
 * style 的文字退回 M3 默认值，与主题脱节。
 *
 * 用户可整体缩放字号与换字体，见 [appTypography]——基准值写在这里，
 * 倍数与字体在那边。这里只保留「设计口径」，不掺用户偏好。
 */
internal val ZincTypography = Typography(
    displayLarge = TextStyle(
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp,
        fontFeatureSettings = "tnum"
    ),
    displaySmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.3).sp
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.2).sp
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.2).sp
    ),
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
    titleSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp
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
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        lineHeight = 16.sp
    )
)

val LocalDarkTheme = compositionLocalOf { false }

/**
 * 全局主题（中性锌灰基调 + 单强调色 + 自选字号/字体/圆角）。
 *
 * @param accent 自选主题色（v0.0.76）。只替换 primary 一组色槽，
 *   背景/卡片/文字走中性锌灰两套基调——强调色是「强调」不是换肤。
 * @param fontScale 全局字号倍数（v0.0.94）。一处调，界面与对话正文一起变。
 * @param font 全局字体（v0.0.94）。只用系统自带字体，不打包字体文件。
 * @param corner 全局圆角倍数（v0.0.94）。M3 组件的默认形状全取自 shapes。
 */
@Composable
fun MistakeBookTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accent: com.mistakebook.data.prefs.AccentTone = com.mistakebook.data.prefs.AccentTone.DEFAULT,
    fontScale: com.mistakebook.data.prefs.FontScale = com.mistakebook.data.prefs.FontScale.DEFAULT,
    font: com.mistakebook.data.prefs.AppFont = com.mistakebook.data.prefs.AppFont.DEFAULT,
    corner: com.mistakebook.data.prefs.CornerStyle = com.mistakebook.data.prefs.CornerStyle.DEFAULT,
    content: @Composable () -> Unit
) {
    val activity = LocalContextActivity()
    val view = LocalView.current
    val base = if (darkTheme) ZincDark else ZincLight
    val palette = accent.palette()
    val colorScheme = if (accent == com.mistakebook.data.prefs.AccentTone.DEFAULT) {
        base
    } else {
        base.copy(
            primary = if (darkTheme) palette.dark else palette.light,
            // 暗色的自定义强调是高亮度浅色块（OLED 可读性口径），必须压近黑字；
            // 浅色主色深，用白字。按明暗分派而不是逐 tone 手调——8 个 tone 一个规则。
            onPrimary = if (darkTheme) Color(0xFF1A1A1E) else Color.White,
            primaryContainer = if (darkTheme) palette.darkContainer else palette.lightContainer,
            onPrimaryContainer = if (darkTheme) palette.darkOnContainer else palette.lightOnContainer
        )
    }

    if (!view.isInEditMode) {
        SideEffect {
            val window = activity?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = appTypography(fontScale.scale, fontFamilyOf(font)),
            shapes = zincShapes(corner),
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

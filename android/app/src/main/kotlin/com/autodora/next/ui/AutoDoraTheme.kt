package com.autodora.next.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.autodora.next.data.ThemeMode
import com.autodora.next.touch.SwipeSpeedPreset

@Composable
fun AutoDoraTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val useDark = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (useDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        useDark -> darkColorScheme()
        else -> lightColorScheme()
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !useDark
            insetsController.isAppearanceLightNavigationBars = !useDark
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

data class ChipColorStyle(
    val bg: Int,
    val fg: Int,
    val stroke: Int
)

/**
 * System Material 3 color palette for View-based floating overlays (mirrors AutoDoraTheme 1:1).
 * Uses Android 12+ Material You dynamic system colors when available, with clean M3 Light/Dark fallbacks,
 * plus harmonious thermal speed progression (Fast -> Slow: Crimson -> Amber -> Primary Blue -> Emerald).
 */
data class OverlayPalette(
    val isDark: Boolean,
    val containerBg: Int,
    val containerStroke: Int,
    val surfaceVariantBg: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val primary: Int,
    val onPrimary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val secondaryContainer: Int,
    val onSecondaryContainer: Int,
    val tertiary: Int,
    val onTertiary: Int,
    val tertiaryContainer: Int,
    val onTertiaryContainer: Int,
    val error: Int,
    val onError: Int,
    val errorContainer: Int,
    val onErrorContainer: Int,
    val chipOffBg: Int,
    val chipOffText: Int,
    val chipOffStroke: Int,
    val outline: Int,
    val orbStroke: Int
) {
    /**
     * Semantic Fast -> Slow 6-tier thermal color progression (2s / 3s / 4s / 5s / 6s / 7s)
     * with consistent 1dp outline across Light & Dark modes:
     *  - 极速 2s: Crimson Red
     *  - 快转 3s: Deep Orange
     *  - 敏捷 4s: Warm Amber Gold
     *  - 标准 5s: System Primary Blue/Accent
     *  - 舒缓 6s: Calm Teal
     *  - 稳健 7s: Calm Emerald Green
     */
    fun speedStyle(preset: SwipeSpeedPreset): ChipColorStyle = if (isDark) {
        when (preset) {
            SwipeSpeedPreset.TURBO_2S -> ChipColorStyle(
                bg = AndroidColor.rgb(122, 24, 28),
                fg = AndroidColor.rgb(255, 214, 214),
                stroke = AndroidColor.rgb(242, 139, 130)
            )
            SwipeSpeedPreset.FAST_3S -> ChipColorStyle(
                bg = AndroidColor.rgb(112, 52, 14),
                fg = AndroidColor.rgb(255, 224, 178),
                stroke = AndroidColor.rgb(255, 167, 77)
            )
            SwipeSpeedPreset.BRISK_4S -> ChipColorStyle(
                bg = AndroidColor.rgb(98, 76, 12),
                fg = AndroidColor.rgb(255, 238, 173),
                stroke = AndroidColor.rgb(255, 209, 77)
            )
            SwipeSpeedPreset.STANDARD -> ChipColorStyle(
                bg = primaryContainer,
                fg = onPrimaryContainer,
                stroke = primary
            )
            SwipeSpeedPreset.SMOOTH_6S -> ChipColorStyle(
                bg = AndroidColor.rgb(14, 78, 78),
                fg = AndroidColor.rgb(186, 244, 240),
                stroke = AndroidColor.rgb(77, 208, 200)
            )
            SwipeSpeedPreset.STEADY -> ChipColorStyle(
                bg = AndroidColor.rgb(22, 82, 46),
                fg = AndroidColor.rgb(200, 247, 212),
                stroke = AndroidColor.rgb(129, 199, 132)
            )
        }
    } else {
        when (preset) {
            SwipeSpeedPreset.TURBO_2S -> ChipColorStyle(
                bg = AndroidColor.rgb(255, 232, 230),
                fg = AndroidColor.rgb(165, 20, 26),
                stroke = AndroidColor.rgb(211, 47, 47)
            )
            SwipeSpeedPreset.FAST_3S -> ChipColorStyle(
                bg = AndroidColor.rgb(255, 238, 214),
                fg = AndroidColor.rgb(166, 68, 0),
                stroke = AndroidColor.rgb(230, 118, 20)
            )
            SwipeSpeedPreset.BRISK_4S -> ChipColorStyle(
                bg = AndroidColor.rgb(255, 246, 210),
                fg = AndroidColor.rgb(146, 98, 0),
                stroke = AndroidColor.rgb(214, 158, 10)
            )
            SwipeSpeedPreset.STANDARD -> ChipColorStyle(
                bg = primaryContainer,
                fg = onPrimaryContainer,
                stroke = primary
            )
            SwipeSpeedPreset.SMOOTH_6S -> ChipColorStyle(
                bg = AndroidColor.rgb(222, 245, 242),
                fg = AndroidColor.rgb(0, 105, 92),
                stroke = AndroidColor.rgb(0, 137, 123)
            )
            SwipeSpeedPreset.STEADY -> ChipColorStyle(
                bg = AndroidColor.rgb(226, 246, 232),
                fg = AndroidColor.rgb(27, 94, 32),
                stroke = AndroidColor.rgb(56, 142, 60)
            )
        }
    }
}

object OverlayThemeResolver {

    fun isDarkEffective(context: Context, mode: ThemeMode): Boolean = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> {
            val nightMask = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            nightMask == Configuration.UI_MODE_NIGHT_YES
        }
    }

    fun resolve(context: Context, mode: ThemeMode): OverlayPalette {
        val isDark = isDarkEffective(context, mode)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            resolveDynamic(context, isDark)
        } else {
            resolveStaticM3(isDark)
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        AndroidColor.argb(alpha, AndroidColor.red(color), AndroidColor.green(color), AndroidColor.blue(color))

    private fun resolveDynamic(context: Context, isDark: Boolean): OverlayPalette {
        fun sys(resId: Int): Int = context.getColor(resId)
        return if (isDark) {
            OverlayPalette(
                isDark = true,
                containerBg = withAlpha(sys(android.R.color.system_neutral1_900), 236),
                containerStroke = withAlpha(sys(android.R.color.system_neutral2_600), 170),
                surfaceVariantBg = withAlpha(sys(android.R.color.system_neutral2_800), 238),
                onSurface = sys(android.R.color.system_neutral1_100),
                onSurfaceVariant = sys(android.R.color.system_neutral2_200),
                primary = sys(android.R.color.system_accent1_200),
                onPrimary = sys(android.R.color.system_accent1_900),
                primaryContainer = sys(android.R.color.system_accent1_700),
                onPrimaryContainer = sys(android.R.color.system_accent1_100),
                secondaryContainer = sys(android.R.color.system_accent2_700),
                onSecondaryContainer = sys(android.R.color.system_accent2_100),
                tertiary = sys(android.R.color.system_accent3_200),
                onTertiary = sys(android.R.color.system_accent3_900),
                tertiaryContainer = sys(android.R.color.system_accent3_700),
                onTertiaryContainer = sys(android.R.color.system_accent3_100),
                error = AndroidColor.rgb(255, 180, 171),
                onError = AndroidColor.rgb(105, 0, 5),
                errorContainer = AndroidColor.rgb(147, 0, 10),
                onErrorContainer = AndroidColor.rgb(255, 218, 214),
                chipOffBg = withAlpha(sys(android.R.color.system_neutral2_800), 220),
                chipOffText = sys(android.R.color.system_neutral2_200),
                chipOffStroke = withAlpha(sys(android.R.color.system_neutral2_500), 150),
                outline = withAlpha(sys(android.R.color.system_neutral2_500), 190),
                orbStroke = withAlpha(sys(android.R.color.system_neutral1_100), 220)
            )
        } else {
            OverlayPalette(
                isDark = false,
                containerBg = withAlpha(sys(android.R.color.system_neutral1_50), 242),
                containerStroke = withAlpha(sys(android.R.color.system_neutral2_300), 210),
                surfaceVariantBg = withAlpha(sys(android.R.color.system_neutral2_100), 244),
                onSurface = sys(android.R.color.system_neutral1_900),
                onSurfaceVariant = sys(android.R.color.system_neutral2_700),
                primary = sys(android.R.color.system_accent1_600),
                onPrimary = AndroidColor.WHITE,
                primaryContainer = sys(android.R.color.system_accent1_100),
                onPrimaryContainer = sys(android.R.color.system_accent1_900),
                secondaryContainer = sys(android.R.color.system_accent2_100),
                onSecondaryContainer = sys(android.R.color.system_accent2_900),
                tertiary = sys(android.R.color.system_accent3_600),
                onTertiary = AndroidColor.WHITE,
                tertiaryContainer = sys(android.R.color.system_accent3_100),
                onTertiaryContainer = sys(android.R.color.system_accent3_900),
                error = AndroidColor.rgb(186, 26, 26),
                onError = AndroidColor.WHITE,
                errorContainer = AndroidColor.rgb(255, 218, 214),
                onErrorContainer = AndroidColor.rgb(65, 0, 2),
                chipOffBg = withAlpha(sys(android.R.color.system_neutral2_100), 235),
                chipOffText = sys(android.R.color.system_neutral2_700),
                chipOffStroke = withAlpha(sys(android.R.color.system_neutral2_400), 170),
                outline = withAlpha(sys(android.R.color.system_neutral2_400), 200),
                orbStroke = withAlpha(AndroidColor.WHITE, 240)
            )
        }
    }

    private fun resolveStaticM3(isDark: Boolean): OverlayPalette {
        return if (isDark) {
            OverlayPalette(
                isDark = true,
                containerBg = AndroidColor.argb(236, 27, 27, 31),
                containerStroke = AndroidColor.argb(170, 144, 144, 154),
                surfaceVariantBg = AndroidColor.argb(238, 46, 48, 56),
                onSurface = AndroidColor.rgb(228, 225, 230),
                onSurfaceVariant = AndroidColor.rgb(198, 197, 208),
                primary = AndroidColor.rgb(176, 198, 255),
                onPrimary = AndroidColor.rgb(0, 45, 110),
                primaryContainer = AndroidColor.rgb(34, 67, 138),
                onPrimaryContainer = AndroidColor.rgb(217, 226, 255),
                secondaryContainer = AndroidColor.rgb(64, 70, 89),
                onSecondaryContainer = AndroidColor.rgb(220, 226, 249),
                tertiary = AndroidColor.rgb(225, 187, 221),
                onTertiary = AndroidColor.rgb(66, 39, 66),
                tertiaryContainer = AndroidColor.rgb(90, 61, 89),
                onTertiaryContainer = AndroidColor.rgb(254, 215, 250),
                error = AndroidColor.rgb(255, 180, 171),
                onError = AndroidColor.rgb(105, 0, 5),
                errorContainer = AndroidColor.rgb(147, 0, 10),
                onErrorContainer = AndroidColor.rgb(255, 218, 214),
                chipOffBg = AndroidColor.argb(220, 50, 52, 60),
                chipOffText = AndroidColor.rgb(200, 202, 212),
                chipOffStroke = AndroidColor.argb(150, 144, 144, 154),
                outline = AndroidColor.argb(180, 144, 144, 154),
                orbStroke = AndroidColor.argb(225, 235, 235, 242)
            )
        } else {
            OverlayPalette(
                isDark = false,
                containerBg = AndroidColor.argb(242, 248, 249, 252),
                containerStroke = AndroidColor.argb(210, 196, 198, 208),
                surfaceVariantBg = AndroidColor.argb(244, 234, 237, 244),
                onSurface = AndroidColor.rgb(27, 27, 31),
                onSurfaceVariant = AndroidColor.rgb(68, 71, 79),
                primary = AndroidColor.rgb(52, 92, 168),
                onPrimary = AndroidColor.WHITE,
                primaryContainer = AndroidColor.rgb(217, 226, 255),
                onPrimaryContainer = AndroidColor.rgb(0, 25, 70),
                secondaryContainer = AndroidColor.rgb(220, 226, 249),
                onSecondaryContainer = AndroidColor.rgb(20, 27, 44),
                tertiary = AndroidColor.rgb(115, 84, 113),
                onTertiary = AndroidColor.WHITE,
                tertiaryContainer = AndroidColor.rgb(254, 215, 250),
                onTertiaryContainer = AndroidColor.rgb(43, 18, 43),
                error = AndroidColor.rgb(186, 26, 26),
                onError = AndroidColor.WHITE,
                errorContainer = AndroidColor.rgb(255, 218, 214),
                onErrorContainer = AndroidColor.rgb(65, 0, 2),
                chipOffBg = AndroidColor.argb(235, 233, 236, 243),
                chipOffText = AndroidColor.rgb(68, 71, 79),
                chipOffStroke = AndroidColor.argb(180, 166, 170, 182),
                outline = AndroidColor.argb(200, 166, 170, 182),
                orbStroke = AndroidColor.argb(240, 255, 255, 255)
            )
        }
    }
}

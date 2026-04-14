package io.github.derundevu.yaxc.presentation.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class YaxcThemeStyle {
    MidnightBlue,
    Graphite,
    LightSlate,
}

@Immutable
data class YaxcExtendedColors(
    val accentSoft: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val textMuted: Color,
    val cardBorder: Color,
    val backgroundGradient: List<Color>,
)

@Immutable
data class YaxcSpacing(
    val xs: androidx.compose.ui.unit.Dp,
    val sm: androidx.compose.ui.unit.Dp,
    val md: androidx.compose.ui.unit.Dp,
    val lg: androidx.compose.ui.unit.Dp,
    val xl: androidx.compose.ui.unit.Dp,
    val xxl: androidx.compose.ui.unit.Dp,
)

private val LocalYaxcExtendedColors = staticCompositionLocalOf {
    midnightBlueExtendedColors()
}

private val LocalYaxcSpacing = staticCompositionLocalOf {
    YaxcSpacing(
        xs = 0.dp,
        sm = 0.dp,
        md = 0.dp,
        lg = 0.dp,
        xl = 0.dp,
        xxl = 0.dp,
    )
}

object YaxcTheme {
    val extendedColors: YaxcExtendedColors
        @Composable
        @ReadOnlyComposable
        get() = LocalYaxcExtendedColors.current

    val spacing: YaxcSpacing
        @Composable
        @ReadOnlyComposable
        get() = LocalYaxcSpacing.current

    val backgroundBrush: Brush
        @Composable
        @ReadOnlyComposable
        get() = Brush.verticalGradient(extendedColors.backgroundGradient)
}

@Composable
fun YaxcTheme(
    style: YaxcThemeStyle = if (isSystemInDarkTheme()) {
        YaxcThemeStyle.MidnightBlue
    } else {
        YaxcThemeStyle.LightSlate
    },
    content: @Composable () -> Unit,
) {
    val colorScheme = when (style) {
        YaxcThemeStyle.MidnightBlue -> midnightBlueColorScheme()
        YaxcThemeStyle.Graphite -> graphiteColorScheme()
        YaxcThemeStyle.LightSlate -> lightSlateColorScheme()
    }

    val extendedColors = when (style) {
        YaxcThemeStyle.MidnightBlue -> midnightBlueExtendedColors()
        YaxcThemeStyle.Graphite -> graphiteExtendedColors()
        YaxcThemeStyle.LightSlate -> lightSlateExtendedColors()
    }

    val spacing = YaxcSpacing(
        xs = 4.dp,
        sm = 8.dp,
        md = 16.dp,
        lg = 20.dp,
        xl = 28.dp,
        xxl = 40.dp,
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = yaxcTypography(),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalYaxcExtendedColors provides extendedColors,
            LocalYaxcSpacing provides spacing,
            content = content,
        )
    }
}

private fun midnightBlueColorScheme() = darkColorScheme(
    primary = Color(0xFF79AFFF),
    onPrimary = Color(0xFF07111F),
    primaryContainer = Color(0xFF0E2340),
    onPrimaryContainer = Color(0xFFD8E7FF),
    secondary = Color(0xFF9CB7D9),
    onSecondary = Color(0xFF122235),
    secondaryContainer = Color(0xFF162B43),
    onSecondaryContainer = Color(0xFFD6E2F7),
    tertiary = Color(0xFF83D8C9),
    onTertiary = Color(0xFF05201B),
    tertiaryContainer = Color(0xFF123C35),
    onTertiaryContainer = Color(0xFFC7F5EC),
    background = Color(0xFF07111B),
    onBackground = Color(0xFFF3F7FC),
    surface = Color(0xFF0B1725),
    onSurface = Color(0xFFF3F7FC),
    surfaceVariant = Color(0xFF112131),
    onSurfaceVariant = Color(0xFFA3B2C4),
    surfaceContainer = Color(0xFF0D1827),
    surfaceContainerHigh = Color(0xFF132236),
    outline = Color(0xFF22384F),
    outlineVariant = Color(0xFF1A2D42),
    error = Color(0xFFFF7C82),
    onError = Color(0xFF2A0C11),
)

private fun graphiteColorScheme() = darkColorScheme(
    primary = Color(0xFFB2C2E6),
    onPrimary = Color(0xFF171C27),
    primaryContainer = Color(0xFF232C3B),
    onPrimaryContainer = Color(0xFFE5EBF8),
    secondary = Color(0xFFBEC5CF),
    onSecondary = Color(0xFF1E232B),
    secondaryContainer = Color(0xFF262D36),
    onSecondaryContainer = Color(0xFFE5E9EF),
    tertiary = Color(0xFF8FCED4),
    onTertiary = Color(0xFF122124),
    tertiaryContainer = Color(0xFF21373A),
    onTertiaryContainer = Color(0xFFD5F1F4),
    background = Color(0xFF111317),
    onBackground = Color(0xFFF4F5F7),
    surface = Color(0xFF171A20),
    onSurface = Color(0xFFF4F5F7),
    surfaceVariant = Color(0xFF20252D),
    onSurfaceVariant = Color(0xFFAAB1BC),
    surfaceContainer = Color(0xFF181D24),
    surfaceContainerHigh = Color(0xFF212630),
    outline = Color(0xFF343B48),
    outlineVariant = Color(0xFF282E39),
    error = Color(0xFFFF8A8A),
    onError = Color(0xFF320D0D),
)

private fun lightSlateColorScheme() = lightColorScheme(
    primary = Color(0xFF1F4E8C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD8E7FF),
    onPrimaryContainer = Color(0xFF0C223E),
    secondary = Color(0xFF405C7A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD8E7FB),
    onSecondaryContainer = Color(0xFF14273B),
    tertiary = Color(0xFF166A70),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFBDF0F2),
    onTertiaryContainer = Color(0xFF04282C),
    background = Color(0xFFF3F6FB),
    onBackground = Color(0xFF0F141C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F141C),
    surfaceVariant = Color(0xFFE4EAF4),
    onSurfaceVariant = Color(0xFF4B596B),
    surfaceContainer = Color(0xFFF8FAFD),
    surfaceContainerHigh = Color(0xFFEDF2F9),
    outline = Color(0xFFBEC8D6),
    outlineVariant = Color(0xFFD5DDE8),
    error = Color(0xFFB3202C),
    onError = Color(0xFFFFFFFF),
)

private fun midnightBlueExtendedColors() = YaxcExtendedColors(
    accentSoft = Color(0xFF193150),
    success = Color(0xFF4FD8AE),
    warning = Color(0xFFFFC468),
    danger = Color(0xFFFF7C82),
    textMuted = Color(0xFF8395A8),
    cardBorder = Color(0xFF1D3249),
    backgroundGradient = listOf(
        Color(0xFF09131F),
        Color(0xFF07111B),
        Color(0xFF0A1930),
    ),
)

private fun graphiteExtendedColors() = YaxcExtendedColors(
    accentSoft = Color(0xFF2A3444),
    success = Color(0xFF6FD1AA),
    warning = Color(0xFFF1BE72),
    danger = Color(0xFFFF8A8A),
    textMuted = Color(0xFF9199A4),
    cardBorder = Color(0xFF2D3643),
    backgroundGradient = listOf(
        Color(0xFF16191E),
        Color(0xFF111317),
        Color(0xFF1A1F28),
    ),
)

private fun lightSlateExtendedColors() = YaxcExtendedColors(
    accentSoft = Color(0xFFD8E5F6),
    success = Color(0xFF1B8B6C),
    warning = Color(0xFFB77408),
    danger = Color(0xFFB3202C),
    textMuted = Color(0xFF5F6D80),
    cardBorder = Color(0xFFDCE4F0),
    backgroundGradient = listOf(
        Color(0xFFF7FAFE),
        Color(0xFFF3F6FB),
        Color(0xFFEAF0F8),
    ),
)

private fun yaxcTypography() = Typography().run {
    copy(
        displayLarge = displayLarge.copy(
            color = Color.Unspecified,
            letterSpacing = (-1.5).sp,
        ),
        headlineMedium = headlineMedium.copy(
            color = Color.Unspecified,
            letterSpacing = (-0.5).sp,
        ),
        titleLarge = titleLarge.copy(
            color = Color.Unspecified,
            letterSpacing = (-0.25).sp,
        ),
        bodyLarge = bodyLarge.copy(
            color = Color.Unspecified,
            lineHeight = 24.sp,
        ),
        bodyMedium = bodyMedium.copy(
            color = Color.Unspecified,
            lineHeight = 21.sp,
        ),
    )
}

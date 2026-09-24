package uk.xa0.dsh.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.xa0.dsh.data.ThemeMode

/**
 * DSH's own design tokens.
 *
 * Names mirror the CSS custom properties in the web client (`--dsw-alias-*`,
 * `--dsw-specific-*`) rather than Material roles, so the mapping stays checkable
 * against the source. Values are the resolved hexes from `docs/research/design-system.md`.
 *
 * Two traps this encodes, both easy to get wrong:
 *  - `brand`/`ink` is NOT blue; the blue accent is [accent] (`state-business-primary`).
 *  - Assistant messages have no bubble — only user messages use [bubble].
 */
data class DshColors(
    val isDark: Boolean,
    // Backgrounds
    val bgBase: Color,
    val bgLayer1: Color,
    val bgLayer2: Color,
    val bgLayer3: Color,
    val sidebarFill: Color,
    val inputMajor: Color,
    val menu: Color,
    val tip: Color,
    val selector: Color,
    // Text
    val labelPrimary: Color,
    val labelSecondary: Color,
    val labelTertiary: Color,
    val labelCaption: Color,
    val labelDimmed: Color,
    /**
     * `--dsw-alias-label-primary-dimmed`. Despite the name it is *not* the dark
     * grey of [labelDimmed]: it is the primary label barely knocked back, so it
     * stays legible as body copy. It is what the queue preview and the goal
     * objective are painted with.
     */
    val labelPrimaryDimmed: Color,
    val ink: Color,
    val inkInverted: Color,
    val link: Color,
    val accent: Color,
    val accentTertiary: Color,
    /** `--dsw-static-deepseek-200`; the bright band inside the running shimmer. */
    val accentLight: Color,
    /** `--dsw-static-deepseek-500`; the shimmer's base, one step below [accent]. */
    val accentDeep: Color,
    // Borders
    val borderL1: Color,
    val borderL2: Color,
    val borderL3: Color,
    val borderL4: Color,
    // States
    val success: Color,
    val error: Color,
    val warn: Color,
    val warnTertiary: Color,
    /** `--dsw-static-deepseek-450`; the running-dot chase colour, not an alias token. */
    val ongoing: Color,
    // Fills
    val hover: Color,
    val active: Color,
    val bubble: Color,
    val bubbleHighlight: Color,
    val sidebarItemHover: Color,
    val sidebarItemActive: Color,
    val sendFill: Color,
    val sendGlyph: Color,
    // Code / markdown surfaces
    val codeBlock: Color,
    val codeBanner: Color,
    val inlineCode: Color,
    val scrollbar: Color,
    // Syntax
    val synConstant: Color,
    val synString: Color,
    val synComment: Color,
    val synKeyword: Color,
    val synParameter: Color,
    val synFunction: Color,
    val synPunctuation: Color,
)

private val DarkColors = DshColors(
    isDark = true,
    bgBase = Color(0xFF151517),
    bgLayer1 = Color(0xFF232324),
    bgLayer2 = Color(0xFF2C2C2E),
    bgLayer3 = Color(0xFF353638),
    sidebarFill = Color(0xFF1B1B1C),
    inputMajor = Color(0xFF2C2C2E),
    menu = Color(0xFF353638),
    tip = Color(0xFF353638),
    selector = Color(0xFF353638),
    labelPrimary = Color(0xFFF9FAFB),
    labelSecondary = Color(0xFFCFD3D6),
    labelTertiary = Color(0xFFADB2B8),
    labelCaption = Color(0xFF81858C),
    labelDimmed = Color(0xFF43454A),
    labelPrimaryDimmed = Color(0xFFEBEEF2),
    ink = Color(0xFFF9FAFB),
    inkInverted = Color(0xFF353638),
    link = Color(0xFF679EFE),
    accent = Color(0xFF679EFE),
    accentTertiary = Color(0xFF34415B),
    accentLight = Color(0xFFD3E2FF),
    accentDeep = Color(0xFF4176E6),
    borderL1 = Color(0x0FFFFFFF),
    borderL2 = Color(0x1FFFFFFF),
    borderL3 = Color(0x29FFFFFF),
    borderL4 = Color(0x33FFFFFF),
    success = Color(0xFF22C55E),
    error = Color(0xFFF25A5A),
    warn = Color(0xFFF59E0B),
    warnTertiary = Color(0xFF27241F),
    ongoing = Color(0xFF5686FE),
    hover = Color(0x14FFFFFF),
    active = Color(0x24FFFFFF),
    bubble = Color(0xFF2C2C2E),
    bubbleHighlight = Color(0xFF43454A),
    sidebarItemHover = Color(0xFF2C2C2E),
    sidebarItemActive = Color(0xFF43454A),
    sendFill = Color(0xFF679EFE),
    sendGlyph = Color(0xFF0F1115),
    codeBlock = Color(0xFF1B1B1C),
    codeBanner = Color(0xFF2C2C2E),
    inlineCode = Color(0xFF292929),
    scrollbar = Color(0xFF3C3C3D),
    synConstant = Color(0xFF4DABF7),
    synString = Color(0xFF69DB7C),
    synComment = Color(0xFFADB5BD),
    synKeyword = Color(0xFFFAA2C1),
    synParameter = Color(0xFFFFA94D),
    synFunction = Color(0xFFB197FC),
    synPunctuation = Color(0xFFCED4DA),
)

private val LightColors = DshColors(
    isDark = false,
    bgBase = Color(0xFFFFFFFF),
    bgLayer1 = Color(0xFFFFFFFF),
    bgLayer2 = Color(0xFFFFFFFF),
    bgLayer3 = Color(0xFFFFFFFF),
    sidebarFill = Color(0xFFF9FAFB),
    inputMajor = Color(0xFFFFFFFF),
    menu = Color(0xFFFFFFFF),
    tip = Color(0xFFF9FAFB),
    selector = Color(0xFFF9FAFB),
    labelPrimary = Color(0xFF0F1115),
    labelSecondary = Color(0xFF61666B),
    labelTertiary = Color(0xFF81858C),
    labelCaption = Color(0xFFADB2B8),
    labelDimmed = Color(0xFFE1E5EE),
    labelPrimaryDimmed = Color(0xFF151517),
    ink = Color(0xFF0F1115),
    inkInverted = Color(0xFFFFFFFF),
    link = Color(0xFF4176E6),
    accent = Color(0xFF4176E6),
    accentTertiary = Color(0xFFE4EDFD),
    accentLight = Color(0xFFD3E2FF),
    accentDeep = Color(0xFF4176E6),
    borderL1 = Color(0x0A000000),
    borderL2 = Color(0x1A000000),
    borderL3 = Color(0x1F000000),
    borderL4 = Color(0x29000000),
    success = Color(0xFF22C55E),
    error = Color(0xFFEC1313),
    warn = Color(0xFFF59E0B),
    warnTertiary = Color(0xFFFEF5E7),
    ongoing = Color(0xFF5686FE),
    hover = Color(0x0F263148),
    active = Color(0x1A263148),
    bubble = Color(0xFFEDF3FE),
    bubbleHighlight = Color(0xFFD3E2FF),
    sidebarItemHover = Color(0xFFF1F3F5),
    sidebarItemActive = Color(0xFFEBEEF2),
    sendFill = Color(0xFF4176E6),
    sendGlyph = Color(0xFFFFFFFF),
    codeBlock = Color(0xFFF9FAFB),
    codeBanner = Color(0xFFF9FAFB),
    inlineCode = Color(0xFFFAFAFA),
    scrollbar = Color(0xFFE5E5E5),
    synConstant = Color(0xFF1C7ED6),
    synString = Color(0xFF2F9E44),
    synComment = Color(0xFF868E96),
    synKeyword = Color(0xFFD6336C),
    synParameter = Color(0xFFE8590C),
    synFunction = Color(0xFF6741D9),
    synPunctuation = Color(0xFF495057),
)

/** Spacing lives on the 2dp grid the web client uses. */
object DshSpacing {
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val sm: Dp = 6.dp
    val md: Dp = 8.dp
    val lg: Dp = 12.dp
    val xl: Dp = 16.dp
    val xxl: Dp = 24.dp
    val xxxl: Dp = 32.dp
}

/** Border-radius inventory, verbatim from the CSS. */
object DshRadius {
    val sm: Dp = 6.dp
    val md: Dp = 8.dp
    val lg: Dp = 10.dp
    val card: Dp = 12.dp
    val thumb: Dp = 16.dp
    val bubble: Dp = 22.dp
    val panel: Dp = 24.dp
    val pill: Dp = 999.dp
}

private val DshTypography = Typography(
    // The markdown "content ladder". The web default is 14/24.
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 22.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 20.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 24.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 26.sp,
        lineHeight = 32.sp,
    ),
)

/** Named styles the transcript and chrome use directly. */
object DshType {
    val messageBody = TextStyle(fontSize = 14.sp, lineHeight = 24.sp)
    val userBubble = TextStyle(fontSize = 14.sp, lineHeight = 24.sp)
    val bodyLarge = TextStyle(fontSize = 14.sp, lineHeight = 24.sp)
    val bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 20.sp)
    val bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp)
    val labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
    val labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    val titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
    val titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
    val titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    val rowTitle = TextStyle(fontSize = 13.sp, lineHeight = 24.sp, color = Color.Unspecified)
    val rowSummary = TextStyle(fontSize = 13.sp, lineHeight = 24.sp)
    val meta = TextStyle(fontSize = 13.sp, lineHeight = 24.sp)
    val micro = TextStyle(fontSize = 11.sp, lineHeight = 14.sp)
    val code = TextStyle(fontSize = 11.sp, lineHeight = 19.sp, fontFamily = FontFamily.Monospace)
    val codeSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace)
    val inlineCode = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    val heading1 = TextStyle(fontSize = 21.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
    val heading2 = TextStyle(fontSize = 18.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
    val heading3 = TextStyle(fontSize = 16.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
}

val LocalDshColors: ProvidableCompositionLocal<DshColors> = staticCompositionLocalOf { DarkColors }

/** Material roles mapped from DSH tokens for the few M3 components in use. */
private fun DshColors.toMaterialScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = sendGlyph,
        background = bgBase,
        onBackground = labelPrimary,
        surface = bgBase,
        onSurface = labelPrimary,
        surfaceVariant = menu,
        onSurfaceVariant = labelSecondary,
        outline = borderL3,
        error = error,
        onError = Color.White,
    )
}

object DshTheme {
    val colors: DshColors
        @Composable get() = LocalDshColors.current
}

@Composable
fun DshTheme(
    themeMode: String = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        else -> isSystemInDarkTheme()
    }
    val palette = if (dark) DarkColors else LightColors
    CompositionLocalProvider(LocalDshColors provides palette) {
        MaterialTheme(
            colorScheme = palette.toMaterialScheme(),
            typography = DshTypography,
            content = content,
        )
    }
}

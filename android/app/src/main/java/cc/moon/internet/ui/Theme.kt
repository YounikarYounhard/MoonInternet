package cc.moon.internet.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import cc.moon.internet.R

/** The desktop palette, carried over 1:1 — deep violet, lunar, no blue. */
object Moon {
    // These three are user-settable in Settings → Оформление, so they are observable state:
    // a plain val would store the choice and never repaint.
    var Accent       by mutableStateOf(Color(0xFF9D7BFF))
    var WinBg        by mutableStateOf(Color(0xFF0D0A18))
    var TextPrimary  by mutableStateOf(Color(0xFFECE9F5))

    val SidebarBg    = Color(0xFF0A0814)
    val Card         = Color(0xFF181229)
    val CardHover    = Color(0xFF221A3A)
    val ChipBg       = Color(0xFF1B1530)
    val BorderSoft   = Color(0xFF2C2545)
    val AccentText   = Color(0xFFB9A7FF)
    val Green        = Color(0xFF34D399)
    val Danger       = Color(0xFFFF6B8A)
    val TextSecondary= Color(0xFF9A93B5)
    val TextMuted    = Color(0xFF6E6790)
    val SectionLabel = Color(0xFF7C74A0)
    val HomeCard     = Color(0xFF150B30)

    /** Night-sky gradient behind the connect button, same stops as the WPF build. */
    val HomeGradient = Brush.verticalGradient(
        0.00f to Color(0xFF2A1466),
        0.42f to Color(0xFF1A0B44),
        1.00f to Color(0xFF12082E),
    )
}

private val MoonColors = darkColorScheme(
    primary = Moon.Accent,
    onPrimary = Color.White,
    secondary = Moon.AccentText,
    background = Moon.WinBg,
    onBackground = Moon.TextPrimary,
    surface = Moon.Card,
    onSurface = Moon.TextPrimary,
    surfaceVariant = Moon.ChipBg,
    onSurfaceVariant = Moon.TextSecondary,
    outline = Moon.BorderSoft,
    error = Moon.Danger,
)

/**
 * The desktop build's default font is Comic Sans MS, which Microsoft's licence does not allow
 * bundling. Shantell Sans (OFL) is the closest free stand-in that actually ships Cyrillic —
 * Comic Neue, the obvious first choice, has none at all and would render every Russian
 * string in a fallback face.
 *
 * It is a variable font, so the weights come from the wght axis rather than separate files.
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun comic(weight: Int) = Font(
    R.font.comic,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val MoonFont = FontFamily(comic(400), comic(500), comic(600), comic(700))

private val MoonTypography = Typography(
    headlineMedium = TextStyle(fontFamily = MoonFont, fontSize = 26.sp, fontWeight = FontWeight.Bold),
    titleLarge     = TextStyle(fontFamily = MoonFont, fontSize = 20.sp, fontWeight = FontWeight.Bold),
    titleMedium    = TextStyle(fontFamily = MoonFont, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge      = TextStyle(fontFamily = MoonFont, fontSize = 15.sp),
    bodyMedium     = TextStyle(fontFamily = MoonFont, fontSize = 14.sp),
    bodySmall      = TextStyle(fontFamily = MoonFont, fontSize = 12.sp),
    labelLarge     = TextStyle(fontFamily = MoonFont, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
    labelMedium    = TextStyle(fontFamily = MoonFont, fontSize = 12.sp),
    labelSmall     = TextStyle(fontFamily = MoonFont, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold),
)

/** Applies the palette the user picked; called once from the activity as state loads. */
fun applyTheme(accentHex: String, bgHex: String, textHex: String) {
    runCatching { Moon.Accent = Color(accentHex.toLong(16)) }
    runCatching { Moon.WinBg = Color(bgHex.toLong(16)) }
    runCatching { Moon.TextPrimary = Color(textHex.toLong(16)) }
}

@Composable
fun MoonTheme(fontName: String = "comic", content: @Composable () -> Unit) {
    val family = if (fontName == "system") FontFamily.Default else MoonFont
    // The app is dark-only by design — the whole look is built around the night sky.
    @Suppress("UNUSED_EXPRESSION") isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = MoonColors.copy(primary = Moon.Accent, background = Moon.WinBg),
        typography = MoonTypography,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = family),
            content = content,
        )
    }
}

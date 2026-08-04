package cc.moon.internet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import cc.moon.internet.R

/**
 * The desktop's shared XAML styles, one Composable each. Sizes and paddings are copied from
 * App.xaml / SettingsView.xaml so a screen ported from XAML lines up without re-tuning.
 */

/** One place for the version, fed by versionName in build.gradle.kts. */
val APP_VERSION: String get() = cc.moon.internet.BuildConfig.VERSION_NAME

/**
 * RU/EN pill, level with the Settings title — the same control the desktop puts in the same spot.
 *
 * Resources are picked at context creation, so there is no live swap the way WPF does it with
 * DynamicResource: the activity is recreated instead, which lands on the same screen and reads
 * as an instant change.
 */
@Composable
fun LanguageToggle() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val current = cc.moon.internet.data.Lang.effective(ctx)
    Surface(shape = RoundedCornerShape(9.dp), color = Moon.Card,
            border = androidx.compose.foundation.BorderStroke(1.dp, Moon.BorderSoft)) {
        Row(Modifier.padding(3.dp)) {
            listOf("ru" to "RU", "en" to "EN").forEach { (tag, label) ->
                val active = current == tag
                Surface(
                    onClick = {
                        if (!active) {
                            cc.moon.internet.data.Lang.save(ctx, tag)
                            (ctx as? android.app.Activity)?.recreate()
                        }
                    },
                    shape = RoundedCornerShape(7.dp),
                    color = if (active) Moon.Accent.copy(alpha = 0.18f) else Color.Transparent,
                ) {
                    Text(label, Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                         color = if (active) Moon.AccentText else Moon.TextSecondary,
                         fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * Empty room the floating bottom nav needs at the end of a scrolling page.
 *
 * The bar has no background of its own around the card — the page scrolls underneath it — so
 * nothing reserves this space in the layout. Every LazyColumn has to add it as contentPadding
 * or its last row ends up parked under the bar with no way to reach it.
 *
 * 8 + 68 + 8 is the bar's own box; the inset is the phone's back/home buttons below it.
 */
@Composable
fun bottomNavSpace(): androidx.compose.ui.unit.Dp =
    84.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()


/**
 * Subscription traffic/expiry, in whatever form the user picked: text, bars or dots.
 *
 * The panel hands us bytes and a timestamp; the text form throws that away, so Subscription keeps
 * the two fractions and this draws them. Shared by Home and Servers so both plates read the same.
 */
@Composable
fun SubMeter(
    trafficText: String,
    expiryText: String,
    trafficFraction: Double,
    expiryFraction: Double,
    style: String,
) {
    // Nothing to meter on an unlimited plan with no expiry — a bar of nothing and ten dots that
    // never move say less than the plain "458 ГБ / ∞" the text style already gives.
    val meterable = trafficFraction >= 0 || expiryFraction >= 0
    when (if (meterable) style else "text") {
        "bar" -> Column(Modifier.padding(top = 6.dp, end = 14.dp)) {
            if (trafficFraction >= 0) MeterBar(trafficFraction, meterColor(trafficFraction), 5.dp)
            if (expiryFraction >= 0) {
                Spacer(Modifier.height(4.dp))
                MeterBar(expiryFraction, Moon.Accent, 3.dp)
            }
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Download, null, tint = Moon.TextMuted, modifier = Modifier.size(10.dp))
                Text(" $trafficText", color = Moon.TextMuted, fontSize = 10.5.sp)
                Spacer(Modifier.width(10.dp))
                Icon(Icons.Filled.Event, null, tint = Moon.TextMuted, modifier = Modifier.size(10.dp))
                Text(" $expiryText", color = Moon.TextMuted, fontSize = 10.5.sp)
            }
        }
        "dots" -> Column(Modifier.padding(top = 5.dp)) {
            if (trafficFraction >= 0) MeterDots(trafficFraction, Moon.Accent, trafficText)
            if (expiryFraction >= 0) {
                if (trafficFraction >= 0) Spacer(Modifier.height(4.dp))
                MeterDots(expiryFraction, Moon.AccentText, expiryText)
            }
        }
        else -> Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Download, null, tint = Moon.TextSecondary, modifier = Modifier.size(11.dp))
            Text(" $trafficText", color = Moon.TextSecondary, fontSize = 11.5.sp)
            Spacer(Modifier.width(12.dp))
            Icon(Icons.Filled.Event, null, tint = Moon.TextSecondary, modifier = Modifier.size(11.dp))
            Text(" $expiryText", color = Moon.TextSecondary, fontSize = 11.5.sp)
        }
    }
}

/** Green while there is room, amber past 75%, red past 90% — the usual traffic-light read. */
private fun meterColor(f: Double) = when {
    f >= 0.9 -> Moon.Danger
    f >= 0.75 -> Color(0xFFE8B339)
    else -> Moon.Green
}

@Composable
private fun MeterBar(fraction: Double, color: Color, height: androidx.compose.ui.unit.Dp) {
    Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(Moon.BorderSoft)) {
        Box(Modifier.fillMaxWidth(fraction.toFloat().coerceIn(0f, 1f)).fillMaxHeight()
                .clip(RoundedCornerShape(height / 2)).background(color))
    }
}

@Composable
private fun MeterDots(fraction: Double, color: Color, label: String) {
    // dots show what is LEFT, so a full row is a fresh plan
    val lit = if (fraction < 0) 10 else Math.round((1 - fraction.coerceIn(0.0, 1.0)) * 10).toInt()
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(10) { i ->
            Box(Modifier.padding(end = 3.dp).size(5.dp).clip(CircleShape)
                    .background(if (i < lit) color else Moon.BorderSoft))
        }
        Text(label, color = Moon.TextMuted, fontSize = 10.5.sp, modifier = Modifier.padding(start = 5.dp))
    }
}

/** HubCard: 38dp rounded icon tile + title + subtitle + chevron. */
@Composable
fun HubCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp), color = Moon.Card,
        border = androidx.compose.foundation.BorderStroke(1.dp, Moon.BorderSoft),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(15.dp, 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xFF2C2058)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = Moon.AccentText, modifier = Modifier.size(19.dp)) }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(title, color = Moon.TextPrimary, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = Moon.TextSecondary, fontSize = 11.5.sp,
                     maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            Icon(Icons.Filled.ChevronRight, null, tint = Moon.TextSecondary, modifier = Modifier.size(18.dp))
        }
    }
}

/** Page header: back arrow + title, as every settings sub-page has. */
@Composable
fun PageHeader(title: String, big: Boolean = true, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack, Modifier.size(34.dp)) {
            Icon(Icons.Filled.ArrowBack, stringResource(R.string.routingscreen_001), tint = Moon.TextPrimary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(6.dp))
        Text(title, fontSize = if (big) 24.sp else 22.sp, fontWeight = FontWeight.Bold, color = Moon.TextPrimary)
    }
}

/** Section label above a card: "ТЕМА", "ЦВЕТА", "ПРОТОКОЛ"… */
@Composable
fun SectionLabel(text: String, top: Int = 18) {
    Text(text, color = Moon.SectionLabel, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold,
         modifier = Modifier.padding(start = 4.dp, top = top.dp, bottom = 8.dp))
}

/** The rounded card every settings group lives in. */
@Composable
fun MoonCard(padding: Int = 6, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp), color = Moon.Card,
        border = androidx.compose.foundation.BorderStroke(1.dp, Moon.BorderSoft),
        modifier = Modifier.fillMaxWidth(),
    ) { Column(Modifier.padding(padding.dp), content = content) }
}

/** Hairline between rows inside a card, inset like the XAML one. */
@Composable
fun RowDivider(inset: Int = 12) =
    Box(Modifier.fillMaxWidth().padding(horizontal = inset.dp).height(1.dp).background(Moon.BorderSoft))

/** Title + subtitle + switch. Text is weighted so it never runs under the switch. */
@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = Moon.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Text(subtitle, color = Moon.TextSecondary, fontSize = 12.sp, lineHeight = 16.sp,
                     modifier = Modifier.padding(top = 2.dp))
            }
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = Moon.Accent,
                uncheckedThumbColor = Moon.TextSecondary, uncheckedTrackColor = Moon.ChipBg,
                uncheckedBorderColor = Moon.BorderSoft,
            ),
        )
    }
}

/** Title + subtitle + chevron, navigates somewhere. */
@Composable
fun NavRow(title: String, subtitle: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Moon.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Text(subtitle, color = Moon.TextSecondary, fontSize = 12.sp,
                     modifier = Modifier.padding(top = 2.dp))
            }
        }
        Icon(Icons.Filled.ChevronRight, null, tint = Moon.TextSecondary, modifier = Modifier.size(18.dp))
    }
}

/** Label on the left, accent value on the right. */
@Composable
fun ValueRow(title: String, value: String, inset: Int = 14) {
    Row(Modifier.fillMaxWidth().padding(horizontal = inset.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Moon.TextPrimary, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
        Text(value, color = Moon.AccentText, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
             maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Chips that wrap, like the XAML WrapPanel — not a fixed 3-per-row grid, which is what made
 * the protocol filter look cramped.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipFlow(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (id, label) -> Chip(label, id == selected) { onPick(id) } }
    }
}

@Composable
fun Chip(text: String, active: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (active) Moon.Accent.copy(alpha = 0.25f) else Moon.ChipBg,
        border = if (active) androidx.compose.foundation.BorderStroke(1.dp, Moon.Accent) else null,
    ) {
        Text(text, Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
             color = if (active) Moon.AccentText else Moon.TextSecondary, fontSize = 12.5.sp)
    }
}

/** Equal-width segmented control (UniformGrid Rows=1 in XAML). */
@Composable
fun Segmented(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    Surface(shape = RoundedCornerShape(10.dp), color = Moon.Card,
            border = androidx.compose.foundation.BorderStroke(1.dp, Moon.BorderSoft),
            modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(3.dp)) {
            options.forEach { (id, label) ->
                val active = id == selected
                Surface(
                    onClick = { onPick(id) },
                    shape = RoundedCornerShape(8.dp),
                    color = if (active) Moon.Accent.copy(alpha = 0.18f) else Color.Transparent,
                    border = if (active) androidx.compose.foundation.BorderStroke(1.dp, Moon.Accent) else null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(label, Modifier.padding(vertical = 7.dp),
                         color = if (active) Moon.Accent else Moon.TextSecondary, fontSize = 12.5.sp,
                         textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}

/** Dark text field, the DarkTextBox style. */
@Composable
fun MoonTextField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = "",
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        placeholder = { Text(placeholder, color = Moon.TextMuted, fontSize = 12.5.sp) },
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, fontFamily = MoonFont),
        shape = RoundedCornerShape(10.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Moon.ChipBg, unfocusedContainerColor = Moon.ChipBg,
            focusedTextColor = Moon.TextPrimary, unfocusedTextColor = Moon.TextPrimary,
            focusedBorderColor = Moon.Accent, unfocusedBorderColor = Moon.BorderSoft,
            cursorColor = Moon.Accent,
        ),
        modifier = modifier,
    )
}

/** Secondary (outlined) button. */
@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(11.dp), color = Moon.Card,
            border = androidx.compose.foundation.BorderStroke(1.dp, Moon.BorderSoft), modifier = modifier) {
        Text(text, Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
             color = Moon.TextPrimary, fontSize = 13.sp,
             textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** Filled accent button. */
@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(11.dp), color = Moon.Accent, modifier = modifier) {
        Text(text, Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
             color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
             textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** Colour swatch strip used on the appearance page. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Swatches(colors: List<String>, selected: String, round: Boolean, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        colors.forEach { hex ->
            val shape = if (round) CircleShape else RoundedCornerShape(7.dp)
            Box(
                Modifier.size(28.dp).clip(shape)
                    .background(runCatching { Color(hex.toLong(16)) }.getOrDefault(Moon.Card))
                    .border(
                        if (hex.equals(selected, true)) 2.dp else 1.dp,
                        if (hex.equals(selected, true)) Moon.TextPrimary else Color(0x50FFFFFF),
                        shape,
                    )
                    .clickable { onPick(hex) },
            )
        }
    }
}

/** Body paragraph on the legal pages. */
@Composable
fun Paragraph(text: String, top: Int = 0) {
    Text(text, Modifier.padding(top = top.dp),
         color = Moon.TextSecondary, fontSize = 13.sp, lineHeight = 20.sp)
}

/** Bold sub-heading inside a legal page card. */
@Composable
fun SubHeading(text: String) {
    Text(text, Modifier.padding(top = 14.dp, bottom = 4.dp),
         color = Moon.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
}

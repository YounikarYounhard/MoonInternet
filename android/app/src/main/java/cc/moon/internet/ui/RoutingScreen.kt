package cc.moon.internet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.moon.internet.core.RoutingProfile
import androidx.compose.ui.res.stringResource
import cc.moon.internet.R

/**
 * Routing, laid out like the desktop page: geo files, the INCY/HAPP/custom picker, a search box
 * and the three rule buckets (DIRECT / PROXY / BLOCK) as collapsible chip lists.
 */
@Composable
fun RoutingScreen(
    profile: RoutingProfile?,
    source: String,
    geoipInfo: String,
    geositeInfo: String,
    geoBusy: Boolean,
    geoStatus: String,
    onBack: () -> Unit,
    onSource: (String) -> Unit,
    onRefreshGeo: () -> Unit,
    onAddRule: (String) -> Unit,
    onRemoveRule: (String, String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var open by remember { mutableStateOf(setOf("direct", "proxy", "block")) }
    val custom = source == "custom"

    fun filtered(list: List<String>) =
        if (query.isBlank()) list else list.filter { it.contains(query, true) }

    // LazyListScope is not a composable scope, so bucket captions are resolved up here.
    val directHint = stringResource(R.string.routingscreen_006)
    val proxyHint = stringResource(R.string.routingscreen_007)
    val blockHint = stringResource(R.string.routingscreen_008)

    Column(Modifier.fillMaxSize().background(Moon.WinBg)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack, Modifier.size(34.dp)) {
                Icon(Icons.Filled.ArrowBack, stringResource(R.string.routingscreen_001), tint = Moon.TextPrimary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.routingscreen_002), fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Moon.TextPrimary)
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = bottomNavSpace()),
        ) {

        // ---- geo files ----------------------------------------------------
        item {
            Card {
                Row(Modifier.fillMaxWidth().padding(14.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Public, null, tint = Moon.Accent, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.routingscreen_003), color = Moon.TextPrimary, fontSize = 14.sp,
                         fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (geoBusy) CircularProgressIndicator(Modifier.size(18.dp), color = Moon.Accent, strokeWidth = 2.dp)
                    else IconButton(onRefreshGeo, Modifier.size(30.dp)) {
                        Icon(Icons.Filled.Refresh, stringResource(R.string.settingsscreen_086), tint = Moon.TextSecondary, modifier = Modifier.size(17.dp))
                    }
                }
                KeyValue("geoip.dat", geoipInfo)
                Divider()
                KeyValue("geosite.dat", geositeInfo)
                if (geoStatus.isNotBlank()) {
                    Text(geoStatus, Modifier.padding(14.dp, 0.dp, 14.dp, 12.dp),
                         color = Moon.AccentText, fontSize = 11.5.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        // ---- profile source ------------------------------------------------
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
                Text(stringResource(R.string.routingscreen_004), color = Moon.TextSecondary, fontSize = 13.sp)
                Spacer(Modifier.width(10.dp))
                Surface(shape = RoundedCornerShape(10.dp), color = Moon.Card,
                        border = androidx.compose.foundation.BorderStroke(1.dp, Moon.BorderSoft)) {
                    Row(Modifier.padding(3.dp)) {
                        listOf("incy" to "INCY", "happ" to "HAPP", "custom" to stringResource(R.string.settingsscreen_050)).forEach { (id, text) ->
                            val active = source == id
                            Surface(
                                onClick = { onSource(id) },
                                shape = RoundedCornerShape(8.dp),
                                color = if (active) Moon.Accent.copy(alpha = 0.18f) else Color.Transparent,
                                border = if (active) androidx.compose.foundation.BorderStroke(1.dp, Moon.Accent) else null,
                            ) {
                                Text(text, Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                     color = if (active) Moon.Accent else Moon.TextSecondary, fontSize = 12.5.sp)
                            }
                        }
                    }
                }
            }
        }

        // ---- search ---------------------------------------------------------
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.routingscreen_005), color = Moon.TextMuted, fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = Moon.TextSecondary, modifier = Modifier.size(18.dp)) },
                singleLine = true, shape = RoundedCornerShape(11.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Moon.Card, unfocusedContainerColor = Moon.Card,
                    focusedBorderColor = Moon.Accent, unfocusedBorderColor = Moon.BorderSoft,
                    focusedTextColor = Moon.TextPrimary, unfocusedTextColor = Moon.TextPrimary,
                    cursorColor = Moon.Accent,
                ),
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            )
        }

        // ---- buckets ---------------------------------------------------------
        val buckets = listOf(
            Bucket("direct", "DIRECT", directHint, Moon.Green,
                   (profile?.directSites ?: emptyList()) + (profile?.directIp ?: emptyList())),
            Bucket("proxy", "PROXY", proxyHint, Moon.AccentText,
                   (profile?.proxySites ?: emptyList()) + (profile?.proxyIp ?: emptyList())),
            Bucket("block", "BLOCK", blockHint, Moon.Danger,
                   (profile?.blockSites ?: emptyList()) + (profile?.blockIp ?: emptyList())),
        )

        items(buckets.size) { i ->
            val b = buckets[i]
            val rules = filtered(b.rules)
            Card {
                Row(Modifier.fillMaxWidth().padding(14.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ open = if (b.id in open) open - b.id else open + b.id }, Modifier.size(24.dp)) {
                        Icon(if (b.id in open) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight, null,
                             tint = Moon.TextSecondary, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(b.title, color = b.color, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(Modifier.width(8.dp))
                    Surface(shape = RoundedCornerShape(8.dp), color = b.color.copy(alpha = 0.15f)) {
                        Text("${b.rules.size}", Modifier.padding(horizontal = 7.dp, vertical = 1.dp),
                             color = b.color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.weight(1f))
                    if (custom) {
                        Surface(onClick = { onAddRule(b.id) }, shape = RoundedCornerShape(9.dp), color = Moon.ChipBg) {
                            Text(stringResource(R.string.settingsscreen_085), Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                 color = Moon.TextPrimary, fontSize = 12.sp)
                        }
                    }
                }
                Text(b.subtitle, Modifier.padding(start = 46.dp, bottom = 8.dp),
                     color = Moon.TextMuted, fontSize = 11.sp)

                if (b.id in open) {
                    if (rules.isEmpty()) {
                        Text(if (query.isBlank()) stringResource(R.string.routingscreen_009) else stringResource(R.string.settingsscreen_059),
                             Modifier.padding(14.dp, 0.dp, 14.dp, 12.dp), color = Moon.TextMuted, fontSize = 12.sp)
                    } else {
                        // chips wrap; a plain Column of Rows avoids pulling in experimental FlowRow
                        Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                               verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            rules.chunked(2).forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    row.forEach { rule ->
                                        RuleChip(rule, custom, Modifier.weight(1f)) { onRemoveRule(b.id, rule) }
                                    }
                                    if (row.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        item {
            if (profile == null) {
                Text(stringResource(R.string.routingscreen_010) +
                     stringResource(R.string.routingscreen_011),
                     Modifier.padding(4.dp), color = Moon.TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
            }
            Spacer(Modifier.height(40.dp))
        }
        }
    }
}

private data class Bucket(
    val id: String, val title: String, val subtitle: String,
    val color: Color, val rules: List<String>,
)

@Composable
private fun RuleChip(value: String, removable: Boolean, modifier: Modifier, onRemove: () -> Unit) {
    Surface(shape = RoundedCornerShape(7.dp), color = Color(0x1EFFFFFF), modifier = modifier) {
        Row(Modifier.padding(8.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(value, color = Moon.TextPrimary, fontSize = 11.5.sp,
                 maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (removable) {
                Icon(Icons.Filled.Close, stringResource(R.string.settingsscreen_084), tint = Moon.TextSecondary,
                     modifier = Modifier.size(13.dp).clickable(onClick = onRemove))
            }
        }
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = Moon.Card,
            border = androidx.compose.foundation.BorderStroke(1.dp, Moon.BorderSoft),
            modifier = Modifier.fillMaxWidth()) {
        Column(content = content)
    }
}

@Composable
private fun Divider() = Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp)
    .height(1.dp).background(Moon.BorderSoft))

@Composable
private fun KeyValue(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(14.dp, 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, color = Moon.TextPrimary, fontSize = 12.5.sp)
        Text(v, color = Moon.TextSecondary, fontSize = 11.5.sp)
    }
}

/** "Добавить правило" — bucket is fixed by the button that opened it. */
@Composable
fun AddRuleDialog(bucket: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val title = when (bucket) {
        "proxy" -> stringResource(R.string.routingscreen_012)
        "block" -> stringResource(R.string.routingscreen_013)
        else -> stringResource(R.string.routingscreen_014)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = Moon.TextPrimary, fontSize = 15.sp) },
        text = {
            Column {
                Text(stringResource(R.string.routingscreen_015), fontSize = 12.sp, color = Moon.TextSecondary)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    placeholder = { Text(stringResource(R.string.routingscreen_016), color = Moon.TextMuted, fontSize = 12.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Moon.ChipBg, unfocusedContainerColor = Moon.ChipBg,
                        focusedTextColor = Moon.TextPrimary, unfocusedTextColor = Moon.TextPrimary,
                        focusedBorderColor = Moon.Accent, unfocusedBorderColor = Moon.BorderSoft,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton({ onConfirm(text.trim()) }) { Text(stringResource(R.string.settingsscreen_195)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.settingsscreen_200), color = Moon.TextSecondary) } },
        containerColor = Moon.Card,
    )
}

package cc.moon.internet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.moon.internet.R
import cc.moon.internet.core.RoutingProfile

/**
 * One routing profile, open for editing. Everything is held in a local copy and written back on
 * Save, so leaving by the ✕ costs nothing and a half-finished edit never reaches the tunnel.
 */
@Composable
fun RoutingEditorScreen(
    original: RoutingProfile,
    geoSources: List<MainViewModel.GeoSource>,
    geoBusy: Boolean,
    geoStatus: String,
    geoTags: suspend () -> List<String>,
    onCancel: () -> Unit,
    onSave: (RoutingProfile) -> Unit,
    onRefreshGeo: () -> Unit,
) {
    var p by remember(original.id) { mutableStateOf(original) }
    var open by remember { mutableStateOf(setOf("direct")) }
    // Which bucket the category picker is filling, null when it is closed. The tags are read off
    // the geo files on first open — they are megabytes, so not before somebody asks.
    var pickFor by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().background(Moon.WinBg)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 16.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onCancel, Modifier.size(34.dp)) {
                Icon(Icons.Filled.Close, stringResource(R.string.settingsscreen_200),
                     tint = Moon.TextPrimary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(6.dp))
            // The profile's own name, not "Routing profile": it fits on one line and tells you
            // which of several copies you are looking at.
            Text(p.name.ifBlank { stringResource(R.string.routing_edit_title) }, fontSize = 19.sp,
                 fontWeight = FontWeight.Bold, color = Moon.TextPrimary,
                 maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Surface(onClick = { onSave(p) }, shape = RoundedCornerShape(10.dp), color = Moon.Accent) {
                Text(stringResource(R.string.routing_save), Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                     color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = bottomNavSpace()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ---- basic ------------------------------------------------------
            item {
                Section(stringResource(R.string.routing_sec_basic))
                EditorCard {
                    Field(
                        label = stringResource(R.string.routing_name),
                        value = p.name,
                        onValue = { p = p.copy(name = it) },
                    )
                    RowDivider(inset = 14)
                    ToggleRow(
                        title = stringResource(R.string.routing_global),
                        subtitle = stringResource(R.string.routing_global_hint),
                        checked = p.globalProxy,
                    ) { p = p.copy(globalProxy = it) }
                }
            }

            // ---- dns ---------------------------------------------------------
            item {
                Section(stringResource(R.string.routing_sec_dns))
                EditorCard {
                    DnsBlock(
                        title = stringResource(R.string.routing_dns_remote),
                        color = Moon.AccentText,
                        type = p.remoteDnsType,
                        address = p.remoteDns,
                        onType = { p = p.copy(remoteDnsType = it) },
                        onAddress = { p = p.copy(remoteDns = it) },
                    )
                    RowDivider(inset = 14)
                    DnsBlock(
                        title = stringResource(R.string.routing_dns_domestic),
                        color = Moon.Green,
                        type = p.domesticDnsType,
                        address = p.domesticDns,
                        onType = { p = p.copy(domesticDnsType = it) },
                        onAddress = { p = p.copy(domesticDns = it) },
                    )
                }
            }

            // ---- rules --------------------------------------------------------
            item { Section(stringResource(R.string.routing_sec_rules)) }

            item {
                Bucket(
                    id = "direct", title = "DIRECT", color = Moon.Green,
                    hint = stringResource(R.string.routingscreen_006),
                    sites = p.directSites, ips = p.directIp,
                    expanded = "direct" in open,
                    onToggle = { open = if ("direct" in open) open - "direct" else open + "direct" },
                    onAdd = { v, isIp ->
                        p = if (isIp) p.copy(directIp = p.directIp + v) else p.copy(directSites = p.directSites + v)
                    },
                    onRemove = { v -> p = p.copy(directSites = p.directSites - v, directIp = p.directIp - v) },
                    onPickGeo = { pickFor = "direct" },
                )
            }
            item {
                Bucket(
                    id = "proxy", title = "PROXY", color = Moon.AccentText,
                    hint = stringResource(R.string.routingscreen_007),
                    sites = p.proxySites, ips = p.proxyIp,
                    expanded = "proxy" in open,
                    onToggle = { open = if ("proxy" in open) open - "proxy" else open + "proxy" },
                    onAdd = { v, isIp ->
                        p = if (isIp) p.copy(proxyIp = p.proxyIp + v) else p.copy(proxySites = p.proxySites + v)
                    },
                    onRemove = { v -> p = p.copy(proxySites = p.proxySites - v, proxyIp = p.proxyIp - v) },
                    onPickGeo = { pickFor = "proxy" },
                )
            }
            item {
                Bucket(
                    id = "block", title = "BLOCK", color = Moon.Danger,
                    hint = stringResource(R.string.routingscreen_008),
                    sites = p.blockSites, ips = p.blockIp,
                    expanded = "block" in open,
                    onToggle = { open = if ("block" in open) open - "block" else open + "block" },
                    onAdd = { v, isIp ->
                        p = if (isIp) p.copy(blockIp = p.blockIp + v) else p.copy(blockSites = p.blockSites + v)
                    },
                    onRemove = { v -> p = p.copy(blockSites = p.blockSites - v, blockIp = p.blockIp - v) },
                    onPickGeo = { pickFor = "block" },
                )
            }

            // ---- advanced ------------------------------------------------------
            item {
                Section(stringResource(R.string.routing_sec_advanced))
                EditorCard {
                    Text(stringResource(R.string.routing_domain_strategy), Modifier.padding(14.dp, 12.dp, 14.dp, 2.dp),
                         color = Moon.TextPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                    listOf(
                        "AsIs" to (stringResource(R.string.routing_ds_asis) to stringResource(R.string.routing_ds_asis_hint)),
                        "IPIfNonMatch" to (stringResource(R.string.routing_ds_ifnomatch) to stringResource(R.string.routing_ds_ifnomatch_hint)),
                        "IPOnDemand" to (stringResource(R.string.routing_ds_ondemand) to stringResource(R.string.routing_ds_ondemand_hint)),
                    ).forEach { (id, text) ->
                        RadioRow(text.first, text.second, p.domainStrategy == id) { p = p.copy(domainStrategy = id) }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }

            // ---- geo -------------------------------------------------------------
            item {
                Section(stringResource(R.string.routing_sec_geo))
                EditorCard {
                    Field(
                        label = "GeoIP URL", value = p.geoipUrl, single = true,
                        onValue = { p = p.copy(geoipUrl = it) },
                    )
                    RowDivider(inset = 14)
                    Field(
                        label = "GeoSite URL", value = p.geositeUrl, single = true,
                        onValue = { p = p.copy(geositeUrl = it) },
                    )
                    RowDivider(inset = 14)
                    geoSources.forEach { g ->
                        KeyValueRow("geoip.dat", g.geoip)
                        KeyValueRow("geosite.dat", g.geosite)
                    }
                    Row(Modifier.fillMaxWidth().padding(14.dp, 6.dp, 14.dp, 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(geoStatus, color = Moon.AccentText, fontSize = 11.5.sp, modifier = Modifier.weight(1f))
                        if (geoBusy) SpinnerRing()
                        else Text(stringResource(R.string.routing_geo_refresh),
                                  Modifier.clickable(onClick = onRefreshGeo).padding(4.dp),
                                  color = Moon.AccentText, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    pickFor?.let { bucket ->
        GeoTagPicker(
            load = geoTags,
            onDismiss = { pickFor = null },
            onPick = { tag ->
                val isIp = tag.startsWith("geoip:", true)
                p = when (bucket) {
                    "proxy" -> if (isIp) p.copy(proxyIp = p.proxyIp + tag) else p.copy(proxySites = p.proxySites + tag)
                    "block" -> if (isIp) p.copy(blockIp = p.blockIp + tag) else p.copy(blockSites = p.blockSites + tag)
                    else -> if (isIp) p.copy(directIp = p.directIp + tag) else p.copy(directSites = p.directSites + tag)
                }
                pickFor = null
            },
        )
    }
}

/**
 * The categories that actually exist in the downloaded geoip.dat / geosite.dat. Typing
 * "geosite:CATEGORY-RU" from memory is how you end up with a rule that silently matches nothing.
 */
@Composable
private fun GeoTagPicker(
    load: suspend () -> List<String>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val tags by produceState(initialValue = emptyList<String>()) { value = load() }
    val shown = remember(tags, query) {
        tags.filter { query.isBlank() || it.contains(query, true) }.take(300)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.routing_geo_pick), color = Moon.TextPrimary, fontSize = 15.sp) },
        text = {
            Column(Modifier.heightIn(max = 420.dp)) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text(stringResource(R.string.routing_geo_search), color = Moon.TextMuted, fontSize = 12.5.sp) },
                    shape = RoundedCornerShape(10.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Moon.ChipBg, unfocusedContainerColor = Moon.ChipBg,
                        focusedBorderColor = Moon.Accent, unfocusedBorderColor = Moon.BorderSoft,
                        focusedTextColor = Moon.TextPrimary, unfocusedTextColor = Moon.TextPrimary,
                        cursorColor = Moon.Accent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                if (tags.isEmpty()) {
                    Text(stringResource(R.string.routing_geo_empty), color = Moon.TextMuted, fontSize = 12.sp)
                } else {
                    LazyColumn {
                        items(shown.size) { i ->
                            val t = shown[i]
                            Text(t, Modifier.fillMaxWidth().clickable { onPick(t) }.padding(vertical = 9.dp),
                                 color = Moon.TextPrimary, fontSize = 12.5.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onDismiss) { Text(stringResource(R.string.settingsscreen_200), color = Moon.TextSecondary) }
        },
        containerColor = Moon.Card,
    )
}

// ---------------------------------------------------------------------------

@Composable
private fun Section(text: String) =
    Text(text.uppercase(), Modifier.padding(start = 4.dp, top = 6.dp, bottom = 6.dp),
         color = Moon.TextMuted, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)

@Composable
private fun EditorCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = Moon.Card,
            border = androidx.compose.foundation.BorderStroke(1.dp, Moon.BorderSoft),
            modifier = Modifier.fillMaxWidth()) {
        Column(content = content)
    }
}

@Composable
private fun Field(label: String, value: String, onValue: (String) -> Unit, single: Boolean = true) {
    Column(Modifier.padding(14.dp, 12.dp)) {
        Text(label, color = Moon.TextSecondary, fontSize = 11.5.sp)
        OutlinedTextField(
            value = value, onValueChange = onValue, singleLine = single,
            shape = RoundedCornerShape(10.dp),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Moon.ChipBg, unfocusedContainerColor = Moon.ChipBg,
                focusedBorderColor = Moon.Accent, unfocusedBorderColor = Moon.BorderSoft,
                focusedTextColor = Moon.TextPrimary, unfocusedTextColor = Moon.TextPrimary,
                cursorColor = Moon.Accent,
            ),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(14.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Moon.TextPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Moon.TextSecondary, fontSize = 11.5.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Switch(checked, onChange, colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White, checkedTrackColor = Moon.Accent,
        ))
    }
}

@Composable
private fun RadioRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(14.dp, 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(18.dp).clip(CircleShape)
                .background(if (selected) Moon.Accent else Color.Transparent)
                .then(if (selected) Modifier else Modifier.border(1.5.dp, Moon.BorderSoft, CircleShape)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(12.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Moon.TextPrimary, fontSize = 13.sp)
            Text(subtitle, color = Moon.TextMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun KeyValueRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(14.dp, 7.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, color = Moon.TextPrimary, fontSize = 12.5.sp)
        Text(v, color = Moon.TextSecondary, fontSize = 11.5.sp)
    }
}

/** DoH / DoT / DoU / TCP plus the address they apply to. */
@Composable
private fun DnsBlock(
    title: String, color: Color, type: String, address: String,
    onType: (String) -> Unit, onAddress: (String) -> Unit,
) {
    Column(Modifier.padding(14.dp, 12.dp)) {
        Text(title, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("doh" to "DoH", "dot" to "DoT", "dou" to "DoU", "tcp" to "TCP").forEach { (id, label) ->
                val active = type.equals(id, true)
                Surface(
                    onClick = { onType(id) },
                    shape = RoundedCornerShape(9.dp),
                    color = if (active) color.copy(alpha = 0.18f) else Moon.ChipBg,
                    border = if (active) androidx.compose.foundation.BorderStroke(1.dp, color) else null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(label, Modifier.fillMaxWidth().padding(vertical = 7.dp),
                         color = if (active) color else Moon.TextSecondary, fontSize = 12.sp,
                         textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
        OutlinedTextField(
            value = address, onValueChange = onAddress, singleLine = true,
            shape = RoundedCornerShape(10.dp),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
            placeholder = { Text("1.1.1.1", color = Moon.TextMuted, fontSize = 13.sp) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Moon.ChipBg, unfocusedContainerColor = Moon.ChipBg,
                focusedBorderColor = Moon.Accent, unfocusedBorderColor = Moon.BorderSoft,
                focusedTextColor = Moon.TextPrimary, unfocusedTextColor = Moon.TextPrimary,
                cursorColor = Moon.Accent,
            ),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
}

/** One rule bucket: a count, the chips, and a line to type a new rule into. */
@Composable
private fun Bucket(
    id: String, title: String, color: Color, hint: String,
    sites: List<String>, ips: List<String>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onAdd: (String, Boolean) -> Unit,
    onRemove: (String) -> Unit,
    onPickGeo: () -> Unit,
) {
    var input by remember(id) { mutableStateOf("") }

    Surface(shape = RoundedCornerShape(14.dp), color = Moon.Card,
            border = androidx.compose.foundation.BorderStroke(1.dp, Moon.BorderSoft),
            modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(14.dp, 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight, null,
                     tint = Moon.TextSecondary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                Count("${sites.size}", color)
                Spacer(Modifier.width(6.dp))
                Count(stringResource(R.string.routing_ip_count, ips.size), Moon.TextSecondary)
            }
            Text(hint, Modifier.padding(start = 40.dp, end = 14.dp, bottom = 10.dp),
                 color = Moon.TextMuted, fontSize = 11.sp)

            if (expanded) {
                val all = sites + ips
                if (all.isEmpty()) {
                    Text(stringResource(R.string.routingscreen_009),
                         Modifier.padding(14.dp, 0.dp, 14.dp, 10.dp), color = Moon.TextMuted, fontSize = 12.sp)
                } else {
                    Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
                           verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        all.chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                row.forEach { rule ->
                                    RuleChip(rule, Modifier.weight(1f)) { onRemove(rule) }
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(14.dp, 0.dp, 14.dp, 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input, onValueChange = { input = it }, singleLine = true,
                        placeholder = { Text(stringResource(R.string.routingscreen_016), color = Moon.TextMuted, fontSize = 12.sp) },
                        shape = RoundedCornerShape(10.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.5.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Moon.ChipBg, unfocusedContainerColor = Moon.ChipBg,
                            focusedBorderColor = Moon.Accent, unfocusedBorderColor = Moon.BorderSoft,
                            focusedTextColor = Moon.TextPrimary, unfocusedTextColor = Moon.TextPrimary,
                            cursorColor = Moon.Accent,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        onClick = {
                            val v = input.trim()
                            if (v.isNotEmpty()) { onAdd(v, looksLikeIp(v)); input = "" }
                        },
                        shape = RoundedCornerShape(10.dp), color = Moon.ChipBg,
                    ) {
                        Text(stringResource(R.string.settingsscreen_195),
                             Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                             color = Moon.TextPrimary, fontSize = 12.5.sp)
                    }
                }
                Surface(
                    onClick = onPickGeo,
                    shape = RoundedCornerShape(10.dp),
                    color = Moon.Accent.copy(alpha = 0.14f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Moon.Accent.copy(alpha = 0.55f)),
                    modifier = Modifier.fillMaxWidth().padding(14.dp, 0.dp, 14.dp, 12.dp),
                ) {
                    Row(
                        Modifier.padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Public, null, tint = Moon.AccentText, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.routing_geo_pick), color = Moon.AccentText,
                             fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** An IP/CIDR or geoip tag belongs in the ip list; everything else is a domain or geosite tag. */
private fun looksLikeIp(v: String) =
    v.contains('/') || v.startsWith("geoip:", true) ||
    v.all { it.isDigit() || it == '.' || it == ':' }

@Composable
private fun Count(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(8.dp), color = color.copy(alpha = 0.15f)) {
        Text(text, Modifier.padding(horizontal = 7.dp, vertical = 1.dp),
             color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun RuleChip(value: String, modifier: Modifier, onRemove: () -> Unit) {
    Surface(shape = RoundedCornerShape(7.dp), color = Color(0x1EFFFFFF), modifier = modifier) {
        Row(Modifier.padding(8.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(value, color = Moon.TextPrimary, fontSize = 11.5.sp,
                 maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.Close, stringResource(R.string.settingsscreen_084), tint = Moon.TextSecondary,
                 modifier = Modifier.size(13.dp).clickable(onClick = onRemove))
        }
    }
}

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
import cc.moon.internet.core.ServerProfile
import cc.moon.internet.core.Subscription
import androidx.compose.ui.res.stringResource
import cc.moon.internet.R

/**
 * Servers, mirroring the desktop tab: header with ping/refresh, add/paste, search,
 * protocol + sort chips, then the servers grouped by subscription (not one flat list).
 */
@Composable
fun ServersScreen(
    subscriptions: List<Subscription>,
    selected: ServerProfile?,
    pings: Map<String, Int>,
    pinging: Set<String>,
    pingDisplay: String,
    refreshing: Set<String>,
    isFavorite: (ServerProfile) -> Boolean,
    collapsed: Set<String>,
    sort: String,
    protocol: String,
    showSubHeader: Boolean,
    showServerCount: Boolean,
    subMeter: String,
    onSort: (String) -> Unit,
    onProtocol: (String) -> Unit,
    onToggleCollapse: (String) -> Unit,
    onSelect: (ServerProfile) -> Unit,
    onServerMenu: (ServerProfile) -> Unit,
    onSubMenu: (Subscription) -> Unit,
    onPingSub: (Subscription) -> Unit,
    onRefreshSub: (Subscription) -> Unit,
    onAdd: () -> Unit,
    onPaste: () -> Unit,
    onPingAll: () -> Unit,
    onRefreshAll: () -> Unit,
    sortedIn: (Subscription) -> List<ServerProfile>,
    listState: androidx.compose.foundation.lazy.LazyListState,
) {
    var query by remember { mutableStateOf("") }
    // Chips carry a stable key, never their label — a label swaps with the language, a key doesn't.
    val allLabel = stringResource(R.string.serversscreen_001)

    val all = subscriptions.flatMap { it.servers }
    val protocols = remember(all) { listOf("") + all.map { it.protocolLabel }.distinct().sorted() }

    // Ordering (and the protocol filter) comes from the store, the same call Home makes — this
    // screen used to sort its own way, which is why the connected server floated to the top on
    // Home and stayed put here. Only the search box is local: it belongs to this screen.
    fun visible(sub: Subscription) = sortedIn(sub).filter { s ->
        query.isBlank() || s.label.contains(query, ignoreCase = true)
    }

    LazyColumn(
        Modifier.fillMaxSize().background(Moon.WinBg).padding(horizontal = 16.dp),
        state = listState,
        contentPadding = PaddingValues(bottom = bottomNavSpace()),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.serversscreen_002), fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Moon.TextPrimary)
                    Text(stringResource(R.string.fmt_servers, all.size), fontSize = 12.sp, color = Moon.TextSecondary,
                         modifier = Modifier.padding(top = 2.dp))
                }
                // Each button reports its own job. One shared flag meant a ping spun the refresh
                // icon too, on every subscription at once — a screenful of spinners for one press.
                BusyIconButton(Icons.Filled.Speed, pinging.isNotEmpty(), stringResource(R.string.serversscreen_003), 40.dp, onPingAll)
                BusyIconButton(Icons.Filled.Refresh, refreshing.isNotEmpty(), stringResource(R.string.serversscreen_004), 40.dp, onRefreshAll)
            }
        }

        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Surface(onClick = onAdd, shape = RoundedCornerShape(11.dp), color = Moon.Accent,
                        modifier = Modifier.weight(1f).padding(end = 4.dp)) {
                    IconLabel(Icons.Filled.Add, stringResource(R.string.settingsscreen_195), Color.White)
                }
                Surface(onClick = onPaste, shape = RoundedCornerShape(11.dp), color = Moon.Card,
                        border = androidx.compose.foundation.BorderStroke(1.dp, Moon.BorderSoft),
                        modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                    IconLabel(Icons.Filled.ContentPaste, stringResource(R.string.serversscreen_005), Moon.TextPrimary)
                }
            }
        }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.serversscreen_006), color = Moon.TextMuted, fontSize = 13.5.sp) },
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = Moon.TextSecondary, modifier = Modifier.size(18.dp)) },
                singleLine = true,
                shape = RoundedCornerShape(11.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Moon.Card, unfocusedContainerColor = Moon.Card,
                    focusedBorderColor = Moon.Accent, unfocusedBorderColor = Moon.BorderSoft,
                    focusedTextColor = Moon.TextPrimary, unfocusedTextColor = Moon.TextPrimary,
                    cursorColor = Moon.Accent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item {
            ChipSection(stringResource(R.string.section_protocol),
                        protocols.map { it to it.ifEmpty { allLabel } }, protocol, onProtocol)
            ChipSection(
                stringResource(R.string.serversscreen_007),
                listOf(
                    "default" to stringResource(R.string.serversscreen_008),
                    "ping" to stringResource(R.string.serversscreen_009),
                    "name" to stringResource(R.string.serversscreen_010),
                    "favorite" to stringResource(R.string.settingsscreen_129),
                ),
                sort.ifEmpty { "default" },
                onSort,
            )
            Spacer(Modifier.height(4.dp))
        }

        items(subscriptions.size) { i ->
            val sub = subscriptions[i]
            val shown = visible(sub)
            if (shown.isNotEmpty() || query.isBlank()) {
                SubGroup(
                    showServerCount = showServerCount,
                    subMeter = subMeter,
                    sub = sub, servers = shown, collapsed = sub.url in collapsed, showHeader = showSubHeader,
                    selected = selected, pings = pings, pinging = pinging,
                    pingDisplay = pingDisplay, isFavorite = isFavorite,
                    onToggleCollapse = { onToggleCollapse(sub.url) },
                    onMenu = { onSubMenu(sub) },
                    pingBusy = shown.any { it.raw in pinging },
                    refreshBusy = sub.url in refreshing,
                    onPing = { onPingSub(sub) },
                    onRefresh = { onRefreshSub(sub) },
                    onSelect = onSelect, onServerMenu = onServerMenu,
                )
                Spacer(Modifier.height(10.dp))
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun IconLabel(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, fg: Color) {
    Row(Modifier.padding(vertical = 11.dp), horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(7.dp))
        Text(text, color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ChipSection(title: String, options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    Column(Modifier.padding(top = 10.dp)) {
        SectionLabel(title, top = 0)
        ChipFlow(options, selected, onPick)
    }
}

@Composable
private fun SubGroup(
    showServerCount: Boolean,
    subMeter: String,
    sub: Subscription,
    servers: List<ServerProfile>,
    collapsed: Boolean,
    showHeader: Boolean,
    selected: ServerProfile?,
    pings: Map<String, Int>,
    pinging: Set<String>,
    pingDisplay: String,
    isFavorite: (ServerProfile) -> Boolean,
    onToggleCollapse: () -> Unit,
    onMenu: () -> Unit,
    pingBusy: Boolean,
    refreshBusy: Boolean,
    onPing: () -> Unit,
    onRefresh: () -> Unit,
    onSelect: (ServerProfile) -> Unit,
    onServerMenu: (ServerProfile) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF120C24),
        border = androidx.compose.foundation.BorderStroke(1.dp, Moon.BorderSoft),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).clickable(onClick = onToggleCollapse).padding(14.dp, 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (collapsed) Icons.Filled.ChevronRight else Icons.Filled.ExpandMore, null,
                         tint = Moon.TextSecondary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(11.dp))
                    Column {
                        Text(sub.name, color = Moon.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                             maxLines = 1, overflow = TextOverflow.Ellipsis)
                        SubMeter(sub.trafficText, sub.expiryText,
                                 sub.trafficFraction, sub.expiryFraction, subMeter)
                    }
                }
                Row(Modifier.padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (showServerCount) Surface(shape = RoundedCornerShape(9.dp), color = Moon.Accent) {
                        Text("${servers.size}", Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                             color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    BusyIconButton(Icons.Filled.Speed, busy = pingBusy, onClick = onPing)
                    BusyIconButton(Icons.Filled.Refresh, busy = refreshBusy, onClick = onRefresh)
                    IconButton(onMenu, Modifier.size(30.dp)) {
                        Icon(Icons.Filled.MoreHoriz, null, tint = Moon.TextSecondary, modifier = Modifier.size(17.dp))
                    }
                }
            }

            if (sub.announcement.isNotBlank() && showHeader) {
                Surface(
                    shape = RoundedCornerShape(10.dp), color = Color(0xFF1E1440),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x33B9A7FF)),
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                ) {
                    Text(sub.announcement, Modifier.padding(12.dp, 10.dp),
                         color = Color(0xFFCFC7EC), fontSize = 11.5.sp, lineHeight = 17.sp)
                }
            }

            if (!collapsed) {
                Column(Modifier.padding(horizontal = 7.dp).padding(bottom = 7.dp)) {
                    servers.forEach { s ->
                        ServerRowCompact(
                            server = s,
                            selected = s.raw == selected?.raw,
                            ping = pings[s.raw],
                            pinging = s.raw in pinging,
                            pingDisplay = pingDisplay,
                            favorite = isFavorite(s),
                            onClick = { onSelect(s) },
                            onMenu = { onServerMenu(s) },
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
    }
}

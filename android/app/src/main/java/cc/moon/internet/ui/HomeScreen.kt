package cc.moon.internet.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.moon.internet.R
import cc.moon.internet.core.ServerProfile
import cc.moon.internet.core.Subscription
import cc.moon.internet.vpn.MoonVpnService.Companion.State
import androidx.compose.ui.res.stringResource

/**
 * Home, laid out exactly like the desktop build: mode switch, the moon as the connect button,
 * the selected server, the connection check, the 3-column stats block, then the collapsible
 * subscription card with its announcement banner and server list.
 */
@Composable
fun HomeScreen(
    state: State,
    server: ServerProfile?,
    subscriptions: List<Subscription>,
    collapsed: Set<String>,
    onToggleCollapse: (String) -> Unit,
    pings: Map<String, Int>,
    pinging: Set<String>,
    pingDisplay: String,
    refreshing: Set<String>,
    /**
     * The starred servers, as data. This used to be a `(ServerProfile) -> Boolean` and that is
     * exactly how the bug hid: Compose skips a screen whose arguments all look unchanged, and a
     * lambda looks unchanged even when the answer it gives is different. The star still repainted,
     * because the row called the lambda itself — but the order, worked out here, never did.
     */
    /** "tun" or "zapret" — the segmented switch above the moon. */
    connMode: String,
    onConnMode: (String) -> Unit,
    favorites: Set<String>,
    checkPing: String,
    showSubHeader: Boolean,
    upSpeed: String,
    downSpeed: String,
    elapsed: String,
    traffic: String,
    onToggle: () -> Unit,
    onCheck: () -> Unit,
    onAdd: () -> Unit,
    onPaste: () -> Unit,
    onSelect: (ServerProfile) -> Unit,
    onSubMenu: (Subscription) -> Unit,
    onServerMenu: (ServerProfile) -> Unit,
    onPingSub: (Subscription) -> Unit,
    onRefreshSub: (Subscription) -> Unit,
    sortedIn: (Subscription) -> List<ServerProfile>,
    onScanQr: () -> Unit,
    showServerCount: Boolean,
    subMeter: String,
    updateAvailable: Boolean,
    onUpdates: () -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
) {
    // Ordered here rather than inside the list below: an item is its own little composition and
    // keeps the list it was first handed, so a new star would repaint a row without moving it.
    val groups = subscriptions.map { it to sortedIn(it) }

    LazyColumn(
        state = listState,
        // the gradient is painted by the activity, behind the window insets — see BottomNav
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = bottomNavSpace()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ---- режим, с плиткой обновления слева -----------------------------
        // Two, not the desktop's three: a proxy-only mode is not a thing on Android — HAPP, INCY
        // and v2RayTun do not offer one either. Запрет is the mode the row was left open for.
        item {
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Surface(
                    shape = RoundedCornerShape(13.dp),
                    color = Color(0xFF160A34),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2C2150)),
                ) {
                    Row(Modifier.padding(3.dp)) {
                        SegButton("TUN", connMode != "zapret") { onConnMode("tun") }   // a protocol name, not a word to translate
                        SegButton(stringResource(R.string.mode_zapret), connMode == "zapret") { onConnMode("zapret") }
                    }
                }

                // red dot in the corner when a newer release exists — same as on the desktop
                Box(Modifier.align(Alignment.CenterStart).size(38.dp)) {
                    // framed like the rest of our small buttons — a bare glyph did not read as one
                    Surface(
                        onClick = onUpdates,
                        shape = RoundedCornerShape(10.dp),
                        color = Moon.Card,
                        border = androidx.compose.foundation.BorderStroke(1.dp, Moon.BorderSoft),
                        modifier = Modifier.size(38.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.FileDownload, stringResource(R.string.homescreen_002),
                                 tint = Moon.AccentText, modifier = Modifier.size(19.dp))
                        }
                    }
                    if (updateAvailable) {
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(top = 3.dp, end = 3.dp)
                                .size(9.dp).clip(CircleShape).background(Moon.Danger)
                                .border(1.5.dp, Color(0xFF1A0B44), CircleShape)
                        )
                    }
                }
            }
        }

        // ---- moon button + state text --------------------------------------
        item {
            Spacer(Modifier.height(10.dp))
            MoonButton(state, onToggle)
            Spacer(Modifier.height(10.dp))
            Text(
                when (state) {
                    State.Connected -> stringResource(R.string.homescreen_003)
                    State.Connecting -> stringResource(R.string.homescreen_004)
                    State.Paused -> stringResource(R.string.homescreen_005)
                    State.Disconnected -> stringResource(R.string.homescreen_006)
                },
                fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Moon.TextPrimary,
            )
        }

        // ---- selected server ------------------------------------------------
        item {
            if (server != null) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(flagOf(server), fontSize = 15.sp)
                    Spacer(Modifier.width(7.dp))
                    Text(
                        server.label,
                        color = Moon.AccentText, fontSize = 14.5.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 300.dp),
                    )
                }
            } else {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.homescreen_007), color = Moon.TextSecondary, fontSize = 13.sp)
            }
        }

        // ---- connection check (only while connected) -------------------------
        item {
            // Fades and grows in when the tunnel comes up, the way the desktop reveals it —
            // popping into existence mid-list read as the layout jumping.
            androidx.compose.animation.AnimatedVisibility(
                visible = state == State.Connected,
                enter = androidx.compose.animation.fadeIn(tween(260)) +
                        androidx.compose.animation.expandVertically(tween(260, easing = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f))),
                exit = androidx.compose.animation.fadeOut(tween(140)) +
                       androidx.compose.animation.shrinkVertically(tween(180)),
            ) {
                Column {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        onClick = onCheck,
                        shape = RoundedCornerShape(11.dp),
                        color = Moon.Card,
                        border = androidx.compose.foundation.BorderStroke(1.dp, Moon.BorderSoft),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 18.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Speed, null, tint = Moon.TextPrimary, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.homescreen_008), color = Moon.TextPrimary, fontSize = 13.sp)
                        }
                    }
                    if (checkPing.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Surface(shape = RoundedCornerShape(9.dp), color = Moon.Green.copy(alpha = 0.15f)) {
                            Row(
                                Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(Moon.Green))
                                Spacer(Modifier.width(6.dp))
                                Text(checkPing, color = Moon.Green, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
                }
        }

        // ---- stats + add/paste ----------------------------------------------
        item {
            Spacer(Modifier.height(12.dp))
            // Everything shares the width by weight. Fixed columns beside a weighted one was the
            // actual tablet problem: the buttons took every extra pixel and the readouts kept
            // their 104dp whatever the screen was.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).widthIn(min = 104.dp)) {
                    StatCell(stringResource(R.string.homescreen_009), "↑", upSpeed, Moon.Green)
                    Spacer(Modifier.height(7.dp))
                    StatCell(stringResource(R.string.homescreen_010), "↓", downSpeed, Moon.AccentText)
                }
                Column(Modifier.weight(1f).widthIn(min = 90.dp)) {
                    StatCell(stringResource(R.string.homescreen_011), null, elapsed, Moon.TextPrimary)
                    Spacer(Modifier.height(7.dp))
                    StatCell(stringResource(R.string.homescreen_012), null, traffic, Moon.TextPrimary)
                }
                Column(Modifier.weight(1.1f).widthIn(max = 260.dp)) {
                    ActionButton(stringResource(R.string.settingsscreen_195), Icons.Filled.Add, Color(0xFF2C2058), Color(0xFFC4B4FF), onAdd)
                    Spacer(Modifier.height(6.dp))
                    ActionButton(stringResource(R.string.serversscreen_005), Icons.Filled.ContentPaste, Moon.ChipBg, Color(0xFFC6CAD3), onPaste)
                }
            }
        }

        // ---- subscriptions ----------------------------------------------------
        item { Spacer(Modifier.height(12.dp)) }

        items(groups.size) { i ->
            val (sub, shown) = groups[i]
            SubscriptionCard(
                    showServerCount = showServerCount,
                    subMeter = subMeter,
                sub = sub,
                collapsed = sub.url in collapsed,
                showHeader = showSubHeader,
                selected = server,
                pings = pings,
                pinging = pinging,
                pingDisplay = pingDisplay,
                favorites = favorites,
                servers = shown,
                onToggleCollapse = { onToggleCollapse(sub.url) },
                onMenu = { onSubMenu(sub) },
                pingBusy = shown.any { it.pingKey in pinging },
                refreshBusy = sub.url in refreshing,
                onPing = { onPingSub(sub) },
                onRefresh = { onRefreshSub(sub) },
                onSelect = onSelect,
                onServerMenu = onServerMenu,
            )
            Spacer(Modifier.height(10.dp))
        }

        item {
            if (subscriptions.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(14.dp), color = Moon.HomeCard,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Moon.BorderSoft),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.homescreen_013), color = Moon.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(3.dp))
                        Text(stringResource(R.string.homescreen_014),
                            color = Moon.TextSecondary, fontSize = 12.sp)

                        // Both ways in, right where there is nothing else to do yet — without
                        // this the only route is the Добавить button further up the screen.
                        Row(
                            Modifier.fillMaxWidth().padding(top = 14.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            EmptyAction(Icons.Filled.QrCodeScanner, stringResource(R.string.homescreen_015), onScanQr)
                            Spacer(Modifier.width(10.dp))
                            EmptyAction(Icons.Filled.ContentPaste, stringResource(R.string.homescreen_016), onPaste)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SegButton(text: String, active: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (active) Moon.Accent.copy(alpha = 0.18f) else Color.Transparent,
        border = if (active) androidx.compose.foundation.BorderStroke(1.dp, Moon.Accent) else null,
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 30.dp, vertical = 7.dp),
            color = if (active) Moon.Accent else Moon.TextSecondary,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun MoonButton(state: State, onClick: () -> Unit) {
    val connected = state == State.Connected
    val pulse = rememberInfiniteTransition(label = "p")
    val scale by pulse.animateFloat(
        1f, if (state == State.Connecting) 1.04f else 1f,
        infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "s",
    )
    Box(
        Modifier.size(204.dp).scale(scale).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(198.dp).clip(CircleShape)
            .border(1.5.dp, Color.White.copy(alpha = if (connected) 0.5f else 0.22f), CircleShape))
        Crossfade(connected, animationSpec = tween(320), label = "m") { on ->
            Icon(
                painterResource(if (on) R.drawable.moon_on else R.drawable.moon_off),
                null, tint = Color.Unspecified, modifier = Modifier.size(172.dp),
            )
        }
    }
}

@Composable
private fun StatCell(label: String, arrow: String?, value: String, arrowColor: Color) {
    Column {
        Text(label, color = Moon.TextSecondary, fontSize = 10.5.sp)
        Row(Modifier.padding(top = 1.dp), verticalAlignment = Alignment.CenterVertically) {
            if (arrow != null) {
                Text(arrow, color = arrowColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(3.dp))
            }
            Text(value, color = Moon.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                 maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    bg: Color, fg: Color, onClick: () -> Unit,
) {
    Surface(onClick = onClick, shape = RoundedCornerShape(10.dp), color = bg, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(vertical = 9.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(6.dp))
            Text(text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SubscriptionCard(
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
    favorites: Set<String>,
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
        color = Moon.HomeCard,
        border = androidx.compose.foundation.BorderStroke(1.dp, Moon.BorderSoft),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            // header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).clickable(onClick = onToggleCollapse).padding(14.dp, 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (collapsed) Icons.Filled.ChevronRight else Icons.Filled.ExpandMore,
                        null, tint = Moon.TextSecondary, modifier = Modifier.size(18.dp),
                    )
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
                        Text("${sub.servers.size}", Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                             color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    BusyIconButton(Icons.Filled.Speed, busy = pingBusy, onClick = onPing)
                    BusyIconButton(Icons.Filled.Refresh, busy = refreshBusy, onClick = onRefresh)
                    IconButton(onMenu, Modifier.size(30.dp)) {
                        Icon(Icons.Filled.MoreHoriz, null, tint = Moon.TextSecondary, modifier = Modifier.size(17.dp))
                    }
                }
            }

            // announcement banner
            if (sub.announcement.isNotBlank() && showHeader) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF1E1440),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x33B9A7FF)),
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                ) {
                    Text(sub.announcement, Modifier.padding(12.dp, 10.dp),
                         color = Color(0xFFCFC7EC), fontSize = 11.5.sp, lineHeight = 17.sp)
                }
            }

            // servers
            if (!collapsed) {
                Column(Modifier.padding(horizontal = 7.dp).padding(bottom = 7.dp)) {
                    servers.forEach { s ->
                        ServerRowCompact(
                            server = s,
                            selected = s.raw == selected?.raw,
                            ping = pings[s.pingKey],
                            pinging = s.pingKey in pinging,
                            pingDisplay = pingDisplay,
                            favorite = s.raw in favorites,
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

@Composable
fun ServerRowCompact(
    server: ServerProfile,
    selected: Boolean,
    ping: Int?,
    pinging: Boolean,
    pingDisplay: String,
    favorite: Boolean,
    onClick: () -> Unit,
    onMenu: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) Color(0xFF251A44) else Moon.Card,
        border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, Moon.Accent) else null,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(14.dp, 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(flagOf(server), fontSize = 20.sp)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(server.label, color = Moon.TextPrimary, fontSize = 13.5.sp,
                     maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Tag(server.protocolLabel, Moon.AccentText, Moon.Accent.copy(alpha = 0.18f))
                    Tag(server.transportLabel, Moon.TextSecondary, Moon.ChipBg)
                    if (server.security != "none") Tag(server.security.uppercase(), Moon.Green, Moon.Green.copy(alpha = 0.15f))
                }
            }
            if (favorite) {
                Icon(Icons.Filled.Star, null, tint = Moon.TextSecondary, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(7.dp))
            }
            PingIndicator(ping, pinging, pingDisplay)
            IconButton(onMenu, Modifier.size(30.dp)) {
                Icon(Icons.Filled.MoreHoriz, null, tint = Moon.TextSecondary, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun Tag(text: String, fg: Color, bg: Color) {
    Surface(shape = RoundedCornerShape(6.dp), color = bg) {
        Text(text, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), color = fg, fontSize = 10.sp,
             fontWeight = FontWeight.SemiBold)
    }
}

fun flagOf(s: ServerProfile): String = s.countryCode?.let { cc ->
    String(Character.toChars(0x1F1E6 + (cc[0] - 'a'))) + String(Character.toChars(0x1F1E6 + (cc[1] - 'a')))
} ?: "🌐"

fun pingColorOf(ms: Int) = when {
    ms < 0 -> Moon.Danger
    ms < 100 -> Moon.Green
    ms < 250 -> Color(0xFFF5C042)
    else -> Color(0xFFFF8A5B)
}

/** A small round icon button for the empty-subscription card. */
@Composable
private fun EmptyAction(icon: androidx.compose.ui.graphics.vector.ImageVector, hint: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Moon.ChipBg,
        border = androidx.compose.foundation.BorderStroke(1.dp, Moon.BorderSoft),
        modifier = Modifier.size(44.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, hint, tint = Moon.AccentText, modifier = Modifier.size(20.dp))
        }
    }
}

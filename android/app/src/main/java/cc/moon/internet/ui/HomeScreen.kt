package cc.moon.internet.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
    /** The switch only exists once Запрет is installed; with one mode there is nothing to choose. */
    showModes: Boolean,
    zapretStrategy: String,
    onPickZapret: (String) -> Unit,
    zapretPing: String,
    zapretBusy: Boolean,
    /** A mode switch is tearing the old one down; show it the way a connect is shown. */
    switching: Boolean = false,
    onCheckZapret: () -> Unit,
    onAutoPickZapret: () -> Unit,
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
    val zapret = connMode == "zapret"
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
        // and v2RayTun do not offer one either. Запрет is the mode the row was left open for, and
        // until it is installed there is only one mode — so the row goes and the moon moves up.
        // Without the row the moon sat straight under the status bar. A little air in its place —
        // not the whole band the row used to take.
        if (!showModes) item { Spacer(Modifier.height(26.dp)) }
        if (showModes) item {
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
            // A mode switch wears the connecting face: the tunnel is coming down and the page is
            // about to change shape, and a still moon over that reads as a freeze.
            val shown = if (switching) State.Connecting else state
            MoonButton(shown, onToggle)
            Spacer(Modifier.height(10.dp))
            Text(
                if (switching) stringResource(R.string.vm_mode_switching) else when (shown) {
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
            // Запрет names the strategy where the others name the server. Leaving the server line
            // up in that mode is what had people connecting to the wrong thing.
            if (zapret) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Bolt, null, tint = Moon.AccentText, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(
                        zapretStrategy,
                        color = Moon.AccentText, fontSize = 14.5.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 300.dp),
                    )
                }
                if (zapretPing != "—") {
                    Text(zapretPing, color = Moon.TextSecondary, fontSize = 12.sp,
                         modifier = Modifier.padding(top = 4.dp))
                }
            } else if (server != null) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val flag = flagOf(server)
                    if (flag.isNotEmpty()) {
                        Text(flag, fontSize = 15.sp)
                        Spacer(Modifier.width(7.dp))
                    }
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
                // The same 420ms the row below it takes. They both change the height of the page,
                // and on two different clocks that is two separate shoves rather than one movement.
                enter = androidx.compose.animation.fadeIn(tween(420)) +
                        androidx.compose.animation.expandVertically(tween(420, easing = FastOutSlowInEasing)),
                exit = androidx.compose.animation.fadeOut(tween(420)) +
                       androidx.compose.animation.shrinkVertically(tween(420, easing = FastOutSlowInEasing)),
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
            // Connected, the four readouts take the left and the buttons stack down the right side.
            // Disconnected they all read "—", so they go and the buttons stand side by side across
            // the whole width. Same two buttons either way — they travel between the two places
            // rather than fading out of one and into the other, which is what a crossfade did and
            // why it looked like nothing had happened.
            val live = state == State.Connected || state == State.Paused
            val progress by androidx.compose.animation.core.animateFloatAsState(
                if (live) 1f else 0f,
                tween(420, easing = FastOutSlowInEasing),
                label = "home-actions",
            )
            HomeActions(
                progress = progress,
                stats = {
                    // Not an even split: the left column carries an arrow and a speed with its
                    // unit ("↓ 9,9 Кбит/с"), the right one a clock and a size. Halves clipped the
                    // speed while the clock sat in white space, so the divide moves right.
                    Row {
                        Column(Modifier.weight(1.35f)) {
                            StatCell(stringResource(R.string.homescreen_009), "↑", upSpeed, Moon.Green)
                            Spacer(Modifier.height(7.dp))
                            StatCell(stringResource(R.string.homescreen_010), "↓", downSpeed, Moon.AccentText)
                        }
                        Column(Modifier.weight(1f).padding(start = 6.dp)) {
                            StatCell(stringResource(R.string.homescreen_011), null, elapsed, Moon.TextPrimary)
                            Spacer(Modifier.height(7.dp))
                            StatCell(stringResource(R.string.homescreen_012), null, traffic, Moon.TextPrimary)
                        }
                    }
                },
                add = {
                    if (zapret) ActionButton(stringResource(R.string.zapret_check_now), Icons.Filled.NetworkCheck,
                                             Color(0xFF2C2058), Color(0xFFC4B4FF)) { if (!zapretBusy) onCheckZapret() }
                    else ActionButton(stringResource(R.string.settingsscreen_195), Icons.Filled.Add,
                                      Color(0xFF2C2058), Color(0xFFC4B4FF), onClick = onAdd)
                },
                paste = {
                    if (zapret) ActionButton(stringResource(R.string.zapret_auto_run), Icons.Filled.AutoFixHigh,
                                             Moon.ChipBg, Color(0xFFC6CAD3)) { if (!zapretBusy) onAutoPickZapret() }
                    else ActionButton(stringResource(R.string.serversscreen_005), Icons.Filled.ContentPaste,
                                      Moon.ChipBg, Color(0xFFC6CAD3), onClick = onPaste)
                },
            )
        }

        // ---- subscriptions ----------------------------------------------------
        item { Spacer(Modifier.height(12.dp)) }

        // One card holding the strategies, shaped like a subscription: a bare list of twenty-one
        // rows read as a debug screen next to the subscriptions it stands in for.
        if (zapret) item {
            Surface(
                shape = RoundedCornerShape(14.dp), color = Moon.HomeCard,
                border = androidx.compose.foundation.BorderStroke(1.dp, Moon.BorderSoft),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    Row(Modifier.padding(14.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Bolt, null, tint = Moon.AccentText, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.zapret_card_title), color = Moon.TextPrimary,
                                 fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.zapret_card_sub), color = Moon.TextSecondary,
                                 fontSize = 11.5.sp, modifier = Modifier.padding(top = 2.dp))
                        }
                        Surface(shape = RoundedCornerShape(9.dp), color = Moon.Accent) {
                            Text(cc.moon.internet.core.ZapretStrategies.all.size.toString(),
                                 Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                 color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Column(Modifier.padding(horizontal = 7.dp).padding(bottom = 7.dp)) {
                        cc.moon.internet.core.ZapretStrategies.all.forEach { st ->
                            val chosen = st.id == zapretStrategy
                            Surface(
                                onClick = { onPickZapret(st.id) },
                                shape = RoundedCornerShape(12.dp),
                                color = if (chosen) Color(0xFF251A44) else Moon.Card,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp, if (chosen) Moon.Accent else Moon.BorderSoft),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(Modifier.padding(14.dp, 11.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Bolt, null, tint = Moon.AccentText, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(11.dp))
                                    Text(st.id, color = Moon.TextPrimary, fontSize = 13.5.sp,
                                         maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            }
        }

        // Keyed by the subscription, so the list can animate a plate moving instead of redrawing
        // the column in its new order. Connecting makes the live subscription jump to the top, and
        // without this it teleports there between two frames.
        if (!zapret) items(groups.size, key = { groups[it].first.url }) { i ->
            val (sub, shown) = groups[i]
            // A card filtered out of the list goes gradually. Picking a protocol one subscription
            // does not carry, or starring servers in only one of them, leaves nothing to show
            // here — and a card that simply blinks out while the next one blinks into its place
            // is the whole list rearranging itself between two frames. One that is genuinely
            // empty keeps its card: that card is how you refresh or remove it.
                // No placement animation while the list is changing shape. Folding a card
                // shortens the list, so a list scrolled past its new end is pulled back up and
                // every plate below lands somewhere new — animating that flies them in from above
                // the window, the further the longer the subscription was.
                Column(Modifier.animateItem(
                    fadeInSpec = tween(220),
                    placementSpec = if (ListMotion.quiet) null
                                    else tween(420, easing = FastOutSlowInEasing),
                    fadeOutSpec = tween(180),
                )) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = shown.isNotEmpty() || sub.servers.isEmpty(),
                    enter = androidx.compose.animation.fadeIn(tween(220)) +
                            androidx.compose.animation.expandVertically(tween(320, easing = FastOutSlowInEasing)),
                    exit = androidx.compose.animation.fadeOut(tween(180)) +
                           androidx.compose.animation.shrinkVertically(tween(320, easing = FastOutSlowInEasing)),
                ) {
                Column {
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
                }
                }
        }

        item {
            // «No subscriptions yet» with a QR button under it is an invitation to add one, and in
            // Zapret there is nothing to add — the strategies above are the whole list.
            if (subscriptions.isEmpty() && !zapret) {
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

/**
 * The Home action row, as one layout with a dial on it.
 *
 * At 0 the readouts have no width and the two buttons share the row. At 1 the readouts have the
 * left half and the buttons are stacked in the right. Every position in between is measured, so
 * turning the dial does not swap one arrangement for another — the same two buttons walk from
 * where they were to where they are going, growing and narrowing as they go.
 *
 * A crossfade was the obvious way and the wrong one: two copies of a button, one fading out at the
 * old place while another faded in at the new, which the eye reads as a redraw rather than a move.
 */
@Composable
private fun HomeActions(
    progress: Float,
    stats: @Composable () -> Unit,
    add: @Composable () -> Unit,
    paste: @Composable () -> Unit,
) {
    androidx.compose.ui.layout.Layout(
        contents = listOf(
            { Box(Modifier.graphicsLayer { alpha = progress }) { stats() } },
            add,
            paste,
        ),
        modifier = Modifier.fillMaxWidth().clipToBounds(),
    ) { (statsM, addM, pasteM), constraints ->
        val width = constraints.maxWidth
        val gap = 8.dp.roundToPx()
        val stack = 6.dp.roundToPx()
        val p = progress.coerceIn(0f, 1f)

        // The readouts are always measured at the width they have when they are up, however much
        // of it is showing. Measured at the width of the moment they re-wrapped as they narrowed,
        // grew a line, and pushed the whole page down before snapping back — the subscriptions
        // below went up, down and up again in one movement, which is the thing you cannot unsee.
        val statsWidth = (width * 0.55f).toInt()
        val right = statsWidth + gap
        val buttonWidth = androidx.compose.ui.util.lerp((width - gap) / 2, (width - right).coerceAtLeast(1), p)

        val statsPlaced = statsM.firstOrNull()?.measure(constraints.copy(minWidth = 0, maxWidth = statsWidth))
        val addPlaced = addM.first().measure(androidx.compose.ui.unit.Constraints.fixedWidth(buttonWidth))
        val pastePlaced = pasteM.first().measure(androidx.compose.ui.unit.Constraints.fixedWidth(buttonWidth))

        // Side by side at 0, one under the other at 1.
        val addX = androidx.compose.ui.util.lerp(0, right, p)
        val pasteX = androidx.compose.ui.util.lerp(buttonWidth + gap, right, p)
        val pasteY = androidx.compose.ui.util.lerp(0, addPlaced.height + stack, p)

        // ...and the height goes straight from one shape's to the other's, rather than being
        // whatever happens to be tallest at this instant.
        val height = androidx.compose.ui.util.lerp(
            maxOf(addPlaced.height, pastePlaced.height),
            maxOf(statsPlaced?.height ?: 0, addPlaced.height + stack + pastePlaced.height),
            p,
        )

        layout(width, height) {
            if (p > 0f) statsPlaced?.placeRelative(0, (height - statsPlaced.height) / 2)
            addPlaced.placeRelative(addX, 0)
            pastePlaced.placeRelative(pasteX, pasteY)
        }
    }
}

@Composable
private fun ActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    bg: Color, fg: Color,
    /** Where it sits in its row — stacked down a column, or sharing the width side by side. */
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick, shape = RoundedCornerShape(10.dp), color = bg, modifier = modifier.fillMaxWidth()) {
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
                    Text(sub.name, Modifier.weight(1f), color = Moon.TextPrimary, fontSize = 14.sp,
                         fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(Modifier.padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (showServerCount) Surface(shape = RoundedCornerShape(9.dp), color = Moon.Accent) {
                        Text("${sub.servers.size}", Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                             color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    BusyIconButton(Icons.Filled.Speed, busy = pingBusy, onClick = onPing)
                    if (!sub.isLoose) BusyIconButton(Icons.Filled.Refresh, busy = refreshBusy, onClick = onRefresh)
                    IconButton(onMenu, Modifier.size(30.dp)) {
                        Icon(Icons.Filled.MoreHoriz, null, tint = Moon.TextSecondary, modifier = Modifier.size(17.dp))
                    }
                }
            }

            // The meter gets a row of its own. Under the title it shared the band with the action
            // icons, so it only ever had half the card to draw in.
            if (!sub.isLoose) Box(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 11.dp)) {
                SubMeter(sub.trafficText, sub.expiryText, sub.trafficFraction, sub.expiryFraction, subMeter)
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
            // The fold itself is the animation: the card gives up its height over a quarter of a
            // second rather than between two frames, and slowest at the end — the page has stopped
            // being pulled before it stops moving. A card taller than the window drags the page
            // back up with it when it shrinks, and that pull is what felt like a kick — it is not
            // wrong, there is simply nothing below to hold the page where it was. Shrinking over
            // time makes the same pull happen a few pixels at a time, which is all it needed.
            Column(Modifier.animateContentSize(tween(420, easing = LinearOutSlowInEasing))) {
                if (!collapsed) {
                    Column(Modifier.padding(horizontal = 7.dp).padding(bottom = 7.dp)) {
                        servers.forEach { s ->
                            // key(), or none of this moves. Without one Compose identifies children by
                            // position, so a reorder leaves every slot where it was and swaps the
                            // contents over — which is instant by definition, and is why the row
                            // appeared to teleport however the animation was tuned. With a key the row
                            // itself is the thing that moves, and there is a movement to animate.
                            key(s.key) {
                            // Each row slides to its new place rather than turning up there: picking a
                            // server lifts it to the top of its card, and in a plain Column that is a
                            // jump between two frames.
                            Column(Modifier.animatePlacement()) {
                            ServerRowCompact(
                                server = s,
                                selected = s === selected,   // the copy that was picked, not every copy with its key
                                ping = pings[s.pingKey],
                                pinging = s.pingKey in pinging,
                                pingDisplay = pingDisplay,
                                favorite = s.key in favorites,
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
            val flag = flagOf(server)
            if (flag.isNotEmpty()) {
                Text(flag, fontSize = 20.sp)
                Spacer(Modifier.width(11.dp))
            }
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

/**
 * The flag for a server, or nothing at all.
 *
 * A globe used to stand in when the country was unknown, which gave every unnamed server the same
 * symbol: a column of identical stickers that told you nothing while looking as though it should.
 * Callers check for the empty string rather than laying out room for it.
 */
fun flagOf(s: ServerProfile): String = s.countryCode?.let { cc ->
    String(Character.toChars(0x1F1E6 + (cc[0] - 'a'))) + String(Character.toChars(0x1F1E6 + (cc[1] - 'a')))
} ?: ""

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

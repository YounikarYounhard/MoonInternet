package cc.moon.internet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import cc.moon.internet.core.RoutingProfile
import cc.moon.internet.core.Subscription

/**
 * Routing, top level. Two shipped profiles, then one row per subscription — a subscription's
 * routing lives inside it, the same way its servers do, and you step into it to pick between the
 * INCY and HAPP variants it carries. Your own copies come last: those are the editable ones.
 *
 * Selection is the accent outline we use for the active server, not a radio column: this is a
 * Moon screen, not a reproduction of somebody else's.
 */
@Composable
fun RoutingScreen(
    profiles: List<RoutingProfile>,
    subscriptions: List<Subscription>,
    selectedId: String,
    /** What "Авто" resolves to right now, and the subscription it came from. */
    autoPick: RoutingProfile?,
    autoSubName: String,
    autoPref: String,
    onAutoPref: (String) -> Unit,
    onBack: () -> Unit,
    onSelect: (String) -> Unit,
    onOpenSub: (String) -> Unit,
    onEdit: (RoutingProfile) -> Unit,
    onDuplicate: (String) -> Unit,
    onExport: (String) -> Unit,
    onDelete: (String) -> Unit,
    onAdd: () -> Unit,
) {
    var menuFor by remember { mutableStateOf<RoutingProfile?>(null) }
    var confirmDelete by remember { mutableStateOf<RoutingProfile?>(null) }

    val builtins = profiles.filter { it.builtin }
    val mine = profiles.filter { !it.builtin && it.subUrl.isBlank() }
    val bySub = profiles.filter { it.subUrl.isNotBlank() }.groupBy { it.subUrl }

    Column(Modifier.fillMaxSize().background(Moon.WinBg)) {
        RoutingHeader(stringResource(R.string.routing_title), onBack) {
            IconButton(onAdd, Modifier.size(38.dp)) {
                Icon(Icons.Filled.Add, stringResource(R.string.routing_add),
                     tint = Moon.Accent, modifier = Modifier.size(22.dp))
            }
        }
        Text(stringResource(R.string.routing_subtitle), Modifier.padding(start = 20.dp, bottom = 12.dp),
             color = Moon.TextSecondary, fontSize = 12.5.sp)

        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = bottomNavSpace()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Авто first: routing follows the subscription the current server came from, and the
            // second line names the profile that lands on right now — the mode is not a promise.
            item {
                RoutingRow(
                    icon = Icons.Filled.AutoAwesome,
                    title = stringResource(R.string.routing_auto),
                    // Only a subscription's own profile is worth naming. A fallback to Глобальный
                    // has no subscription and no source, so it says so instead of printing " · ".
                    subtitle = autoPick?.takeIf { it.subUrl.isNotBlank() }
                        ?.let { "$autoSubName · ${it.source.uppercase()}" }
                        ?: stringResource(R.string.routing_auto_none),
                    selected = selectedId.isBlank(),
                    onClick = { onSelect("") },
                )
            }

            // Which of a subscription's pair Авто reaches for. Only while the mode is on: with a
            // profile pinned by hand there is nothing for it to decide.
            if (selectedId.isBlank()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp), color = Moon.Card,
                        border = androidx.compose.foundation.BorderStroke(1.dp, Moon.BorderSoft),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(14.dp, 10.dp)) {
                            Text(stringResource(R.string.routing_prefer), color = Moon.TextPrimary,
                                 fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.routing_prefer_hint), color = Moon.TextMuted,
                                 fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
                            Row {
                                listOf("incy" to "INCY", "happ" to "HAPP").forEach { (id, label) ->
                                    val on = autoPref == id
                                    Surface(
                                        onClick = { onAutoPref(id) },
                                        shape = RoundedCornerShape(9.dp),
                                        color = if (on) Moon.Accent.copy(alpha = 0.18f) else Moon.ChipBg,
                                        border = if (on) androidx.compose.foundation.BorderStroke(1.dp, Moon.Accent) else null,
                                        modifier = Modifier.padding(end = 8.dp),
                                    ) {
                                        Text(label, Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                                             color = if (on) Moon.AccentText else Moon.TextSecondary,
                                             fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }
            }
            }

            items(builtins.size) { i ->
                val p = builtins[i]
                RoutingRow(
                    icon = if (p.id == "builtin-lan") Icons.Filled.Lan else Icons.Filled.Public,
                    title = p.name,
                    subtitle = describe(p),
                    selected = p.id == selectedId,
                    onClick = { onSelect(p.id) },
                )
            }

            if (bySub.isNotEmpty()) {
                item { SectionLabel(stringResource(R.string.routing_sec_subs)) }
                items(subscriptions.size) { i ->
                    val sub = subscriptions[i]
                    val list = bySub[sub.url].orEmpty()
                    if (list.isNotEmpty()) {
                        val active = list.firstOrNull { it.id == selectedId }
                        RoutingRow(
                            icon = Icons.Filled.CloudDownload,
                            title = sub.name,
                            subtitle = active?.let { stringResource(R.string.routing_sub_active, it.source.uppercase()) }
                                ?: list.joinToString(" · ") { it.source.uppercase() },
                            selected = selectedId.isNotBlank() && active != null,
                            onClick = { onOpenSub(sub.url) },
                            trailing = {
                                Icon(Icons.Filled.ChevronRight, null, tint = Moon.TextSecondary,
                                     modifier = Modifier.size(20.dp))
                            },
                        )
                    }
                }
            }

            if (mine.isNotEmpty()) {
                item { SectionLabel(stringResource(R.string.routing_sec_mine)) }
                items(mine.size) { i ->
                    val p = mine[i]
                    RoutingRow(
                        icon = Icons.Filled.Tune,
                        title = p.name,
                        subtitle = describe(p),
                        selected = p.id == selectedId,
                        onClick = { onSelect(p.id) },
                        trailing = {
                            IconButton({ menuFor = p }, Modifier.size(32.dp)) {
                                Icon(Icons.Filled.MoreVert, stringResource(R.string.routing_menu),
                                     tint = Moon.TextSecondary, modifier = Modifier.size(18.dp))
                            }
                        },
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    ProfileSheets(
        menuFor = menuFor, confirmDelete = confirmDelete,
        onCloseMenu = { menuFor = null },
        onAskDelete = { menuFor = null; confirmDelete = it },
        onCloseDelete = { confirmDelete = null },
        onEdit = { menuFor = null; onEdit(it) },
        onDuplicate = { menuFor = null; onDuplicate(it.id) },
        onExport = { menuFor = null; onExport(it.id) },
        onDelete = { onDelete(it.id); confirmDelete = null },
    )
}

/**
 * One subscription's routing. This is where INCY and HAPP sit side by side — the choice the old
 * segmented control used to make, now in the place the profiles actually come from.
 */
@Composable
fun RoutingSubScreen(
    subName: String,
    profiles: List<RoutingProfile>,
    selectedId: String,
    onBack: () -> Unit,
    onSelect: (String) -> Unit,
    onDuplicate: (String) -> Unit,
    onExport: (String) -> Unit,
) {
    var menuFor by remember { mutableStateOf<RoutingProfile?>(null) }

    Column(Modifier.fillMaxSize().background(Moon.WinBg)) {
        RoutingHeader(subName, onBack)
        Text(stringResource(R.string.routing_sub_hint), Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
             color = Moon.TextSecondary, fontSize = 12.5.sp, lineHeight = 17.sp)

        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = bottomNavSpace()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(profiles.size) { i ->
                val p = profiles[i]
                RoutingRow(
                    icon = Icons.Filled.Route,
                    title = p.name,
                    subtitle = describe(p),
                    selected = p.id == selectedId,
                    badge = p.source.uppercase(),
                    onClick = { onSelect(p.id) },
                    trailing = {
                        IconButton({ menuFor = p }, Modifier.size(32.dp)) {
                            Icon(Icons.Filled.MoreVert, stringResource(R.string.routing_menu),
                                 tint = Moon.TextSecondary, modifier = Modifier.size(18.dp))
                        }
                    },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    ProfileSheets(
        menuFor = menuFor, confirmDelete = null,
        onCloseMenu = { menuFor = null },
        onAskDelete = {}, onCloseDelete = {},
        onEdit = {}, onDelete = {},
        onDuplicate = { menuFor = null; onDuplicate(it.id) },
        onExport = { menuFor = null; onExport(it.id) },
    )
}

// ---------------------------------------------------------------------------

@Composable
private fun RoutingHeader(title: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onBack, Modifier.size(34.dp)) {
            Icon(Icons.Filled.ArrowBack, stringResource(R.string.routingscreen_001),
                 tint = Moon.TextPrimary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(6.dp))
        Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Moon.TextPrimary,
             maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun SectionLabel(text: String) =
    Text(text.uppercase(), Modifier.padding(start = 4.dp, top = 8.dp),
         color = Moon.TextMuted, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)

/** The card the whole screen is built from — same shape and selection as a server row. */
@Composable
private fun RoutingRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    badge: String? = null,
    trailing: @Composable () -> Unit = {},
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = Moon.Card,
        border = androidx.compose.foundation.BorderStroke(
            if (selected) 1.5.dp else 1.dp, if (selected) Moon.Accent else Moon.BorderSoft,
        ),
        modifier = Modifier.fillMaxWidth().pressScale(),
    ) {
        Row(Modifier.padding(14.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (selected) Moon.Accent.copy(alpha = 0.16f) else Moon.ChipBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = if (selected) Moon.Accent else Moon.TextSecondary,
                     modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = Moon.TextPrimary, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold,
                         maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                    if (badge != null) {
                        Spacer(Modifier.width(8.dp))
                        Surface(shape = RoundedCornerShape(6.dp), color = Moon.AccentText.copy(alpha = 0.15f)) {
                            Text(badge, Modifier.padding(horizontal = 7.dp, vertical = 1.dp),
                                 color = Moon.AccentText, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Text(subtitle, color = Moon.TextSecondary, fontSize = 11.5.sp,
                     maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            trailing()
        }
    }
}

/** Short line under the name: what this profile does, worked out rather than stored. */
@Composable
private fun describe(p: RoutingProfile): String = when {
    // The shipped two say what they are for; a rule count tells you nothing about them.
    p.id == "builtin-global" -> stringResource(R.string.routing_desc_all)
    p.id == "builtin-lan" -> stringResource(R.string.routing_desc_lan)
    p.ruleCount == 0 && p.globalProxy -> stringResource(R.string.routing_desc_all)
    p.ruleCount == 0 -> stringResource(R.string.routing_desc_direct)
    else -> stringResource(R.string.routing_desc_rules, p.siteCount, p.ipCount)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileSheets(
    menuFor: RoutingProfile?,
    confirmDelete: RoutingProfile?,
    onCloseMenu: () -> Unit,
    onAskDelete: (RoutingProfile) -> Unit,
    onCloseDelete: () -> Unit,
    onEdit: (RoutingProfile) -> Unit,
    onDuplicate: (RoutingProfile) -> Unit,
    onExport: (RoutingProfile) -> Unit,
    onDelete: (RoutingProfile) -> Unit,
) {
    menuFor?.let { p ->
        // Editing is offered only for your own profiles. A subscription's routing is overwritten
        // by the next fetch, so an edit there would quietly vanish — copy it and edit the copy.
        val editable = !p.builtin && p.subUrl.isBlank()
        ModalBottomSheet(onDismissRequest = onCloseMenu, containerColor = Moon.Card) {
            SheetHeader(p.name, describe(p))
            if (editable) SheetItem(Icons.Filled.Edit, stringResource(R.string.routing_edit), { onEdit(p) })
            SheetItem(Icons.Filled.ContentCopy, stringResource(R.string.routing_duplicate), { onDuplicate(p) })
            SheetItem(Icons.Filled.Share, stringResource(R.string.routing_export), { onExport(p) })
            if (editable) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)
                    .height(1.dp).background(Moon.BorderSoft))
                SheetItem(Icons.Filled.Delete, stringResource(R.string.routing_delete), { onAskDelete(p) }, Moon.Danger)
            } else {
                Text(stringResource(R.string.routing_readonly_hint),
                     Modifier.padding(20.dp, 6.dp, 20.dp, 0.dp), color = Moon.TextMuted, fontSize = 11.5.sp)
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    confirmDelete?.let { p ->
        AlertDialog(
            onDismissRequest = onCloseDelete,
            title = { Text(stringResource(R.string.routing_delete_title), color = Moon.TextPrimary, fontSize = 15.sp) },
            text = { Text(stringResource(R.string.routing_delete_text, p.name), color = Moon.TextSecondary, fontSize = 13.sp) },
            confirmButton = {
                TextButton({ onDelete(p) }) { Text(stringResource(R.string.routing_delete), color = Moon.Danger) }
            },
            dismissButton = {
                TextButton(onCloseDelete) { Text(stringResource(R.string.settingsscreen_200), color = Moon.TextSecondary) }
            },
            containerColor = Moon.Card,
        )
    }
}

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

/**
 * Servers, mirroring the desktop tab: header with ping/refresh, add/paste, search,
 * protocol + sort chips, then the servers grouped by subscription (not one flat list).
 */
@Composable
fun ServersScreen(
    subscriptions: List<Subscription>,
    selected: ServerProfile?,
    pings: Map<String, Int>,
    isFavorite: (ServerProfile) -> Boolean,
    collapsed: Set<String>,
    sort: String,
    showSubHeader: Boolean,
    onSort: (String) -> Unit,
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
    listState: androidx.compose.foundation.lazy.LazyListState,
) {
    var query by remember { mutableStateOf("") }
    var protocol by remember { mutableStateOf("Все") }

    val all = subscriptions.flatMap { it.servers }
    val protocols = remember(all) { listOf("Все") + all.map { it.protocolLabel }.distinct().sorted() }

    fun visible(list: List<ServerProfile>) = list.filter { s ->
        (protocol == "Все" || s.protocolLabel == protocol) &&
        (query.isBlank() || s.label.contains(query, ignoreCase = true))
    }.let { filtered ->
        val fav = { s: ServerProfile -> if (isFavorite(s)) 0 else 1 }
        when (sort) {
            "ping" -> filtered.sortedWith(compareBy(fav, { pings[it.raw]?.takeIf { p -> p >= 0 } ?: Int.MAX_VALUE }, { it.label }))
            "name" -> filtered.sortedWith(compareBy(fav, { it.label.lowercase() }))
            "favorite" -> filtered.filter(isFavorite).ifEmpty { filtered }
            else -> filtered.sortedBy(fav)
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().background(Moon.WinBg).padding(horizontal = 16.dp),
        state = listState,
        contentPadding = PaddingValues(bottom = bottomNavSpace()),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Сервера", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Moon.TextPrimary)
                    Text("${all.size} серверов", fontSize = 12.sp, color = Moon.TextSecondary,
                         modifier = Modifier.padding(top = 2.dp))
                }
                IconButton(onPingAll) { Icon(Icons.Filled.Speed, "Пинговать все", tint = Moon.TextSecondary) }
                IconButton(onRefreshAll) { Icon(Icons.Filled.Refresh, "Обновить все", tint = Moon.TextSecondary) }
            }
        }

        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Surface(onClick = onAdd, shape = RoundedCornerShape(11.dp), color = Moon.Accent,
                        modifier = Modifier.weight(1f).padding(end = 4.dp)) {
                    IconLabel(Icons.Filled.Add, "Добавить", Color.White)
                }
                Surface(onClick = onPaste, shape = RoundedCornerShape(11.dp), color = Moon.Card,
                        border = androidx.compose.foundation.BorderStroke(1.dp, Moon.BorderSoft),
                        modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                    IconLabel(Icons.Filled.ContentPaste, "Вставить", Moon.TextPrimary)
                }
            }
        }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Поиск серверов…", color = Moon.TextMuted, fontSize = 13.5.sp) },
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
            ChipSection("ПРОТОКОЛ", protocols, protocol) { protocol = it }
            ChipSection(
                "СОРТИРОВКА",
                listOf("Обычная", "↕ Пинг", "А–Я", "★ Избранное"),
                when (sort) { "ping" -> "↕ Пинг"; "name" -> "А–Я"; "favorite" -> "★ Избранное"; else -> "Обычная" },
            ) {
                onSort(when (it) { "↕ Пинг" -> "ping"; "А–Я" -> "name"; "★ Избранное" -> "favorite"; else -> "default" })
            }
            Spacer(Modifier.height(4.dp))
        }

        items(subscriptions.size) { i ->
            val sub = subscriptions[i]
            val shown = visible(sub.servers)
            if (shown.isNotEmpty() || query.isBlank()) {
                SubGroup(
                    sub = sub, servers = shown, collapsed = sub.url in collapsed, showHeader = showSubHeader,
                    selected = selected, pings = pings, isFavorite = isFavorite,
                    onToggleCollapse = { onToggleCollapse(sub.url) },
                    onMenu = { onSubMenu(sub) },
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
private fun ChipSection(title: String, options: List<String>, selected: String, onPick: (String) -> Unit) {
    Column(Modifier.padding(top = 10.dp)) {
        SectionLabel(title, top = 0)
        ChipFlow(options.map { it to it }, selected, onPick)
    }
}

@Composable
private fun SubGroup(
    sub: Subscription,
    servers: List<ServerProfile>,
    collapsed: Boolean,
    showHeader: Boolean,
    selected: ServerProfile?,
    pings: Map<String, Int>,
    isFavorite: (ServerProfile) -> Boolean,
    onToggleCollapse: () -> Unit,
    onMenu: () -> Unit,
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
                        Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Download, null, tint = Moon.TextSecondary, modifier = Modifier.size(11.dp))
                            Text(" ${sub.trafficText}", color = Moon.TextSecondary, fontSize = 11.5.sp)
                            Spacer(Modifier.width(12.dp))
                            Icon(Icons.Filled.Event, null, tint = Moon.TextSecondary, modifier = Modifier.size(11.dp))
                            Text(" ${sub.expiryText}", color = Moon.TextSecondary, fontSize = 11.5.sp)
                        }
                    }
                }
                Row(Modifier.padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(9.dp), color = Moon.Accent) {
                        Text("${servers.size}", Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                             color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    IconButton(onPing, Modifier.size(30.dp)) {
                        Icon(Icons.Filled.Speed, null, tint = Moon.TextSecondary, modifier = Modifier.size(15.dp))
                    }
                    IconButton(onRefresh, Modifier.size(30.dp)) {
                        Icon(Icons.Filled.Refresh, null, tint = Moon.TextSecondary, modifier = Modifier.size(15.dp))
                    }
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

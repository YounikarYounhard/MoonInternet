package cc.moon.internet.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.moon.internet.R
import cc.moon.internet.core.RoutingProfile
import cc.moon.internet.data.AppState

/**
 * Settings, ported page-for-page from the desktop SettingsView.xaml — same hub order, same
 * sub-pages, same rows and wording. Windows-only rows (autostart, tray, window opacity,
 * background image) are the only omissions, each noted where it used to sit.
 */
enum class SettingsPage(
    @androidx.annotation.StringRes val titleRes: Int,
    @androidx.annotation.StringRes val subtitleRes: Int,
    val icon: ImageVector,
) {
    // Resource ids, not strings: an enum constructor runs outside composition so it cannot call
    // stringResource, and a string baked in here would never follow a language change.
    Appearance(R.string.page_appearance, R.string.page_appearance_sub, Icons.Filled.Palette),
    Connection(R.string.page_connection, R.string.page_connection_sub, Icons.Filled.Cable),
    Routing(R.string.page_routing, R.string.page_routing_sub, Icons.Filled.AltRoute),
    Subscriptions(R.string.page_subs, R.string.page_subs_sub, Icons.Filled.LibraryBooks),
    Ping(R.string.page_ping, R.string.page_ping_sub, Icons.Filled.Speed),
    Auto(R.string.page_auto, R.string.page_auto_sub, Icons.Filled.PlayCircle),
    Notifications(R.string.page_notify, R.string.page_notify_sub, Icons.Filled.Notifications),
    Logs(R.string.page_logs, R.string.page_logs_sub, Icons.Filled.Description),
    About(R.string.page_about, R.string.page_about_sub, Icons.Filled.Info),

    // reached from inside another page, never from the hub
    Privacy(R.string.page_privacy, R.string.page_privacy_sub, Icons.Filled.PrivacyTip),
    AppRouting(R.string.page_approuting, R.string.empty, Icons.Filled.Apps),
    Terms(R.string.page_terms, R.string.empty, Icons.Filled.Gavel),
    Libs(R.string.page_libs, R.string.empty, Icons.Filled.Code),
    ;

    companion object {
        /** Exactly the cards the desktop hub shows, in the same order. */
        val hub = listOf(Appearance, Connection, Routing, Subscriptions, Ping, Auto, Notifications, Logs, About)
    }
}

@Composable
fun SettingsScreen(state: AppState, onOpen: (SettingsPage) -> Unit) {
    Column(Modifier.fillMaxSize().background(Moon.WinBg)) {
        Column(Modifier.padding(start = 26.dp, end = 24.dp, top = 18.dp, bottom = 14.dp)) {
            // The pill sits on the title's own line, not centred on the title+version block —
            // that is where the desktop puts it.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settingsscreen_001), fontSize = 26.sp, fontWeight = FontWeight.Bold,
                     color = Moon.TextPrimary, modifier = Modifier.weight(1f))
                LanguageToggle()
            }
            Text("Moon Internet · $APP_VERSION", fontSize = 12.5.sp, color = Moon.TextSecondary,
                 modifier = Modifier.padding(top = 2.dp))
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 24.dp),
            contentPadding = PaddingValues(bottom = bottomNavSpace()),
        ) {
            items(SettingsPage.hub.size) { i ->
                val p = SettingsPage.hub[i]
                HubCard(p.icon, stringResource(p.titleRes), stringResource(p.subtitleRes)) { onOpen(p) }
            }
        }
    }
}

@Composable
fun SettingsDetail(
    page: SettingsPage,
    state: AppState,
    routing: RoutingProfile?,
    apps: List<AppEntry>,
    logsSize: String,
    xrayVersion: String,
    onBack: () -> Unit,
    onOpen: (SettingsPage) -> Unit,
    onSet: (AppState.() -> AppState) -> Unit,
    onRefresh: () -> Unit,
    onRemoveSub: (String) -> Unit,
    onAdd: () -> Unit,
    onOpenRouting: () -> Unit,
    onCopy: (String, String) -> Unit,
    onResetProxyCreds: () -> Unit,
    onClearLogs: () -> Unit,
    onViewLog: () -> Unit,
) {
    // The header is outside the scrolling area on purpose: the desktop page keeps its title and
    // back button fixed while only the body moves.
    Column(Modifier.fillMaxSize().background(Moon.WinBg)) {
        Spacer(Modifier.height(14.dp))
        Box(Modifier.padding(horizontal = 24.dp)) {
            val title = stringResource(page.titleRes)
            PageHeader(title, big = title.length < 20, onBack = onBack)
        }
        // A fresh scroll state per page: one shared state meant opening a short page after a
        // long one dropped you halfway down it, exactly like the desktop bug we fixed.
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 24.dp),
            state = androidx.compose.foundation.lazy.rememberLazyListState(),
            contentPadding = PaddingValues(bottom = bottomNavSpace()),
        ) {
            item {
            when (page) {
                SettingsPage.Appearance -> AppearancePage(state, onSet)
                SettingsPage.Connection -> ConnectionPage(state, onSet, onCopy, onResetProxyCreds)
                SettingsPage.Routing -> RoutingSettingsPage(state, routing, onSet, onOpenRouting, onOpen)
                SettingsPage.AppRouting -> AppRoutingPage(state, apps, onSet)
                SettingsPage.Subscriptions -> SubsPage(state, onSet, onRefresh, onRemoveSub, onAdd)
                SettingsPage.Ping -> PingPage(state, onSet)
                SettingsPage.Auto -> AutoPage(state, onSet)
                SettingsPage.Notifications -> NotificationsPage(state, onSet)
                SettingsPage.Logs -> LogsPage(state, logsSize, onSet, onClearLogs, onViewLog)
                SettingsPage.Privacy -> PrivacyPage()
                SettingsPage.About -> AboutPage(state, xrayVersion, onOpen, onCopy)
                SettingsPage.Terms -> TermsPage()
                SettingsPage.Libs -> LibsPage()
            }
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

/** One launchable app for the per-app routing list. */
data class AppEntry(val pkg: String, val label: String, val icon: android.graphics.drawable.Drawable?)

// ---------------------------------------------------------------- ОФОРМЛЕНИЕ
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearancePage(state: AppState, onSet: ((AppState.() -> AppState)) -> Unit) {
    Column {
        // Only the font is settable now. Themes, colour swatches and the language block are
        // gone — same cut as on the desktop build, so the two pages still match.
        SectionLabel(stringResource(R.string.settingsscreen_002), top = 0)
        MoonCard(padding = 14) {
            ChipFlow(listOf("comic" to stringResource(R.string.settingsscreen_003), "system" to stringResource(R.string.settingsscreen_004)), state.fontName) {
                onSet { copy(fontName = it) }
            }
        }
    }
}

// ---------------------------------------------------------------- ПОДКЛЮЧЕНИЕ
@Composable
private fun ConnectionPage(
    state: AppState,
    onSet: ((AppState.() -> AppState)) -> Unit,
    onCopy: (String, String) -> Unit,
    onResetCreds: () -> Unit,
) {
    Column {
        MoonCard {
            SwitchRow("Kill Switch", stringResource(R.string.settingsscreen_007),
                state.killSwitch) { v -> onSet { copy(killSwitch = v) } }
            RowDivider()
            SwitchRow(stringResource(R.string.settingsscreen_008), stringResource(R.string.settingsscreen_009),
                state.allowLan) { v -> onSet { copy(allowLan = v) } }
            SwitchRow(stringResource(R.string.settingsscreen_010), stringResource(R.string.settingsscreen_011),
                state.lanThroughProxy) { v -> onSet { copy(lanThroughProxy = v) } }
            RowDivider()
            SwitchRow(stringResource(R.string.settingsscreen_012), stringResource(R.string.settingsscreen_013),
                state.showProxyOnlyButton) { v -> onSet { copy(showProxyOnlyButton = v) } }
            RowDivider()
            Column(Modifier.padding(12.dp)) {
                Text(stringResource(R.string.settingsscreen_014), color = Moon.TextPrimary,
                     fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.settingsscreen_015), color = Moon.TextSecondary,
                     fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
                MoonTextField(state.proxyBypassHosts, { v -> onSet { copy(proxyBypassHosts = v) } })
            }
            RowDivider()
            Column(Modifier.padding(12.dp)) {
                Text(stringResource(R.string.settingsscreen_016), color = Moon.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("SOCKS5 127.0.0.1:${state.socksPort} · HTTP 127.0.0.1:${state.httpPort}",
                     color = Moon.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }

        SectionLabel(stringResource(R.string.settingsscreen_017))
        MoonCard {
            SwitchRow(stringResource(R.string.settingsscreen_018),
                stringResource(R.string.settingsscreen_019),
                state.socks5Auth) { v ->
                // Generate on the way in, like the desktop does: turning the switch on with empty
                // credentials left the accounts block out of the config, so nothing was protected.
                onSet {
                    if (v && proxyUser.isBlank())
                        copy(socks5Auth = true,
                             proxyUser = "moon_" + java.util.UUID.randomUUID().toString().replace("-", "").take(8),
                             proxyPass = java.util.UUID.randomUUID().toString().replace("-", "").take(12))
                    else copy(socks5Auth = v)
                }
            }
            if (state.socks5Auth) {
                val userToast = stringResource(R.string.settingsscreen_021)
                val passToast = stringResource(R.string.settingsscreen_023)
                RowDivider()
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settingsscreen_020), color = Moon.TextPrimary, fontSize = 14.sp,
                         fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(state.proxyUser, color = Moon.AccentText, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace)
                    IconButton({ onCopy(state.proxyUser, userToast) }, Modifier.size(30.dp)) {
                        Icon(Icons.Filled.ContentCopy, null, tint = Moon.TextSecondary, modifier = Modifier.size(15.dp))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(12.dp, 0.dp, 12.dp, 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settingsscreen_022), color = Moon.TextPrimary, fontSize = 14.sp,
                         fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("•".repeat(state.proxyPass.length.coerceAtMost(12)),
                         color = Moon.TextSecondary, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace)
                    IconButton({ onCopy(state.proxyPass, passToast) }, Modifier.size(30.dp)) {
                        Icon(Icons.Filled.ContentCopy, null, tint = Moon.TextSecondary, modifier = Modifier.size(15.dp))
                    }
                }
                Row(Modifier.clickable(onClick = onResetCreds).padding(12.dp, 4.dp, 12.dp, 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Refresh, null, tint = Moon.AccentText, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settingsscreen_024), color = Moon.AccentText, fontSize = 13.sp)
                }
            }
            RowDivider()
            SwitchRow(stringResource(R.string.settingsscreen_025),
                stringResource(R.string.settingsscreen_026),
                state.blockUdp) { v -> onSet { copy(blockUdp = v) } }
            RowDivider()
            SwitchRow(stringResource(R.string.settingsscreen_027), stringResource(R.string.settingsscreen_028),
                state.httpProxyAuth) { v -> onSet { copy(httpProxyAuth = v) } }
        }

        // Приоритет трафика — бета, выключено по умолчанию. Same three modes as the desktop.
        SectionLabel(stringResource(R.string.settingsscreen_029))
        MoonCard(padding = 14) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settingsscreen_030), color = Moon.TextPrimary, fontSize = 14.sp,
                     fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Surface(shape = RoundedCornerShape(6.dp), color = Color(0x33F5C042)) {
                    Text(stringResource(R.string.settingsscreen_031), color = Color(0xFFF5C042), fontSize = 9.5.sp,
                         fontWeight = FontWeight.Bold,
                         modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp))
                }
            }
            Text(
                stringResource(R.string.settingsscreen_032) +
                    stringResource(R.string.settingsscreen_033) +
                    stringResource(R.string.settingsscreen_034),
                color = Moon.TextSecondary, fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(10.dp))
            ChipFlow(
                listOf("off" to stringResource(R.string.settingsscreen_035), "balance" to stringResource(R.string.settingsscreen_036), "games" to stringResource(R.string.settingsscreen_037)),
                state.trafficPriority,
            ) { onSet { copy(trafficPriority = it) } }
        }
    }
}

// ------------------------------------------------------------- МАРШРУТИЗАЦИЯ
@Composable
private fun RoutingSettingsPage(
    state: AppState,
    routing: RoutingProfile?,
    onSet: ((AppState.() -> AppState)) -> Unit,
    onOpenRouting: () -> Unit,
    onOpen: (SettingsPage) -> Unit,
) {
    MoonCard {
        SwitchRow(stringResource(R.string.settingsscreen_038), stringResource(R.string.settingsscreen_039),
            state.useRouting) { v -> onSet { copy(useRouting = v) } }
        RowDivider()
        NavRow(stringResource(R.string.settingsscreen_040),
               stringResource(R.string.fmt_routing_sub, routing?.name ?: stringResource(R.string.none)), onOpenRouting)
        // Next to the profile picker, not below the tunnel switches: both rows answer "what goes
        // through the tunnel", so the two doors into that belong side by side.
        NavRow(stringResource(R.string.settingsscreen_047), stringResource(R.string.settingsscreen_048)) {
            onOpen(SettingsPage.AppRouting)
        }
        RowDivider()
        SwitchRow(stringResource(R.string.settingsscreen_041), stringResource(R.string.settingsscreen_042),
            state.tlsFragment) { v -> onSet { copy(tlsFragment = v) } }
        SwitchRow(stringResource(R.string.settingsscreen_043), stringResource(R.string.settingsscreen_044),
            state.mux) { v -> onSet { copy(mux = v) } }
        SwitchRow(stringResource(R.string.settingsscreen_045), stringResource(R.string.settingsscreen_046),
            state.sniffing) { v -> onSet { copy(sniffing = v) } }
        RowDivider()
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settingsscreen_049), color = Moon.TextPrimary, fontSize = 14.sp,
                     fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(state.preferredIp.uppercase(), color = Moon.AccentText, fontSize = 13.sp,
                     fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            Segmented(listOf("ipv4" to "IPv4", "ipv6" to "IPv6", "auto" to "AUTO"), state.preferredIp) { v ->
                onSet { copy(preferredIp = v) }
            }
        }
        RowDivider()
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("VPN DNS", color = Moon.TextPrimary, fontSize = 14.sp,
                     fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(dnsLabel(state.vpnDns), color = Moon.AccentText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            ChipFlow(
                listOf("cf_google" to "Cloudflare + Google", "google" to "Google DNS",
                       "cloudflare" to "Cloudflare", "quad9" to "Quad9", "custom" to stringResource(R.string.settingsscreen_050)),
                state.vpnDns,
            ) { v -> onSet { copy(vpnDns = v) } }
            if (state.vpnDns == "custom") {
                Spacer(Modifier.height(8.dp))
                MoonTextField(state.vpnDnsCustom, { v -> onSet { copy(vpnDnsCustom = v) } }, "8.8.8.8, 1.1.1.1")
                Text(stringResource(R.string.settingsscreen_051), color = Moon.TextSecondary,
                     fontSize = 11.sp, modifier = Modifier.padding(start = 2.dp, top = 4.dp))
            }
        }
    }
}

@Composable
private fun dnsLabel(id: String) = when (id) {
    "google" -> "Google DNS"
    "cloudflare" -> "Cloudflare"
    "quad9" -> "Quad9"
    "custom" -> stringResource(R.string.settingsscreen_050)
    else -> "Cloudflare + Google"
}

// ------------------------------------------------- ПРОКСИ ПО ПРИЛОЖЕНИЯМ
@Composable
private fun AppRoutingPage(
    state: AppState,
    apps: List<AppEntry>,
    onSet: ((AppState.() -> AppState)) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    Column {
        MoonCard(padding = 16) {
            Text(stringResource(R.string.settingsscreen_052), color = Moon.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.settingsscreen_053),
                 color = Moon.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(10.dp))
            ChipFlow(
                listOf("off" to stringResource(R.string.settingsscreen_054), "bypass" to stringResource(R.string.settingsscreen_055), "only" to stringResource(R.string.settingsscreen_056)),
                state.perAppMode,
            ) { v -> onSet { copy(perAppMode = v) } }
        }

        if (state.perAppMode != "off") {
            Spacer(Modifier.height(12.dp))
            MoonTextField(query, { query = it }, stringResource(R.string.settingsscreen_057))
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.fmt_selected, state.perApps.size), color = Moon.TextSecondary, fontSize = 12.sp,
                     modifier = Modifier.weight(1f))
                if (state.perApps.isNotEmpty()) {
                    Text(stringResource(R.string.settingsscreen_058), color = Moon.AccentText, fontSize = 12.sp,
                         modifier = Modifier.clickable { onSet { copy(perApps = emptyList()) } }.padding(6.dp))
                }
            }
            Spacer(Modifier.height(6.dp))

            // Chosen ones first: the list is hundreds of apps long and the ones you picked were
            // scattered through it alphabetically.
            val chosen = state.perApps.toSet()
            val ordered = apps.sortedWith(compareBy({ if (it.pkg in chosen) 0 else 1 }, { it.label.lowercase() }))
            val shown = ordered.filter {
                query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true)
            }
            MoonCard(padding = 0) {
                if (shown.isEmpty()) {
                    Text(stringResource(R.string.settingsscreen_059), Modifier.padding(14.dp), color = Moon.TextMuted, fontSize = 12.sp)
                }
                shown.forEachIndexed { i, app ->
                    if (i > 0) RowDivider(inset = 14)
                    val checked = app.pkg in state.perApps
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            onSet { copy(perApps = if (checked) perApps - app.pkg else perApps + app.pkg) }
                        }.padding(14.dp, 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val icon = app.icon
                        if (icon != null) {
                            Image(rememberDrawablePainter(icon), null, Modifier.size(30.dp))
                        } else {
                            Icon(Icons.Filled.Android, null, tint = Moon.TextSecondary, modifier = Modifier.size(30.dp))
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp, end = 10.dp)) {
                            Text(app.label, color = Moon.TextPrimary, fontSize = 13.5.sp,
                                 maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(app.pkg, color = Moon.TextMuted, fontSize = 10.sp,
                                 maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Checkbox(checked, onCheckedChange = null,
                                 colors = CheckboxDefaults.colors(checkedColor = Moon.Accent))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- ПОДПИСКИ
@Composable
private fun SubsPage(
    state: AppState,
    onSet: ((AppState.() -> AppState)) -> Unit,
    onRefresh: () -> Unit,
    onRemoveSub: (String) -> Unit,
    onAdd: () -> Unit,
) {
    Column {
        MoonCard {
            SwitchRow(stringResource(R.string.settingsscreen_060), stringResource(R.string.settingsscreen_061),
                state.autoUpdateSubs) { v -> onSet { copy(autoUpdateSubs = v) } }
            if (state.autoUpdateSubs) {
                Column(Modifier.padding(12.dp, 0.dp, 12.dp, 10.dp)) {
                    ChipFlow(
                        listOf("0" to stringResource(R.string.settingsscreen_062), "30" to stringResource(R.string.settingsscreen_063), "60" to stringResource(R.string.settingsscreen_064), "120" to stringResource(R.string.settingsscreen_065),
                               "360" to stringResource(R.string.settingsscreen_066), "720" to stringResource(R.string.settingsscreen_067), "1440" to stringResource(R.string.settingsscreen_068)),
                        state.subIntervalMinutes.toString(),
                    ) { v -> onSet { copy(subIntervalMinutes = v.toInt()) } }
                }
            }
            RowDivider()
            SwitchRow(stringResource(R.string.settingsscreen_069), stringResource(R.string.settingsscreen_070),
                state.notifyOnUpdate) { v -> onSet { copy(notifyOnUpdate = v) } }
            RowDivider()
            SwitchRow(stringResource(R.string.settingsscreen_071), stringResource(R.string.settingsscreen_072),
                state.updateOnStart) { v -> onSet { copy(updateOnStart = v) } }
            RowDivider()
            SwitchRow(stringResource(R.string.settingsscreen_073), stringResource(R.string.settingsscreen_074),
                state.pingOnStart) { v -> onSet { copy(pingOnStart = v) } }
            RowDivider()
            SwitchRow(stringResource(R.string.settingsscreen_075), stringResource(R.string.settingsscreen_076),
                state.sendHwid) { v -> onSet { copy(sendHwid = v) } }
            RowDivider()
            SwitchRow(stringResource(R.string.settingsscreen_077), stringResource(R.string.settingsscreen_078),
                state.showSubHeader) { v -> onSet { copy(showSubHeader = v) } }

            RowDivider()
            SwitchRow(stringResource(R.string.settingsscreen_079), stringResource(R.string.settingsscreen_080),
                state.showServerCount) { v -> onSet { copy(showServerCount = v) } }
            RowDivider()
            SwitchRow(stringResource(R.string.pin_active), stringResource(R.string.pin_active_sub),
                state.pinActive) { v -> onSet { copy(pinActive = v) } }
            RowDivider()
            Column(Modifier.padding(12.dp)) {
                Text(stringResource(R.string.submeter_title), color = Moon.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.submeter_sub), color = Moon.TextSecondary, fontSize = 12.sp,
                     modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.height(8.dp))
                ChipFlow(
                    listOf("text" to stringResource(R.string.submeter_text),
                           "bar" to stringResource(R.string.submeter_bar),
                           "dots" to stringResource(R.string.submeter_dots)),
                    state.subMeter,
                ) { v -> onSet { copy(subMeter = v) } }
            }
        }

        Spacer(Modifier.height(10.dp))
        MoonCard {
            SwitchRow(stringResource(R.string.notify_traffic), stringResource(R.string.notify_traffic_sub),
                state.notifyTrafficLow) { v -> onSet { copy(notifyTrafficLow = v) } }
            RowDivider()
            SwitchRow(stringResource(R.string.settingsscreen_081), stringResource(R.string.settingsscreen_082),
                state.notifyExpiry) { v -> onSet { copy(notifyExpiry = v) } }
            if (state.notifyExpiry) {
                // caption above the chips, not beside them: next to four chips it had room for
                // three words in Russian and wrapped to three lines in English
                Column(Modifier.padding(12.dp, 0.dp, 12.dp, 10.dp)) {
                    Text(stringResource(R.string.settingsscreen_083), color = Moon.TextSecondary, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        listOf(1, 3, 5, 7).forEach { d ->
                            Chip("$d", state.expiryNotifyDays == d) { onSet { copy(expiryNotifyDays = d) } }
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        state.subscriptions.forEach { sub ->
            MoonCard(padding = 0) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(sub.name, color = Moon.TextPrimary, fontSize = 13.5.sp,
                             fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (sub.expiryText == "∞") stringResource(R.string.fmt_sub_meta_noexp, sub.servers.size, sub.trafficText)
                             else stringResource(R.string.fmt_sub_meta, sub.servers.size, sub.trafficText, sub.expiryText),
                             color = Moon.TextSecondary, fontSize = 11.5.sp)
                    }
                    TextButton({ onRemoveSub(sub.url) }) { Text(stringResource(R.string.settingsscreen_084), color = Moon.Danger, fontSize = 12.5.sp) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(stringResource(R.string.settingsscreen_085), Modifier.weight(1f), onAdd)
            SecondaryButton(stringResource(R.string.settingsscreen_086), Modifier.weight(1f), onRefresh)
        }
    }
}

// ------------------------------------------------------------------- ПИНГ
/** The one description that matches the picked method. Plain function, no composition needed. */
@androidx.annotation.StringRes
private fun pingHint(method: String) = when (method) {
    "tcp" -> R.string.ping_desc_tcp
    "httpget" -> R.string.ping_desc_httpget
    "httphead" -> R.string.ping_desc_httphead
    "stability" -> R.string.ping_desc_stability
    else -> R.string.ping_desc_moon
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PingPage(state: AppState, onSet: ((AppState.() -> AppState)) -> Unit) {
    Column {
        SectionLabel(stringResource(R.string.ping_kind), top = 0)
        ChipFlow(
            listOf("moon" to "Moon Ping", "tcp" to "TCP", "httpget" to "HTTP GET",
                   "httphead" to "HTTP HEAD"),
            state.pingMethod,
        ) { v -> onSet { copy(pingMethod = v) } }
        // One line about the method you actually picked, instead of a paragraph covering all of
        // them at once. Nothing here while Stability is on: none of these four chips is selected
        // then, so a description under them described a choice that was not made — and the card
        // below says the same thing a second time.
        if (state.pingMethod != "stability") {
            Text(stringResource(pingHint(state.pingMethod)),
                 color = Moon.TextSecondary, fontSize = 11.5.sp, lineHeight = 16.sp,
                 modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 16.dp))
        } else {
            Spacer(Modifier.height(16.dp))
        }

        // Стабильность стоит отдельно: она поднимает настоящее соединение, работает заметно
        // дольше остальных и пока в бете.
        MoonCard(padding = 14) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settingsscreen_088), color = Moon.TextPrimary,
                     fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Surface(shape = RoundedCornerShape(6.dp), color = Color(0x33F5C042)) {
                    Text(stringResource(R.string.settingsscreen_031), Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                         color = Color(0xFFF5C042), fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text(stringResource(R.string.ping_desc_stability),
                 color = Moon.TextSecondary, fontSize = 11.5.sp, lineHeight = 16.sp,
                 modifier = Modifier.padding(top = 6.dp, bottom = 10.dp))
            ChipFlow(listOf("stability" to stringResource(R.string.ping_use_stability)), state.pingMethod) { v ->
                onSet { copy(pingMethod = if (pingMethod == "stability") "moon" else v) }
            }
        }
        Spacer(Modifier.height(16.dp))

        SectionLabel(stringResource(R.string.settingsscreen_094), top = 0)
        MoonCard {
            SwitchRow(stringResource(R.string.settingsscreen_095),
                stringResource(R.string.settingsscreen_096) +
                    stringResource(R.string.settingsscreen_097),
                state.pingStagger) { v -> onSet { copy(pingStagger = v) } }
            if (state.pingStagger) {
                RowDivider()
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.settingsscreen_098), color = Moon.TextSecondary, fontSize = 11.5.sp)
                    Spacer(Modifier.height(8.dp))
                    ChipFlow(listOf("50" to stringResource(R.string.settingsscreen_099), "150" to stringResource(R.string.settingsscreen_100), "300" to stringResource(R.string.settingsscreen_101)),
                             state.pingStaggerMs.toString()) { v -> onSet { copy(pingStaggerMs = v.toInt()) } }
                }
            }
        }

        SectionLabel(stringResource(R.string.settingsscreen_102))
        MoonCard(padding = 14) {
            Text(stringResource(R.string.settingsscreen_103),
                 color = Moon.TextSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            ChipFlow(
                listOf("0" to stringResource(R.string.settingsscreen_104), "1" to stringResource(R.string.settingsscreen_105), "5" to stringResource(R.string.settingsscreen_106), "10" to stringResource(R.string.settingsscreen_107),
                       "15" to stringResource(R.string.settingsscreen_108), "20" to stringResource(R.string.settingsscreen_109), "30" to stringResource(R.string.settingsscreen_063)),
                state.pingEveryMinutes.toString(),
            ) { v -> onSet { copy(pingEveryMinutes = v.toInt()) } }
        }

        // top = 0 glued it to the АВТОПРОВЕРКА card above; every other section keeps the default gap
        SectionLabel(stringResource(R.string.settingsscreen_110))
        ChipFlow(
            listOf("num" to stringResource(R.string.settingsscreen_111), "bar" to stringResource(R.string.settingsscreen_112), "both" to stringResource(R.string.settingsscreen_113), "dots" to stringResource(R.string.settingsscreen_114)),
            state.pingDisplay,
        ) { v -> onSet { copy(pingDisplay = v) } }

        SectionLabel(stringResource(R.string.settingsscreen_115), top = 14)
        MoonTextField(state.pingTestUrl, { v -> onSet { copy(pingTestUrl = v) } })
        Text(stringResource(R.string.settingsscreen_116), color = Moon.TextSecondary, fontSize = 12.sp,
             modifier = Modifier.padding(start = 2.dp, top = 10.dp, bottom = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "Google 204" to "https://www.gstatic.com/generate_204",
                "Cloudflare" to "https://cp.cloudflare.com/generate_204",
                "Apple" to "https://captive.apple.com",
            ).forEach { (name, url) -> SecondaryButton(name) { onSet { copy(pingTestUrl = url) } } }
        }
        Text(stringResource(R.string.settingsscreen_117),
             color = Moon.TextSecondary, fontSize = 11.5.sp, lineHeight = 16.sp,
             modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 14.dp))

        SectionLabel(stringResource(R.string.settingsscreen_118), top = 0)
        MoonCard(padding = 0) {
            Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settingsscreen_119), color = Moon.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.settingsscreen_120), color = Moon.TextSecondary, fontSize = 12.sp,
                         modifier = Modifier.padding(top = 2.dp))
                }
                IconButton({ onSet { copy(pingTimeoutMs = (pingTimeoutMs - 1000).coerceAtLeast(1000)) } },
                           Modifier.size(30.dp)) {
                    Icon(Icons.Filled.Remove, stringResource(R.string.settingsscreen_121), tint = Moon.TextSecondary, modifier = Modifier.size(17.dp))
                }
                Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF2C2058)) {
                    Text(stringResource(R.string.fmt_seconds, state.pingTimeoutMs / 1000), Modifier.padding(10.dp, 4.dp).widthIn(min = 26.dp),
                         color = Moon.AccentText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                         textAlign = TextAlign.Center)
                }
                IconButton({ onSet { copy(pingTimeoutMs = (pingTimeoutMs + 1000).coerceAtMost(15000)) } },
                           Modifier.size(30.dp)) {
                    Icon(Icons.Filled.Add, stringResource(R.string.settingsscreen_122), tint = Moon.TextSecondary, modifier = Modifier.size(17.dp))
                }
            }
        }
    }
}

// -------------------------------------------------------------------- АВТО
@Composable
private fun AutoPage(state: AppState, onSet: ((AppState.() -> AppState)) -> Unit) {
    MoonCard {
        SwitchRow(stringResource(R.string.settingsscreen_123), stringResource(R.string.settingsscreen_124),
            state.autoConnect) { v -> onSet { copy(autoConnect = v) } }
        if (state.autoConnect) {
            Column(Modifier.padding(12.dp, 0.dp, 12.dp, 12.dp)) {
                Text(stringResource(R.string.settingsscreen_125), color = Moon.TextSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                ChipFlow(
                    listOf("first" to stringResource(R.string.settingsscreen_126), "last" to stringResource(R.string.settingsscreen_127),
                           "lowest" to stringResource(R.string.settingsscreen_128), "favorite-first" to stringResource(R.string.settingsscreen_129)),
                    if (state.autoConnectTarget.startsWith("favorite")) "favorite-first" else state.autoConnectTarget,
                ) { v -> onSet { copy(autoConnectTarget = v) } }

                if (state.autoConnectTarget.startsWith("favorite")) {
                    Text(stringResource(R.string.settingsscreen_130), color = Moon.TextSecondary, fontSize = 12.sp,
                         modifier = Modifier.padding(top = 12.dp))
                    Spacer(Modifier.height(8.dp))
                    ChipFlow(
                        listOf("favorite-first" to stringResource(R.string.settingsscreen_131), "favorite-last" to stringResource(R.string.settingsscreen_132),
                               "favorite-lowest" to stringResource(R.string.settingsscreen_128)),
                        state.autoConnectTarget,
                    ) { v -> onSet { copy(autoConnectTarget = v) } }
                }
            }
        }
        RowDivider()
        // Auto-reconnect lives here, not on Подключение: it is the same family as auto-connect,
        // and one place to look beats two.
        SwitchRow(stringResource(R.string.settingsscreen_005), stringResource(R.string.settingsscreen_006),
            state.autoReconnect) { v -> onSet { copy(autoReconnect = v) } }
        if (state.autoReconnect) {
            Column(Modifier.padding(12.dp, 0.dp, 12.dp, 12.dp)) {
                Text(stringResource(R.string.auto_delay), color = Moon.TextSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                ChipFlow(listOf("3" to "3 с", "5" to "5 с", "10" to "10 с", "30" to "30 с"),
                         state.reconnectDelaySec.toString()) { v -> onSet { copy(reconnectDelaySec = v.toInt()) } }
            }
        }
        RowDivider()
        SwitchRow(stringResource(R.string.auto_failover), stringResource(R.string.auto_failover_sub),
            state.autoFailover) { v -> onSet { copy(autoFailover = v) } }
        RowDivider()
        SwitchRow(stringResource(R.string.auto_boot), stringResource(R.string.auto_boot_sub),
            state.startOnBoot) { v -> onSet { copy(startOnBoot = v) } }
    }

    // Two things only the system can grant. Both are one screen away and neither can be set for
    // the user, so these rows take them straight there instead of explaining where to look.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    SectionLabel(stringResource(R.string.auto_permissions))
    MoonCard {
        val ignoring = remember {
            val pm = ctx.getSystemService(android.os.PowerManager::class.java)
            android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M ||
                pm.isIgnoringBatteryOptimizations(ctx.packageName)
        }
        NavRow(
            stringResource(R.string.auto_battery),
            stringResource(if (ignoring) R.string.auto_battery_done else R.string.auto_battery_sub),
        ) {
            runCatching {
                ctx.startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:" + ctx.packageName),
                    )
                )
            }
        }
        RowDivider()
        NavRow(stringResource(R.string.auto_alwayson), stringResource(R.string.auto_alwayson_sub)) {
            // No API can turn this on for us — it lives in the system VPN screen by design.
            runCatching { ctx.startActivity(android.content.Intent("android.net.vpn.SETTINGS")) }
                .onFailure { runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS)) } }
        }
    }
}

// ---------------------------------------------------------------- УВЕДОМЛЕНИЯ
@Composable
private fun NotificationsPage(state: AppState, onSet: ((AppState.() -> AppState)) -> Unit) {
    Column {
        MoonCard {
            SwitchRow(stringResource(R.string.notify_master), stringResource(R.string.notify_master_sub),
                state.notificationsEnabled) { v -> onSet { copy(notificationsEnabled = v) } }
            // everything below is dead while the master switch is off, so it hides
            if (state.notificationsEnabled) {
                RowDivider()
                val ctx = androidx.compose.ui.platform.LocalContext.current
                SwitchRow(stringResource(R.string.notify_headsup), stringResource(R.string.notify_headsup_sub),
                    state.notifyHeadsUp) { v ->
                    // mirrored into prefs: the service picks its channel before the state file loads
                    cc.moon.internet.data.Lang.setHeadsUp(ctx, v)
                    onSet { copy(notifyHeadsUp = v) }
                }
            }
        }
        if (state.notificationsEnabled) {
            SectionLabel(stringResource(R.string.notify_section))
            MoonCard {
                SwitchRow(stringResource(R.string.notify_conn), stringResource(R.string.notify_conn_sub),
                    state.notifyConnection) { v -> onSet { copy(notifyConnection = v) } }
                RowDivider()
                SwitchRow(stringResource(R.string.notify_upd), stringResource(R.string.notify_upd_sub),
                    state.notifyAppUpdate) { v -> onSet { copy(notifyAppUpdate = v) } }
                RowDivider()
                SwitchRow(stringResource(R.string.notify_after), stringResource(R.string.notify_after_sub),
                    state.notifyAfterUpdate) { v -> onSet { copy(notifyAfterUpdate = v) } }
            }
        }
    }
}

// -------------------------------------------------------------------- ЛОГИ
@Composable
private fun LogsPage(
    state: AppState,
    logsSize: String,
    onSet: ((AppState.() -> AppState)) -> Unit,
    onClear: () -> Unit,
    onViewLog: () -> Unit,
) {
    Column {
        MoonCard {
            SwitchRow(stringResource(R.string.settingsscreen_133), stringResource(R.string.settingsscreen_134),
                state.logsEnabled) { v -> onSet { copy(logsEnabled = v) } }
            if (state.logsEnabled) {
                RowDivider()
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.settingsscreen_135), color = Moon.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    ChipFlow(
                        listOf("error" to stringResource(R.string.settingsscreen_136), "warning" to stringResource(R.string.settingsscreen_137),
                               "info" to stringResource(R.string.settingsscreen_138), "debug" to stringResource(R.string.settingsscreen_139)),
                        state.logLevel,
                    ) { v -> onSet { copy(logLevel = v) } }
                }
                RowDivider()
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.settingsscreen_140), color = Moon.TextPrimary, fontSize = 14.sp,
                         fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.settingsscreen_141), color = Moon.TextSecondary,
                         fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                    Spacer(Modifier.height(8.dp))
                    ChipFlow(
                        listOf("1" to stringResource(R.string.settingsscreen_142), "3" to stringResource(R.string.settingsscreen_143), "7" to stringResource(R.string.settingsscreen_144),
                               "30" to stringResource(R.string.settingsscreen_145), "0" to stringResource(R.string.settingsscreen_146)),
                        state.logKeepDays.toString(),
                    ) { v -> onSet { copy(logKeepDays = v.toInt()) } }
                }
            }
            RowDivider()
            ValueRow(stringResource(R.string.settingsscreen_147), logsSize, inset = 12)
        }
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(stringResource(R.string.settingsscreen_148), onClick = onViewLog)
            SecondaryButton(stringResource(R.string.settingsscreen_149), onClick = onClear)
        }
    }
}

// ------------------------------------------------- ПОЛИТИКА / УСЛОВИЯ / ЛИБЫ
@Composable
private fun PrivacyPage() {
    MoonCard(padding = 0) {
        Column(Modifier.padding(18.dp, 16.dp)) {
            Paragraph(stringResource(R.string.settingsscreen_150))
            SubHeading(stringResource(R.string.settingsscreen_151))
            Paragraph(stringResource(R.string.settingsscreen_152) +
                      stringResource(R.string.settingsscreen_153))
            SubHeading(stringResource(R.string.settingsscreen_154))
            Paragraph(stringResource(R.string.settingsscreen_155) +
                      stringResource(R.string.settingsscreen_156))
            SubHeading(stringResource(R.string.settingsscreen_157))
            Paragraph(stringResource(R.string.settingsscreen_158) +
                      stringResource(R.string.settingsscreen_159) +
                      stringResource(R.string.settingsscreen_160))
            SubHeading(stringResource(R.string.settingsscreen_161))
            Paragraph(stringResource(R.string.settingsscreen_162) +
                      stringResource(R.string.settingsscreen_163) +
                      stringResource(R.string.settingsscreen_164))
            SubHeading(stringResource(R.string.settingsscreen_165))
            Paragraph(stringResource(R.string.settingsscreen_166))
            SubHeading(stringResource(R.string.settingsscreen_167))
            Paragraph(stringResource(R.string.settingsscreen_168) +
                      stringResource(R.string.settingsscreen_169))
            SubHeading(stringResource(R.string.settingsscreen_170))
            Paragraph(stringResource(R.string.settingsscreen_171))
        }
    }
}

@Composable
private fun TermsPage() {
    MoonCard(padding = 0) {
        Column(Modifier.padding(18.dp, 16.dp)) {
            Paragraph(stringResource(R.string.settingsscreen_172) +
                      stringResource(R.string.settingsscreen_173))
            Paragraph(stringResource(R.string.settingsscreen_174) +
                      stringResource(R.string.settingsscreen_175) +
                      stringResource(R.string.settingsscreen_176), top = 12)
            Paragraph(stringResource(R.string.settingsscreen_177), top = 12)
        }
    }
}

@Composable
private fun LibsPage() {
    MoonCard(padding = 0) {
        Column(Modifier.padding(18.dp, 14.dp)) {
            Text(stringResource(R.string.settingsscreen_178), color = Moon.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("• xray-core — MPL-2.0\n• AndroidLibXrayLite — GPL-3.0",
                 color = Moon.TextSecondary, fontSize = 12.5.sp, lineHeight = 20.sp,
                 modifier = Modifier.padding(top = 6.dp))
            Text(stringResource(R.string.settingsscreen_179), color = Moon.TextPrimary, fontSize = 13.sp,
                 fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp))
            Text("• Jetpack Compose — Apache-2.0\n• kotlinx.serialization — Apache-2.0\n" +
                 "• kotlinx.coroutines — Apache-2.0\n• ZXing — Apache-2.0\n• Shantell Sans — OFL-1.1",
                 color = Moon.TextSecondary, fontSize = 12.5.sp, lineHeight = 20.sp,
                 modifier = Modifier.padding(top = 6.dp))
            Text(stringResource(R.string.settingsscreen_180),
                 color = Moon.TextMuted, fontSize = 11.5.sp, modifier = Modifier.padding(top = 14.dp))
        }
    }
}

// ------------------------------------------------------------ О ПРИЛОЖЕНИИ
@Composable
private fun AboutPage(
    state: AppState,
    xrayVersion: String,
    onOpen: (SettingsPage) -> Unit,
    onCopy: (String, String) -> Unit,
) {
    Column {
        SectionLabel(stringResource(R.string.settingsscreen_181), top = 0)
        MoonCard(padding = 0) {
            ValueRow(stringResource(R.string.settingsscreen_182), xrayVersion)
            RowDivider(inset = 14)
            ValueRow(stringResource(R.string.settingsscreen_183), APP_VERSION)
        }

        SectionLabel(stringResource(R.string.settingsscreen_184))
        MoonCard(padding = 0) {
            NavRow(stringResource(R.string.settingsscreen_185)) { onOpen(SettingsPage.Terms) }
            RowDivider(inset = 14)
            NavRow(stringResource(R.string.settingsscreen_186)) { onOpen(SettingsPage.Privacy) }
            RowDivider(inset = 14)
            NavRow(stringResource(R.string.settingsscreen_187)) { onOpen(SettingsPage.Libs) }
        }
        Text(stringResource(R.string.settingsscreen_188) +
             stringResource(R.string.settingsscreen_189),
             color = Moon.TextSecondary, fontSize = 11.5.sp, textAlign = TextAlign.Center,
             modifier = Modifier.fillMaxWidth().padding(10.dp, 12.dp, 10.dp, 0.dp))

        SectionLabel(stringResource(R.string.settingsscreen_190))
        MoonCard(padding = 0) {
            ValueRow(stringResource(R.string.settingsscreen_191), "Android ${android.os.Build.VERSION.RELEASE}")
            RowDivider(inset = 14)
            ValueRow(stringResource(R.string.settingsscreen_192), android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
            RowDivider(inset = 14)
            ValueRow(stringResource(R.string.settingsscreen_193), "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            RowDivider(inset = 14)
            val hwidToast = stringResource(R.string.settingsscreen_194)
            Row(Modifier.fillMaxWidth().padding(14.dp, 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("HWID", color = Moon.TextPrimary, fontSize = 13.5.sp)
                Text(state.hwid, color = Moon.TextSecondary, fontSize = 11.5.sp,
                     maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
                     modifier = Modifier.weight(1f).padding(start = 10.dp, end = 6.dp))
                IconButton({ onCopy(state.hwid, hwidToast) }, Modifier.size(30.dp)) {
                    Icon(Icons.Filled.ContentCopy, null, tint = Moon.TextSecondary, modifier = Modifier.size(15.dp))
                }
            }
        }
    }
}

@Composable
fun AddDialog(
    onDismiss: () -> Unit,
    onConfirm: (url: String, name: String) -> Unit,
    onScan: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settingsscreen_195), color = Moon.TextPrimary) },
        text = {
            Column {
                Text(stringResource(R.string.settingsscreen_196), fontSize = 12.sp, color = Moon.TextSecondary)
                Spacer(Modifier.height(8.dp))
                MoonTextField(text, { text = it }, stringResource(R.string.settingsscreen_197))
                Spacer(Modifier.height(8.dp))
                // Optional, same as on the desktop: a name of your own beats whatever the panel
                // calls itself, and with two subscriptions from one provider that is the only way
                // to tell them apart.
                MoonTextField(name, { name = it }, stringResource(R.string.add_name_hint))
                Spacer(Modifier.height(12.dp))

                // Paste does not fill the field for you to press Добавить afterwards — there is
                // nothing to decide between those two taps, so it adds.
                AddDialogRow(
                    Icons.Filled.ContentPaste,
                    stringResource(R.string.add_paste),
                    stringResource(R.string.add_paste_sub),
                ) {
                    val fromClip = clipboard.getText()?.text?.trim().orEmpty()
                    if (fromClip.isNotEmpty()) onConfirm(fromClip, name)
                }
                Spacer(Modifier.height(8.dp))
                AddDialogRow(
                    Icons.Filled.QrCodeScanner,
                    stringResource(R.string.settingsscreen_198),
                    stringResource(R.string.settingsscreen_199),
                    onScan,
                )
            }
        },
        confirmButton = { TextButton({ onConfirm(text, name) }) { Text(stringResource(R.string.settingsscreen_195)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.settingsscreen_200), color = Moon.TextSecondary) } },
        containerColor = Moon.Card,
    )
}

/** Full-width row inside the add dialog: icon, title, one line of explanation. */
@Composable
private fun AddDialogRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .background(Moon.ChipBg, RoundedCornerShape(11.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Moon.AccentText, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, color = Moon.TextPrimary, fontSize = 13.5.sp)
            Text(subtitle, color = Moon.TextSecondary, fontSize = 11.sp)
        }
    }
}

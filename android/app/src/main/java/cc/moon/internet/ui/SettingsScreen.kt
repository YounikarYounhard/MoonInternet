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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.moon.internet.core.RoutingProfile
import cc.moon.internet.data.AppState

/**
 * Settings, ported page-for-page from the desktop SettingsView.xaml — same hub order, same
 * sub-pages, same rows and wording. Windows-only rows (autostart, tray, window opacity,
 * background image) are the only omissions, each noted where it used to sit.
 */
enum class SettingsPage(val title: String, val subtitle: String, val icon: ImageVector) {
    Appearance("Оформление", "Шрифт", Icons.Filled.Palette),
    Connection("Подключение", "Локальный прокси, LAN, UDP", Icons.Filled.Cable),
    Routing("Маршрутизация", "Профили, правила, приложения, DNS", Icons.Filled.AltRoute),
    Subscriptions("Настройки подписок", "Обновление, срок, авто-обновление", Icons.Filled.LibraryBooks),
    Ping("Настройки пинга", "Как и когда измерять задержку", Icons.Filled.Speed),
    Auto("Авто", "Автоподключение, выбор сервера", Icons.Filled.PlayCircle),
    Logs("Логи", "Диагностика и журналы", Icons.Filled.Description),
    About("О приложении", "Версии, ссылки, система", Icons.Filled.Info),

    // reached from inside another page, never from the hub
    Privacy("Политика конфиденциальности", "Как приложение обращается с данными", Icons.Filled.PrivacyTip),
    AppRouting("Прокси по приложениям", "", Icons.Filled.Apps),
    Terms("Условия использования", "", Icons.Filled.Gavel),
    Libs("Сторонние библиотеки", "", Icons.Filled.Code),
    ;

    companion object {
        /** Exactly the cards the desktop hub shows, in the same order. */
        val hub = listOf(Appearance, Connection, Routing, Subscriptions, Ping, Auto, Logs, About)
    }
}

@Composable
fun SettingsScreen(state: AppState, onOpen: (SettingsPage) -> Unit) {
    Column(Modifier.fillMaxSize().background(Moon.WinBg)) {
        Column(Modifier.padding(start = 26.dp, end = 24.dp, top = 18.dp, bottom = 14.dp)) {
            Text("Настройки", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Moon.TextPrimary)
            Text("Moon Internet · 0.9.0 beta", fontSize = 12.5.sp, color = Moon.TextSecondary,
                 modifier = Modifier.padding(top = 2.dp))
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 24.dp),
            contentPadding = PaddingValues(bottom = bottomNavSpace()),
        ) {
            items(SettingsPage.hub.size) { i ->
                val p = SettingsPage.hub[i]
                HubCard(p.icon, p.title, p.subtitle) { onOpen(p) }
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
) {
    // The header is outside the scrolling area on purpose: the desktop page keeps its title and
    // back button fixed while only the body moves.
    Column(Modifier.fillMaxSize().background(Moon.WinBg)) {
        Spacer(Modifier.height(14.dp))
        Box(Modifier.padding(horizontal = 24.dp)) {
            PageHeader(page.title, big = page.title.length < 20, onBack = onBack)
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 24.dp),
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
                SettingsPage.Logs -> LogsPage(state, logsSize, onSet, onClearLogs)
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
        SectionLabel("ШРИФТ", top = 0)
        MoonCard(padding = 14) {
            ChipFlow(listOf("comic" to "Comic (как на ПК)", "system" to "Системный"), state.fontName) {
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
            SwitchRow("Авто-переподключение", "Если ядро упало — переподключиться автоматически",
                state.autoReconnect) { v -> onSet { copy(autoReconnect = v) } }
            RowDivider()
            SwitchRow("Kill Switch", "Блокировать трафик при отключении VPN",
                state.killSwitch) { v -> onSet { copy(killSwitch = v) } }
            RowDivider()
            SwitchRow("Разрешить LAN подключения", "Исключить локальную сеть из VPN туннеля",
                state.allowLan) { v -> onSet { copy(allowLan = v) } }
            SwitchRow("LAN через прокси", "Направить локальный трафик через VPN прокси",
                state.lanThroughProxy) { v -> onSet { copy(lanThroughProxy = v) } }
            RowDivider()
            SwitchRow("Кнопка «Только прокси»", "Показывать кнопку на главном экране",
                state.showProxyOnlyButton) { v -> onSet { copy(showProxyOnlyButton = v) } }
            RowDivider()
            Column(Modifier.padding(12.dp)) {
                Text("Дополнительные адреса в обход прокси", color = Moon.TextPrimary,
                     fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("Хосты в обход прокси (напр. *.local)", color = Moon.TextSecondary,
                     fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
                MoonTextField(state.proxyBypassHosts, { v -> onSet { copy(proxyBypassHosts = v) } })
            }
            RowDivider()
            Column(Modifier.padding(12.dp)) {
                Text("Локальный прокси", color = Moon.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("SOCKS5 127.0.0.1:${state.socksPort} · HTTP 127.0.0.1:${state.httpPort}",
                     color = Moon.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }

        SectionLabel("SOCKS5 / ПРОКСИ")
        MoonCard {
            SwitchRow("SOCKS5 авторизация",
                "Защищает локальный прокси от приложений, которые сканируют порты",
                state.socks5Auth) { v -> onSet { copy(socks5Auth = v) } }
            if (state.socks5Auth) {
                RowDivider()
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Логин", color = Moon.TextPrimary, fontSize = 14.sp,
                         fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(state.proxyUser, color = Moon.AccentText, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace)
                    IconButton({ onCopy(state.proxyUser, "Логин скопирован") }, Modifier.size(30.dp)) {
                        Icon(Icons.Filled.ContentCopy, null, tint = Moon.TextSecondary, modifier = Modifier.size(15.dp))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(12.dp, 0.dp, 12.dp, 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Пароль", color = Moon.TextPrimary, fontSize = 14.sp,
                         fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("•".repeat(state.proxyPass.length.coerceAtMost(12)),
                         color = Moon.TextSecondary, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace)
                    IconButton({ onCopy(state.proxyPass, "Пароль скопирован") }, Modifier.size(30.dp)) {
                        Icon(Icons.Filled.ContentCopy, null, tint = Moon.TextSecondary, modifier = Modifier.size(15.dp))
                    }
                }
                Row(Modifier.clickable(onClick = onResetCreds).padding(12.dp, 4.dp, 12.dp, 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Refresh, null, tint = Moon.AccentText, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Сбросить логин/пароль", color = Moon.AccentText, fontSize = 13.sp)
                }
            }
            RowDivider()
            SwitchRow("Блокировать UDP",
                "Сломает DNS-over-UDP, QUIC, голосовые звонки, игры. Включайте только если знаете зачем",
                state.blockUdp) { v -> onSet { copy(blockUdp = v) } }
            RowDivider()
            SwitchRow("HTTP-прокси авторизация", "Защитить HTTP-прокси теми же логином/паролем",
                state.httpProxyAuth) { v -> onSet { copy(httpProxyAuth = v) } }
        }

        // Приоритет трафика — бета, выключено по умолчанию. Same three modes as the desktop.
        SectionLabel("ПРИОРИТЕТ ТРАФИКА")
        MoonCard(padding = 14) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Приоритет трафика", color = Moon.TextPrimary, fontSize = 14.sp,
                     fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Surface(shape = RoundedCornerShape(6.dp), color = Color(0x33F5C042)) {
                    Text("БЕТА", color = Color(0xFFF5C042), fontSize = 9.5.sp,
                         fontWeight = FontWeight.Bold,
                         modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp))
                }
            }
            Text(
                "Не даёт закачке забить очередь, из-за которой в играх и звонках подскакивает " +
                    "пинг. Укорачивает буфер и отключает мультиплексирование. Скорость закачки " +
                    "может немного снизиться.",
                color = Moon.TextSecondary, fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(10.dp))
            ChipFlow(
                listOf("off" to "Выключен", "balance" to "Баланс", "games" to "Игры и звонки"),
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
        SwitchRow("Профили маршрутизации", "Раздельное туннелирование с профилями",
            state.useRouting) { v -> onSet { copy(useRouting = v) } }
        RowDivider()
        NavRow("Настройки маршрутизации", "Профиль, правила · ${routing?.name ?: "нет"}", onOpenRouting)
        RowDivider()
        SwitchRow("Фрагментация", "Разделение TLS hello для обхода блокировок",
            state.tlsFragment) { v -> onSet { copy(tlsFragment = v) } }
        SwitchRow("Мультиплексирование", "Мультиплексирование соединений",
            state.mux) { v -> onSet { copy(mux = v) } }
        SwitchRow("Снифинг", "Определение протокола для маршрутизации",
            state.sniffing) { v -> onSet { copy(sniffing = v) } }
        RowDivider()
        NavRow("Прокси по приложениям", "Раздельное туннелирование по приложениям") {
            onOpen(SettingsPage.AppRouting)
        }
        RowDivider()
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Предпочтительный IP", color = Moon.TextPrimary, fontSize = 14.sp,
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
                       "cloudflare" to "Cloudflare", "quad9" to "Quad9", "custom" to "Свой"),
                state.vpnDns,
            ) { v -> onSet { copy(vpnDns = v) } }
            if (state.vpnDns == "custom") {
                Spacer(Modifier.height(8.dp))
                MoonTextField(state.vpnDnsCustom, { v -> onSet { copy(vpnDnsCustom = v) } }, "8.8.8.8, 1.1.1.1")
                Text("IP через запятую, напр. 8.8.8.8, 1.1.1.1", color = Moon.TextSecondary,
                     fontSize = 11.sp, modifier = Modifier.padding(start = 2.dp, top = 4.dp))
            }
        }
    }
}

private fun dnsLabel(id: String) = when (id) {
    "google" -> "Google DNS"
    "cloudflare" -> "Cloudflare"
    "quad9" -> "Quad9"
    "custom" -> "Свой"
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
            Text("Раздельный туннель", color = Moon.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("Какие приложения пускать мимо VPN — или только их через VPN.",
                 color = Moon.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(10.dp))
            ChipFlow(
                listOf("off" to "Выкл", "bypass" to "Мимо VPN", "only" to "Только VPN"),
                state.perAppMode,
            ) { v -> onSet { copy(perAppMode = v) } }
        }

        if (state.perAppMode != "off") {
            Spacer(Modifier.height(12.dp))
            MoonTextField(query, { query = it }, "Поиск приложений…")
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Выбрано: ${state.perApps.size}", color = Moon.TextSecondary, fontSize = 12.sp,
                     modifier = Modifier.weight(1f))
                if (state.perApps.isNotEmpty()) {
                    Text("Снять все", color = Moon.AccentText, fontSize = 12.sp,
                         modifier = Modifier.clickable { onSet { copy(perApps = emptyList()) } }.padding(6.dp))
                }
            }
            Spacer(Modifier.height(6.dp))

            val shown = apps.filter {
                query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true)
            }
            MoonCard(padding = 0) {
                if (shown.isEmpty()) {
                    Text("Ничего не найдено", Modifier.padding(14.dp), color = Moon.TextMuted, fontSize = 12.sp)
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
            SwitchRow("Автообновление", "Обновлять подписки автоматически",
                state.autoUpdateSubs) { v -> onSet { copy(autoUpdateSubs = v) } }
            if (state.autoUpdateSubs) {
                Column(Modifier.padding(12.dp, 0.dp, 12.dp, 10.dp)) {
                    ChipFlow(
                        listOf("0" to "По подписке", "30" to "30 мин", "60" to "1 час", "120" to "2 часа",
                               "360" to "6 часов", "720" to "12 часов", "1440" to "24 часа"),
                        state.subIntervalMinutes.toString(),
                    ) { v -> onSet { copy(subIntervalMinutes = v.toInt()) } }
                }
            }
            RowDivider()
            SwitchRow("Уведомлять об обновлении", "Показывать уведомление после обновления",
                state.notifyOnUpdate) { v -> onSet { copy(notifyOnUpdate = v) } }
            RowDivider()
            SwitchRow("Обновлять при запуске", "Обновлять подписки при старте приложения",
                state.updateOnStart) { v -> onSet { copy(updateOnStart = v) } }
            RowDivider()
            SwitchRow("Пинг при запуске", "Тестировать задержку при запуске приложения",
                state.pingOnStart) { v -> onSet { copy(pingOnStart = v) } }
            RowDivider()
            SwitchRow("Отправлять HWID", "Отправлять ID устройства в запросах подписки",
                state.sendHwid) { v -> onSet { copy(sendHwid = v) } }
            RowDivider()
            SwitchRow("Показывать шапку подписки", "Приветствие / объявление от подписки на списке серверов",
                state.showSubHeader) { v -> onSet { copy(showSubHeader = v) } }
        }

        Spacer(Modifier.height(10.dp))
        MoonCard {
            SwitchRow("Уведомление об истечении", "Предупреждать об истечении подписки",
                state.notifyExpiry) { v -> onSet { copy(notifyExpiry = v) } }
            if (state.notifyExpiry) {
                Row(Modifier.padding(12.dp, 0.dp, 12.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(1, 3, 5, 7).forEach { d ->
                        Chip("$d", state.expiryNotifyDays == d) { onSet { copy(expiryNotifyDays = d) } }
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Уведомлять за (дней)", color = Moon.TextSecondary, fontSize = 12.sp)
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
                        Text("${sub.servers.size} серверов · ${sub.trafficText} · ${sub.expiryText}",
                             color = Moon.TextSecondary, fontSize = 11.5.sp)
                    }
                    TextButton({ onRemoveSub(sub.url) }) { Text("Удалить", color = Moon.Danger, fontSize = 12.5.sp) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("+ Добавить", Modifier.weight(1f), onAdd)
            SecondaryButton("Обновить", Modifier.weight(1f), onRefresh)
        }
    }
}

// ------------------------------------------------------------------- ПИНГ
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PingPage(state: AppState, onSet: ((AppState.() -> AppState)) -> Unit) {
    Column {
        SectionLabel("ПРОТОКОЛ", top = 0)
        ChipFlow(
            listOf("moon" to "Moon Ping", "tcp" to "TCP", "httpget" to "HTTP GET",
                   "httphead" to "HTTP HEAD", "stability" to "Стабильность"),
            state.pingMethod,
        ) { v -> onSet { copy(pingMethod = v) } }
        Text("Moon Ping, TCP и HTTP отвечают на вопрос «порт кто-то слушает». Это не то же самое, " +
             "что «протокол работает»: CDN, промежуточный сервер провайдера или протухший ключ " +
             "рукопожатие тоже завершат, и мёртвый сервер покажет бодрые 30 мс.",
             color = Moon.TextSecondary, fontSize = 11.5.sp, lineHeight = 16.sp,
             modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 6.dp))
        Text("Стабильность — единственный метод, который поднимает настоящее соединение и делает " +
             "через него запрос. Медленнее остальных, зато не врёт.",
             color = Moon.AccentText, fontSize = 11.5.sp, lineHeight = 16.sp,
             modifier = Modifier.padding(start = 4.dp, bottom = 16.dp))

        SectionLabel("ПОРЯДОК ПРОВЕРКИ", top = 0)
        MoonCard {
            SwitchRow("Проверять по очереди",
                "Разносить проверки во времени, а не бить все разом. Некоторые провайдеры " +
                    "принимают залп из тридцати соединений за скан портов и режут его",
                state.pingStagger) { v -> onSet { copy(pingStagger = v) } }
            if (state.pingStagger) {
                RowDivider()
                Column(Modifier.padding(12.dp)) {
                    Text("Задержка между проверками", color = Moon.TextSecondary, fontSize = 11.5.sp)
                    Spacer(Modifier.height(8.dp))
                    ChipFlow(listOf("50" to "50 мс", "150" to "150 мс", "300" to "300 мс"),
                             state.pingStaggerMs.toString()) { v -> onSet { copy(pingStaggerMs = v.toInt()) } }
                }
            }
        }

        SectionLabel("АВТОПРОВЕРКА")
        MoonCard(padding = 14) {
            Text("Перемеряет пинги в фоне, чтобы к открытию списка числа были свежими.",
                 color = Moon.TextSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            ChipFlow(
                listOf("0" to "Выключено", "1" to "1 мин", "5" to "5 мин", "10" to "10 мин",
                       "15" to "15 мин", "20" to "20 мин", "30" to "30 мин"),
                state.pingEveryMinutes.toString(),
            ) { v -> onSet { copy(pingEveryMinutes = v.toInt()) } }
        }

        SectionLabel("ОТОБРАЖЕНИЕ ПИНГА", top = 0)
        ChipFlow(
            listOf("num" to "Цифры", "bar" to "Шкала", "both" to "Оба", "dots" to "Точки"),
            state.pingDisplay,
        ) { v -> onSet { copy(pingDisplay = v) } }

        SectionLabel("ТЕСТОВЫЙ URL", top = 14)
        MoonTextField(state.pingTestUrl, { v -> onSet { copy(pingTestUrl = v) } })
        Text("Пресеты", color = Moon.TextSecondary, fontSize = 12.sp,
             modifier = Modifier.padding(start = 2.dp, top = 10.dp, bottom = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "Google 204" to "https://www.gstatic.com/generate_204",
                "Cloudflare" to "https://cp.cloudflare.com/generate_204",
                "Apple" to "https://captive.apple.com",
            ).forEach { (name, url) -> SecondaryButton(name) { onSet { copy(pingTestUrl = url) } } }
        }
        Text("Используется для HTTP GET / HEAD. Moon Ping и TCP проверяют сам сервер.",
             color = Moon.TextSecondary, fontSize = 11.5.sp, lineHeight = 16.sp,
             modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 14.dp))

        SectionLabel("ТАЙМ-АУТ", top = 0)
        MoonCard(padding = 0) {
            Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Тайм-аут пинга", color = Moon.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("Максимальное время ожидания ответа", color = Moon.TextSecondary, fontSize = 12.sp,
                         modifier = Modifier.padding(top = 2.dp))
                }
                IconButton({ onSet { copy(pingTimeoutMs = (pingTimeoutMs - 1000).coerceAtLeast(1000)) } },
                           Modifier.size(30.dp)) {
                    Icon(Icons.Filled.Remove, "Меньше", tint = Moon.TextSecondary, modifier = Modifier.size(17.dp))
                }
                Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF2C2058)) {
                    Text("${state.pingTimeoutMs / 1000} с", Modifier.padding(10.dp, 4.dp).widthIn(min = 26.dp),
                         color = Moon.AccentText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                         textAlign = TextAlign.Center)
                }
                IconButton({ onSet { copy(pingTimeoutMs = (pingTimeoutMs + 1000).coerceAtMost(15000)) } },
                           Modifier.size(30.dp)) {
                    Icon(Icons.Filled.Add, "Больше", tint = Moon.TextSecondary, modifier = Modifier.size(17.dp))
                }
            }
        }
    }
}

// -------------------------------------------------------------------- АВТО
@Composable
private fun AutoPage(state: AppState, onSet: ((AppState.() -> AppState)) -> Unit) {
    MoonCard {
        SwitchRow("Авто-подключение при запуске", "Подключаться автоматически при старте приложения",
            state.autoConnect) { v -> onSet { copy(autoConnect = v) } }
        if (state.autoConnect) {
            Column(Modifier.padding(12.dp, 0.dp, 12.dp, 12.dp)) {
                Text("Какой сервер подключать", color = Moon.TextSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                ChipFlow(
                    listOf("first" to "Первый", "last" to "Последний",
                           "lowest" to "Мин. пинг", "favorite-first" to "★ Избранное"),
                    if (state.autoConnectTarget.startsWith("favorite")) "favorite-first" else state.autoConnectTarget,
                ) { v -> onSet { copy(autoConnectTarget = v) } }

                if (state.autoConnectTarget.startsWith("favorite")) {
                    Text("Какое избранное", color = Moon.TextSecondary, fontSize = 12.sp,
                         modifier = Modifier.padding(top = 12.dp))
                    Spacer(Modifier.height(8.dp))
                    ChipFlow(
                        listOf("favorite-first" to "Первое", "favorite-last" to "Последнее",
                               "favorite-lowest" to "Мин. пинг"),
                        state.autoConnectTarget,
                    ) { v -> onSet { copy(autoConnectTarget = v) } }
                }
            }
        }
        // «Автозапуск с Windows» и «Авто-скрытие в трее» — только для ПК.
    }
}

// -------------------------------------------------------------------- ЛОГИ
@Composable
private fun LogsPage(
    state: AppState,
    logsSize: String,
    onSet: ((AppState.() -> AppState)) -> Unit,
    onClear: () -> Unit,
) {
    Column {
        MoonCard {
            SwitchRow("Вести логи", "Записывать журналы ядра для диагностики",
                state.logsEnabled) { v -> onSet { copy(logsEnabled = v) } }
            if (state.logsEnabled) {
                RowDivider()
                Column(Modifier.padding(12.dp)) {
                    Text("Уровень", color = Moon.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    ChipFlow(
                        listOf("error" to "Ошибки", "warning" to "Предупреждения",
                               "info" to "Инфо", "debug" to "Отладка"),
                        state.logLevel,
                    ) { v -> onSet { copy(logLevel = v) } }
                }
                RowDivider()
                Column(Modifier.padding(12.dp)) {
                    Text("Сколько хранить логи", color = Moon.TextPrimary, fontSize = 14.sp,
                         fontWeight = FontWeight.SemiBold)
                    Text("Старые записи удаляются автоматически при запуске", color = Moon.TextSecondary,
                         fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                    Spacer(Modifier.height(8.dp))
                    ChipFlow(
                        listOf("1" to "1 день", "3" to "3 дня", "7" to "7 дней",
                               "30" to "30 дней", "0" to "Всегда"),
                        state.logKeepDays.toString(),
                    ) { v -> onSet { copy(logKeepDays = v.toInt()) } }
                }
            }
            RowDivider()
            ValueRow("Размер логов", logsSize, inset = 12)
        }
        Row(Modifier.padding(top = 14.dp)) {
            SecondaryButton("Очистить логи", onClick = onClear)
        }
    }
}

// ------------------------------------------------- ПОЛИТИКА / УСЛОВИЯ / ЛИБЫ
@Composable
private fun PrivacyPage() {
    MoonCard(padding = 0) {
        Column(Modifier.padding(18.dp, 16.dp)) {
            Paragraph("Moon Internet — локальный VPN-клиент. Ниже — как приложение обращается с вашими данными.")
            SubHeading("Мы не собираем данные")
            Paragraph("Приложение работает на вашем устройстве. Разработчик не получает ваши данные: " +
                      "нет аккаунтов, телеметрии, аналитики и обращений «домой».")
            SubHeading("Данные на устройстве")
            Paragraph("Подписки, список серверов, ключи и пароли доступа хранятся локально в памяти " +
                      "приложения и не покидают телефон. Удаление приложения удаляет их.")
            SubHeading("Ваш трафик")
            Paragraph("Трафик идёт через серверы, которые вы добавили сами (по ссылке-подписке). " +
                      "Их операторы обрабатывают трафик по своим правилам — ознакомьтесь с их политикой. " +
                      "Само приложение ваш трафик не логирует и никуда не пересылает.")
            SubHeading("Сетевые обращения")
            Paragraph("Приложение обращается только к: (1) вашим URL-подпискам — чтобы получить список " +
                      "серверов; (2) источникам гео-правил (например, GitHub) — чтобы скачать файлы " +
                      "маршрутизации, если включён роутинг. Больше никуда.")
            SubHeading("Логи")
            Paragraph("Технические логи для диагностики соединения хранятся локально и не отправляются.")
            SubHeading("Права")
            Paragraph("Приложению нужно разрешение на создание VPN-подключения — Android спрашивает его " +
                      "один раз. Оно нужно только для маршрутизации трафика и не собирает данные.")
            SubHeading("Изменения")
            Paragraph("Политика может обновляться вместе с приложением.")
        }
    }
}

@Composable
private fun TermsPage() {
    MoonCard(padding = 0) {
        Column(Modifier.padding(18.dp, 16.dp)) {
            Paragraph("Moon Internet предоставляется «как есть», без каких-либо гарантий. Приложение — " +
                      "только инструмент для подключения к прокси/VPN-серверам, которые вы добавляете сами.")
            Paragraph("Вы сами отвечаете за серверы и подписки, которые используете, и за соблюдение " +
                      "законов вашей страны. Разработчик не предоставляет прокси-услуги и не несёт " +
                      "ответственности за содержимое трафика.", top = 12)
            Paragraph("Используя приложение, вы соглашаетесь с этими условиями.", top = 12)
        }
    }
}

@Composable
private fun LibsPage() {
    MoonCard(padding = 0) {
        Column(Modifier.padding(18.dp, 14.dp)) {
            Text("Ядра", color = Moon.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("• xray-core — MPL-2.0\n• AndroidLibXrayLite — GPL-3.0",
                 color = Moon.TextSecondary, fontSize = 12.5.sp, lineHeight = 20.sp,
                 modifier = Modifier.padding(top = 6.dp))
            Text("Библиотеки Android", color = Moon.TextPrimary, fontSize = 13.sp,
                 fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp))
            Text("• Jetpack Compose — Apache-2.0\n• kotlinx.serialization — Apache-2.0\n" +
                 "• kotlinx.coroutines — Apache-2.0\n• ZXing — Apache-2.0\n• Shantell Sans — OFL-1.1",
                 color = Moon.TextSecondary, fontSize = 12.5.sp, lineHeight = 20.sp,
                 modifier = Modifier.padding(top = 6.dp))
            Text("Полные тексты лицензий поставляются вместе с соответствующими компонентами.",
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
        SectionLabel("ИНФОРМАЦИЯ", top = 0)
        MoonCard(padding = 0) {
            ValueRow("Версия Xray", xrayVersion)
            RowDivider(inset = 14)
            ValueRow("Версия приложения", "0.9.0 beta")
        }

        SectionLabel("ССЫЛКИ")
        MoonCard(padding = 0) {
            NavRow("Условия использования") { onOpen(SettingsPage.Terms) }
            RowDivider(inset = 14)
            NavRow("Политика конфиденциальности") { onOpen(SettingsPage.Privacy) }
            RowDivider(inset = 14)
            NavRow("Сторонние библиотеки") { onOpen(SettingsPage.Libs) }
        }
        Text("Это приложение — только прокси-инструмент. Мы не собираем персональные данные " +
             "и не предоставляем прокси-услуги.",
             color = Moon.TextSecondary, fontSize = 11.5.sp, textAlign = TextAlign.Center,
             modifier = Modifier.fillMaxWidth().padding(10.dp, 12.dp, 10.dp, 0.dp))

        SectionLabel("СИСТЕМА")
        MoonCard(padding = 0) {
            ValueRow("Платформа", "Android ${android.os.Build.VERSION.RELEASE}")
            RowDivider(inset = 14)
            ValueRow("Архитектура", android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
            RowDivider(inset = 14)
            ValueRow("Устройство", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            RowDivider(inset = 14)
            Row(Modifier.fillMaxWidth().padding(14.dp, 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("HWID", color = Moon.TextPrimary, fontSize = 13.5.sp)
                Text(state.hwid, color = Moon.TextSecondary, fontSize = 11.5.sp,
                     maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
                     modifier = Modifier.weight(1f).padding(start = 10.dp, end = 6.dp))
                IconButton({ onCopy(state.hwid, "HWID скопирован") }, Modifier.size(30.dp)) {
                    Icon(Icons.Filled.ContentCopy, null, tint = Moon.TextSecondary, modifier = Modifier.size(15.dp))
                }
            }
        }
    }
}

@Composable
fun AddDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit, onScan: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить", color = Moon.TextPrimary) },
        text = {
            Column {
                Text("Ссылка на подписку или сервер", fontSize = 12.sp, color = Moon.TextSecondary)
                Spacer(Modifier.height(8.dp))
                MoonTextField(text, { text = it }, "https://… или vless://…")
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onScan)
                        .background(Moon.ChipBg, RoundedCornerShape(11.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.QrCodeScanner, null, tint = Moon.AccentText, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Сканировать QR-код", color = Moon.TextPrimary, fontSize = 13.5.sp)
                        Text("Камера прочитает ссылку сама", color = Moon.TextSecondary, fontSize = 11.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton({ onConfirm(text) }) { Text("Добавить") } },
        dismissButton = { TextButton(onDismiss) { Text("Отмена", color = Moon.TextSecondary) } },
        containerColor = Moon.Card,
    )
}

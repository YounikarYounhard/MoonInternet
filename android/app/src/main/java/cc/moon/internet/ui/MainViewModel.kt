package cc.moon.internet.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cc.moon.internet.core.*
import cc.moon.internet.data.GeoService
import cc.moon.internet.data.Store
import cc.moon.internet.data.SubscriptionService
import cc.moon.internet.vpn.MoonVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private companion object {
        /** Same cap as the desktop build; see the comment on pingServers. */
        const val PING_PARALLEL = 6
    }

    private val store = Store(app)
    val state get() = store.state

    private val _status = MutableStateFlow("")
    val status = _status.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    /** raw share link → ping in ms (-1 failed). */
    private val _pings = MutableStateFlow<Map<String, Int>>(emptyMap())
    val pings = _pings.asStateFlow()

    /** result of "Проверить соединение" — shown next to the button while connected. */
    private val _checkPing = MutableStateFlow("")
    val checkPing = _checkPing.asStateFlow()

    val vpnState = MoonVpnService.state
    val traffic = MoonVpnService.traffic
    val vpnError = MoonVpnService.lastError

    private var lastUp = 0L
    private var lastDown = 0L
    private var connectedAt = 0L

    private val _speed = MutableStateFlow("—" to "—")
    val speed = _speed.asStateFlow()

    private val _sessionTraffic = MutableStateFlow("—")
    val sessionTraffic = _sessionTraffic.asStateFlow()

    private val _elapsed = MutableStateFlow("—")
    val elapsed = _elapsed.asStateFlow()

    // ---- обновления ------------------------------------------------------
    private val _release = MutableStateFlow<cc.moon.internet.data.ReleaseInfo?>(null)
    val release = _release.asStateFlow()

    private val _updateAvailable = MutableStateFlow(false)
    val updateAvailable = _updateAvailable.asStateFlow()

    private val _updateStatus = MutableStateFlow("")
    val updateStatus = _updateStatus.asStateFlow()

    val appVersion: String = cc.moon.internet.BuildConfig.VERSION_NAME

    /** Asks GitHub. Runs once at launch too, quietly — a failure just leaves the badge off. */
    fun checkUpdate() = viewModelScope.launch {
        _updateStatus.value = "Проверяю…"
        val r = cc.moon.internet.data.UpdateService.latest()
        if (r == null) { _updateStatus.value = "Не удалось проверить — нет связи с GitHub"; return@launch }
        _release.value = r
        _updateAvailable.value = cc.moon.internet.data.UpdateService.isNewer(r.version, appVersion)
        _updateStatus.value =
            if (_updateAvailable.value) "Доступна версия ${r.version}" else "У вас последняя версия"
    }

    init {
        viewModelScope.launch {
            store.load()                                  // offline first: servers show instantly
            if (store.state.value.updateOnStart) refreshAll(silent = true) else if (store.state.value.pingOnStart) pingAll()
        }
        checkUpdate()
        viewModelScope.launch { watchTraffic() }
        viewModelScope.launch {
            vpnState.collect { s ->
                if (s == MoonVpnService.Companion.State.Connected) {
                    connectedAt = System.currentTimeMillis()
                    // the desktop measures the tunnel as soon as it is up; do the same here
                    checkConnection()
                }
                if (s == MoonVpnService.Companion.State.Disconnected ||
                    s == MoonVpnService.Companion.State.Paused) {
                    _speed.value = "—" to "—"; _sessionTraffic.value = "—"; _elapsed.value = "—"
                    _checkPing.value = ""
                    lastUp = 0; lastDown = 0
                }
            }
        }
        // the tunnel reports failures on its own thread — surface them instead of silently going dark
        viewModelScope.launch { vpnError.filterNotNull().collect { _status.value = it } }
    }

    // ---- subscriptions ---------------------------------------------------
    fun addSubscription(url: String) = viewModelScope.launch {
        val u = url.trim()
        if (u.isBlank()) return@launch
        // a bare share link is a single server, not a subscription
        if (!u.startsWith("http", true)) {
            ShareLinkParser.parse(u)?.let { p ->
                store.update { st ->
                    val sub = Subscription(url = "clipboard:${UUID.randomUUID()}", name = p.label.ifBlank { "Сервер" }, servers = listOf(p))
                    st.copy(subscriptions = st.subscriptions + sub, selectedServerRaw = st.selectedServerRaw ?: p.raw)
                }
                _status.value = "Сервер добавлен"
            } ?: run { _status.value = "Не похоже на ссылку сервера" }
            return@launch
        }
        _busy.value = true
        runCatching { SubscriptionService.fetch(u) }
            .onSuccess { f ->
                store.update { st ->
                    val sub = Subscription(
                        url = u,
                        name = f.title ?: hostOf(u),
                        servers = f.servers,
                        announcement = f.announcement,
                        trafficText = f.trafficText,
                        expiryText = f.expiryText,
                        updateIntervalMinutes = f.updateMinutes,
                        fetchedAt = System.currentTimeMillis(),
                    )
                    st.copy(
                        subscriptions = st.subscriptions.filterNot { it.url == u } + sub,
                        selectedServerRaw = st.selectedServerRaw ?: f.servers.firstOrNull()?.raw,
                    )
                }
                _status.value = "Загружено серверов: ${f.servers.size}"
                pingAll()
            }
            .onFailure { _status.value = "Ошибка загрузки: ${it.message}" }
        _busy.value = false
    }

    fun refreshAll(silent: Boolean = false) = viewModelScope.launch {
        val urls = store.state.value.subscriptions.map { it.url }.filter { it.startsWith("http") }
        if (urls.isEmpty()) return@launch
        if (!silent) _busy.value = true
        urls.forEach { refreshOne(it) }
        _busy.value = false
        pingAll()
    }

    fun refreshSubscription(url: String) = viewModelScope.launch {
        if (!url.startsWith("http")) return@launch
        _busy.value = true
        if (refreshOne(url)) _status.value = "Подписка обновлена" else _status.value = "Не удалось обновить"
        _busy.value = false
    }

    private suspend fun refreshOne(u: String): Boolean =
        runCatching { SubscriptionService.fetch(u) }.onSuccess { f ->
            store.update { st ->
                st.copy(subscriptions = st.subscriptions.map {
                    if (it.url != u) it else it.copy(
                        name = f.title ?: it.name,
                        servers = f.servers,
                        announcement = f.announcement,
                        trafficText = f.trafficText,
                        expiryText = f.expiryText,
                        fetchedAt = System.currentTimeMillis(),
                    )
                })
            }
            importRoutings(f.routingLinks)
        }.isSuccess

    /** Subscriptions carry incy://routing/add/… payloads; that is where the rule set comes from. */
    private suspend fun importRoutings(links: List<String>) {
        links.filter(RoutingParser::isRoutingLink)
            .mapNotNull(RoutingParser::parse)
            .forEach { store.putRouting(it) }
    }

    fun removeSubscription(url: String) = viewModelScope.launch {
        store.removeSubscription(url)
        _status.value = "Подписка удалена"
    }

    fun toggleCollapse(url: String) = viewModelScope.launch {
        store.update { st ->
            st.copy(collapsed = if (url in st.collapsed) st.collapsed - url else st.collapsed + url)
        }
    }

    fun pingSubscription(url: String) = viewModelScope.launch {
        val servers = store.state.value.subscriptions.firstOrNull { it.url == url }?.servers ?: return@launch
        pingServers(servers)
    }

    // ---- servers ---------------------------------------------------------
    fun selectServer(s: ServerProfile) = viewModelScope.launch {
        store.selectServer(s)
        // switching while connected should reconnect, not silently keep the old tunnel
        if (vpnState.value == MoonVpnService.Companion.State.Connected) connect()
    }

    fun toggleFavorite(s: ServerProfile) = viewModelScope.launch { store.toggleFavorite(s) }

    fun setSort(mode: String) = viewModelScope.launch { store.update { it.copy(sort = mode) } }

    fun setTunMode(on: Boolean) = viewModelScope.launch { store.update { it.copy(tunMode = on) } }

    fun sortedServers(): List<ServerProfile> = store.sortedServers(_pings.value)

    /** Sorted servers of one subscription — Home and Servers both go through this. */
    fun sortedIn(sub: Subscription): List<ServerProfile> = store.sortedIn(sub, _pings.value)

    fun isFavorite(s: ServerProfile) = store.isFavorite(s)

    fun pingAll() = viewModelScope.launch { pingServers(store.allServers) }

    fun pingServer(s: ServerProfile) = viewModelScope.launch { pingServers(listOf(s)) }

    /**
     * Which server auto-connect would pick. The quick-settings tile uses the same rule, and falls
     * back to the first server when auto-connect is switched off.
     */
    fun autoTarget(): ServerProfile? = store.autoTarget(_pings.value)

    /**
     * Measures every server, but never more than [PING_PARALLEL] at a time.
     *
     * Both halves matter and neither replaces the other:
     *  * the semaphore keeps us from opening 30+ sockets at once — that overloads the
     *    provider's panel and balloons our own memory (the desktop build caps it the same way);
     *  * the atomic update is what actually fixed "not every server gets a ping": a plain
     *    read-modify-write on the map lost most results when jobs finished together.
     */
    private suspend fun pingServers(servers: List<ServerProfile>) {
        val targets = servers.filter { !it.raw.isNullOrBlank() }
        if (targets.isEmpty()) return
        _status.value = "Пингую ${targets.size}…"

        val gate = kotlinx.coroutines.sync.Semaphore(PING_PARALLEL)
        kotlinx.coroutines.coroutineScope {
            targets.map { s ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val ms = measurePing(s)
                        s.raw?.let { raw -> _pings.update { it + (raw to ms) } }
                    }
                }
            }.forEach { it.await() }
        }
        _status.value = ""
    }

    /**
     * The four methods the desktop offers:
     *  moon / tcp — TCP handshake straight to the server, outside the tunnel (channel latency);
     *  httpget / httphead — a real request to the test URL, which measures the whole path.
     */
    private fun measurePing(s: ServerProfile): Int {
        val st = store.state.value
        val timeout = st.pingTimeoutMs
        return when (st.pingMethod) {
            "httpget", "httphead" -> httpPing(st.pingTestUrl, timeout, head = st.pingMethod == "httphead")
            else -> tcpPing(s.address, s.port, timeout)
        }
    }

    private fun tcpPing(host: String, port: Int, timeout: Int = 3000): Int = try {
        val t0 = System.currentTimeMillis()
        Socket().use { it.connect(InetSocketAddress(host, port), timeout) }
        (System.currentTimeMillis() - t0).toInt()
    } catch (_: Exception) { -1 }

    private fun httpPing(url: String, timeout: Int, head: Boolean): Int = try {
        val t0 = System.currentTimeMillis()
        (java.net.URL(url).openConnection() as java.net.HttpURLConnection).run {
            connectTimeout = timeout; readTimeout = timeout
            requestMethod = if (head) "HEAD" else "GET"
            try { responseCode } finally { disconnect() }
        }
        (System.currentTimeMillis() - t0).toInt()
    } catch (_: Exception) { -1 }

    /**
     * Real end-to-end check. The core measures it itself: our own process is excluded from the
     * tunnel, so an HTTP call from here would not go through the proxy at all and would report
     * a meaningless number.
     */
    fun checkConnection() = viewModelScope.launch(Dispatchers.IO) {
        _checkPing.value = "…"
        val ms = MoonVpnService.runner?.measureDelay()
        _checkPing.value = when {
            ms == null -> "нет ответа"
            ms < 0 -> "нет ответа"
            else -> "$ms ms"
        }
    }

    // ---- connection ------------------------------------------------------
    fun connect() {
        val s = store.selectedServer() ?: run { _status.value = "Сначала выберите сервер"; return }
        if (!XrayConfig.supports(s.protocol)) {
            _status.value = "${s.protocolLabel} не поддерживается ядром xray"
            return
        }
        val st = store.state.value

        // Another VPN client on the phone probably owns 10808/10809 already — see freeLocalPort.
        val listen = if (st.allowLan) "0.0.0.0" else "127.0.0.1"
        val socks = freeLocalPort(st.socksPort, listen)
        val http = freeLocalPort(st.httpPort, listen, avoid = setOf(socks))
        if (socks != st.socksPort || http != st.httpPort) {
            _status.value = "Порт занят другим VPN, слушаю $socks/$http"
        }

        var config = runCatching {
            XrayConfig.build(
                server = s,
                routing = if (st.useRouting) store.activeRouting() else null,
                dns = st.dns,
                hasGeoFiles = GeoService.ready(getApplication()),
                sniffing = st.sniffing,
                blockUdp = st.blockUdp,
                tlsFragment = st.tlsFragment,
                mux = st.mux,
                trafficPriority = st.trafficPriority,
                preferredIp = st.preferredIp,
                logLevel = if (st.logsEnabled) st.logLevel else "none",
                socksPort = socks,
                httpPort = http,
                proxyUser = st.proxyUser,
                proxyPass = st.proxyPass,
                socksAuth = st.socks5Auth,
                httpAuth = st.httpProxyAuth,
                allowLan = st.allowLan,
                dnsList = dnsServers(st),
            )
        }.getOrElse { _status.value = "Ошибка конфигурации: ${it.message}"; return }

        // Прокси-режим: только локальные SOCKS/HTTP, без системного туннеля.
        if (!st.tunMode) config = XrayConfig.buildProxyOnly(config)

        if (st.useRouting && store.activeRouting() != null && !GeoService.ready(getApplication())) {
            refreshGeo(force = false)
        }

        MoonVpnService.start(
            getApplication(), config, s.label,
            tun = st.tunMode, perAppMode = st.perAppMode, perApps = st.perApps,
        )
    }

    /** Resolves the DNS preset from the settings page into actual addresses. */
    private fun dnsServers(st: cc.moon.internet.data.AppState): List<String> = when (st.vpnDns) {
        "google" -> listOf("8.8.8.8", "8.8.4.4")
        "cloudflare" -> listOf("1.1.1.1", "1.0.0.1")
        "quad9" -> listOf("9.9.9.9", "149.112.112.112")
        "custom" -> st.vpnDnsCustom.split(',').map(String::trim).filter(String::isNotEmpty)
        else -> listOf("1.1.1.1", "8.8.8.8")
    }

    fun disconnect() {
        MoonVpnService.lastError.value = null      // a stopped tunnel is not a failure
        MoonVpnService.stop(getApplication())
    }

    // ---- live counters ---------------------------------------------------
    /**
     * Ticks every second while connected. The counters live in a StateFlow that stays silent
     * when nothing changes, so collecting it left the timer and the speed frozen on an idle
     * tunnel — this loop reads them instead.
     */
    private suspend fun watchTraffic() {
        while (true) {
            kotlinx.coroutines.delay(1000)
            if (vpnState.value != MoonVpnService.Companion.State.Connected) continue

            val (up, down) = traffic.value
            val dUp = (up - lastUp).coerceAtLeast(0)
            val dDown = (down - lastDown).coerceAtLeast(0)
            lastUp = up; lastDown = down

            _speed.value = SubscriptionService.speed(dUp) to SubscriptionService.speed(dDown)
            _sessionTraffic.value = SubscriptionService.size(up + down)
            val secs = (System.currentTimeMillis() - connectedAt) / 1000
            _elapsed.value = String.format("%02d:%02d:%02d", secs / 3600, (secs % 3600) / 60, secs % 60)
        }
    }

    // ---- routing ---------------------------------------------------------
    private val _geoBusy = MutableStateFlow(false)
    val geoBusy = _geoBusy.asStateFlow()

    private val _geoStatus = MutableStateFlow("")
    val geoStatus = _geoStatus.asStateFlow()

    fun activeRouting(): RoutingProfile? = store.activeRouting()

    fun geoipInfo() = GeoService.info(GeoService.geoip(getApplication()))
    fun geositeInfo() = GeoService.info(GeoService.geosite(getApplication()))

    fun setRoutingSource(src: String) = viewModelScope.launch {
        store.update { it.copy(routingSource = src) }
        reconnectIfConnected()
    }

    fun refreshGeo(force: Boolean = true) = viewModelScope.launch {
        if (_geoBusy.value) return@launch
        _geoBusy.value = true
        _geoStatus.value = "Скачиваю…"
        val r = store.activeRouting()
        _geoStatus.value = GeoService.refresh(
            getApplication(), r?.geoipUrl.orEmpty(), r?.geositeUrl.orEmpty(), force,
        )
        _geoBusy.value = false
    }

    fun addRule(bucket: String, value: String) = viewModelScope.launch {
        if (value.isBlank()) return@launch
        editCustom { p ->
            // an IP/CIDR belongs in the ip list, everything else is a domain or geo tag
            val isIp = value.contains('/') || value.startsWith("geoip:", true) ||
                       value.all { it.isDigit() || it == '.' || it == ':' }
            when (bucket) {
                "proxy" -> if (isIp) p.copy(proxyIp = p.proxyIp + value) else p.copy(proxySites = p.proxySites + value)
                "block" -> if (isIp) p.copy(blockIp = p.blockIp + value) else p.copy(blockSites = p.blockSites + value)
                else -> if (isIp) p.copy(directIp = p.directIp + value) else p.copy(directSites = p.directSites + value)
            }
        }
    }

    fun removeRule(bucket: String, value: String) = viewModelScope.launch {
        editCustom { p ->
            when (bucket) {
                "proxy" -> p.copy(proxySites = p.proxySites - value, proxyIp = p.proxyIp - value)
                "block" -> p.copy(blockSites = p.blockSites - value, blockIp = p.blockIp - value)
                else -> p.copy(directSites = p.directSites - value, directIp = p.directIp - value)
            }
        }
    }

    /** Rules are only editable on the "Свой" profile — imported ones are replaced on every fetch. */
    private suspend fun editCustom(block: (RoutingProfile) -> RoutingProfile) {
        val st = store.state.value
        if (st.routingSource != "custom") { _status.value = "Правила меняются только в профиле «Свой»"; return }
        val current = st.routings.firstOrNull { it.source == "custom" }
            ?: RoutingProfile(name = "Свой", source = "custom")
        store.putRouting(block(current))
        reconnectIfConnected()
    }

    private fun reconnectIfConnected() {
        if (vpnState.value == MoonVpnService.Companion.State.Connected) connect()
    }

    fun clearStatus() { _status.value = "" }

    /** Regenerates the local proxy credentials, same shape as the desktop ones. */
    fun resetProxyCreds() = viewModelScope.launch {
        store.update {
            it.copy(
                proxyUser = "moon_" + UUID.randomUUID().toString().replace("-", "").take(8),
                proxyPass = UUID.randomUUID().toString().replace("-", "").take(12),
            )
        }
        _status.value = "Логин/пароль сброшены"
        reconnectIfConnected()
    }

    fun clearLogs() { _status.value = "Журнал ядра ведёт система, отдельный файл не пишется" }

    fun setProxyMode(tun: Boolean) = viewModelScope.launch {
        store.update { it.copy(tunMode = tun) }
        reconnectIfConnected()
    }

    /** Generic one-off message in the snackbar (copied, empty clipboard, not-built-yet screens). */
    fun notImplemented(what: String) { _status.value = what }

    fun reportPermissionDenied() { _status.value = "Разрешение на VPN не выдано" }

    /** Settings screen edits the state directly — one setter beats twenty. */
    fun patch(block: cc.moon.internet.data.AppState.() -> cc.moon.internet.data.AppState) =
        viewModelScope.launch { store.update { it.block() } }

    private fun hostOf(url: String) = runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)
}

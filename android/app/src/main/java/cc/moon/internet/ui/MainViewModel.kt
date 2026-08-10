package cc.moon.internet.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cc.moon.internet.R
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

    /** Status text is user-facing, so it goes through resources like everything else. */
    private fun s(@androidx.annotation.StringRes id: Int, vararg args: Any?) =
        getApplication<Application>().getString(id, *args)

    private val store = Store(app)
    val state get() = store.state
    val ready get() = store.ready

    private val _status = MutableStateFlow("")
    val status = _status.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    /** raw share link → ping in ms (-1 failed, -2 unknown/not measured). */
    private val _pings = MutableStateFlow<Map<String, Int>>(emptyMap())
    val pings = _pings.asStateFlow()

    /** Share links currently being measured — those rows show a spinner instead of a number. */
    private val _pinging = MutableStateFlow<Set<String>>(emptySet())
    val pinging = _pinging.asStateFlow()

    /** True while any subscription-wide ping or refresh is running, for the button spinners. */
    private val _pingingAll = MutableStateFlow(false)
    val pingingAll = _pingingAll.asStateFlow()

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

    /**
     * A one-shot "connected"/"disconnected" note, separate from the ongoing VPN notification the
     * system requires. Uses the same channel pair, so it follows the heads-up switch.
     */
    private fun notifyConnection(text: String) {
        if (!store.state.value.notifyConnection) return
        postNotice(text)
    }

    /** Posts a one-shot notice. The master switch is the only gate here; callers add their own. */
    private fun postNotice(text: String) {
        if (!store.state.value.notificationsEnabled) return
        val ctx = getApplication<Application>()
        val id = if (cc.moon.internet.data.Lang.headsUp(ctx)) "moon_vpn_headsup" else "moon_vpn"
        val n = androidx.core.app.NotificationCompat.Builder(ctx, id)
            .setSmallIcon(R.drawable.ic_tile_moon)
            .setContentTitle("Moon Internet")
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        runCatching { androidx.core.app.NotificationManagerCompat.from(ctx).notify(42, n) }
    }

    /** 0..100 while an update is downloading, -1 when the size is unknown, null when idle. */
    private val _downloadProgress = MutableStateFlow<Int?>(null)
    val downloadProgress = _downloadProgress.asStateFlow()

    /**
     * Fetches the release APK and opens the system installer on it. Android never installs
     * silently — the installer asks, and on 8+ the user has to allow "install unknown apps" for us
     * once; both are its own screens. If that permission is missing we send them straight to it.
     */
    fun downloadAndInstall() = viewModelScope.launch {
        val url = _release.value?.apkUrl
        val ctx = getApplication<Application>()
        if (url.isNullOrBlank()) { _updateStatus.value = s(R.string.upd_no_apk); return@launch }
        if (!cc.moon.internet.data.ApkInstaller.canInstall(ctx)) {
            _updateStatus.value = s(R.string.upd_allow_install)
            cc.moon.internet.data.ApkInstaller.openInstallPermission(ctx)
            return@launch
        }
        _downloadProgress.value = 0
        _updateStatus.value = s(R.string.upd_downloading)
        val file = cc.moon.internet.data.ApkInstaller.download(ctx, url) { p -> _downloadProgress.value = p }
        _downloadProgress.value = null
        if (file == null) { _updateStatus.value = s(R.string.upd_failed); return@launch }
        _updateStatus.value = s(R.string.upd_installing)
        if (!cc.moon.internet.data.ApkInstaller.install(ctx, file)) _updateStatus.value = s(R.string.upd_failed)
    }

    /**
     * First run of a build we have not greeted yet: say what changed, once. A fresh install says
     * nothing — there is no "what changed" from nothing.
     */
    private suspend fun announceIfUpdated() {
        val st = store.state.value
        if (st.lastSeenVersion == appVersion) return
        val firstEver = st.lastSeenVersion.isEmpty()
        store.update { it.copy(lastSeenVersion = appVersion) }
        if (firstEver || !st.notifyAfterUpdate) return
        _updateStatus.value = s(R.string.upd_installed, appVersion)
        postNotice(_updateStatus.value)
    }

    /** Asks GitHub. Runs once at launch too, quietly — a failure just leaves the badge off. */
    fun checkUpdate() = viewModelScope.launch {
        _updateStatus.value = s(R.string.vm_checking)
        val r = cc.moon.internet.data.UpdateService.latest()
        if (r == null) { _updateStatus.value = s(R.string.vm_check_failed); return@launch }
        _release.value = r
        _updateAvailable.value = cc.moon.internet.data.UpdateService.isNewer(r.version, appVersion)
        _updateStatus.value =
            if (_updateAvailable.value) s(R.string.vm_update_available, r.version) else s(R.string.vm_up_to_date)
        val st = store.state.value
        if (_updateAvailable.value && st.notificationsEnabled && st.notifyAppUpdate)
            postNotice(s(R.string.vm_update_available, r.version))
    }

    init {
        viewModelScope.launch {
            store.load()                                  // offline first: servers show instantly
            // A stable per-install id, made once. Panels key their limits off it, and the About
            // row was showing an empty value because nothing ever created one.
            if (store.state.value.hwid.isBlank())
                store.update { it.copy(hwid = UUID.randomUUID().toString().replace("-", "")) }
            // Both, not one or the other: refreshAll bails out early when there is nothing to
            // fetch, and the ping went with it — which is why the list opened with no numbers
            // until the button was pressed.
            if (store.state.value.updateOnStart) refreshAll(silent = true)
            if (store.state.value.pingOnStart && _pings.value.isEmpty()) pingAll()
            announceIfUpdated()
            requestAutoConnect()
        }
        // Always check at launch. Gating the check itself on the notification setting left the
        // About page showing a dash for the GitHub version and "you are up to date" for something
        // nobody had looked up — the setting belongs on the popup, not on the lookup.
        checkUpdate()
        viewModelScope.launch { watchTraffic() }
        viewModelScope.launch {
            vpnState.collect { s ->
                if (s == MoonVpnService.Companion.State.Connected) {
                    connectedAt = System.currentTimeMillis()
                    notifyConnection(s(R.string.homescreen_003))
                    // the desktop measures the tunnel as soon as it is up; do the same here
                    checkConnection()
                }
                if (s == MoonVpnService.Companion.State.Disconnected ||
                    s == MoonVpnService.Companion.State.Paused) {
                    _speed.value = "—" to "—"; _sessionTraffic.value = "—"; _elapsed.value = "—"
                    _checkPing.value = ""
                    lastUp = 0; lastDown = 0
                    if (s == MoonVpnService.Companion.State.Disconnected)
                        notifyConnection(this@MainViewModel.s(R.string.homescreen_006))
                }
            }
        }
        // the tunnel reports failures on its own thread — surface them instead of silently going dark
        viewModelScope.launch { vpnError.filterNotNull().collect { _status.value = it } }
        viewModelScope.launch { autoPingLoop() }
    }

    /**
     * Re-measures in the background on the interval the user picked, off by default.
     *
     * Checks the setting every minute rather than sleeping for the whole interval, so changing it
     * takes effect now instead of after the old one finally elapses. Skips a round while the
     * screen is not in use — the point is to have fresh numbers when the list is opened, not to
     * keep the radio busy in a pocket.
     */
    private suspend fun autoPingLoop() {
        var sinceLastRun = 0
        while (true) {
            kotlinx.coroutines.delay(60_000)
            val every = store.state.value.pingEveryMinutes
            if (every <= 0) { sinceLastRun = 0; continue }
            sinceLastRun++
            if (sinceLastRun < every) continue
            sinceLastRun = 0
            val servers = store.state.value.subscriptions.flatMap { it.servers }
            if (servers.isNotEmpty()) pingServers(servers)
        }
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
                _status.value = s(R.string.vm_server_added)
            } ?: run { _status.value = s(R.string.vm_not_a_link) }
            return@launch
        }
        _busy.value = true
        runCatching { SubscriptionService.fetch(u, hwidHeader()) }
            .onSuccess { f ->
                store.update { st ->
                    val sub = Subscription(
                        url = u,
                        name = f.title ?: hostOf(u),
                        servers = f.servers,
                        announcement = f.announcement,
                        trafficText = f.trafficText,
                        expiryText = f.expiryText,
                        trafficFraction = f.trafficFraction,
                        expiryFraction = f.expiryFraction,
                        updateIntervalMinutes = f.updateMinutes,
                        fetchedAt = System.currentTimeMillis(),
                    )
                    st.copy(
                        subscriptions = st.subscriptions.filterNot { it.url == u } + sub,
                        selectedServerRaw = st.selectedServerRaw ?: f.servers.firstOrNull()?.raw,
                    )
                }
                _status.value = s(R.string.vm_loaded, f.servers.size)
                warnAboutSubscription(f.title ?: hostOf(u), u, f)
                pingAll()
            }
            .onFailure { _status.value = s(R.string.vm_load_error, it.message) }
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
        if (refreshOne(url)) _status.value = s(R.string.vm_sub_updated) else _status.value = s(R.string.vm_sub_update_failed)
        _busy.value = false
    }

    /** Warned already this run — one notice per subscription per condition, not per refresh. */
    private val warned = mutableSetOf<String>()

    /**
     * The two "your plan is running out" notices. Both were switches with nothing behind them:
     * they saved fine and nobody ever looked at them.
     */
    private fun warnAboutSubscription(name: String, url: String, f: SubscriptionService.Fetched) {
        val st = store.state.value
        if (!st.notificationsEnabled) return

        if (st.notifyExpiry && f.expiryFraction >= 0) {
            val daysLeft = Math.round((1 - f.expiryFraction) * 30).toInt()
            if (daysLeft <= st.expiryNotifyDays && warned.add("$url|exp"))
                postNotice(s(R.string.sub_warn_expiry, name, daysLeft))
        }
        if (st.notifyTrafficLow && f.trafficFraction >= 0) {
            val leftPct = Math.round((1 - f.trafficFraction) * 100).toInt()
            if (leftPct <= 10 && warned.add("$url|traffic"))
                postNotice(s(R.string.sub_warn_traffic, name, leftPct))
        }
    }

    /** null when the user turned the header off, so the request goes out without it. */
    private fun hwidHeader() = store.state.value.let { if (it.sendHwid) it.hwid.ifBlank { null } else null }

    private suspend fun refreshOne(u: String): Boolean =
        runCatching { SubscriptionService.fetch(u, hwidHeader()) }.onSuccess { f ->
            store.update { st ->
                st.copy(subscriptions = st.subscriptions.map {
                    if (it.url != u) it else it.copy(
                        name = f.title ?: it.name,
                        servers = f.servers,
                        announcement = f.announcement,
                        trafficText = f.trafficText,
                        expiryText = f.expiryText,
                        trafficFraction = f.trafficFraction,
                        expiryFraction = f.expiryFraction,
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
        _status.value = s(R.string.vm_sub_removed)
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

    fun dismissWelcome() = viewModelScope.launch { store.update { it.copy(welcomeShown = true) } }


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
     * Raised once at launch when auto-connect is on and a server has been chosen. The activity
     * owns the VPN consent dialog, so it has to do the connecting — the view model can only ask.
     *
     * Auto-connect never fired on the phone before: autoTarget() existed but only the
     * quick-settings tile ever called it, and nothing connected on start at all.
     */
    private val _autoConnectRequest = MutableStateFlow<ServerProfile?>(null)
    val autoConnectRequest = _autoConnectRequest.asStateFlow()
    fun autoConnectHandled() { _autoConnectRequest.value = null }

    private suspend fun requestAutoConnect() {
        if (!store.state.value.autoConnect) return
        if (vpnState.value != MoonVpnService.Companion.State.Disconnected) return
        // Give the first ping pass a moment: without any measurement the picker has no grounds to
        // overrule the stored preference, which is how it used to dial a server that was down.
        repeat(20) {
            if (_pings.value.isNotEmpty()) return@repeat
            kotlinx.coroutines.delay(250)
        }
        val target = autoTarget() ?: return
        store.selectServer(target)
        _autoConnectRequest.value = target
    }

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
        val st = store.state.value
        _status.value = s(R.string.vm_pinging, targets.size)

        // rows show a spinner instead of a stale number while they are being measured
        _pinging.value = targets.mapNotNull { it.raw }.toSet()

        // Стабильность starts a core per server, so one at a time — six at once would be six
        // cores and the phone would feel it.
        val parallel = if (st.pingMethod == "stability") 1 else PING_PARALLEL
        val gate = kotlinx.coroutines.sync.Semaphore(parallel)
        try {
            kotlinx.coroutines.coroutineScope {
                targets.mapIndexed { i, s ->
                    async(Dispatchers.IO) {
                        // Optional stagger: thirty handshakes in one instant look like a port
                        // scan and some providers throttle or drop the volley.
                        if (st.pingStagger && st.pingStaggerMs > 0) {
                            kotlinx.coroutines.delay(i.toLong() * st.pingStaggerMs)
                        }
                        gate.withPermit {
                            val ms = measurePing(s)
                            s.raw?.let { raw ->
                                _pings.update { it + (raw to ms) }
                                _pinging.update { it - raw }
                            }
                        }
                    }
                }.forEach { it.await() }
            }
        } finally {
            _pinging.value = emptySet()
            _status.value = ""
        }
    }

    /**
     * The same five methods as the desktop.
     *
     *  moon / tcp          — TCP handshake straight to the server. Channel latency, and all it
     *                        proves is that something is listening on the port.
     *  httpget / httphead  — an HTTP request to the server itself, time to first byte.
     *  stability           — a real connection through the protocol; see [XrayRunner.measureOutbound].
     *
     * httpget/httphead used to request the *test URL* instead of the server, which measured the
     * path to gstatic and handed every server the same number no matter what — that is the
     * "only our method works" report.
     */
    private fun measurePing(s: ServerProfile): Int {
        val st = store.state.value
        val timeout = st.pingTimeoutMs
        return when (st.pingMethod) {
            "stability" -> stabilityPing(s, st.pingTestUrl)
            "httpget", "httphead" -> httpPing(s, timeout, head = st.pingMethod == "httphead")
            else -> tcpPing(s.address, s.port, timeout)
        }
    }

    private fun tcpPing(host: String, port: Int, timeout: Int = 3000): Int = try {
        val t0 = System.currentTimeMillis()
        Socket().use { it.connect(InetSocketAddress(host, port), timeout) }
        (System.currentTimeMillis() - t0).toInt()
    } catch (_: Exception) { -1 }

    /** Time to first byte from the server's own port — not from the test URL. */
    private fun httpPing(s: ServerProfile, timeout: Int, head: Boolean): Int = try {
        val t0 = System.currentTimeMillis()
        val scheme = if (s.port == 80) "http" else "https"
        (java.net.URL("$scheme://${s.address}:${s.port}/").openConnection() as java.net.HttpURLConnection).run {
            connectTimeout = timeout; readTimeout = timeout
            instanceFollowRedirects = false
            requestMethod = if (head) "HEAD" else "GET"
            // A VLESS/Trojan port answers no HTTP at all; the connect time is still a real
            // round-trip, so a read failure after a successful connect is not a dead server.
            try { responseCode } catch (_: Exception) { }
            disconnect()
        }
        (System.currentTimeMillis() - t0).toInt()
    } catch (_: Exception) { -1 }

    /**
     * Raises a real connection through this server and fetches the test URL over it. Slower than
     * a handshake, and the only method here that can tell a working protocol from an open port.
     */
    private fun stabilityPing(s: ServerProfile, url: String): Int {
        if (!XrayConfig.supports(s.protocol)) return -2   // unknown, not down
        // Not while our own tunnel is up. The probe raises a second core in this same process,
        // and doing that beside a running one is what made every Hysteria server read as dead and
        // occasionally took the live connection down with it. A plain handshake is a worse answer
        // than stability gives, but it is an answer that does not lie about the tunnel.
        if (vpnState.value != MoonVpnService.Companion.State.Disconnected)
            return tcpPing(s.address, s.port, store.state.value.pingTimeoutMs)
        val cfg = runCatching {
            XrayConfig.buildProxyOnly(
                XrayConfig.build(server = s, routing = null, hasGeoFiles = false, logLevel = "none")
            )
        }.getOrNull() ?: return -1
        return cc.moon.internet.vpn.XrayRunner.measureOutbound(cfg, url)
    }

    /**
     * Real end-to-end check. The core measures it itself: our own process is excluded from the
     * tunnel, so an HTTP call from here would not go through the proxy at all and would report
     * a meaningless number.
     */
    fun checkConnection() = viewModelScope.launch(Dispatchers.IO) {
        _checkPing.value = "…"
        val ms = MoonVpnService.runner?.measureDelay()
        _checkPing.value = when {
            ms == null -> s(R.string.vm_no_reply)
            ms < 0 -> s(R.string.vm_no_reply)
            else -> "$ms ms"
        }
        // The same number goes into the row, so the pill by the button and the list cannot show
        // two different figures for one server.
        if (ms != null && ms >= 0) store.selectedServer()?.raw?.let { raw ->
            _pings.update { it + (raw to ms.toInt()) }
        }
    }

    // ---- connection ------------------------------------------------------
    fun connect() {
        val s = store.selectedServer() ?: run { _status.value = s(R.string.vm_pick_server); return }
        if (!XrayConfig.supports(s.protocol)) {
            _status.value = s(R.string.vm_unsupported, s.protocolLabel)
            return
        }
        val st = store.state.value

        // Another VPN client on the phone probably owns 10808/10809 already — see freeLocalPort.
        // proxy mode also shares the listeners on the LAN — that is what makes it a proxy
        val listen = if (st.allowLan || !st.tunMode) "0.0.0.0" else "127.0.0.1"
        val socks = freeLocalPort(st.socksPort, listen)
        val http = freeLocalPort(st.httpPort, listen, avoid = setOf(socks))
        if (socks != st.socksPort || http != st.httpPort) {
            _status.value = s(R.string.vm_port_taken, socks, http)
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
                logFile = if (st.logsEnabled) cc.moon.internet.data.LogStore.file(getApplication()).absolutePath else null,
                socksPort = socks,
                httpPort = http,
                proxyUser = st.proxyUser,
                proxyPass = st.proxyPass,
                socksAuth = st.socks5Auth,
                httpAuth = st.httpProxyAuth,
                allowLan = st.allowLan,
                dnsList = dnsServers(st),
            )
        }.getOrElse { _status.value = s(R.string.vm_config_error, it.message); return }

        // Прокси-режим: только локальные SOCKS/HTTP, без системного туннеля.
        // Proxy mode keeps the tunnel. Stripping the tun inbound left nothing capturing traffic,
        // which is why picking it looked like the app had stopped working — INCY's "proxy" is
        // their TUN_PROXY, tunnel plus an exposed local proxy, not their PROXY_ONLY.

        if (st.useRouting && store.activeRouting() != null && !GeoService.ready(getApplication())) {
            refreshGeo(force = false)
        }

        MoonVpnService.start(
            // flag + label: the notification headline, same as INCY's
            getApplication(), config, (flagOf(s) + " " + s.label).trim(),
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

    /** One block of the geo-files card: who it belongs to and where its two lists come from. */
    data class GeoSource(val owner: String, val geoip: String, val geosite: String, val showOwner: Boolean)

    /**
     * INCY and HAPP usually point at the same geoip/geosite release, and then there is nothing to
     * choose between — one block, as before. When their URLs differ that is worth seeing, so each
     * set gets its own block with the owners named.
     */
    fun geoSources(): List<GeoSource> {
        val imported = store.state.value.routings.filter { it.source == "incy" || it.source == "happ" }
        if (imported.isEmpty()) return listOf(GeoSource("", geoipInfo(), geositeInfo(), false))
        val groups = imported.groupBy { it.geoipUrl to it.geositeUrl }
        val split = groups.size > 1
        val active = store.state.value.routingSource
        return groups.map { (urls, profiles) ->
            // Only the selected profile's lists are the ones actually on disk; for the other set we
            // show where it would come from rather than a size we never fetched.
            val onDisk = !split || profiles.any { it.source == active }
            GeoSource(
                profiles.map { it.source.uppercase() }.distinct().joinToString(" · "),
                if (onDisk) geoipInfo() else host(urls.first),
                if (onDisk) geositeInfo() else host(urls.second),
                split,
            )
        }
    }

    private fun host(url: String) =
        runCatching { java.net.URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: s(R.string.geo_no_url)

    fun setRoutingSource(src: String) = viewModelScope.launch {
        store.update { it.copy(routingSource = src) }
        reconnectIfConnected()
    }

    fun refreshGeo(force: Boolean = true) = viewModelScope.launch {
        if (_geoBusy.value) return@launch
        _geoBusy.value = true
        _geoStatus.value = s(R.string.vm_downloading)
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
        if (st.routingSource != "custom") { _status.value = s(R.string.vm_rules_custom_only); return }
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
        _status.value = s(R.string.vm_creds_reset)
        reconnectIfConnected()
    }

    fun clearLogs() {
        cc.moon.internet.data.LogStore.clear(getApplication())
        _status.value = s(R.string.vm_logs_cleared)
    }

    /** Size of the core log for the settings row. */
    fun logsSize(): String = cc.moon.internet.data.LogStore.size(getApplication())

    /** Last lines of the core log, for the viewer. */
    fun logsTail(): String = cc.moon.internet.data.LogStore.tail(getApplication())

    /** Generic one-off message in the snackbar (copied, empty clipboard, not-built-yet screens). */
    fun notImplemented(what: String) { _status.value = what }

    fun reportPermissionDenied() { _status.value = s(R.string.vm_no_permission) }

    /** Settings screen edits the state directly — one setter beats twenty. */
    fun patch(block: cc.moon.internet.data.AppState.() -> cc.moon.internet.data.AppState) =
        viewModelScope.launch { store.update { it.block() } }

    private fun hostOf(url: String) = runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)
}

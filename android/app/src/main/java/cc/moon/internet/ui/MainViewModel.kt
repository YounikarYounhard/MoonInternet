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
import kotlinx.coroutines.withContext
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
        /** The one bucket every hand-added server goes into — see addSubscription. */
        const val LOOSE_URL = "clipboard:local"

        // One id per kind of notice. 1 belongs to the service's ongoing notification.
        const val NOTICE_CONNECTION = 42
        const val NOTICE_UPDATE = 43
        const val NOTICE_INSTALLED = 44
        const val NOTICE_EXPIRY = 45
        const val NOTICE_TRAFFIC = 46
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

    /** Subscriptions being refetched right now, by URL — so only the row you pressed spins. */
    private val _refreshing = MutableStateFlow<Set<String>>(emptySet())
    val refreshing = _refreshing.asStateFlow()

    /** result of "Проверить соединение" — shown next to the button while connected. */
    private val _checkPing = MutableStateFlow("")
    val checkPing = _checkPing.asStateFlow()

    val vpnState = MoonVpnService.state
    val traffic = MoonVpnService.traffic
    val vpnError = MoonVpnService.lastError

    private var lastUp = 0L
    private var lastDown = 0L

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
        postNotice(text, NOTICE_CONNECTION)
    }

    /**
     * Posts a one-shot notice. The master switch is the only gate here; callers add their own.
     *
     * Each kind gets its own id. They all shared one, so whichever came second silently replaced
     * the first — an update notice wiped the "connected" one, a subscription warning wiped that —
     * and the ones that lost read as notifications that only turn up every other time.
     */
    private fun postNotice(text: String, id: Int) {
        if (!store.state.value.notificationsEnabled) return
        val ctx = getApplication<Application>()
        val channel = if (cc.moon.internet.data.Lang.headsUp(ctx)) "moon_vpn_headsup" else "moon_vpn"
        val open = android.app.PendingIntent.getActivity(
            ctx, id,
            android.content.Intent(ctx, cc.moon.internet.MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = androidx.core.app.NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_tile_moon)
            .setContentTitle("Moon Internet")
            .setContentText(text)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { androidx.core.app.NotificationManagerCompat.from(ctx).notify(id, n) }
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
        // upd_whats_new is the one-line changelog; it lives beside the version check so it
        // gets rewritten whenever the version does.
        _updateStatus.value = s(R.string.upd_installed, appVersion, s(R.string.upd_whats_new))
        postNotice(_updateStatus.value, NOTICE_INSTALLED)
    }

    /** Asks GitHub. Runs once at launch too, quietly — a failure just leaves the badge off. */
    fun checkUpdate() = viewModelScope.launch { checkUpdateOnce() }

    /** True when GitHub answered. */
    private suspend fun checkUpdateOnce(): Boolean {
        _updateStatus.value = s(R.string.vm_checking)
        val r = cc.moon.internet.data.UpdateService.latest()
        if (r == null) { _updateStatus.value = s(R.string.vm_check_failed); return false }
        _release.value = r
        _updateAvailable.value = cc.moon.internet.data.UpdateService.isNewer(r.version, appVersion)
        _updateStatus.value =
            if (_updateAvailable.value) s(R.string.vm_update_available, r.version) else s(R.string.vm_up_to_date)
        val st = store.state.value
        if (_updateAvailable.value && st.notificationsEnabled && st.notifyAppUpdate)
            postNotice(s(R.string.vm_update_available, r.version), NOTICE_UPDATE)
        return true
    }

    /**
     * Keep asking. One attempt at launch is one attempt at the worst possible moment — the phone
     * has just woken, the tunnel is not up, the network often is not either — and when it failed
     * nothing ever asked again, so the About page sat on "could not check" until somebody pressed
     * the button. That is what "the update thing works sometimes" was.
     *
     * A minute apart while it is failing, six hours apart once it has answered.
     */
    private fun watchForUpdates() = viewModelScope.launch {
        while (true) {
            val answered = runCatching { checkUpdateOnce() }.getOrDefault(false)
            kotlinx.coroutines.delay(if (answered) 6 * 60 * 60_000L else 60_000L)
        }
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
            // Awaited, both of them. refreshAll ends with a ping of its own, and this used to
            // fire a second pass on top of it because _pings was still empty when the check ran —
            // two passes measuring the same servers at once, trampling the same maps.
            if (store.state.value.updateOnStart) refreshAll(silent = true).join()
            if (store.state.value.pingOnStart && _pings.value.isEmpty())
                viewModelScope.launch { pingServers(store.allServers, auto = true) }.join()
            announceIfUpdated()
            requestAutoConnect()
            // Last, and quietly: an engine the user already has, missing or older than the
            // catalogue says, comes back on its own. This is what an app update looks like from
            // the engines' side — they outlive it, and only a newer one has to be fetched.
            refreshCores()
        }
        // Always check at launch. Gating the check itself on the notification setting left the
        // About page showing a dash for the GitHub version and "you are up to date" for something
        // nobody had looked up — the setting belongs on the popup, not on the lookup.
        watchForUpdates()
        viewModelScope.launch { watchTraffic() }
        viewModelScope.launch {
            vpnState.collect { s ->
                if (s == MoonVpnService.Companion.State.Connected) {
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
        viewModelScope.launch { autoUpdateSubsLoop() }
        // The filter is remembered across runs, so a stale one survives a rename or a deleted
        // subscription. orderServers already refuses to empty the list over it, but the chip row
        // would still show nothing selected — so drop it once the servers are known.
        viewModelScope.launch {
            store.ready.first { it }
            val chosen = store.state.value.protocol
            if (chosen.isNotEmpty() && store.allServers.none { it.protocolLabel == chosen }) {
                store.update { it.copy(protocol = "") }
            }
            store.healSelection()
            store.migrateFavorites()
        }
    }

    /**
     * Refetches subscriptions on their own schedule. «Автообновление подписок» and the interval
     * chips under it were drawn, saved — and read by nothing at all: the only refresh that ever
     * happened on the phone was the one at launch, so a subscription whose servers had changed
     * stayed stale until the app was restarted.
     *
     * Interval 0 means "follow the subscription", i.e. whatever the panel put in its own header,
     * falling back to an hour. Checked every minute rather than slept through, so a change to the
     * setting takes effect now; skipped while a fetch or a ping pass is already running.
     */
    private suspend fun autoUpdateSubsLoop() {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            val st = store.state.value
            if (!st.autoUpdateSubs || st.subscriptions.isEmpty()) continue
            if (_pingingAll.value || _refreshing.value.isNotEmpty()) continue
            val now = System.currentTimeMillis()
            val due = st.subscriptions.filter { sub ->
                val every = when {
                    st.subIntervalMinutes > 0 -> st.subIntervalMinutes
                    sub.updateIntervalMinutes > 0 -> sub.updateIntervalMinutes
                    else -> 60
                }
                sub.fetchedAt <= 0 || now - sub.fetchedAt >= every * 60_000L
            }
            due.forEach { refreshSubscription(it.url, silent = true).join() }
        }
    }

    /**
     * Re-measures in the background on the interval the user picked; a quarter of an hour to
     * start with, which is often enough that the numbers stay fresh and rare enough that
     * nobody's provider notices.
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
            if (servers.isNotEmpty()) pingServers(servers, auto = true)
        }
    }

    // ---- subscriptions ---------------------------------------------------
    /** @param name what you called it yourself; blank keeps whatever the panel sends. */
    fun addSubscription(url: String, name: String = "") = viewModelScope.launch {
        val u = url.trim()
        if (u.isBlank()) return@launch
        // a bare share link is a single server, not a subscription
        if (!u.startsWith("http", true)) {
            ShareLinkParser.parse(u)?.let { p ->
                store.update { st ->
                    // One bucket for everything pasted or scanned by hand. Every paste used to mint
                    // its own pseudo-subscription, so five links drew five cards each holding one
                    // server named after the card — "подключение в подписке подключение".
                    val bucket = st.subscriptions.firstOrNull { it.url == LOOSE_URL }
                    val merged = Subscription(
                        url = LOOSE_URL,
                        name = this@MainViewModel.s(R.string.loose_servers),
                        servers = (bucket?.servers.orEmpty() + p).distinctBy { it.key },
                    )
                    st.copy(
                        subscriptions =
                            if (bucket == null) st.subscriptions + merged
                            else st.subscriptions.map { if (it.url == LOOSE_URL) merged else it },
                        selectedServerRaw = st.selectedServerRaw ?: p.key,
                    )
                }
                _status.value = s(R.string.vm_server_added)
            } ?: run { _status.value = s(R.string.vm_not_a_link) }
            return@launch
        }
        _busy.value = true
        val wasKnown = store.state.value.subscriptions.any { it.url == u }
        runCatching { SubscriptionService.fetch(u, hwidHeader()) }
            .onSuccess { f ->
                store.update { st ->
                    val sub = Subscription(
                        url = u,
                        name = name.ifBlank { f.title ?: hostOf(u) },
                        servers = f.servers,
                        announcement = f.announcement,
                        trafficText = f.trafficText,
                        expiryText = f.expiryText,
                        trafficFraction = f.trafficFraction,
                        expiryFraction = f.expiryFraction,
                        updateIntervalMinutes = f.updateMinutes,
                        fetchedAt = System.currentTimeMillis(),
                    )
                    // Adding one you already have is a refresh, not a new entry. Dropping it and
                    // appending sent it to the bottom of the list and threw away the name you had
                    // given it, while the notice still read "загружено N серверов" as though it
                    // were new.
                    val known = st.subscriptions.any { it.url == u }
                    st.copy(
                        subscriptions =
                            if (known) st.subscriptions.map {
                                if (it.url == u) sub.copy(name = name.ifBlank { it.name }) else it
                            }
                            else st.subscriptions + sub,
                        selectedServerRaw = st.selectedServerRaw ?: f.servers.firstOrNull()?.key,
                    )
                }
                _status.value =
                    if (wasKnown) s(R.string.vm_sub_updated) else s(R.string.vm_loaded, f.servers.size)
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
        _refreshing.update { it + urls }
        urls.forEach { refreshOne(it); _refreshing.update { s -> s - it } }
        _busy.value = false
        pingServers(store.allServers, auto = true)
    }

    /** [silent]: the background refresh says nothing — a snackbar every hour is not news. */
    fun refreshSubscription(url: String, silent: Boolean = false) = viewModelScope.launch {
        if (!url.startsWith("http")) return@launch
        if (!silent) _busy.value = true
        _refreshing.update { it + url }
        val ok = refreshOne(url)
        if (!silent) {
            // Which one. With several subscriptions "Подписка обновлена" leaves you guessing
            // whether the button you pressed is the one that answered.
            val name = store.state.value.subscriptions.firstOrNull { it.url == url }?.name
                ?.takeIf { it.isNotBlank() }
            _status.value = when {
                ok && name != null -> s(R.string.vm_sub_updated_named, name)
                ok -> s(R.string.vm_sub_updated)
                name != null -> s(R.string.vm_sub_update_failed_named, name)
                else -> s(R.string.vm_sub_update_failed)
            }
        }
        _refreshing.update { it - url }
        if (!silent) _busy.value = false   // a background pass must not clear somebody else's spinner
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
                postNotice(s(R.string.sub_warn_expiry, name, daysLeft), NOTICE_EXPIRY)
        }
        if (st.notifyTrafficLow && f.trafficFraction >= 0) {
            val leftPct = Math.round((1 - f.trafficFraction) * 100).toInt()
            if (leftPct <= 10 && warned.add("$url|traffic"))
                postNotice(s(R.string.sub_warn_traffic, name, leftPct), NOTICE_TRAFFIC)
        }
    }

    /** null when the user turned the header off, so the request goes out without it. */
    private fun hwidHeader() = store.state.value.let { if (it.sendHwid) it.hwid.ifBlank { null } else null }

    private suspend fun refreshOne(u: String): Boolean =
        runCatching { SubscriptionService.fetch(u, hwidHeader()) }.onSuccess { f ->
            store.update { st ->
                st.copy(subscriptions = st.subscriptions.map {
                    if (it.url != u) it else it.copy(
                        // Deliberately NOT the panel's title: a refresh must not rename. The name
                        // is chosen when the subscription is added and changed only on purpose —
                        // and now that refreshes run on a timer, taking the title back would undo
                        // whatever you called it, once an hour, for ever. The desktop already
                        // behaves this way.
                        servers = f.servers,
                        announcement = f.announcement,
                        trafficText = f.trafficText,
                        expiryText = f.expiryText,
                        trafficFraction = f.trafficFraction,
                        expiryFraction = f.expiryFraction,
                        updateIntervalMinutes = f.updateMinutes,
                        fetchedAt = System.currentTimeMillis(),
                    )
                })
            }
            importRoutings(u, f.routingLinks)
            cc.moon.internet.data.DevLog.log(
                "подписка", "обновлена ${hostOf(u)}: серверов ${f.servers.size}")
            // A refresh can retire the server the selection named. Write down whatever we fall
            // back to, so the saved value and the highlighted row stay the same server.
            store.healSelection()
            store.migrateFavorites()
        }.isSuccess

    /**
     * Subscriptions carry incy://routing/add/… payloads; that is where the rule set comes from.
     *
     * The id is the subscription plus the source rather than random, so refetching updates the
     * profile it already imported instead of stacking another copy on every refresh.
     */
    private suspend fun importRoutings(subUrl: String, links: List<String>) {
        links.filter(RoutingParser::isRoutingLink)
            .mapNotNull(RoutingParser::parse)
            .forEach { store.putRouting(it.copy(id = store.importedId(subUrl, it.source), subUrl = subUrl)) }
    }

    fun removeSubscription(url: String) = viewModelScope.launch {
        val loose = url.startsWith(LOOSE_URL.substringBefore(':'))
        store.removeSubscription(url)
        // «Мои подключения» is not a subscription — saying one was removed sends people looking
        // for something they never had.
        _status.value = s(if (loose) R.string.vm_loose_removed else R.string.vm_sub_removed)
    }

    fun removeServer(server: ServerProfile) = viewModelScope.launch {
        store.removeServer(server)
        _status.value = s(R.string.vm_server_removed)
    }

    fun toggleCollapse(url: String) = viewModelScope.launch {
        // Folding a card is the list changing SHAPE, and everything below it lands somewhere new
        // through no choice of its own — see ListMotion. Said before the state changes, so the
        // very first re-layout is already covered.
        ListMotion.hush()
        store.update { st ->
            st.copy(collapsed = if (url in st.collapsed) st.collapsed - url else st.collapsed + url)
        }
        // Long enough for the list to be re-laid-out and the scroll to settle against its new end.
        kotlinx.coroutines.delay(700)
        ListMotion.release()
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
    fun setProtocol(p: String) = viewModelScope.launch { store.update { it.copy(protocol = p) } }

    fun dismissWelcome() = viewModelScope.launch { store.update { it.copy(welcomeShown = true) } }


    fun sortedServers(): List<ServerProfile> = store.sortedServers(_pings.value)

    /**
     * The subscription list as the Home and Servers pages should draw it: static by default, or
     * with the one holding the active server at the top when «Активная подписка сверху» is on.
     */
    fun orderedSubscriptions(st: cc.moon.internet.data.AppState): List<Subscription> {
        if (!st.pinActiveSub) return st.subscriptions
        // The server actually selected, not the saved string: when that string names nothing the
        // app falls back to the first server, and sorting by the string then floats nobody.
        val active = store.selectedServer()?.key ?: return st.subscriptions
        return st.subscriptions.sortedByDescending { sub -> sub.servers.any { it.key == active } }
    }

    /** Sorted servers of one subscription — Home and Servers both go through this. */
    fun sortedIn(sub: Subscription): List<ServerProfile> = store.sortedIn(sub, _pings.value)

    fun isFavorite(s: ServerProfile) = store.isFavorite(s)

    fun pingAll() = viewModelScope.launch { pingServers(store.allServers) }

    fun pingServer(s: ServerProfile) = viewModelScope.launch { pingServers(listOf(s)) }

    /**
     * «Стабильность» for one server, on demand, whatever the configured method is.
     *
     * It was only ever reachable by switching the global ping method in settings, running a pass
     * and switching back — for the one check that actually proves a server carries traffic rather
     * than merely accepting connections. That is the friction: it is an action, not a mode.
     */
    fun stabilityServer(s: ServerProfile) = viewModelScope.launch {
        if (_pingingAll.value) return@launch
        _pinging.update { it + s.pingKey }
        _status.value = this@MainViewModel.s(R.string.vm_stability_checking, s.label)
        val ms = withContext(Dispatchers.IO) { stabilityPing(s, store.state.value.pingTestUrl) }
        _pings.update { it + (s.pingKey to ms) }
        _pinging.update { it - s.pingKey }
        _status.value =
            if (ms >= 0) this@MainViewModel.s(R.string.vm_stability_ok, s.label, ms)
            else this@MainViewModel.s(R.string.vm_stability_dead, s.label)
    }

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
        // No waiting for measurements here: our caller already joins the start-up ping pass, so by
        // the time we run it has either finished or was never asked for. The old wait was five
        // seconds of nothing either way — `return@repeat` leaves one iteration of `repeat`, not
        // the loop, so the early exit never fired.
        val target = autoTarget() ?: return
        store.selectServer(target, "автоподключение при запуске")
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
     *
     * @param auto true when nobody asked — the startup sweep, the one after a subscription
     *   refresh, and the background timer.
     */
    private suspend fun pingServers(servers: List<ServerProfile>, auto: Boolean = false) {
        // Favourites first: they are pinned to the top of the list, so measuring them in list
        // order meant the one server you actually use waited out every other one. The stagger
        // below is indexed off this order, so first here is also first out.
        val targets = servers.sortedByDescending { store.isFavorite(it) }
        if (targets.isEmpty()) return
        // The flag existed and nothing ever raised it, so the ping button never span and stayed
        // pressable — a second tap on top of a running pass fought the first one for the same
        // sockets. One pass at a time, and the button says so.
        if (_pingingAll.value) return
        _pingingAll.value = true
        val st = store.state.value
        _status.value = s(R.string.vm_pinging, targets.size)

        // rows show a spinner instead of a stale number while they are being measured
        _pinging.value = targets.map { it.pingKey }.toSet()

        // Стабильность is not a ping. It brings up a core and carries a real request through the
        // protocol, so one measurement is one full session on the provider's server. Sweeping
        // every server with it, unprompted, every time a subscription refreshes, is thirty real
        // sessions the user never asked for — and that is what was filling the server's memory.
        // Automatic passes therefore use the cheap probe; Стабильность stays for when you press it.
        val method = if (auto && st.pingMethod == "stability") "moon" else st.pingMethod

        // Стабильность starts a core per server, so one at a time — six at once would be six
        // cores and the phone would feel it.
        val parallel = if (method == "stability") 1 else PING_PARALLEL
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
                            // One server, one number. The row for the server we are connected
                            // THROUGH is measured through the tunnel, exactly as the button
                            // measures it — otherwise the two ask different questions (does that
                            // address answer a probe / does this connection work) and answer with
                            // two different figures for the same row, which is what "the ping from
                            // below does not match the one on top" was.
                            // Against the server actually selected, not against the saved string:
                            // when that string names nothing the app falls back to the first server
                            // in the list, so the two are not always the same thing.
                            val active = store.selectedServer()?.key
                            val live = vpnState.value == MoonVpnService.Companion.State.Connected &&
                                s.pingKey == active
                            val ms = if (live) tunnelPing() else measurePing(s, method)
                            s.pingKey.let { raw ->
                                _pings.update { it + (raw to ms) }
                                _pinging.update { it - raw }
                                // ...and the same figure goes to the pill by the button, so the two
                                // cannot disagree by construction rather than by luck.
                                if (raw == active) {
                                    _checkPing.value = if (ms >= 0) "$ms ms" else s(R.string.vm_no_reply)
                                }
                            }
                        }
                    }
                }.forEach { it.await() }
            }
        } finally {
            _pinging.value = emptySet()
            _pingingAll.value = false
            // A whole pass with nothing to show has two very different causes and they were
            // reported as one. "Ни один сервер не отвечает" over mobile data that had dropped sent
            // people looking for a fault in their subscription.
            val silent = targets.size > 1 && targets.all { (_pings.value[it.pingKey] ?: -2) < 0 }
            _status.value = if (silent && !hasInternet()) s(R.string.vm_offline) else ""
        }
    }

    /**
     * Whether the phone has a working connection at all — validated, not merely attached: a hotel
     * Wi-Fi you have not signed into reports a network and carries nothing.
     */
    private fun hasInternet(): Boolean = runCatching {
        val cm = getApplication<Application>()
            .getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(true)   // cannot tell → do not accuse the network

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
    /**
     * The live tunnel's own round trip, in ms, or -1.
     *
     * The one measurement that answers "does this connection work", as opposed to "does that
     * address answer a probe". The core's own figure first, then a real request through our local
     * SOCKS port if it declines to give one — it has come back empty on a tunnel demonstrably
     * carrying hundreds of kilobytes, and a check that says "no reply" over a working connection
     * is worse than no check at all.
     */
    private fun tunnelPing(): Int =
        (MoonVpnService.runner?.measureDelay()?.takeIf { it >= 0 } ?: socksCheck())?.toInt() ?: -1

    private fun measurePing(s: ServerProfile, method: String): Int {
        val st = store.state.value
        val timeout = st.pingTimeoutMs
        // Стабильность carries a real request through the protocol, so it works for UDP endpoints
        // too. Everything else here is a TCP probe, and there is nothing on TCP to probe: mKCP and
        // QUIC are UDP transports, Hysteria2 and WireGuard are UDP protocols. Those rows showed ✕
        // for servers that were perfectly alive.
        if (method != "stability" && s.udpOnly) return udpEndpointPing(s, timeout)
        return when (method) {
            "stability" -> stabilityPing(s, st.pingTestUrl)
            "httpget", "httphead" -> httpPing(s, timeout, head = method == "httphead")
            else -> tcpPing(s.address, s.port, timeout)
        }
    }

    /**
     * A UDP endpoint answers no TCP handshake — but a *closed* TCP port still answers, with an
     * RST, and that answer travels the same path the tunnel will. Its own port first, then the
     * ports a host usually has open. Nothing at all → -2 ("—", unknown), never ✕: a server that
     * refuses to be probed is not a server that is down.
     */
    private fun udpEndpointPing(s: ServerProfile, timeout: Int): Int {
        for (port in listOf(s.port, 443, 80, 22)) {
            val ms = rstPing(s.address, port, timeout)
            if (ms >= 0) return ms
        }
        return -2
    }

    /** Round trip to [host]:[port], counting a refused connection as the answer it is. */
    private fun rstPing(host: String, port: Int, timeout: Int): Int {
        val t0 = System.currentTimeMillis()
        return try {
            Socket().use {
                MoonVpnService.protectSocket(it)          // out of our own tunnel — see tcpPing
                it.connect(InetSocketAddress(host, port), timeout)
            }
            (System.currentTimeMillis() - t0).toInt()      // unlikely: the port is actually open
        } catch (e: java.net.ConnectException) {
            (System.currentTimeMillis() - t0).toInt()      // refused — the RST came back, that is the round trip
        } catch (_: Exception) {
            -1                                             // dropped, no answer at all
        }
    }

    private fun tcpPing(host: String, port: Int, timeout: Int = 3000): Int = try {
        val t0 = System.currentTimeMillis()
        Socket().use {
            // Out of our own tunnel, or the reading is the tunnel's: Android sends an app's
            // sockets through the VpnService it started, and a TUN stack answers the handshake
            // itself in a millisecond for live and dead servers alike. This is what the desktop
            // does by binding the probe to the physical adapter.
            MoonVpnService.protectSocket(it)
            it.connect(InetSocketAddress(host, port), timeout)
        }
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
        // Deliberately the same probe whether or not our tunnel is up. Falling back to a plain
        // handshake while connected handed a number to every server with an open port, including
        // the protocols that do not actually carry traffic — the probe is the only thing here
        // that can tell those apart. The probe instance binds no inbound, so it does not collide
        // with the running core, and pingServers already runs stability one server at a time.
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
        // The core's own measureDelay is tried first, but it is not the last word: it came back
        // empty on a tunnel that was demonstrably carrying hundreds of kilobytes, and a check that
        // says "нет ответа" over a working connection is worse than no check at all. Falling back
        // to a real request through our own SOCKS listener measures the same path the apps use.
        val ms = tunnelPing()
        _checkPing.value = if (ms < 0) s(R.string.vm_no_reply) else "$ms ms"
        // The same number goes into the row, so the pill by the button and the list cannot show
        // two different figures for one server.
        if (ms != null && ms >= 0) store.selectedServer()?.let { sel ->
            _pings.update { it + (sel.pingKey to ms.toInt()) }
        }
    }

    /**
     * A HEAD request to the test URL through the local SOCKS port — the same route every other app
     * on the phone takes while the tunnel is up. Returns the round trip in ms, or -1.
     */
    private fun socksCheck(): Long {
        val st = store.state.value
        return runCatching {
            val proxy = java.net.Proxy(
                java.net.Proxy.Type.SOCKS,
                InetSocketAddress("127.0.0.1", st.socksPort),
            )
            val t0 = System.currentTimeMillis()
            (java.net.URL(st.pingTestUrl).openConnection(proxy) as java.net.HttpURLConnection).run {
                connectTimeout = 6000; readTimeout = 6000
                requestMethod = "HEAD"
                instanceFollowRedirects = false
                responseCode
                disconnect()
            }
            System.currentTimeMillis() - t0
        }.getOrDefault(-1L)
    }

    // ---- engines ---------------------------------------------------------
    /**
     * The engine a connection is waiting on, with the protocol that asked for it, or null.
     *
     * Set instead of connecting, so the screen can ask rather than the attempt failing somewhere
     * down inside the core with a message nobody can act on.
     */
    private val _needCore = MutableStateFlow<Pair<cc.moon.internet.core.MoonCore, String>?>(null)
    val needCore = _needCore.asStateFlow()

    /** True while an engine is being put in place; the screen blocks itself meanwhile. */
    private val _installingCore = MutableStateFlow(false)
    val installingCore = _installingCore.asStateFlow()

    /** 0–100 while an engine downloads, so the blocking screen has something to say. */
    private val _coreProgress = MutableStateFlow(0)
    val coreProgress = _coreProgress.asStateFlow()

    fun cancelCoreRequest() { _needCore.value = null }

    /**
     * Fetch the engine and carry on with the connection that wanted it.
     *
     * No restart: the engine is a file in the app's own data, not a package, so there is nothing
     * for the system to swap out underneath us. Still a screen you cannot touch — it is a download
     * measured in megabytes, and starting a second one halfway through is not a thing to allow.
     */
    fun installCore(core: cc.moon.internet.core.MoonCore? = null, thenConnect: Boolean = true) =
        viewModelScope.launch {
            val want = core ?: _needCore.value?.first ?: return@launch
            _needCore.value = null
            // An engine still carried in the APK has nothing to fetch; wanting it is the whole
            // installation. Only the downloaded ones need the blocking screen.
            if (want.downloadable) {
                _installingCore.value = true
                _coreProgress.value = 0
                val why = fetchCore(want)
                _installingCore.value = false
                if (why != null) { _status.value = why; return@launch }
            }
            store.update { it.copy(installedCores = it.installedCores + want.id) }
            if (thenConnect) connect()
        }

    /** Downloads one engine, verifying it against the catalogue. Null when it is in place. */
    private suspend fun fetchCore(core: cc.moon.internet.core.MoonCore): String? {
        val app = getApplication<android.app.Application>()
        val rel = cc.moon.internet.core.CoreStore.catalogue().firstOrNull { it.id == core.id }
        // No catalogue, but the build happens to carry this engine: take what is here rather than
        // refuse over a list we only needed in order to find a copy we already have.
            ?: return if (core.present(getApplication())) null else "Не удалось получить список ядер"
        cc.moon.internet.data.DevLog.log("ядро", "ставлю ${core.title} ${rel.version}")
        val why = cc.moon.internet.core.CoreStore.install(app, rel) { _coreProgress.value = it }
        cc.moon.internet.data.DevLog.log("ядро", why ?: "${core.title} ${rel.version} на месте")
        return why
    }

    /**
     * After an app update: an engine the user has, in a version this build did not ship with, is
     * fetched again in the background.
     *
     * The engines outlive the app they were installed by — that is the point of keeping them in app
     * data — so nothing is lost on an update. What can happen is that a newer app wants a newer
     * engine, and this is where that is noticed. Silent on purpose: the user asked for the engine
     * once and does not need to be asked again.
     */
    fun refreshCores() = viewModelScope.launch {
        val app = getApplication<android.app.Application>()
        val wanted = store.state.value.installedCores
        if (wanted.isEmpty()) return@launch
        val catalogue = cc.moon.internet.core.CoreStore.catalogue()
        for (core in cc.moon.internet.core.MoonCore.optional) {
            if (!core.downloadable || core.id !in wanted) continue
            val rel = catalogue.firstOrNull { it.id == core.id } ?: continue
            val here = cc.moon.internet.core.CoreStore.have(app, core)
            if (here && cc.moon.internet.core.CoreStore.versionOf(app, core.id) == rel.version) continue
            cc.moon.internet.data.DevLog.log("ядро", if (here) "обновляю ${core.title} до ${rel.version}"
                               else "докачиваю ${core.title} ${rel.version}")
            cc.moon.internet.core.CoreStore.install(app, rel)
                ?.let { cc.moon.internet.data.DevLog.log("ядро", it) }
        }
    }

    // ---- connection ------------------------------------------------------
    fun connect() = viewModelScope.launch {
        // Запрет has no server to dial and no routing to apply: it edits what leaves the phone
        // rather than sending it somewhere. Every check below is about a server it does not have.
        if (store.state.value.connMode == "zapret") {
            connectZapret()
            return@launch
        }
        var s = store.selectedServer() ?: run { _status.value = s(R.string.vm_pick_server); return@launch }

        // Some servers need an engine this install may not have. Ask before dialling rather than
        // failing halfway with a message about a port nobody opened. Asked about the file on disk
        // rather than about the list of what was once chosen: an engine whose download never
        // finished has to be offered again, not dialled into.
        cc.moon.internet.core.MoonCore.forServer(s)
            ?.takeIf { it.id !in store.state.value.installedCores || !it.present(getApplication()) }
            ?.let { _needCore.value = it to s.protocolLabel; return@launch }

        // "The chosen server is silent -> take the fastest live one." That is what the setting
        // says, and until now it only applied when auto-connect picked a server at launch:
        // pressing Connect on a server whose ping had just come back dead dialled it anyway and
        // sat there failing. Only a measured failure counts — an unmeasured server is not a dead
        // one, and we do not overrule a choice we know nothing about.
        // Dead means either: it answered nothing when measured (-1), or the core cannot speak its
        // protocol at all, so it was never going to carry traffic. The first version tested only
        // for -1 and missed the case that prompted this — a protocol that does not ping is scored
        // -2, "unknown", and slipped through as if it were fine.
        // A server nobody measured is not a dead one, so "-1" already implies a measurement; an
        // unsupported protocol needs none, we would refuse to dial it anyway.
        val dead = !XrayConfig.supports(s.protocol) || _pings.value[s.pingKey] == -1
        if (store.state.value.autoFailover && dead) {
            val alt = store.allServers
                .filter { it.key != s.key && XrayConfig.supports(it.protocol) && (_pings.value[it.pingKey] ?: -2) >= 0 }
                .minByOrNull { _pings.value[it.pingKey] ?: Int.MAX_VALUE }
            if (alt != null) {
                store.selectServer(alt, "выбранный молчит, БЕТА-переключение")
                _status.value = s(R.string.vm_failover, alt.label)
                s = alt
            }
        }

        if (!XrayConfig.supports(s.protocol)) {
            _status.value = s(R.string.vm_unsupported, s.protocolLabel)
            return@launch
        }
        val st = store.state.value

        // Another VPN client on the phone probably owns 10808/10809 already — see freeLocalPort.
        // proxy mode also shares the listeners on the LAN — that is what makes it a proxy
        val listen = if (st.allowLan || !st.tunMode) "0.0.0.0" else "127.0.0.1"
        // ...but when WE are the ones already listening — every switch to another server — the
        // probe finds its own core, moves both ports and announces "занят другим VPN" about
        // itself. The service tears the old core down before binding the new one (see
        // startTunnel → stopCore), so our own ports are free by the time they are needed.
        val holdingOurOwn = MoonVpnService.state.value != MoonVpnService.Companion.State.Disconnected
        val socks = if (holdingOurOwn) st.socksPort else freeLocalPort(st.socksPort, listen)
        val http = if (holdingOurOwn) st.httpPort else freeLocalPort(st.httpPort, listen, avoid = setOf(socks))
        if (socks != st.socksPort || http != st.httpPort) {
            _status.value = s(R.string.vm_port_taken, socks, http)
        }

        // Look the server up before the tunnel exists, so its own addresses can be pinned to the
        // direct rule. Without them, anything else on that machine — its panel, its console —
        // resolved to an IP no rule mentioned and went out through the tunnel back to itself.
        // Off the main thread: a lookup there is a NetworkOnMainThreadException.
        val serverIps = withContext(Dispatchers.IO) {
            runCatching {
                java.net.InetAddress.getAllByName(s.address).map { it.hostAddress }.filterNotNull()
            }.getOrDefault(emptyList())
        }

        var config = runCatching {
            XrayConfig.build(
                server = s,
                serverIps = serverIps,
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
        }.getOrElse { _status.value = s(R.string.vm_config_error, it.message); return@launch }

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
            // AmneziaWG needs its own engine up before the core; the service starts it.
            awgConfig = if (s.wg?.isAmnezia == true) s.raw else null,
            // Hysteria2 likewise: xray's own implementation does not survive РФ filtering, so
            // sing-box carries it and the core only dials into it.
            hy2Link = if (s.protocol == cc.moon.internet.core.Protocol.HYSTERIA2) s.raw else null,
        )
    }

    /**
     * Запрет: byedpi listens on loopback, and the tunnel exists only to feed it. No server, no
     * subscription, no geo files — which is why this is four lines and connect() is fifty.
     */
    private fun connectZapret() {
        val st = store.state.value
        val strategy = st.zapretStrategy
        val config = XrayConfig.buildZapret(
            mtu = 1500,
            logLevel = if (st.logsEnabled) st.logLevel else "none",
            logFile = if (st.logsEnabled) cc.moon.internet.data.LogStore.file(getApplication()).absolutePath else null,
        )
        MoonVpnService.start(
            getApplication(), config, s(R.string.zapret_notification, strategy),
            tun = true, perAppMode = st.perAppMode, perApps = st.perApps,
            zapretStrategy = strategy,
        )
    }

    private val _zapretPing = kotlinx.coroutines.flow.MutableStateFlow("—")
    val zapretPing: kotlinx.coroutines.flow.StateFlow<String> = _zapretPing
    private val _zapretBusy = kotlinx.coroutines.flow.MutableStateFlow(false)
    val zapretBusy: kotlinx.coroutines.flow.StateFlow<Boolean> = _zapretBusy

    /** Opens a blocked page through byedpi and times it. See ByedpiRunner.check for why. */
    fun checkZapret() = viewModelScope.launch {
        if (_zapretBusy.value) return@launch
        _zapretBusy.value = true
        _zapretPing.value = s(R.string.zapret_checking)
        val ms = withContext(Dispatchers.IO) { cc.moon.internet.vpn.ByedpiRunner(getApplication()).check(store.state.value.zapretCheckUrl) }
        _zapretPing.value = if (ms < 0) s(R.string.zapret_no_answer) else "$ms ms"
        _zapretBusy.value = false
    }

    /**
     * Walks the list until a page opens. Which strategy works depends on the provider, and by hand
     * that is twenty-one reconnects — the thing that makes the mode feel broken rather than fiddly.
     */
    fun autoPickZapret() = viewModelScope.launch {
        if (_zapretBusy.value) return@launch
        _zapretBusy.value = true
        try {
            val all = cc.moon.internet.core.ZapretStrategies.all
            all.forEachIndexed { i, st ->
                _zapretPing.value = s(R.string.zapret_trying, i + 1, all.size, st.id)
                store.update { it.copy(zapretStrategy = st.id) }
                connectZapret()
                kotlinx.coroutines.delay(1500)
                val ms = withContext(Dispatchers.IO) { cc.moon.internet.vpn.ByedpiRunner(getApplication()).check(store.state.value.zapretCheckUrl) }
                if (ms >= 0) { _zapretPing.value = s(R.string.zapret_found, st.id, ms); return@launch }
            }
            _zapretPing.value = s(R.string.zapret_none_worked)
        } finally { _zapretBusy.value = false }
    }

    fun setZapretCheckUrl(u: String) = viewModelScope.launch { store.update { it.copy(zapretCheckUrl = u) } }

    /** True while a mode switch is tearing the old one down — the moon keeps spinning through it. */
    private val _switchingMode = MutableStateFlow(false)
    val switchingMode = _switchingMode.asStateFlow()

    fun setConnMode(m: String) = viewModelScope.launch {
        if (m !in setOf("tun", "zapret") || store.state.value.connMode == m) return@launch
        // A tunnel and запрет both want the traffic; leaving one up would have them fight.
        if (vpnState.value != MoonVpnService.Companion.State.Disconnected) {
            _switchingMode.value = true
            _status.value = s(R.string.vm_mode_switching)
            disconnect()
            // Flip the page only once the tunnel is really down. Changing it mid-teardown is what
            // made the switch jarring: the subscriptions vanished while the moon was still lit and
            // the whole screen rebuilt under the user's thumb.
            kotlinx.coroutines.withTimeoutOrNull(4000) {
                vpnState.first { it == MoonVpnService.Companion.State.Disconnected }
            }
            _switchingMode.value = false
        }
        store.update { it.copy(connMode = m, tunMode = m == "tun") }
    }

    fun pickZapret(id: String) = viewModelScope.launch {
        store.update { it.copy(zapretStrategy = id) }
        if (vpnState.value == MoonVpnService.Companion.State.Connected) connectZapret()
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
            // the service owns the clock; keeping a second one here is what made the
            // notification and the screen disagree
            val start = MoonVpnService.connectedAt
            val secs = if (start <= 0L) 0L else (System.currentTimeMillis() - start) / 1000
            _elapsed.value = String.format("%02d:%02d:%02d", secs / 3600, (secs % 3600) / 60, secs % 60)
        }
    }

    // ---- routing ---------------------------------------------------------
    private val _geoBusy = MutableStateFlow(false)
    val geoBusy = _geoBusy.asStateFlow()

    private val _geoStatus = MutableStateFlow("")
    val geoStatus = _geoStatus.asStateFlow()

    /** Which of a subscription's pair "Авто" reaches for; reconnects if it is what we are on. */
    fun setAutoRoutingPref(source: String) = viewModelScope.launch {
        store.update { it.copy(autoRoutingPref = source) }
        if (store.state.value.selectedRoutingId.isBlank()) reconnectIfConnected()
    }

    /** What "Авто" would use right now — the routing of the current server's subscription. */
    fun autoRouting(): RoutingProfile? = store.autoRoutingPublic()

    /** Name of the subscription that profile came from, for the line under "Авто". */
    fun autoSubName(): String {
        val p = autoRouting() ?: return ""
        return store.state.value.subscriptions.firstOrNull { it.url == p.subUrl }?.name.orEmpty()
    }

    fun activeRouting(): RoutingProfile? = store.activeRouting()

    /** Categories present in the downloaded geo files, for the rule picker. Off the main thread. */
    suspend fun geoTags(): List<String> = withContext(Dispatchers.IO) {
        val ctx = getApplication<android.app.Application>()
        GeoService.tags(GeoService.geosite(ctx), "geosite") + GeoService.tags(GeoService.geoip(ctx), "geoip")
    }

    fun geoipInfo() = GeoService.info(getApplication(), GeoService.geoip(getApplication()))
    fun geositeInfo() = GeoService.info(getApplication(), GeoService.geosite(getApplication()))

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
        val active = store.activeRouting()?.id
        return groups.map { (urls, profiles) ->
            // Only the selected profile's lists are the ones actually on disk; for the other set we
            // show where it would come from rather than a size we never fetched.
            val onDisk = !split || profiles.any { it.id == active }
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

    fun selectRouting(id: String) = viewModelScope.launch {
        store.selectRouting(id)
        reconnectIfConnected()
    }

    /** Save from the editor: an existing id updates in place, a blank one appends. */
    fun saveRouting(profile: RoutingProfile) = viewModelScope.launch {
        store.putRouting(profile)
        reconnectIfConnected()
    }

    fun deleteRouting(id: String) = viewModelScope.launch {
        store.deleteRouting(id)
        reconnectIfConnected()
    }

    fun duplicateRouting(id: String) = viewModelScope.launch {
        store.duplicateRouting(id, s(R.string.routing_copy_suffix))
    }

    /** Puts the profile on the clipboard as an incy://routing/add/… link. */
    fun exportRouting(id: String) = viewModelScope.launch {
        val p = store.state.value.routings.firstOrNull { it.id == id } ?: return@launch
        val link = RoutingParser.toLink(p)
        val cm = getApplication<android.app.Application>()
            .getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("routing", link))
        _status.value = s(R.string.routing_exported)
    }

    /** A blank profile for the "+" button. */
    fun blankRouting() = RoutingProfile(name = s(R.string.routing_new_name), source = "custom",
                                        domainStrategy = "AsIs",
                                        geoipUrl = GEOIP_URL_DEFAULT, geositeUrl = GEOSITE_URL_DEFAULT)

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
        _logsSize.value = cc.moon.internet.data.LogStore.size(getApplication())
        _status.value = s(R.string.vm_logs_cleared)
    }

    /**
     * Size of the core log for the settings row. A flow, not a plain call: read once when the page
     * opened, the row went on showing the old size after Clear and only told the truth if you left
     * the page and came back. The desktop assigns its own LogsSizeInfo there, so it never did this.
     */
    private val _logsSize = MutableStateFlow("")
    val logsSize = _logsSize.asStateFlow()
    fun refreshLogsSize() { _logsSize.value = cc.moon.internet.data.LogStore.size(getApplication()) }

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

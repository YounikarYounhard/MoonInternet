package cc.moon.internet.vpn

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import cc.moon.internet.MainActivity
import cc.moon.internet.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The tunnel. Android gives us a TUN file descriptor through [VpnService]; xray-core takes that
 * fd through its own `tun` inbound and does everything else — protocols, routing, DNS.
 *
 * Unlike the Windows build there is no privileged helper here: the OS grants the VPN permission
 * once, and the service runs in our own process.
 */
class MoonVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "cc.moon.internet.CONNECT"
        const val ACTION_DISCONNECT = "cc.moon.internet.DISCONNECT"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_PROFILE_NAME = "profile"
        const val EXTRA_MTU = "mtu"
        const val EXTRA_APP_MODE = "appMode"
        const val EXTRA_APPS = "apps"
        const val EXTRA_TUN = "tun"
        /** Strategy id when this is the запрет mode; absent means an ordinary tunnel. */
        const val EXTRA_ZAPRET = "zapret"
        /** The AmneziaWG config text, when the chosen server is one. */
        const val EXTRA_AWG = "awg"
        /** The Hysteria2 share link, when the chosen server is one — sing-box carries those. */
        const val EXTRA_HY2 = "hy2"
        const val ACTION_RECONNECT = "cc.moon.internet.RECONNECT"
        const val ACTION_PING = "cc.moon.internet.PING"
        const val ACTION_PAUSE = "cc.moon.internet.PAUSE"

        /** Everything the last connect was made with, so the tile and the notification can redo it. */
        @Volatile private var lastConfig: String? = null
        @Volatile private var lastProfile: String = ""
        @Volatile private var lastMtu: Int = 1500
        @Volatile private var lastTun: Boolean = true
        @Volatile private var lastZapret: String? = null
        @Volatile private var lastAwg: String? = null
        @Volatile private var lastAppMode: String = "off"
        @Volatile private var lastApps: List<String> = emptyList()

        /** Latency through the tunnel, refreshed on connect and by the notification button. */
        val livePing = MutableStateFlow<String?>(null)

        /** When the current tunnel came up, so the UI and the notification agree. */
        @Volatile var connectedAt = 0L

        private const val CHANNEL_ID = "moon_vpn"
        private const val CHANNEL_ID_HEADSUP = "moon_vpn_headsup"
        private const val NOTIFICATION_ID = 1

        /**
         * Paused is a real state, not "disconnected with a notification": the tunnel is down but
         * the service stays alive holding the profile, so Resume brings back exactly the server
         * that was running.
         */
        enum class State { Disconnected, Connecting, Connected, Paused }

        private val _state = MutableStateFlow(State.Disconnected)
        val state = _state.asStateFlow()

        /**
         * True once a measurement through the tunnel came back with nothing. "Connected" only means
         * the interface is up; a server that has stopped carrying traffic looks exactly the same
         * from here, and the notification went on cheerfully reporting 0 B/s as though that were a
         * speed. False until something actually fails, so it never accuses a fresh connection.
         */
        val linkDead = MutableStateFlow(false)
        /** The phone itself is offline — not the tunnel's fault, and the shade says which. */
        val noNetwork = MutableStateFlow(false)
        /** AmneziaWG on a TUN: unix second of the engine's last handshake, 0 before the first. */
        val awgHandshake = MutableStateFlow(0L)

        private val _activeProfile = MutableStateFlow<String?>(null)
        val activeProfile = _activeProfile.asStateFlow()

        /** Cumulative counters read from the core (bytes). */
        val traffic = MutableStateFlow(0L to 0L)   // up to down

        /** Why the last connect attempt died. Null while things are fine. */
        val lastError = MutableStateFlow<String?>(null)

        /** Set while the tunnel is up, so the UI can ask the core for a real latency figure. */
        @Volatile var runner: XrayRunner? = null
            private set

        /**
         * Live service, kept only so a latency probe can be excluded from our own tunnel.
         *
         * Android routes an app's own sockets through the VpnService it started, so a probe made
         * while connected measures the tunnel rather than the server — a TUN stack completes the
         * handshake locally, in a millisecond or two, for live and dead servers alike. The desktop
         * build binds its probes to the physical adapter for exactly this reason; protect() is the
         * Android equivalent.
         */
        @Volatile internal var current: MoonVpnService? = null
            private set

        /** Keeps a socket out of the tunnel. False when no tunnel is up, which needs no help. */
        fun protectSocket(socket: java.net.Socket): Boolean =
            current?.protect(socket) ?: false

        fun start(
            ctx: Context, config: String, profileName: String, mtu: Int = 1500,
            tun: Boolean = true, perAppMode: String = "off", perApps: List<String> = emptyList(),
            zapretStrategy: String? = null,
            awgConfig: String? = null,
            hy2Link: String? = null,
        ) {
            androidx.core.content.ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, MoonVpnService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_CONFIG, config)
                putExtra(EXTRA_PROFILE_NAME, profileName)
                putExtra(EXTRA_MTU, mtu)
                putExtra(EXTRA_APP_MODE, perAppMode)
                putExtra(EXTRA_APPS, perApps.toTypedArray())
                putExtra(EXTRA_TUN, tun)
                putExtra(EXTRA_ZAPRET, zapretStrategy)
                putExtra(EXTRA_AWG, awgConfig)
                putExtra(EXTRA_HY2, hy2Link)
            },
            )
        }

        fun stop(ctx: Context) {
            // Starting a service can throw if the app is already in the background; the tunnel is
            // going away either way, so a failure here must not reach the user as an error.
            runCatching {
                androidx.core.content.ContextCompat.startForegroundService(
                    ctx, Intent(ctx, MoonVpnService::class.java).apply { action = ACTION_DISCONNECT })
            }
        }
    }

    private var tunFd: ParcelFileDescriptor? = null
    // Tells our own teardown apart from the core falling over. Both arrive as the same shutdown
    // callback, and without the distinction auto-reconnect would fight every ordinary disconnect.
    /** The in-flight connect, so a newer one can cancel it — see startTunnel. */
    private var connectJob: kotlinx.coroutines.Job? = null
    @Volatile private var intentionalStop = false
    @Volatile private var reconnecting = false
    private val byedpi by lazy { ByedpiRunner(this) }
    // The engine's process ending on its own is a dropped core like any other — see onCoreDropped.
    private val awgRunner by lazy { AwgRunner(this) { onCoreDropped() } }
    /** AmneziaWG is carrying the TUN itself: no xray, so ping and counters come from elsewhere. */
    @Volatile private var awgTun: cc.moon.internet.core.WireGuardConfig? = null
    @Volatile private var lastRxAt = 0L
    private var lastRx = -1L
    private val singBox by lazy { SingBoxRunner(this) }
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private var currentNetwork: android.net.Network? = null
    // see the companion: one clock, shared with the UI
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        current = this
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                runCatching { startForeground(NOTIFICATION_ID, notification(lastProfile, connecting = false)) }
                stopTunnel()
                return START_NOT_STICKY
            }

            ACTION_PAUSE -> {
                pauseTunnel()
                return START_STICKY
            }

            ACTION_RECONNECT -> {
                val cfg = lastConfig
                if (cfg.isNullOrBlank()) { stopSelf(); return START_NOT_STICKY }
                // a paused tunnel closed its fd; startTunnel builds a fresh one
                startTunnel(cfg, lastProfile, lastMtu, lastAppMode, lastApps, lastTun, lastZapret, lastAwg)
            }

            ACTION_PING -> {
                scope.launch {
                    val ms = judgeLink()
                    livePing.value = ms?.let { "$it ms" } ?: getString(R.string.vm_no_reply).takeIf { runner != null }
                    runCatching { updateNotification(_activeProfile.value.orEmpty(), false) }
                }
            }
            ACTION_CONNECT -> {
                val config = intent.getStringExtra(EXTRA_CONFIG).orEmpty()
                val name = intent.getStringExtra(EXTRA_PROFILE_NAME).orEmpty()
                if (config.isBlank()) { stopTunnel(); return START_NOT_STICKY }
                startTunnel(
                    config, name, intent.getIntExtra(EXTRA_MTU, 1500),
                    intent.getStringExtra(EXTRA_APP_MODE) ?: "off",
                    intent.getStringArrayExtra(EXTRA_APPS)?.toList().orEmpty(),
                    intent.getBooleanExtra(EXTRA_TUN, true),
                    intent.getStringExtra(EXTRA_ZAPRET),
                    intent.getStringExtra(EXTRA_AWG),
                    intent.getStringExtra(EXTRA_HY2),
                )
            }
        }
        return START_STICKY
    }

    private fun startTunnel(
        config: String, profileName: String, mtu: Int, appMode: String, apps: List<String>,
        tun: Boolean = true, zapret: String? = null, awg: String? = null, hy2: String? = null,
    ) {
        cc.moon.internet.data.DevLog.log("туннель", "поднимаю: $profileName")
        _state.value = State.Connecting
        _activeProfile.value = profileName
        lastError.value = null
        linkDead.value = false
        noNetwork.value = false
        awgHandshake.value = 0
        lastConfig = config; lastProfile = profileName; lastMtu = mtu
        lastTun = tun; lastAppMode = appMode; lastApps = apps; lastZapret = zapret; lastAwg = awg
        startForeground(NOTIFICATION_ID, notification(profileName, connecting = true))

        // One connect at a time. Tapping through the server list hunting for a working one sends
        // a CONNECT per tap, and each used to launch its own coroutine: two of them would be
        // establishing interfaces and starting cores over each other, which is what filled a log
        // with "Xray started" every couple of seconds. The newest tap wins.
        connectJob?.cancel()
        connectJob = scope.launch {
            try {
                // Tear down anything still up — a stale fd would silently blackhole traffic,
                // the same class of bug that bit the Windows helper.
                stopCore()

                // Android hands the tunnel to one app at a time. If another VPN took the slot
                // while we were idle we are no longer the prepared package, and establish()
                // would just return null — the mystery "не удалось подключиться". Only the
                // system dialog can take the slot back, and confirming it is also what stops
                // the other VPN, which is exactly how every other client does this.
                if (tun && VpnService.prepare(this@MoonVpnService) != null) {
                    askForVpnSlot()
                    return@launch
                }

                val wg = awg?.let { cc.moon.internet.core.WireGuardParser.parse(it) }
                if (awg != null && wg == null) {
                    lastError.value = "Конфигурация AmneziaWG не читается"
                    _state.value = State.Disconnected; stopTunnel(); return@launch
                }

                // Proxy mode runs the core with no TUN: only the local SOCKS/HTTP listeners, and
                // no VPN interface — the same shape INCY's ProxyOnlyService has, which is an
                // ordinary foreground service rather than a VpnService.
                val fd = if (tun) {
                    establish(profileName, mtu, appMode, apps, wg)
                        ?: run { askForVpnSlot(); return@launch }
                } else null
                tunFd = fd

                if (zapret != null) {
                    byedpi.start(zapret)?.let { why ->
                        lastError.value = why
                        _state.value = State.Disconnected
                        stopTunnel()
                        return@launch
                    }
                }

                // AmneziaWG. With a TUN the engine carries the phone's packets itself and xray is
                // not started at all — the way the Amnezia client works, and the only way to its
                // speed: xray's tun inbound plus a second TCP stack behind SOCKS cost most of it.
                // Without one (proxy mode) awgproxy is a SOCKS listener that xray's outbound
                // dials, so it has to be listening before the core starts — same order as byedpi.
                if (wg != null) {
                    awgRunner.start(wg, fd)?.let { why ->
                        lastError.value = why
                        _state.value = State.Disconnected; stopTunnel(); return@launch
                    }
                    watchNetwork()
                }

                // Hysteria2: sing-box carries it and xray's proxy outbound is a SOCKS dial into it,
                // so it has to be listening before the core starts — same order as the two above.
                if (hy2 != null) {
                    val profile = cc.moon.internet.core.ShareLinkParser.parse(hy2)
                    if (profile == null) {
                        lastError.value = "Ссылка Hysteria2 не читается"
                        _state.value = State.Disconnected; stopTunnel(); return@launch
                    }
                    singBox.start(profile)?.let { why ->
                        lastError.value = why
                        _state.value = State.Disconnected; stopTunnel(); return@launch
                    }
                }

                intentionalStop = false
                awgTun = wg?.takeIf { fd != null }
                lastRx = -1L; lastRxAt = System.currentTimeMillis()
                if (awgTun == null) {
                    runner = XrayRunner(this@MoonVpnService) { onCoreDropped() }
                        .also { it.start(config, fd?.fd ?: 0) }
                }

                connectedAt = System.currentTimeMillis()
                _state.value = State.Connected
                // One measurement straight away: the ping line used to stay empty until the
                // notification's own button was pressed.
                scope.launch {
                    runCatching {
                        val ms = if (zapret == null && runner != null) judgeLink() else measureDelay()
                        livePing.value = ms?.let { "$it ms" }
                        updateNotification(profileName, connecting = false)
                    }
                }
                updateNotification(profileName, connecting = false)
                pollTraffic()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // The tunnel coroutine parks in pollTraffic(); stopping the service cancels it.
                // That is an ordinary shutdown, not a failed connection — rethrow, never report.
                throw e
            } catch (e: Throwable) {
                android.util.Log.e("MoonVpn", "connect failed", e)
                android.util.Log.e("MoonVpn", "config was:\n$config")
                lastError.value = "Не удалось подключиться: ${e.message ?: e.javaClass.simpleName}"
                stopTunnel()
            }
        }
    }

    /**
     * Hands the connect back to the activity so it can show the system VPN dialog with a result
     * callback. Confirming it revokes whichever VPN currently holds the slot and reconnects us;
     * a service cannot do that itself, it has no way to hear the answer.
     */
    private fun askForVpnSlot() {
        lastError.value = "Занято другим VPN — подтвердите замену"
        runCatching {
            startActivity(
                Intent(this, cc.moon.internet.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(cc.moon.internet.MainActivity.EXTRA_CONNECT_NOW, true)
            )
        }
        stopTunnel()
    }

    /**
     * Builds the TUN interface. Routes everything; xray decides what actually goes out.
     *
     * Excluding our own package is what keeps the core's dials to the proxy server outside the
     * tunnel — without it every connection loops back into itself and dies.
     */
    private fun establish(
        profileName: String, mtu: Int, appMode: String, apps: List<String>,
        wg: cc.moon.internet.core.WireGuardConfig? = null,
    ): ParcelFileDescriptor? {
        val b = Builder().setSession(profileName.ifBlank { "Moon Internet" })
        if (wg == null) {
            b.setMtu(mtu)
                .addAddress("172.19.0.1", 30)
                .addDnsServer("1.1.1.1")
                .addRoute("0.0.0.0", 0)
        } else {
            // AmneziaWG carries this interface itself, so it is built from the config, as the
            // Amnezia client builds it: its addresses, its DNS, its AllowedIPs as the routes.
            // MTU capped at 1280 for the reason in build/awgproxy/main.go — a bigger one comes
            // up, handshakes, and then drops every full-sized packet on mobile data.
            b.setMtu(if (wg.mtu in 576..1280) wg.mtu else 1280)
            wg.address.forEach { a -> prefix(a)?.let { (ip, len) -> b.addAddress(ip, len) } }
            wg.dns.ifEmpty { listOf("1.1.1.1") }.forEach { runCatching { b.addDnsServer(it) } }
            wg.allowedIps.ifEmpty { listOf("0.0.0.0/0") }
                .forEach { r -> prefix(r)?.let { (ip, len) -> runCatching { b.addRoute(ip, len) } } }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) b.setMetered(false)

        // Split tunnelling is per package on Android. Our own package is always excluded so the
        // core's dials to the proxy stay outside the tunnel instead of looping into it.
        when {
            appMode == "only" && apps.isNotEmpty() ->
                apps.filter { it != packageName }.forEach { runCatching { b.addAllowedApplication(it) } }
            appMode == "bypass" && apps.isNotEmpty() ->
                (apps + packageName).distinct().forEach { runCatching { b.addDisallowedApplication(it) } }
            else -> runCatching { b.addDisallowedApplication(packageName) }
        }
        return b.establish()
    }

    /** "10.8.1.2/32" → ("10.8.1.2", 32); a bare address is a host route. Null if unreadable. */
    private fun prefix(cidr: String): Pair<String, Int>? {
        val ip = cidr.substringBefore('/').trim()
        if (ip.isEmpty()) return null
        val len = cidr.substringAfter('/', "").trim().toIntOrNull() ?: if (':' in ip) 128 else 32
        return ip to len
    }

    /**
     * Round trip for the notification, in ms, or null when nothing answered.
     *
     * Through xray when it runs. AmneziaWG on a TUN has no xray to ask, and our own sockets are
     * kept out of the tunnel, so the figure is the server's own round trip — a closed TCP port
     * answering with a reset, the same probe the server list uses for UDP protocols.
     */
    private fun measureDelay(): Long? {
        runner?.let { r -> return r.measureDelay()?.takeIf { it >= 0 } }
        val wg = awgTun ?: return null
        val t0 = System.currentTimeMillis()
        return try {
            java.net.Socket().use { it.connect(java.net.InetSocketAddress(wg.endpointHost, wg.endpointPort), 3000) }
            System.currentTimeMillis() - t0
        } catch (_: java.net.ConnectException) {
            System.currentTimeMillis() - t0
        } catch (_: Exception) {
            null
        }
    }

    /**
     * AmneziaWG on a TUN: (up, down) from the engine, and whether the link has gone dead. Dead is
     * no handshake for three minutes — with keepalive on, a live tunnel renews one every two —
     * and nothing received for the last 15 seconds either, so a quiet tunnel without keepalive is
     * not accused for being quiet. A brand-new tunnel gets 15 seconds for its first handshake.
     */
    private fun awgTotals(): Pair<Long, Long>? {
        val (rx, tx, handshake) = awgRunner.counters() ?: return null
        awgHandshake.value = handshake
        val now = System.currentTimeMillis()
        if (rx != lastRx) { lastRx = rx; lastRxAt = now }
        val since = if (handshake > 0) handshake * 1000 else connectedAt - 165_000
        val dead = now - since > 180_000 && now - lastRxAt > 15_000
        val offline = dead && !underlyingOnline()
        noNetwork.value = offline
        linkDead.value = dead && !offline
        return tx to rx
    }

    /**
     * The verdict behind the shade's warning, and the only place it is reached. The tunnel's own
     * ping, and once more two seconds later before calling it dead — and never while bytes are
     * coming in: a tunnel delivering traffic is working whatever one probe said. A single failed
     * 204 used to be enough, so the shade said «Подключение не работает» over a link that the
     * Пинг button then found perfectly fine. When it really is dead, whose fault: the phone with
     * no internet at all says so instead of telling you to change servers.
     *
     * @return the round trip in ms, or null.
     */
    private suspend fun judgeLink(): Long? {
        val ms = measureDelay() ?: run { delay(2000); measureDelay() }
        if (ms != null || System.currentTimeMillis() - lastRxAt < 20_000) {
            linkDead.value = false; noNetwork.value = false
            return ms
        }
        val offline = !underlyingOnline()
        noNetwork.value = offline
        linkDead.value = !offline
        return null
    }

    /** A real network under the VPN that Android itself has checked reaches the internet. */
    private fun underlyingOnline(): Boolean = runCatching {
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        cm.allNetworks.any { n ->
            cm.getNetworkCapabilities(n)?.let { c ->
                c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                    c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } == true
        }
    }.getOrDefault(true)   // cannot tell → do not blame the network

    private suspend fun pollTraffic() {
        var lastUp = 0L
        var lastDown = 0L
        var tick = 0
        while (_state.value == State.Connected) {
            // One 204 through the tunnel a minute. Cheap, and the only thing that can tell a live
            // connection from an interface that is merely up. In its own coroutine: measureDelay
            // blocks for as long as its timeout, and on this thread that froze the speed line in
            // the notification for a second or two every minute.
            // Not in Zapret: everything there goes through byedpi, and whether one probe URL
            // opens depends on the strategy rather than on the connection being up. That page has
            // its own «Проверить», aimed at a site that is actually blocked.
            // Skipped while traffic is arriving: that already proves the link, and a probe then is
            // a handshake to the server for nothing.
            if (tick % 60 == 30 && lastZapret == null && runner != null &&
                System.currentTimeMillis() - lastRxAt > 20_000) scope.launch {
                runCatching { judgeLink() }
            }
            (runner?.trafficTotals() ?: awgTun?.let { awgTotals() })?.let { (up, down) ->
                traffic.value = up to down
                if (runner != null && down != lastDown) {
                    lastRxAt = System.currentTimeMillis()
                    if (linkDead.value || noNetwork.value) { linkDead.value = false; noNetwork.value = false }
                }
                // refresh the notification every other second with the current speed
                if (tick++ % 2 == 0) {
                    val dUp = ((up - lastUp) / 2).coerceAtLeast(0)
                    val dDown = ((down - lastDown) / 2).coerceAtLeast(0)
                    lastUp = up; lastDown = down
                    runCatching {
                        updateNotification(
                            _activeProfile.value.orEmpty(),
                            connecting = false,
                            detail = "↑ ${speedText(dUp)}  ↓ ${speedText(dDown)}",
                        )
                    }
                }
            }
            delay(1000)
        }
    }

    /** Same formatting the UI uses, kept local so the service has no dependency on the VM. */
    // ditto: shared formatter, so bit/s follows the language like everything else
    private fun speedText(bytesPerSec: Long) = cc.moon.internet.data.SubscriptionService.speed(bytesPerSec)

    /**
     * Rebuild the AmneziaWG tunnel when the phone changes network.
     *
     * Every other protocol survives Wi-Fi turning into mobile because xray simply dials again over
     * whatever is there now. AmneziaWG does not: its UDP socket was opened on the network that has
     * just gone, and Android does not carry sockets across — it fails them. Nothing inside the
     * engine watches for that, so the tunnel sat there looking connected while carrying nothing,
     * and the only cure was reconnecting by hand.
     *
     * The cure is the one that already works when done by hand: take the tunnel down and put it
     * back up. Restarting only the proxy underneath a running core was the clever version, and it
     * was worse — so this goes through onCoreDropped, the same path a dead core takes, retries and
     * the auto-reconnect setting included.
     *
     * Two things it must NOT do, both learned the hard way when the first version made connecting
     * less reliable than having no fix at all:
     *
     *  - It must not watch the DEFAULT network. Bringing a VPN up makes the VPN the default, so
     *    every single connection reported "the network changed" and tore down what had just been
     *    built. Hence NOT_VPN: only the real networks underneath.
     *  - It must not act when a network merely APPEARS. Android keeps the old one alive while it
     *    can, so the tunnel is still fine and a reconnect would be a break we caused ourselves.
     *    Losing the one we were using is the only event that actually kills it.
     */
    private fun watchNetwork() {
        stopWatchingNetwork()
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return
        val request = android.net.NetworkRequest.Builder()
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                // Remember the first real network we see; a second one appearing beside it changes
                // nothing for a socket that is already open.
                if (currentNetwork == null) currentNetwork = network
            }

            override fun onLost(network: android.net.Network) {
                if (network != currentNetwork) return
                currentNetwork = null
                scope.launch {
                    // Let the replacement settle: right after a loss there is often nothing to
                    // bind to yet, and reconnecting into nothing only burns an attempt.
                    kotlinx.coroutines.delay(1500)
                    if (_state.value != State.Connected || lastAwg == null) return@launch
                    currentNetwork = cm.activeNetwork?.takeIf { net ->
                        cm.getNetworkCapabilities(net)
                            ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true
                    }
                    android.util.Log.i("MoonVpn", "awg roam: network changed, reconnecting")
                    onCoreDropped()
                }
            }
        }
        runCatching { cm.registerNetworkCallback(request, callback) }
            .onSuccess { networkCallback = callback }
    }

    private fun stopWatchingNetwork() {
        networkCallback?.let { cb ->
            runCatching {
                getSystemService(android.net.ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
            }
        }
        networkCallback = null
        currentNetwork = null
    }

    private fun stopCore() {
        intentionalStop = true
        stopWatchingNetwork()
        runCatching { byedpi.stop() }
        runCatching { awgRunner.stop() }
        runCatching { singBox.stop() }
        runCatching { runner?.stop() }
        runner = null
        awgTun = null
        runCatching { tunFd?.close() }
        tunFd = null
    }

    /**
     * The core stopped without being asked to — the server went away, the phone changed network,
     * the radio dropped. «Автоподключение при обрыве» and its delay were settings the user could
     * turn on and watch do nothing: no code read either of them, and a dropped tunnel just stayed
     * dropped with the notification still claiming otherwise.
     *
     * Five attempts, then give up for real. Retrying forever on a server that is simply gone keeps
     * the radio awake all night for nothing.
     */
    private fun onCoreDropped() {
        // Our own teardown already owns the state. Stamping Disconnected here first meant every
        // deliberate reconnect flickered through it: startTunnel sets Connecting, then stops the
        // old core, whose shutdown callback lands right here and undoes that.
        if (intentionalStop || reconnecting) return
        _state.value = State.Disconnected
        val cfg = lastConfig
        if (cfg.isNullOrBlank()) return
        reconnecting = true
        scope.launch {
            try {
                val st = cc.moon.internet.data.Store(applicationContext).also { it.load() }.state.value
                if (!st.autoReconnect) { stopTunnel(); return@launch }
                repeat(5) {
                    kotlinx.coroutines.delay(st.reconnectDelaySec.coerceIn(1, 60) * 1000L)
                    if (intentionalStop) return@launch
                    startTunnel(cfg, lastProfile, lastMtu, lastAppMode, lastApps, lastTun, lastZapret, lastAwg)
                    // startTunnel is itself async; give it room to land before judging it
                    kotlinx.coroutines.delay(6000)
                    if (_state.value == State.Connected) return@launch
                }
                lastError.value = getString(R.string.vm_reconnect_gave_up)
                stopTunnel()
            } finally { reconnecting = false }
        }
    }

    /** Drops the tunnel, keeps the service and the last profile so Resume can restore it. */
    private fun pauseTunnel() {
        scope.launch {
            stopCore()
            _state.value = State.Paused
            traffic.value = 0L to 0L
            livePing.value = null
            connectedAt = 0
            withContext(Dispatchers.Main) {
                runCatching { updateNotification(lastProfile, connecting = false) }
            }
        }
    }

    private fun stopTunnel() {
        scope.launch {
            stopCore()
            cc.moon.internet.data.DevLog.log("туннель", "опущен")
            _state.value = State.Disconnected
            _activeProfile.value = null
            traffic.value = 0L to 0L
            livePing.value = null
            connectedAt = 0
            withContext(Dispatchers.Main) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onRevoke() { stopTunnel() }          // user disabled the VPN from system settings
    override fun onDestroy() { current = null; stopCore(); scope.cancel(); super.onDestroy() }

    // ---- notification ----------------------------------------------------
    /**
     * Two channels, not one: a channel's importance is fixed at creation and Android ignores any
     * later change, so "pop up over the screen" has to be a different channel rather than a flag.
     */
    private fun channelId() =
        if (cc.moon.internet.data.Lang.headsUp(this)) CHANNEL_ID_HEADSUP else CHANNEL_ID

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        listOf(
            CHANNEL_ID to NotificationManager.IMPORTANCE_LOW,
            CHANNEL_ID_HEADSUP to NotificationManager.IMPORTANCE_DEFAULT,
        ).forEach { (id, importance) ->
            nm.createNotificationChannel(NotificationChannel(id, "VPN", importance).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
        }
    }

    private fun notification(profile: String, connecting: Boolean, detail: String? = null): Notification {
        // EXTRA_OPEN_HOME: the app is singleTask, so tapping this used to resume whatever page was
        // left open — the logs, a settings sub-page — instead of the screen the notification is about.
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_HOME, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        fun service(action: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, MoonVpnService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val pickServer = PendingIntent.getActivity(
            this, 5,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_SERVERS, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val paused = _state.value == State.Paused
        val state = when {
            paused -> getString(R.string.homescreen_005)
            connecting -> getString(R.string.homescreen_004)
            else -> getString(R.string.homescreen_003)
        }

        // Laid out the way INCY does it: the server is the headline, and the numbers sit under it
        // as labelled lines instead of one run-on row. Our state word moves to the sub-text so it
        // is still there without taking the title from the thing you actually look for.
        // The server is the one thing you look for in the shade, so never let the headline fall
        // back to our own name while we still know which server it is. _activeProfile is null
        // between a stop and the traffic poller noticing, and that tick repainted the notification
        // as plain "Moon Internet" — the flag and the country simply vanished mid-session.
        val title = profile.ifBlank { lastProfile }.ifBlank { "Moon Internet" }

        val b = NotificationCompat.Builder(this, channelId())
            .setSmallIcon(R.drawable.ic_tile_moon)
            .setContentTitle(title)
            .setSubText(state)
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)

        // Hand-drawn views are gone. They had to guess at the shade's colours and its button
        // styling, and a launcher theme that did either differently left our text unreadable and
        // our actions looking like plain words. The system's own layout is never wrong about its
        // own theme, and it draws real action buttons with the labels spelled out.
        if (paused || connecting) {
            b.setContentText(state)
        } else if (noNetwork.value) {
            b.setContentText(getString(R.string.notif_no_network))
                .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.notif_no_network_long)))
        } else if (linkDead.value) {
            // Say it. "Connected" with a speed of zero reads as "working, quiet"; it is not.
            b.setContentText(getString(R.string.notif_not_working))
                .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.notif_not_working_long)))
        } else {
            val (up, down) = traffic.value
            val speed = getString(R.string.notif_speed, detail ?: "↑ —  ↓ —")
            val lines = buildString {
                append(speed).append('\n')
                append(getString(R.string.notif_total, sizeText(up), sizeText(down))).append('\n')
                append(getString(R.string.notif_time, elapsedText()))
                livePing.value?.let { append('\n').append(getString(R.string.notif_ping, it)) }
            }
            b.setContentText(speed)                                      // collapsed: one line
                .setStyle(NotificationCompat.BigTextStyle().bigText(lines))   // expanded: all of it
        }

        val actions = if (paused) listOf(
            getString(R.string.notif_start) to service(ACTION_RECONNECT, 3),
            getString(R.string.notif_disconnect) to service(ACTION_DISCONNECT, 1),
            getString(R.string.notif_server) to pickServer,
        ) else listOf(
            getString(R.string.notif_pause) to service(ACTION_PAUSE, 4),
            getString(R.string.notif_ping_action) to service(ACTION_PING, 2),
            getString(R.string.notif_disconnect) to service(ACTION_DISCONNECT, 1),
        )
        actions.forEach { (label, intent) -> b.addAction(0, label, intent) }

        return b.build()
    }

    private fun elapsedText(): String {
        val start = connectedAt
        if (start <= 0) return "00:00"
        val secs = (System.currentTimeMillis() - start) / 1000
        return String.format("%02d:%02d:%02d", secs / 3600, (secs % 3600) / 60, secs % 60)
    }

    // one formatter for the whole app, so the units follow the language here too
    private fun sizeText(bytes: Long) = cc.moon.internet.data.SubscriptionService.size(bytes)

    private fun updateNotification(profile: String, connecting: Boolean, detail: String? = null) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(profile, connecting, detail))
    }
}

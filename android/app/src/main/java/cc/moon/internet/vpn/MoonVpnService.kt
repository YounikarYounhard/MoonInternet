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
        const val ACTION_RECONNECT = "cc.moon.internet.RECONNECT"
        const val ACTION_PING = "cc.moon.internet.PING"
        const val ACTION_PAUSE = "cc.moon.internet.PAUSE"

        /** Everything the last connect was made with, so the tile and the notification can redo it. */
        @Volatile private var lastConfig: String? = null
        @Volatile private var lastProfile: String = ""
        @Volatile private var lastMtu: Int = 1500
        @Volatile private var lastTun: Boolean = true
        @Volatile private var lastAppMode: String = "off"
        @Volatile private var lastApps: List<String> = emptyList()

        /** Latency through the tunnel, refreshed on connect and by the notification button. */
        val livePing = MutableStateFlow<String?>(null)

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

        private val _activeProfile = MutableStateFlow<String?>(null)
        val activeProfile = _activeProfile.asStateFlow()

        /** Cumulative counters read from the core (bytes). */
        val traffic = MutableStateFlow(0L to 0L)   // up to down

        /** Why the last connect attempt died. Null while things are fine. */
        val lastError = MutableStateFlow<String?>(null)

        /** Set while the tunnel is up, so the UI can ask the core for a real latency figure. */
        @Volatile var runner: XrayRunner? = null
            private set

        fun start(
            ctx: Context, config: String, profileName: String, mtu: Int = 1500,
            tun: Boolean = true, perAppMode: String = "off", perApps: List<String> = emptyList(),
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
    @Volatile private var connectedAt = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
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
                startTunnel(cfg, lastProfile, lastMtu, lastAppMode, lastApps, lastTun)
            }

            ACTION_PING -> {
                scope.launch {
                    val ms = runner?.measureDelay()
                    livePing.value = if (ms == null || ms < 0) "нет ответа" else "$ms ms"
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
                )
            }
        }
        return START_STICKY
    }

    private fun startTunnel(
        config: String, profileName: String, mtu: Int, appMode: String, apps: List<String>,
        tun: Boolean = true,
    ) {
        _state.value = State.Connecting
        _activeProfile.value = profileName
        lastError.value = null
        lastConfig = config; lastProfile = profileName; lastMtu = mtu
        lastTun = tun; lastAppMode = appMode; lastApps = apps
        startForeground(NOTIFICATION_ID, notification(profileName, connecting = true))

        scope.launch {
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

                // Proxy mode runs the core with no TUN: only the local SOCKS/HTTP listeners, and
                // no VPN interface — the same shape INCY's ProxyOnlyService has, which is an
                // ordinary foreground service rather than a VpnService.
                val fd = if (tun) {
                    establish(profileName, mtu, appMode, apps)
                        ?: run { askForVpnSlot(); return@launch }
                } else null
                tunFd = fd

                runner = XrayRunner(this@MoonVpnService) { _state.value = State.Disconnected }
                    .also { it.start(config, fd?.fd ?: 0) }

                connectedAt = System.currentTimeMillis()
                _state.value = State.Connected
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
    ): ParcelFileDescriptor? {
        val b = Builder()
            .setSession(profileName.ifBlank { "Moon Internet" })
            .setMtu(mtu)
            .addAddress("172.19.0.1", 30)
            .addDnsServer("1.1.1.1")
            .addRoute("0.0.0.0", 0)
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

    private suspend fun pollTraffic() {
        var lastUp = 0L
        var lastDown = 0L
        var tick = 0
        while (_state.value == State.Connected) {
            runner?.trafficTotals()?.let { (up, down) ->
                traffic.value = up to down
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

    private fun stopCore() {
        runCatching { runner?.stop() }
        runner = null
        runCatching { tunFd?.close() }
        tunFd = null
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
    override fun onDestroy() { stopCore(); scope.cancel(); super.onDestroy() }

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
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
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
        val b = NotificationCompat.Builder(this, channelId())
            .setSmallIcon(R.drawable.ic_tile_moon)
            .setContentTitle(profile.ifBlank { "Moon Internet" })
            .setSubText(state)
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)

        if (paused || connecting) {
            b.setContentText(state)
        } else {
            val (up, down) = traffic.value
            val speed = getString(R.string.notif_speed, detail ?: "↑ —  ↓ —")
            val lines = buildString {
                append(speed).append('\n')
                append(getString(R.string.notif_total, sizeText(up), sizeText(down))).append('\n')
                append(getString(R.string.notif_time, elapsedText()))
                livePing.value?.let { append('\n').append(getString(R.string.notif_ping, it)) }
            }
            b.setContentText(speed)                                  // collapsed: one line
                .setStyle(NotificationCompat.BigTextStyle().bigText(lines))   // expanded: all of it
        }

        if (paused) {
            b.addAction(0, getString(R.string.notif_start), service(ACTION_RECONNECT, 3))
            b.addAction(0, getString(R.string.notif_disconnect), service(ACTION_DISCONNECT, 1))
            b.addAction(0, getString(R.string.notif_server), pickServer)
        } else {
            b.addAction(0, getString(R.string.notif_pause), service(ACTION_PAUSE, 4))
            b.addAction(0, getString(R.string.notif_ping_action), service(ACTION_PING, 2))
            b.addAction(0, getString(R.string.notif_disconnect), service(ACTION_DISCONNECT, 1))
        }
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

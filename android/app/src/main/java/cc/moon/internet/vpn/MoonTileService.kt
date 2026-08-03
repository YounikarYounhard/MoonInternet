package cc.moon.internet.vpn

import android.content.Intent
import android.net.VpnService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import cc.moon.internet.MainActivity
import cc.moon.internet.R
import cc.moon.internet.core.XrayConfig
import cc.moon.internet.data.GeoService
import cc.moon.internet.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Quick Settings tile: connect and disconnect straight from the notification shade, the way the
 * other VPN clients do it.
 *
 * The tile can only start the tunnel when the VPN permission is already granted; the very first
 * time it opens the app instead, because that consent dialog needs an Activity.
 */
class MoonTileService : TileService() {

    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        scope?.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { s ->
            s.launch { MoonVpnService.state.collect { render(it) } }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        when (MoonVpnService.state.value) {
            // a paused tunnel already knows its profile — resume instead of asking again
            MoonVpnService.Companion.State.Paused ->
                androidx.core.content.ContextCompat.startForegroundService(
                    this, Intent(this, MoonVpnService::class.java).setAction(MoonVpnService.ACTION_RECONNECT))
            MoonVpnService.Companion.State.Disconnected -> connect()
            else -> MoonVpnService.stop(this)
        }
    }

    private fun connect() {
        // No consent yet → the user has to go through the app once.
        if (VpnService.prepare(this) != null) {
            val open = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_CONNECT_NOW, true)
            startActivityAndCollapse(open)
            return
        }

        // Build the profile here rather than replaying a cached one: after a reboot the service
        // has no memory, and the tile has to work without the app ever being opened.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val store = Store(applicationContext)
            store.load()
            val st = store.state.value
            val server = store.autoTarget()
            if (server == null || !XrayConfig.supports(server.protocol)) return@launch

            val socks = cc.moon.internet.core.freeLocalPort(st.socksPort)
            val config = runCatching {
                XrayConfig.build(
                    server = server,
                    routing = if (st.useRouting) store.activeRouting() else null,
                    dns = st.dns,
                    hasGeoFiles = GeoService.ready(applicationContext),
                    sniffing = st.sniffing,
                    blockUdp = st.blockUdp,
                    tlsFragment = st.tlsFragment,
                    mux = st.mux,
                    trafficPriority = st.trafficPriority,
                    preferredIp = st.preferredIp,
                    logLevel = if (st.logsEnabled) st.logLevel else "none",
                    // another VPN client on the phone probably owns 10808/10809 already
                    socksPort = socks,
                    httpPort = cc.moon.internet.core.freeLocalPort(st.httpPort, avoid = setOf(socks)),
                    proxyUser = st.proxyUser,
                    proxyPass = st.proxyPass,
                    socksAuth = st.socks5Auth,
                    httpAuth = st.httpProxyAuth,
                    allowLan = st.allowLan,
                )
            }.getOrNull() ?: return@launch

            val finalConfig = if (st.tunMode) config else XrayConfig.buildProxyOnly(config)
            MoonVpnService.start(
                applicationContext, finalConfig, server.label,
                tun = st.tunMode, perAppMode = st.perAppMode, perApps = st.perApps,
            )
        }
    }

    private fun render(state: MoonVpnService.Companion.State) {
        val tile = qsTile ?: return
        tile.state = when (state) {
            MoonVpnService.Companion.State.Connected -> Tile.STATE_ACTIVE
            MoonVpnService.Companion.State.Connecting -> Tile.STATE_UNAVAILABLE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = "Moon Internet"
        tile.contentDescription = when (state) {
            MoonVpnService.Companion.State.Connected -> "Луна укрыла"
            MoonVpnService.Companion.State.Connecting -> "Луна просыпается…"
            MoonVpnService.Companion.State.Paused -> "Луна на паузе"
            MoonVpnService.Companion.State.Disconnected -> "Луна спит"
        }
        runCatching { tile.subtitle = tile.contentDescription }   // API 29+, ignored below that
        tile.icon = android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_tile_moon)
        tile.updateTile()
    }
}

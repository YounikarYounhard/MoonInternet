package cc.moon.internet.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import cc.moon.internet.core.XrayConfig
import cc.moon.internet.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Reconnects after a reboot, when the user asked for it.
 *
 * Only possible because the VPN consent survives a restart: prepare() comes back null once it has
 * been granted, and a service started from BOOT_COMPLETED is allowed to go foreground. If the
 * consent is gone there is nothing a receiver can do — only an activity can show that dialog — so
 * we quietly do nothing rather than pop something over whatever the user is looking at.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON") return

        val ctx = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val store = Store(ctx)
                store.load()
                val st = store.state.value
                if (!st.startOnBoot) return@launch
                if (VpnService.prepare(ctx) != null) return@launch   // consent revoked; nothing to do

                val server = store.autoTarget() ?: return@launch
                if (!XrayConfig.supports(server.protocol)) return@launch

                val config = XrayConfig.build(
                    server = server,
                    routing = if (st.useRouting) store.activeRouting() else null,
                    dns = st.dns,
                    hasGeoFiles = cc.moon.internet.data.GeoService.ready(ctx),
                    sniffing = st.sniffing,
                    blockUdp = st.blockUdp,
                    tlsFragment = st.tlsFragment,
                    mux = st.mux,
                    trafficPriority = st.trafficPriority,
                    preferredIp = st.preferredIp,
                    socksPort = st.socksPort,
                    httpPort = st.httpPort,
                    allowLan = st.allowLan,
                    socksAuth = st.socks5Auth,
                    httpAuth = st.httpProxyAuth,
                    proxyUser = st.proxyUser,
                    proxyPass = st.proxyPass,
                )   // build() already returns the JSON text

                MoonVpnService.start(
                    ctx, config, server.label,
                    tun = st.tunMode, perAppMode = st.perAppMode, perApps = st.perApps,
                )
            } catch (_: Throwable) {
                // A failed reconnect after a reboot is not worth a crash on a device that just
                // started; the user opens the app and connects.
            } finally {
                pending.finish()
            }
        }
    }
}

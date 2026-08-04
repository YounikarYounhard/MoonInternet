package cc.moon.internet.data

import android.content.Context
import cc.moon.internet.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Everything we persist. Same idea as the desktop settings.json + subscription cache. */
@Serializable
data class AppState(
    val subscriptions: List<Subscription> = emptyList(),
    val selectedServerRaw: String? = null,
    val favorites: List<String> = emptyList(),
    val routing: RoutingProfile? = null,
    val useRouting: Boolean = true,
    val autoConnect: Boolean = false,
    val perAppMode: String = "off",
    val perApps: List<String> = emptyList(),
    val dns: String = "1.1.1.1",
    val ipv6: Boolean = false,
    val sort: String = "default",
    val tunMode: Boolean = true,
    /** urls of subscriptions the user collapsed on Home */
    val collapsed: List<String> = emptyList(),
    val autoReconnect: Boolean = true,
    val updateOnStart: Boolean = true,
    val pingOnStart: Boolean = true,
    val showSubHeader: Boolean = true,
    val subMeter: String = "text",   // text | bar | dots — как плашка подписки показывает остаток
    /** Routing profiles imported from subscriptions (incy/happ) plus the user's own. */
    val routings: List<RoutingProfile> = emptyList(),
    val routingSource: String = "incy",
    // connection tuning, same switches as the desktop settings page
    val tlsFragment: Boolean = false,
    val mux: Boolean = false,
    val trafficPriority: String = "off",   // off | balance | games — БЕТА, выключено по умолчанию
    val sniffing: Boolean = true,
    val blockUdp: Boolean = false,
    val preferredIp: String = "auto",   // auto | ipv4 | ipv6
    val showServerCount: Boolean = true,   // кружок с числом серверов у подписки
    val pingMethod: String = "moon",    // moon | tcp | httpget | httphead | stability
    val pingStagger: Boolean = true,    // space the probes out instead of firing them all at once
    val pingStaggerMs: Int = 150,       // gap between them when staggering
    val pingEveryMinutes: Int = 0,      // 0 = off; otherwise re-ping in the background this often
    val pingDisplay: String = "num",    // num | dot | both | off
    val pingTestUrl: String = "https://www.gstatic.com/generate_204",
    val pingTimeoutMs: Int = 4000,
    val autoUpdateSubs: Boolean = true,
    val autoUpdateSubsMinutes: Int = 0,
    val notifyOnUpdate: Boolean = false,
    // Уведомления: главный выключатель, всплывать (heads-up) или тихо, и что именно показывать
    val notificationsEnabled: Boolean = true,
    val notifyHeadsUp: Boolean = false,
    val notifyConnection: Boolean = false,
    val notifyAppUpdate: Boolean = true,
    val notifyExpiry: Boolean = true,
    val notifyTrafficLow: Boolean = true,   // предупредить, когда осталось меньше 10 % трафика
    val expiryNotifyDays: Int = 3,
    val sendHwid: Boolean = true,
    val autoConnectTarget: String = "first",   // first | favorite | last
    val autoFailover: Boolean = true,          // выбранный сервер молчит -> взять самый быстрый живой
    val reconnectDelaySec: Int = 5,            // пауза перед попыткой переподключиться: 3 | 5 | 10 | 30
    val logsEnabled: Boolean = true,
    val logLevel: String = "warning",
    // appearance — the desktop keeps these in theme.json; on a phone one state file is enough
    val accentHex: String = "FF9D7BFF",
    val bgHex: String = "FF0D0A18",
    val textHex: String = "FFECE9F5",
    val fontName: String = "comic",     // comic | system
    // connection page
    val killSwitch: Boolean = false,
    val allowLan: Boolean = true,
    val lanThroughProxy: Boolean = false,
    val showProxyOnlyButton: Boolean = false,
    val proxyBypassHosts: String = "",
    val socks5Auth: Boolean = false,
    val httpProxyAuth: Boolean = false,
    val proxyUser: String = "",
    val proxyPass: String = "",
    /** Local SOCKS/HTTP proxy ports, the way v2rayNG and HAPP expose them. */
    val socksPort: Int = 10808,
    val httpPort: Int = 10809,
    val vpnDns: String = "cf_google",   // cf_google | google | cloudflare | quad9 | custom
    val vpnDnsCustom: String = "",
    val logKeepDays: Int = 7,
    val subIntervalMinutes: Int = 0,    // 0 = follow the subscription's own interval
    val hwid: String = "",
    val welcomeShown: Boolean = false,   // экран первого запуска уже закрыли
    /**
     * Bumped when a default changes in a way an existing install should pick up. Without it a
     * saved value from the old default wins forever and the change only reaches new installs.
     */
    val settingsVersion: Int = 0,
)

/**
 * Single source of truth, kept in one JSON file. Written on every change so a kill never loses
 * the subscription — and read at startup **before** any network call, so servers show up offline.
 */
class Store(private val ctx: Context) {

    private val file = File(ctx.filesDir, "state.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    private val _state = MutableStateFlow(AppState())
    val state = _state.asStateFlow()

    private companion object { const val CURRENT_VERSION = 4 }

    suspend fun load() = withContext(Dispatchers.IO) {
        val loaded = runCatching {
            if (file.exists()) json.decodeFromString<AppState>(file.readText()) else AppState(settingsVersion = CURRENT_VERSION)
        }.getOrDefault(AppState(settingsVersion = CURRENT_VERSION))
        // v2: probes are spaced out by default now, so a batch fills the list one row at a time
        // instead of snapping to thirty numbers at once.
        // v3: the plate shows plain numbers unless asked otherwise.
        // v4: anybody who already has a subscription has clearly been past the first launch.
        _state.value = if (loaded.settingsVersion < CURRENT_VERSION)
            loaded.copy(
                pingStagger = true,
                subMeter = "text",
                welcomeShown = loaded.welcomeShown || loaded.subscriptions.isNotEmpty(),
                settingsVersion = CURRENT_VERSION,
            ).also { save(it) }
        else loaded
    }

    private fun save(st: AppState) = runCatching { file.writeText(json.encodeToString(st)) }

    suspend fun update(block: (AppState) -> AppState) = withContext(Dispatchers.IO) {
        val next = block(_state.value)
        _state.value = next
        runCatching { file.writeText(json.encodeToString(next)) }
    }

    // ---- convenience ------------------------------------------------------
    val allServers: List<ServerProfile>
        get() = _state.value.subscriptions.flatMap { it.servers }

    fun selectedServer(): ServerProfile? {
        val raw = _state.value.selectedServerRaw ?: return allServers.firstOrNull()
        return allServers.firstOrNull { it.raw == raw } ?: allServers.firstOrNull()
    }

    fun isFavorite(s: ServerProfile) = s.raw != null && s.raw in _state.value.favorites

    suspend fun toggleFavorite(s: ServerProfile) {
        val raw = s.raw ?: return
        update { st ->
            st.copy(favorites = if (raw in st.favorites) st.favorites - raw else st.favorites + raw)
        }
    }

    suspend fun selectServer(s: ServerProfile) = update { it.copy(selectedServerRaw = s.raw) }

    suspend fun removeSubscription(url: String) =
        update { it.copy(subscriptions = it.subscriptions.filterNot { s -> s.url == url }) }

    /** The routing profile currently in effect, picked by source the way the desktop does. */
    fun activeRouting(): RoutingProfile? = _state.value.let { st ->
        st.routings.firstOrNull { it.source == st.routingSource } ?: st.routings.firstOrNull()
    }

    suspend fun putRouting(profile: RoutingProfile) = update { st ->
        st.copy(routings = st.routings.filterNot { it.source == profile.source } + profile)
    }

    /** One subscription's servers, ordered by the current sort — used by Home and Servers alike. */
    fun sortedIn(sub: Subscription, pings: Map<String, Int> = emptyMap()): List<ServerProfile> =
        order(sub.servers, pings)

    /** Servers ordered the way the user asked, favourites always first (same rule as desktop). */
    fun sortedServers(pings: Map<String, Int> = emptyMap()): List<ServerProfile> = order(allServers, pings)

    /**
     * The server auto-connect (and the quick-settings tile) should start. Honours the
     * "какой сервер подключать" setting; with auto-connect off it is simply the first one.
     */
    /**
     * What auto-connect should dial.
     *
     * The user's preference wins whenever it answers. When it does not, we fall over to the
     * fastest server that did — without changing the stored preference, so the next launch tries
     * the preferred one again and only falls over if it is still down.
     *
     * Null means connect to nothing: everything was measured and none of it answered. Dialling a
     * server already known to be dead only produces a spinner and a failure.
     */
    fun autoTarget(pings: Map<String, Int> = emptyMap()): ServerProfile? {
        val st = _state.value
        val all = allServers
        if (all.isEmpty()) return null
        if (!st.autoConnect) return all.first()

        val favs = all.filter { isFavorite(it) }
        fun reachable(s: ServerProfile) = (pings[s.raw] ?: -2) >= 0
        fun lowest(list: List<ServerProfile>) =
            list.filter(::reachable).minByOrNull { pings[it.raw] ?: Int.MAX_VALUE }

        val preferred = when (st.autoConnectTarget) {
            "last" -> selectedServer() ?: all.last()
            "lowest" -> lowest(all) ?: all.first()
            "favorite-first" -> favs.firstOrNull() ?: all.first()
            "favorite-last" -> favs.lastOrNull() ?: all.first()
            "favorite-lowest" -> lowest(favs) ?: all.first()
            else -> all.first()
        }

        // Nothing measured yet — we have no grounds to overrule the preference.
        val measuredAny = all.any { pings.containsKey(it.raw) }
        if (!measuredAny || reachable(preferred)) return preferred
        // The user can ask us to respect the preference even when it looks dead — some servers
        // simply do not answer a probe while carrying traffic perfectly well.
        if (!st.autoFailover) return preferred

        val pool = if (st.autoConnectTarget.startsWith("favorite") && favs.isNotEmpty()) favs else all
        return lowest(pool) ?: lowest(all)   // null when nothing answered at all
    }

    private fun order(list: List<ServerProfile>, pings: Map<String, Int>): List<ServerProfile> {
        val fav = { s: ServerProfile -> if (isFavorite(s)) 0 else 1 }
        return when (_state.value.sort) {
            "ping" -> list.sortedWith(compareBy(fav, { pings[it.raw]?.takeIf { p -> p >= 0 } ?: Int.MAX_VALUE }, { it.label }))
            "name" -> list.sortedWith(compareBy(fav, { it.label.lowercase() }))
            "favorite" -> list.filter { isFavorite(it) }.ifEmpty { list }
            else -> list.sortedBy(fav)
        }
    }
}

package cc.moon.internet.data

import android.content.Context
import cc.moon.internet.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock
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
    /** Protocol chip on Servers. Lives here, not in the screen, so Home obeys it too. */
    val protocol: String = "",
    /** Connected server first in the list. Off = leave it wherever the sort puts it. */
    val pinActive: Boolean = false,
    /** Always true for now: the proxy-only mode was removed, another is planned. */
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
    /** Which profile is in effect. Empty falls back to the first in the list. */
    val selectedRoutingId: String = "",
    /** Which of a subscription's pair "Авто" reaches for first: incy | happ. */
    val autoRoutingPref: String = "incy",
    /** Pre-v6 selection: one profile per source. Read once by the migration, never written. */
    val routingSource: String = "",
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
    // On by default. Fifteen minutes keeps the list honest without a probe to every server
    // every few minutes — automatic passes use the cheap probe, but they are still traffic.
    val pingEveryMinutes: Int = 15,     // 0 = off; otherwise re-ping in the background this often
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
    val notifyAfterUpdate: Boolean = true,     // один раз сказать, что изменилось, после обновления
    val lastSeenVersion: String = "",          // сборка, для которой это уже показали
    val notifyExpiry: Boolean = true,
    val notifyTrafficLow: Boolean = true,   // предупредить, когда осталось меньше 10 % трафика
    val expiryNotifyDays: Int = 3,
    val sendHwid: Boolean = true,
    val autoConnectTarget: String = "first",   // first | favorite | last
    val startOnBoot: Boolean = false,          // поднять туннель после перезагрузки телефона
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

    /** False until the state file has been read, so nothing renders off the defaults. */
    private val _ready = MutableStateFlow(false)
    val ready = _ready.asStateFlow()
    val state = _state.asStateFlow()

    private companion object { const val CURRENT_VERSION = 8 }

    private val loadOnce = kotlinx.coroutines.sync.Mutex()
    @Volatile private var loaded = false

    /**
     * Reads the state file. Callable from anywhere, but it only ever reads once: two coroutines
     * racing here meant the second one could read the file mid-write, fail to decode, fall back to
     * defaults — and then the next update() wrote those defaults over everything the user had.
     */
    suspend fun load() = loadOnce.withLock {
        if (loaded) return@withLock
        loaded = true
        loadNow()
    }

    private suspend fun loadNow() = withContext(Dispatchers.IO) {
        val loaded = runCatching {
            if (file.exists()) json.decodeFromString<AppState>(file.readText()) else AppState(settingsVersion = CURRENT_VERSION)
        }.getOrDefault(AppState(settingsVersion = CURRENT_VERSION))
        // v2: probes are spaced out by default now, so a batch fills the list one row at a time
        // instead of snapping to thirty numbers at once.
        // v3: the plate shows plain numbers unless asked otherwise.
        // v4: anybody who already has a subscription has clearly been past the first launch.
        // v6: routing became a list of profiles instead of one-per-source, so imported profiles
        // need ids and the built-in three need to exist. Computed once and reused — ids are
        // random, so migrating twice would hand the selection an id nothing in the list has.
        val routings = migrateRoutings(loaded.routings, loaded.subscriptions.map { it.url })
        _state.value = if (loaded.settingsVersion < CURRENT_VERSION)
            loaded.copy(
                tunMode = true,   // v5: proxy-only is gone; anybody stuck in it gets the tunnel back
                pingStagger = true,
                subMeter = "text",
                welcomeShown = loaded.welcomeShown || loaded.subscriptions.isNotEmpty(),
                routings = routings,
                selectedRoutingId = releaseAutoSelection(migratedSelection(loaded, routings), routings),
                // v8: the automatic check is on by default now; switch it on for installs
                // that never touched it rather than leaving them on the old off.
                pingEveryMinutes = if (loaded.pingEveryMinutes == 0) 15 else loaded.pingEveryMinutes,
                settingsVersion = CURRENT_VERSION,
            ).also { save(it) }
        // Ids handed out here must survive the next launch, or the selection points at nothing.
        else loaded.copy(routings = routings).also { if (routings != loaded.routings) save(it) }
        _ready.value = true
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

    /** The routing profile currently in effect. */
    /**
     * The routing the tunnel will use.
     *
     * A profile picked by hand wins. With nothing picked we follow the server: the routing that
     * came with the subscription this server belongs to — INCY when a subscription carries both,
     * since that is the one those panels keep current, HAPP when it is all there is. So two
     * subscriptions keep their own rules and switching servers switches rules with them, without
     * anybody choosing anything.
     */
    fun activeRouting(): RoutingProfile? = _state.value.let { st ->
        st.routings.firstOrNull { it.id == st.selectedRoutingId }
            ?: autoRouting(st)
            ?: st.routings.firstOrNull()
    }

    /** Same answer the tunnel uses, for the "Авто" card to show. */
    fun autoRoutingPublic(): RoutingProfile? = autoRouting(_state.value)

    private fun autoRouting(st: AppState): RoutingProfile? {
        // A subscription need not carry routing at all, and a server can come from none. Falling
        // through to "whatever is first" would make those cases depend on list order; the shipped
        // Глобальный is the answer, and the card under Авто says so.
        val global = st.routings.firstOrNull { it.id == "builtin-global" }
        val raw = st.selectedServerRaw ?: return global
        val sub = st.subscriptions.firstOrNull { s -> s.servers.any { it.raw == raw } } ?: return global
        val mine = st.routings.filter { it.subUrl == sub.url }
        if (mine.isEmpty()) return global
        // A preference, not a rule: a subscription that only ships HAPP gets HAPP even when INCY
        // is preferred. Refusing the only profile there is would help nobody.
        val first = st.autoRoutingPref
        val second = if (first == "happ") "incy" else "happ"
        return mine.firstOrNull { it.source == first }
            ?: mine.firstOrNull { it.source == second }
            ?: mine.firstOrNull()
    }

    /** Saves an edited profile in place, or appends it when the id is new. */
    suspend fun putRouting(profile: RoutingProfile) = update { st ->
        val p = if (profile.id.isBlank()) profile.copy(id = newRoutingId()) else profile
        val list = if (st.routings.any { it.id == p.id })
            st.routings.map { if (it.id == p.id) p else it }
        else st.routings + p
        // Deliberately does not pick the profile it just stored. A subscription refresh calls this,
        // and making the import the chosen profile would end "follow the server" on the first fetch.
        st.copy(routings = list)
    }

    suspend fun selectRouting(id: String) = update { it.copy(selectedRoutingId = id) }

    /** Built-ins are the floor: deleting the last profile would leave nothing to route with. */
    suspend fun deleteRouting(id: String) = update { st ->
        val left = st.routings.filterNot { it.id == id && !it.builtin }
        st.copy(
            routings = left,
            selectedRoutingId = if (st.selectedRoutingId == id) left.firstOrNull()?.id.orEmpty()
                                else st.selectedRoutingId,
        )
    }

    /** "(копия)" the way INCY does it — a duplicate is a plain editable profile. */
    suspend fun duplicateRouting(id: String, copySuffix: String) = update { st ->
        val src = st.routings.firstOrNull { it.id == id } ?: return@update st
        // A copy leaves the subscription behind: that is the whole point of copying one, and it
        // is what makes it editable when the original is not.
        val copy = src.copy(
            id = newRoutingId(), builtin = false, source = "custom", subUrl = "",
            name = "${src.name} $copySuffix",
        )
        st.copy(routings = st.routings + copy)
    }

    private fun newRoutingId() = java.util.UUID.randomUUID().toString().take(8)

    /**
     * The three profiles that ship with the app. Fixed ids, so an update recognises the ones it
     * already installed instead of adding a second copy of each.
     *
     * Names are not localised on purpose: they are data the user can duplicate and rename, and a
     * profile that renamed itself when the language changed would be a profile you cannot refer to.
     */
    private fun builtinRoutings() = listOf(
        RoutingProfile(
            id = "builtin-global", builtin = true, source = "custom",
            name = "Глобальный", globalProxy = true, domainStrategy = "AsIs",
        ),
        RoutingProfile(
            id = "builtin-lan", builtin = true, source = "custom",
            name = "Обход LAN", globalProxy = true, domainStrategy = "AsIs",
            directIp = listOf("geoip:private"),
        ),
    )

    /** A subscription carries at most one profile per source, so that pair is the identity. */
    fun importedId(subUrl: String, source: String) = "sub:$subUrl:$source"

    /**
     * A selection pointing at a subscription's own profile is what "follow the server" would pick
     * anyway, so it is cleared: the setting becomes automatic instead of frozen on one
     * subscription. A built-in or a profile of your own is a real decision and stays.
     */
    private fun releaseAutoSelection(id: String, routings: List<RoutingProfile>): String =
        if (routings.any { it.id == id && it.subUrl.isNotBlank() }) "" else id

    private fun migrateRoutings(list: List<RoutingProfile>, subs: List<String>): List<RoutingProfile> {
        val withIds = list.map {
            when {
                // An imported profile is identified by its subscription and its source. Anything
                // else and every refresh adds another copy of a profile the list already holds.
                it.source == "incy" || it.source == "happ" -> {
                    // Profiles stored before subscriptions were tracked have nothing to group
                    // under; park them on the first subscription and let the next fetch correct it.
                    val url = it.subUrl.ifBlank { subs.firstOrNull().orEmpty() }
                    it.copy(id = importedId(url, it.source), subUrl = url)
                }
                it.id.isBlank() -> it.copy(id = newRoutingId())
                else -> it
            }
        }
        // Last one wins: a refetched profile is fresher than the copy already on disk.
        val builtins = builtinRoutings()
        val unique = withIds.associateBy { it.id }.values
            // A built-in this version no longer ships stays on disk forever otherwise: it is
            // marked builtin, so the list offers no way to delete it either.
            .filterNot { it.builtin && builtins.none { b -> b.id == it.id } }
        val missing = builtins.filterNot { b -> unique.any { it.id == b.id } }
        return missing + unique
    }

    private fun migratedSelection(old: AppState, migrated: List<RoutingProfile>): String {
        // The old model kept one profile per source and remembered the source name; carry that
        // choice over instead of dropping everyone onto the first built-in.
        val src = old.routingSource.ifBlank { "incy" }
        val kept = migrated.firstOrNull { it.source == src && !it.builtin }
        return kept?.id ?: builtinRoutings().first().id
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
        fun reachable(s: ServerProfile) = (pings[s.pingKey] ?: -2) >= 0
        fun lowest(list: List<ServerProfile>) =
            list.filter(::reachable).minByOrNull { pings[it.pingKey] ?: Int.MAX_VALUE }

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
        // The one you are connected through goes first — it is the row you look for, and hunting
        // for it in thirty others while it is live is the whole complaint.
        val active = if (_state.value.pinActive) _state.value.selectedServerRaw else null
        val pinned = { s: ServerProfile -> if (active != null && s.raw == active) 0 else 1 }
        val fav = { s: ServerProfile -> if (isFavorite(s)) 0 else 1 }
        val proto = _state.value.protocol
        @Suppress("NAME_SHADOWING")
        val list = if (proto.isEmpty()) list else list.filter { it.protocolLabel == proto }
        return when (_state.value.sort) {
            "ping" -> list.sortedWith(compareBy(pinned, fav, { pings[it.pingKey]?.takeIf { p -> p >= 0 } ?: Int.MAX_VALUE }, { it.label }))
            "name" -> list.sortedWith(compareBy(pinned, fav, { it.label.lowercase() }))
            "favorite" -> list.filter { isFavorite(it) }.ifEmpty { list }.sortedWith(compareBy(pinned))
            else -> list.sortedBy(fav)
        }
    }
}

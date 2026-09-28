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

/**
 * The server a saved selection names.
 *
 * The saved value is the share link without its `#remark`, and a panel rewrites its own links — a
 * new sni, a new fingerprint, the parameters in another order. The link moves, the machine does
 * not, so an exact miss is asked again by address and port before we give up. Without that step
 * every subscription refresh "lost" the selection and `healSelection` wrote the top of the list
 * down in its place — which is the app changing servers on its own, at whatever moment the panel
 * happened to touch its config.
 */
internal fun matchSelected(all: List<ServerProfile>, raw: String?): ServerProfile? {
    if (raw == null) return all.firstOrNull()
    all.firstOrNull { it.key == raw }?.let { return it }
    hostPortOf(raw)?.let { hp ->
        all.firstOrNull { "${it.address}:${it.port}" == hp }?.let { return it }
    }
    return all.firstOrNull()
}

/**
 * `host:port` out of a key of either shape, or null when it keeps one out of reach (vmess).
 * Both the old link-shaped key and the identity key put the endpoint after the last `@`, which
 * is what lets a star or a selection saved in the old shape find its server again.
 */
internal fun hostPortOf(key: String): String? {
    val at = key.lastIndexOf('@')
    if (at < 0) return null
    val tail = key.substring(at + 1).substringBefore('?').substringBefore('/').substringBefore('#').trim()
    return tail.takeIf { it.contains(':') }?.lowercase()
}

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
    /**
     * The server you are on goes first in its list — above the starred ones, because "the one I am
     * using" is a stronger answer to "which of these matters right now" than "one I liked once".
     * On by default since v14.
     */
    val pinActive: Boolean = true,
    /** The subscription holding the active server floats to the top. On by default since v13. */
    val pinActiveSub: Boolean = true,
    /** Always true for now: the proxy-only mode was removed, another is planned. */
    val tunMode: Boolean = true,
    /**
     * "tun" or "zapret". Two modes on the phone where the desktop has three: proxy-only was
     * dropped back in v5, and запрет is not a tunnel at all.
     */
    val connMode: String = "tun",
    val zapretStrategy: String = "general",
    /**
     * What the Zapret check opens. Not YouTube: it is throttled by IP address rather than by
     * name, so no amount of handshake rewriting brings it back and the check would report
     * failure for a strategy that is working perfectly well on everything else.
     */
    val zapretCheckUrl: String = "https://discord.com",
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
    // The interval lives in subIntervalMinutes; autoUpdateSubsMinutes was a second copy that was
    // written and never read. Files still carrying the key load fine — ignoreUnknownKeys.
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
    /**
     * What auto-connect reaches for. "last" — the server you chose — since v15.
     *
     * It used to be "first", and auto-connect does not merely dial its target, it SAVES it as the
     * selection. So every launch quietly replaced a deliberate choice with whatever happened to be
     * at the top of the list, which reads as the app switching servers on its own.
     */
    val autoConnectTarget: String = "last",
    val startOnBoot: Boolean = false,          // поднять туннель после перезагрузки телефона
    /** Unlocked by tapping the version seven times; only decides whether Отладка is reachable. */
    val devMode: Boolean = false,
    val autoFailover: Boolean = false,         // БЕТА: выбранный сервер молчит -> взять самый быстрый живой
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
    /**
     * Ids of the engines this install has, beyond the one it is built around — see [MoonCore].
     *
     * Kept here rather than read off the disk every time because the two are about to differ: an
     * engine that is present but not wanted still has its binary in the build until the splits
     * exist, and Запрет is exactly that case — it weighs 71 KB, so nothing is saved by leaving it
     * out, and the point was never the size but the row of mode buttons it puts on the Home screen.
     */
    val installedCores: List<String> = emptyList(),
)

/**
 * Single source of truth, kept in one JSON file. Written on every change so a kill never loses
 * the subscription — and read at startup **before** any network call, so servers show up offline.
 */
class Store(private val ctx: Context) {

    private val file = File(ctx.filesDir, "state.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    init {
        // External dir, not filesDir: a release-signed build refuses `run-as`, so this is the only
        // place a journal can live and still be pulled off a cable. See DevLog.attach.
        DevLog.attach(ctx.getExternalFilesDir(null) ?: ctx.filesDir)
    }

    private val _state = MutableStateFlow(AppState())

    /** False until the state file has been read, so nothing renders off the defaults. */
    private val _ready = MutableStateFlow(false)
    val ready = _ready.asStateFlow()
    val state = _state.asStateFlow()

    private companion object { const val CURRENT_VERSION = 16 }

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
                // v10: undoes v9, which had switched this on for everybody — see the desktop note.
                autoFailover = false,
                pingStagger = true,
                subMeter = "text",
                welcomeShown = loaded.welcomeShown || loaded.subscriptions.isNotEmpty(),
                routings = routings,
                selectedRoutingId = releaseAutoSelection(migratedSelection(loaded, routings), routings),
                // v8: the automatic check is on by default now; switch it on for installs
                // that never touched it rather than leaving them on the old off.
                pingEveryMinutes = if (loaded.pingEveryMinutes == 0) 15 else loaded.pingEveryMinutes,
                // v11: stars and the current selection are keyed by the share link without its
                // #remark now (see ServerProfile.key). Trim what is already saved, or every
                // favourite anybody has would stop matching the moment this build lands.
                favorites = loaded.favorites.map { it.substringBefore('#') }.distinct(),
                // v12: hand-added servers share one bucket instead of one pseudo-subscription
                // each. Fold whatever is already there into it, keeping the order.
                subscriptions = mergeLooseSubscriptions(loaded.subscriptions),
                // v13: the subscription you are connected through belongs at the top, and having
                // to find the setting first was the reason nobody had it on.
                pinActiveSub = true,
                // v14: and the active server itself, which was the other half of the same idea and
                // had been left switched off, so nothing rose to the top at all.
                pinActive = true,
                // v15: auto-connect keeps the server you chose instead of jumping to the first one.
                autoConnectTarget = if (loaded.autoConnectTarget == "first") "last" else loaded.autoConnectTarget,
                selectedServerRaw = loaded.selectedServerRaw?.substringBefore('#'),
                // v16: engines became something you have. Whatever is already in the build counts
                // as installed, so nobody loses a protocol on update — except Запрет, which is the
                // one this was done for: it stays out unless this install has actually used it.
                installedCores = MoonCore.optional
                    .filter { it.present(ctx) }
                    .filter { it != MoonCore.Zapret || loaded.connMode == "zapret" }
                    .map { it.id },
                settingsVersion = CURRENT_VERSION,
            ).also { save(it) }
        // Ids handed out here must survive the next launch, or the selection points at nothing.
        else loaded.copy(routings = routings).also { if (routings != loaded.routings) save(it) }
        _ready.value = true
    }

    /** All `clipboard:*` pseudo-subscriptions folded into one, in place of the first of them. */
    private fun mergeLooseSubscriptions(subs: List<Subscription>): List<Subscription> {
        val loose = subs.filter { it.url.startsWith("clipboard:") }
        if (loose.size <= 1 && loose.firstOrNull()?.url.let { it == null || it == "clipboard:local" }) return subs
        val merged = Subscription(
            url = "clipboard:local",
            name = loose.first().name.takeIf { loose.size == 1 } ?: "Отдельные подключения",
            servers = loose.flatMap { it.servers }.distinctBy { it.key },
        )
        var placed = false
        return subs.mapNotNull {
            when {
                !it.url.startsWith("clipboard:") -> it
                placed -> null
                else -> { placed = true; merged }
            }
        }
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

    fun selectedServer(): ServerProfile? = matchSelected(allServers, _state.value.selectedServerRaw)

    /**
     * Write the fallback down when the saved selection no longer names anything.
     *
     * [selectedServer] quietly falls back to the first server, which keeps the app usable but
     * leaves the state disagreeing with itself: the row highlighted on screen is one server and
     * the saved value names another, so everything asked in terms of the saved value answers about
     * the wrong one. That is how a subscription refresh could highlight the server you were on and
     * still give it a ping measured as though it were somebody else's.
     */
    suspend fun healSelection() {
        val raw = _state.value.selectedServerRaw
        if (raw != null && allServers.any { it.key == raw }) return
        val resolved = selectedServer() ?: return
        // Two very different things used to wear the same sentence. A panel rewriting its own link
        // while the machine stays put is routine and happens constantly; losing the server and
        // taking the top of the list is the one worth spotting in a log read days later.
        val sameMachine = raw != null && hostPortOf(raw) == "${resolved.address.lowercase()}:${resolved.port}"
        DevLog.log("сервер", if (sameMachine)
            "ссылка в подписке изменилась, сервер тот же -> ${resolved.name}"
        else
            "сохранённый сервер пропал из подписки, беру первый -> ${resolved.name}")
        update { it.copy(selectedServerRaw = resolved.key) }
    }

    /**
     * Carry stars written in the old link-shaped key over to the identity key.
     *
     * A star was saved as the link, and the link is not what the panel serves next time, so the
     * star matched nothing and quietly went out — once per subscription refresh, for whichever
     * third of the servers the panel had rewritten. Same endpoint, same server: move it. A star
     * naming a server that is in no subscription right now is left alone; the subscription holding
     * it may simply not have loaded yet.
     */
    suspend fun migrateFavorites() {
        val favorites = _state.value.favorites
        if (favorites.isEmpty() || allServers.isEmpty()) return
        val live = allServers.mapTo(HashSet()) { it.key }
        if (favorites.all { it in live }) return          // the ordinary case, nothing to do

        val byEndpoint = HashMap<String, String>()
        for (s in allServers) hostPortOf(s.key)?.let { byEndpoint.putIfAbsent(it, s.key) }

        var moved = 0
        val carried = favorites.map { f ->
            if (f in live) f
            else hostPortOf(f)?.let { byEndpoint[it] }?.also { moved++ } ?: f
        }
        if (moved == 0) return
        DevLog.log("избранное", "перенёс на новые ключи: $moved")
        update { it.copy(favorites = carried.distinct()) }
    }


    fun isFavorite(s: ServerProfile) = s.key in _state.value.favorites

    suspend fun toggleFavorite(s: ServerProfile) {
        val raw = s.key
        update { st ->
            st.copy(favorites = if (raw in st.favorites) st.favorites - raw else st.favorites + raw)
        }
    }

    /**
     * [why] is written to [DevLog] next to the change. Every caller names itself, so a selection
     * that moved on its own can be traced back to whatever moved it instead of being guessed at.
     */
    suspend fun selectServer(s: ServerProfile, why: String = "вручную") {
        val was = _state.value.selectedServerRaw
        if (was == s.key) return
        DevLog.log("сервер", "$why -> ${s.name}")
        update { it.copy(selectedServerRaw = s.key) }
    }

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
        val sub = st.subscriptions.firstOrNull { s -> s.servers.any { it.key == raw } } ?: return global
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
        // Whatever we cannot work out, we leave alone. The tile and the boot receiver call this
        // with no measurements at all and write the answer back as the chosen server, so every
        // fallback to "the first in the list" quietly replaced the user's choice with the top row.
        // Falling back to the choice itself makes those calls a no-op instead.
        val saved = selectedServer()
        if (!st.autoConnect) return saved ?: all.first()

        val favs = all.filter { isFavorite(it) }
        fun reachable(s: ServerProfile) = (pings[s.pingKey] ?: -2) >= 0
        fun lowest(list: List<ServerProfile>) =
            list.filter(::reachable).minByOrNull { pings[it.pingKey] ?: Int.MAX_VALUE }

        val preferred = when (st.autoConnectTarget) {
            "last" -> saved ?: all.last()
            "lowest" -> lowest(all) ?: saved ?: all.first()
            "favorite-first" -> favs.firstOrNull() ?: saved ?: all.first()
            "favorite-last" -> favs.lastOrNull() ?: saved ?: all.first()
            "favorite-lowest" -> lowest(favs) ?: saved ?: all.first()
            else -> saved ?: all.first()
        }

        // Nothing measured yet — we have no grounds to overrule the preference.
        val measuredAny = all.any { pings.containsKey(it.pingKey) }
        if (!measuredAny || reachable(preferred)) return preferred
        // The user can ask us to respect the preference even when it looks dead — some servers
        // simply do not answer a probe while carrying traffic perfectly well.
        if (!st.autoFailover) return preferred

        val pool = if (st.autoConnectTarget.startsWith("favorite") && favs.isNotEmpty()) favs else all
        return lowest(pool) ?: lowest(all)   // null when nothing answered at all
    }

    private fun order(list: List<ServerProfile>, pings: Map<String, Int>): List<ServerProfile> {
        val st = _state.value
        // Whether the chosen protocol still exists anywhere is a question about EVERY server, not
        // about the handful in this one subscription. Asked per subscription, one that simply does
        // not carry the protocol looked like one where the protocol had vanished — so it showed all
        // of its servers instead of none of them, which is the opposite of filtering.
        val protocol =
            if (st.protocol.isEmpty() || allServers.any { it.protocolLabel == st.protocol }) st.protocol
            else ""
        // Same question, asked of the whole collection rather than of one subscription: "show only
        // the starred ones" has to fall back to showing everything when nothing anywhere is
        // starred, or the screen is simply empty with no way to tell why. Asked per subscription
        // it meant something else entirely — a subscription with no favourites of its own showed
        // ALL of its servers, sitting among the ones that had been filtered down to their stars.
        val sort =
            if (st.sort == "favorite" && allServers.none { it.key in st.favorites }) "" else st.sort
        return orderServers(list, pings, st.favorites, sort, protocol,
                            if (st.pinActive) selectedServer()?.key else null)
    }
}

/**
 * Who goes above whom in a server list. Free of the store on purpose: the ordering is the part
 * that is easy to get quietly wrong, and this way it can be checked without a phone — see
 * ServerOrderTest.
 *
 * @param activeRaw the connected server, or null when it should not be pinned to the top.
 */
internal fun orderServers(
    list: List<ServerProfile>,
    pings: Map<String, Int>,
    favorites: List<String>,
    sort: String,
    protocol: String,
    activeRaw: String?,
): List<ServerProfile> {
    // The one you are connected through goes first — it is the row you look for, and hunting
    // for it in thirty others while it is live is the whole complaint.
    val pinned = { s: ServerProfile -> if (activeRaw != null && s.key == activeRaw) 0 else 1 }
    val fav = { s: ServerProfile -> if (s.key in favorites) 0 else 1 }
    // Filtering plainly. A protocol that no longer exists ANYWHERE would empty every list with no
    // chip selected to explain why, so it is caught before it gets here — see order(), which asks
    // that question of the whole collection rather than of one subscription at a time.
    // The connected row is exempt from both filters. Pick a VLESS server, connect, then tap the
    // Hysteria2 chip and the server you are actually using vanishes — the one row that should never
    // be in question. Same for «★ Избранное» when it is not starred.
    val active = { s: ServerProfile -> activeRaw != null && s.key == activeRaw }
    val kept = if (protocol.isEmpty()) list else list.filter { it.protocolLabel == protocol || active(it) }
    return when (sort) {
        "ping" -> kept.sortedWith(compareBy(pinned, fav, { pings[it.pingKey]?.takeIf { p -> p >= 0 } ?: Int.MAX_VALUE }, { it.label }))
        "name" -> kept.sortedWith(compareBy(pinned, fav, { it.label.lowercase() }))
        "favorite" -> kept.filter { fav(it) == 0 || active(it) }.sortedWith(compareBy(pinned))
        // "Обычная" is the subscription's own order with the stars lifted out of it — and the
        // pinned row above those, like every other mode here. It was the one branch that forgot
        // to ask, so in the order everybody actually uses the active server never moved at all.
        else -> kept.sortedWith(compareBy(pinned, fav))
    }
}

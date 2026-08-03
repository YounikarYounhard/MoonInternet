package cc.moon.internet

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import cc.moon.internet.core.ServerProfile
import cc.moon.internet.core.Subscription
import cc.moon.internet.ui.*
import cc.moon.internet.vpn.MoonVpnService

/** The four pages the desktop window has. */
private enum class Page { Home, Servers, Routing, Settings }

/**
 * How wide the content is allowed to get. The desktop window it was ported from is 420dp;
 * a little more than that reads fine on a big phone, and anything past it is a tablet
 * stretching a layout that was never meant to fill one.
 */
private val CONTENT_MAX_WIDTH = 460.dp

class MainActivity : ComponentActivity() {

    companion object {
        /** Set by the quick-settings tile when the VPN consent has not been granted yet. */
        const val EXTRA_CONNECT_NOW = "connect_now"
        /** Set by the notification's "Сервер" button. */
        const val EXTRA_OPEN_SERVERS = "open_servers"
    }

    private val vm: MainViewModel by viewModels()

    /** Camera QR scan; the decoded text goes through the same import path as a pasted link. */
    private val scanQr = registerForActivityResult(ScanQrContract()) { text ->
        if (!text.isNullOrBlank()) vm.addSubscription(text)
    }

    /** Android asks the user once whether this app may run a VPN; we connect after they agree. */
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) vm.connect() else vm.reportPermissionDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge to edge. The phone's own bars stay on screen — the app just paints underneath
        // them, so the back/home buttons sit on our background instead of a black strip.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightNavigationBars = false
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false

        setContent {
            val state by vm.state.collectAsState()
            LaunchedEffect(state.accentHex, state.bgHex, state.textHex) {
                applyTheme(state.accentHex, state.bgHex, state.textHex)
            }
            MoonTheme(fontName = state.fontName) {
                val vpn by vm.vpnState.collectAsState()
                val pings by vm.pings.collectAsState()
                val (up, down) = vm.speed.collectAsState().value
                val elapsed by vm.elapsed.collectAsState()
                val traffic by vm.sessionTraffic.collectAsState()
                val status by vm.status.collectAsState()
                val checkPing by vm.checkPing.collectAsState()
                val geoBusy by vm.geoBusy.collectAsState()
                val geoStatus by vm.geoStatus.collectAsState()
                val updateAvailable by vm.updateAvailable.collectAsState()
                val release by vm.release.collectAsState()
                val updateStatus by vm.updateStatus.collectAsState()

                var page by remember { mutableStateOf(Page.Home) }
                // one scroll state per list, so switching tabs comes back where you left off
                val homeScroll = rememberLazyListState()
                val serversScroll = rememberLazyListState()
                var settingsPage by remember { mutableStateOf<SettingsPage?>(null) }
                // sub-pages can open other sub-pages (About → Terms), so back needs a stack
                val settingsBack = remember { mutableStateListOf<SettingsPage>() }
                // touching the core loads a 19 MB native library; keep it off the first frame
                val xrayVersion by produceState(initialValue = "…") {
                    value = withContext(Dispatchers.IO) { cc.moon.internet.vpn.XrayRunner.version() }
                }
                var showAdd by remember { mutableStateOf(false) }
                var subMenu by remember { mutableStateOf<Subscription?>(null) }
                var serverMenu by remember { mutableStateOf<ServerProfile?>(null) }
                var jsonOf by remember { mutableStateOf<ServerProfile?>(null) }
                var qrOf by remember { mutableStateOf<Pair<String, String>?>(null) }
                var showUpdate by remember { mutableStateOf(false) }
                var addRuleTo by remember { mutableStateOf<String?>(null) }
                val snackbar = remember { SnackbarHostState() }

                // Back walks the same path the user came in by; only Home exits, and even then
                // it drops to the launcher instead of killing the app so the tunnel keeps running.
                BackHandler(enabled = true) {
                    when {
                        qrOf != null -> qrOf = null
                        jsonOf != null -> jsonOf = null
                        addRuleTo != null -> addRuleTo = null
                        showAdd -> showAdd = false
                        subMenu != null -> subMenu = null
                        serverMenu != null -> serverMenu = null
                        page == Page.Routing -> { page = Page.Settings; settingsPage = SettingsPage.Routing }
                        settingsPage != null -> settingsPage = settingsBack.removeLastOrNull()
                        page != Page.Home -> page = Page.Home
                        else -> moveTaskToBack(true)
                    }
                }

                LaunchedEffect(status) {
                    if (status.isNotBlank()) { snackbar.showSnackbar(status); vm.clearStatus() }
                }

                // launchable apps only, loaded off the main thread: querying every launcher
                // activity and decoding its icon takes seconds and used to block first frame
                val installedApps by produceState(initialValue = emptyList<AppEntry>()) {
                    value = withContext(Dispatchers.IO) { loadApps() }
                }

                val servers = state.subscriptions.flatMap { it.servers }
                val selected = servers.firstOrNull { it.raw == state.selectedServerRaw } ?: servers.firstOrNull()
                val collapsed = state.collapsed.toSet()

                // The page background goes behind the insets, not inside them: in landscape the
                // cutout and the nav bar sit on the sides, and a Scaffold-coloured strip there
                // framed the screen in black.
                Box(
                    Modifier.fillMaxSize().background(
                        if (page == Page.Home) Moon.HomeGradient
                        else androidx.compose.ui.graphics.SolidColor(Moon.WinBg)
                    )
                ) {
                Scaffold(
                    containerColor = Color.Transparent,
                    // Top and sides only. The nav is no longer a bottomBar: it floats over the
                    // page, so the page has to run all the way to the bottom of the window for
                    // the content to show through around the card and under the phone's buttons.
                    // What keeps the last row reachable is bottomNavSpace() on each list.
                    contentWindowInsets = WindowInsets.safeDrawing
                        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
                    snackbarHost = {
                        SnackbarHost(snackbar) { data ->
                            Snackbar(
                                snackbarData = data,
                                containerColor = Moon.Card,
                                contentColor = Moon.TextPrimary,
                                actionColor = Moon.AccentText,
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            )
                        }
                    },
                ) { pad ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    // The layout is a phone layout — it was ported from a 420dp-wide desktop
                    // window. Left to fill a tablet it stretches into nonsense: the Добавить /
                    // Вставить column grew until the speed and traffic readouts had no room.
                    // Capping the content and centring it fixes every row at once, and is what
                    // the other clients do on a tablet too.
                    Box(Modifier.padding(pad).widthIn(max = CONTENT_MAX_WIDTH).fillMaxHeight()) {
                        when (page) {
                            Page.Home -> HomeScreen(
                                state = vpn,
                                tunMode = state.tunMode,
                                onTunMode = vm::setProxyMode,
                                server = selected,
                                subscriptions = state.subscriptions,
                                collapsed = collapsed,
                                onToggleCollapse = vm::toggleCollapse,
                                pings = pings,
                                isFavorite = vm::isFavorite,
                                checkPing = checkPing,
                                showSubHeader = state.showSubHeader,
                                upSpeed = up, downSpeed = down, elapsed = elapsed, traffic = traffic,
                                onToggle = ::onToggle,
                                onCheck = { vm.checkConnection() },
                                onAdd = { showAdd = true },
                                onPaste = ::pasteImport,
                                onSelect = vm::selectServer,
                                onSubMenu = { subMenu = it },
                                onServerMenu = { serverMenu = it },
                                onPingSub = { vm.pingSubscription(it.url) },
                                onRefreshSub = { vm.refreshSubscription(it.url) },
                                sortedIn = vm::sortedIn,
                                updateAvailable = updateAvailable,
                                onUpdates = { showUpdate = true },
                                listState = homeScroll,
                            )

                            Page.Servers -> ServersScreen(
                                subscriptions = state.subscriptions,
                                selected = selected,
                                pings = pings,
                                isFavorite = vm::isFavorite,
                                collapsed = collapsed,
                                sort = state.sort,
                                showSubHeader = state.showSubHeader,
                                onSort = vm::setSort,
                                onToggleCollapse = vm::toggleCollapse,
                                onSelect = vm::selectServer,
                                onServerMenu = { serverMenu = it },
                                onSubMenu = { subMenu = it },
                                onPingSub = { vm.pingSubscription(it.url) },
                                onRefreshSub = { vm.refreshSubscription(it.url) },
                                onAdd = { showAdd = true },
                                onPaste = ::pasteImport,
                                onPingAll = { vm.pingAll() },
                                onRefreshAll = { vm.refreshAll() },
                                listState = serversScroll,
                            )

                            Page.Routing -> RoutingScreen(
                                profile = vm.activeRouting(),
                                source = state.routingSource,
                                geoipInfo = vm.geoipInfo(),
                                geositeInfo = vm.geositeInfo(),
                                geoBusy = geoBusy,
                                geoStatus = geoStatus,
                                onBack = { page = Page.Settings; settingsPage = SettingsPage.Routing },
                                onSource = vm::setRoutingSource,
                                onRefreshGeo = { vm.refreshGeo() },
                                onAddRule = { addRuleTo = it },
                                onRemoveRule = vm::removeRule,
                            )

                            Page.Settings -> settingsPage?.let { sp ->
                                SettingsDetail(
                                    page = sp,
                                    state = state,
                                    routing = vm.activeRouting(),
                                    apps = installedApps,
                                    // recomputed whenever the page opens, not once at startup
                                    logsSize = remember(settingsPage) { vm.logsSize() },
                                    xrayVersion = xrayVersion,
                                    onBack = { settingsPage = settingsBack.removeLastOrNull() },
                                    onOpen = { settingsBack.add(sp); settingsPage = it },
                                    onSet = vm::patch,
                                    onRefresh = { vm.refreshAll() },
                                    onRemoveSub = vm::removeSubscription,
                                    onAdd = { showAdd = true },
                                    onOpenRouting = { page = Page.Routing },
                                    onCopy = ::copy,
                                    onResetProxyCreds = { vm.resetProxyCreds() },
                                    onClearLogs = { vm.clearLogs() },
                                )
                            } ?: SettingsScreen(state = state, onOpen = { settingsPage = it })
                        }

                        if (showAdd) AddDialog(
                            onDismiss = { showAdd = false },
                            onConfirm = { url -> vm.addSubscription(url); showAdd = false },
                            onScan = { showAdd = false; scanQr.launch(Unit) },
                        )

                        addRuleTo?.let { bucket ->
                            AddRuleDialog(
                                bucket = bucket,
                                onDismiss = { addRuleTo = null },
                                onConfirm = { v -> vm.addRule(bucket, v); addRuleTo = null },
                            )
                        }

                        subMenu?.let { sub ->
                            SubscriptionSheet(
                                sub = sub,
                                onDismiss = { subMenu = null },
                                onUpdate = { vm.refreshSubscription(sub.url); subMenu = null },
                                onPing = { vm.pingSubscription(sub.url); subMenu = null },
                                onCopy = { copy(sub.url, "Ссылка скопирована"); subMenu = null },
                                onQr = { qrOf = sub.name to sub.url; subMenu = null },
                                onDelete = { vm.removeSubscription(sub.url); subMenu = null },
                            )
                        }

                        serverMenu?.let { s ->
                            ServerSheet(
                                server = s,
                                favorite = vm.isFavorite(s),
                                onDismiss = { serverMenu = null },
                                onConnect = { vm.selectServer(s); serverMenu = null; onToggleIfIdle() },
                                onFavorite = { vm.toggleFavorite(s); serverMenu = null },
                                onPing = { vm.pingServer(s); serverMenu = null },
                                onCopy = { copy(s.raw.orEmpty(), "Ссылка скопирована"); serverMenu = null },
                                onQr = { qrOf = s.label to s.raw.orEmpty(); serverMenu = null },
                                onJson = { jsonOf = s; serverMenu = null },
                            )
                        }

                        jsonOf?.let { s ->
                            JsonDialog(s, onCopy = { copy(it, "Конфигурация скопирована") }) { jsonOf = null }
                        }
                        qrOf?.let { (title, url) -> QrDialog(title, url) { qrOf = null } }

                        

                        if (showUpdate) UpdateDialog(

                            currentVersion = vm.appVersion,

                            release = release,

                            status = updateStatus,

                            available = updateAvailable,

                            onCheck = { vm.checkUpdate() },

                            onDownload = { openUrl(release?.apkUrl ?: release?.pageUrl) },

                            onDismiss = { showUpdate = false },

                        )

                    }

                    // outside the padded Box on purpose: it has to sit over the nav inset too
                    BottomNav(
                        page = page,
                        modifier = Modifier.align(Alignment.BottomCenter),
                        onServers = { page = Page.Servers },
                        onHome = { page = Page.Home },
                        onSettings = { if (page == Page.Settings) settingsPage = null else page = Page.Settings },
                    )
                }
                }   // Scaffold content
                }   // background Box
            }
        }

        handleIntent(intent)
    }

    /**
     * singleTask means a second launch lands here, not in onCreate. Without this the tile and
     * the service's "another VPN holds the slot" bounce did nothing at all whenever the app
     * happened to be running already.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        // a share link opened from the browser / another app
        intent?.dataString?.let { vm.addSubscription(it) }
        if (intent?.getBooleanExtra(EXTRA_CONNECT_NOW, false) == true) {
            intent.removeExtra(EXTRA_CONNECT_NOW)   // or every rotation would reconnect
            // connect, never toggle: the service bounces us here while it is still tearing the
            // old attempt down, and onToggle() would read that as "already on" and disconnect
            val consent = VpnService.prepare(this)
            if (consent != null) vpnPermission.launch(consent) else vm.connect()
        }
    }

    private fun onToggle() {
        when (vm.vpnState.value) {
            // paused keeps the profile, so the moon resumes it instead of dropping everything
            MoonVpnService.Companion.State.Paused -> {
                androidx.core.content.ContextCompat.startForegroundService(
                    this, Intent(this, MoonVpnService::class.java)
                        .setAction(MoonVpnService.ACTION_RECONNECT))
            }
            MoonVpnService.Companion.State.Disconnected -> {
                val prepare: Intent? = VpnService.prepare(this)
                if (prepare != null) vpnPermission.launch(prepare) else vm.connect()
            }
            else -> vm.disconnect()
        }
    }

    /** "Подключиться" from a server sheet — only starts, never toggles off. */
    private fun onToggleIfIdle() {
        if (vm.vpnState.value == MoonVpnService.Companion.State.Disconnected) onToggle()
    }

    /** Everything the user can actually launch, with icons, sorted by name. */
    private fun loadApps(): List<AppEntry> = runCatching {
        val pm = packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(main, 0)
            .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString(),
                            runCatching { it.loadIcon(pm) }.getOrNull()) }
            .filter { it.pkg != packageName }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }.getOrDefault(emptyList())

    private fun pasteImport() {
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cb.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (text.isBlank()) vm.notImplemented("Буфер пуст") else vm.addSubscription(text)
    }

    /**
     * Hands the release off to the browser. The APK goes through the package installer either
     * way, so fetching it ourselves would only add a copy we then have to hand over.
     */
    private fun openUrl(url: String?) {
        if (url.isNullOrBlank()) return
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun copy(text: String, message: String) {
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(android.content.ClipData.newPlainText("moon", text))
        vm.notImplemented(message)
    }
}

/**
 * Bottom nav: Сервера on the left, Настройки on the right and the moon in the middle, the same
 * three the desktop window has.
 *
 * Only the card is painted — everything around it is transparent, so the page keeps scrolling
 * underneath and behind the phone's own back/home buttons. It never hides: a bar that came and
 * went with the scroll direction is exactly what made "Главная" unreachable before.
 */
@Composable
private fun BottomNav(
    page: Page,
    modifier: Modifier = Modifier,
    onServers: () -> Unit,
    onHome: () -> Unit,
    onSettings: () -> Unit,
) {
    Box(
        modifier
            // the cap goes before fillMaxWidth, not after: constraints flow outwards in, so
            // filling first and clamping second leaves the bar full width on a tablet
            .widthIn(max = CONTENT_MAX_WIDTH)
            .fillMaxWidth()
            // the inset next, so the card clears the system buttons instead of sitting on them
            .navigationBarsPadding()
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Surface(
            color = Moon.SidebarBg,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, Moon.BorderSoft),
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NavTab("Сервера", Icons.Filled.Dns, page == Page.Servers, Modifier.weight(1f), onServers)

                // 52dp of moon in a 68dp row left it touching both edges of the card; it has to
                // sit inside with air around it like the two tabs do
                Box(
                    Modifier.padding(horizontal = 10.dp).size(54.dp)
                        .clip(CircleShape)
                        .background(if (page == Page.Home) Moon.Accent.copy(alpha = 0.16f) else Color.Transparent)
                        .clickable(onClick = onHome),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_moon), "Главная",
                        tint = Color.Unspecified,
                        modifier = Modifier.size(42.dp).graphicsLayer { alpha = if (page == Page.Home) 1f else 0.9f },
                    )
                }

                NavTab("Настройки", Icons.Filled.Settings,
                       page == Page.Settings || page == Page.Routing, Modifier.weight(1f), onSettings)
            }
        }
    }
}

/** One side tab. The active one gets a tinted pill behind it instead of only a colour change. */
@Composable
private fun NavTab(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .padding(vertical = 6.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(18.dp))
            .background(if (active) Moon.Accent.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = if (active) Moon.Accent else Moon.TextSecondary, modifier = Modifier.size(21.dp))
        Spacer(Modifier.height(4.dp))
        Text(text, color = if (active) Moon.TextPrimary else Moon.TextSecondary, fontSize = 11.5.sp,
             fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
    }
}

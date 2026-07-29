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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import cc.moon.internet.core.ServerProfile
import cc.moon.internet.core.Subscription
import cc.moon.internet.ui.*
import cc.moon.internet.vpn.MoonVpnService

/** The four pages the desktop window has. */
private enum class Page { Home, Servers, Routing, Settings }

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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

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

                var page by remember { mutableStateOf(Page.Home) }
                // one scroll state per list, so the nav bar can react to the direction of travel
                val homeScroll = rememberLazyListState()
                val serversScroll = rememberLazyListState()
                val activeScroll = if (page == Page.Servers) serversScroll else homeScroll
                val navVisible = rememberNavVisibility(activeScroll, page)
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

                Scaffold(
                    containerColor = Moon.WinBg,
                    // dynamic: 0 while the bars are hidden, real height the moment they return,
                    // so nothing is ever overlapped and nothing reserves dead space
                    contentWindowInsets = WindowInsets.safeDrawing,
                    bottomBar = {
                        // hides on the way down, returns on the way up; the Scaffold shrinks the
                        // content padding with it, so no dead strip is left behind
                        AnimatedVisibility(
                            visible = navVisible,
                            enter = slideInVertically { it } + fadeIn(),
                            exit = slideOutVertically { it } + fadeOut(),
                        ) {
                            BottomNav(
                                page = page,
                                onServers = { page = Page.Servers },
                                onHome = { page = Page.Home },
                                onSettings = { if (page == Page.Settings) settingsPage = null else page = Page.Settings },
                            )
                        }
                    },
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
                    Box(Modifier.padding(pad)) {
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
                                    logsSize = "—",
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

                    }
                }
            }
        }

        // a share link opened from the browser / another app
        intent?.dataString?.let { vm.addSubscription(it) }
        if (intent?.getBooleanExtra(EXTRA_CONNECT_NOW, false) == true) onToggle()
    }

    /**
     * MIUI brings the bars back after a swipe, a permission dialog or the recents screen, and
     * they stay until asked again — so re-hide them every time we regain focus.
     */
    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
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

    private fun copy(text: String, message: String) {
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(android.content.ClipData.newPlainText("moon", text))
        vm.notImplemented(message)
    }
}

/**
 * Bottom nav, matching the desktop window: Сервера on the left, Настройки on the right and the
 * moon in the middle — the moon is a bare image with no caption under it.
 */
@Composable
private fun BottomNav(page: Page, onServers: () -> Unit, onHome: () -> Unit, onSettings: () -> Unit) {
    Surface(color = Moon.SidebarBg) {
        Box {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Moon.BorderSoft))
            Row(
                Modifier.fillMaxWidth().height(70.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NavTab("Сервера", Icons.Filled.Dns, page == Page.Servers, Modifier.weight(1f), onServers)

                Box(
                    Modifier.padding(horizontal = 14.dp).size(60.dp).clickable(onClick = onHome),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_moon), "Главная",
                        tint = Color.Unspecified,
                        modifier = Modifier.size(56.dp).graphicsLayer { alpha = if (page == Page.Home) 1f else 0.92f },
                    )
                }

                NavTab("Настройки", Icons.Filled.Settings,
                       page == Page.Settings || page == Page.Routing, Modifier.weight(1f), onSettings)
            }
        }
    }
}

@Composable
private fun NavTab(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier.fillMaxHeight().clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = if (active) Moon.Accent else Moon.TextSecondary, modifier = Modifier.size(21.dp))
        Spacer(Modifier.height(4.dp))
        Text(text, color = if (active) Moon.TextPrimary else Moon.TextSecondary, fontSize = 11.5.sp,
             fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
    }
}


/**
 * True while the nav bar should be on screen: hidden once the user scrolls down, shown again on
 * any upward movement, and always shown at the very top or bottom of the list.
 *
 * The thresholds matter — reacting to every pixel made the bar flicker, and hiding it at the end
 * of the list left the last row unreachable behind it.
 */
@Composable
private fun rememberNavVisibility(state: LazyListState, page: Page): Boolean {
    if (page != Page.Home && page != Page.Servers) return true
    var visible by remember { mutableStateOf(true) }
    var lastIndex by remember { mutableIntStateOf(0) }
    var lastOffset by remember { mutableIntStateOf(0) }

    LaunchedEffect(state) {
        snapshotFlow {
            Triple(
                state.firstVisibleItemIndex,
                state.firstVisibleItemScrollOffset,
                state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0,
            )
        }.collect { (index, offset, lastVisible) ->
            val total = state.layoutInfo.totalItemsCount
            val atTop = index == 0 && offset < 40
            val atBottom = total > 0 && lastVisible >= total - 1

            val delta = if (index == lastIndex) offset - lastOffset else (index - lastIndex) * 1000
            when {
                atTop || atBottom -> visible = true       // both ends always show the bar
                delta > 24 -> visible = false             // clearly scrolling down
                delta < -24 -> visible = true             // clearly scrolling up
            }
            lastIndex = index; lastOffset = offset
        }
    }
    return visible
}

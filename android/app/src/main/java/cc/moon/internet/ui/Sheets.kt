package cc.moon.internet.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.moon.internet.core.QrCode
import cc.moon.internet.core.ServerProfile
import cc.moon.internet.core.Subscription
import cc.moon.internet.core.XrayConfig
import androidx.compose.ui.res.stringResource
import cc.moon.internet.R

/** The "⋯" menu for a subscription — same four actions as the desktop sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionSheet(
    sub: Subscription,
    onDismiss: () -> Unit,
    onUpdate: () -> Unit,
    onPing: () -> Unit,
    onCopy: () -> Unit,
    onQr: () -> Unit,
    onDelete: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Moon.Card) {
        // Servers added by hand have no address to fetch from, so every line that acts on one is
        // left out rather than offered and then quietly doing nothing: no refresh, no URL to copy,
        // no QR of a URL that does not exist, and no traffic or expiry in the subtitle.
        SheetHeader(sub.name, when {
            sub.isLoose -> stringResource(R.string.fmt_sub_servers, sub.servers.size)
            sub.expiryText == "∞" -> stringResource(R.string.fmt_sub_meta_noexp, sub.servers.size, sub.trafficText)
            else -> stringResource(R.string.fmt_sub_meta, sub.servers.size, sub.trafficText, sub.expiryText)
        })
        if (!sub.isLoose) SheetItem(Icons.Filled.Refresh, stringResource(R.string.settingsscreen_086), onUpdate)
        SheetItem(Icons.Filled.Speed, stringResource(R.string.sheets_001), onPing)
        if (!sub.isLoose) {
            SheetItem(Icons.Filled.ContentCopy, stringResource(R.string.sheets_002), onCopy)
            SheetItem(Icons.Filled.QrCode2, stringResource(R.string.sheets_003), onQr)
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)
            .height(1.dp).background(Moon.BorderSoft))
        // «Мои подключения» is not a subscription: there is nothing to unsubscribe from, only a
        // pile of hand-pasted links to throw away. The word has to say which.
        SheetItem(
            Icons.Filled.Delete,
            stringResource(if (sub.isLoose) R.string.sheets_delete_all else R.string.settingsscreen_084),
            onDelete, Moon.Danger,
        )
        Spacer(Modifier.height(20.dp))
    }
}

/** The "⋯" menu for one server. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSheet(
    server: ServerProfile,
    favorite: Boolean,
    onDismiss: () -> Unit,
    onConnect: () -> Unit,
    onFavorite: () -> Unit,
    onPing: () -> Unit,
    /** «Стабильность» for this one server — see the view model. */
    onStability: () -> Unit,
    onCopy: () -> Unit,
    onQr: () -> Unit,
    onJson: () -> Unit,
    /**
     * Only for a server added by hand. One that came from a subscription would be back on the next
     * refresh, so the line is left out rather than offered and then undone behind the user's back —
     * the same rule this file already follows for what a hand-made group cannot do.
     */
    onDelete: (() -> Unit)? = null,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Moon.Card) {
        SheetHeader(server.label, "${server.protocolLabel} · ${server.address}:${server.port}")
        SheetItem(Icons.Filled.PlayArrow, stringResource(R.string.sheets_004), onConnect)
        SheetItem(
            if (favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
            if (favorite) stringResource(R.string.sheets_005) else stringResource(R.string.sheets_006),
            onFavorite,
        )
        SheetItem(Icons.Filled.Speed, stringResource(R.string.sheets_001), onPing)
        // The one check that proves the server carries traffic. It was reachable only by switching
        // the global ping method in settings and switching back, which is the whole complaint.
        SheetItem(Icons.Filled.NetworkCheck, stringResource(R.string.sheets_stability), onStability)
        SheetItem(Icons.Filled.Code, stringResource(R.string.sheets_007), onJson)
        SheetItem(Icons.Filled.ContentCopy, stringResource(R.string.sheets_002), onCopy)
        SheetItem(Icons.Filled.QrCode2, stringResource(R.string.sheets_003), onQr)
        if (onDelete != null) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)
                .height(1.dp).background(Moon.BorderSoft))
            SheetItem(Icons.Filled.Delete, stringResource(R.string.settingsscreen_084), onDelete, Moon.Danger)
        }
        Spacer(Modifier.height(20.dp))
    }
}

/**
 * What this server's config actually is, read-only, with a copy button.
 *
 * For AmneziaWG that is its own [Interface]/[Peer] document rather than an xray outbound: ours is
 * a SOCKS dial into the tunnel on loopback, so the dialog used to answer "what is my server?"
 * with 127.0.0.1:1091, which tells you nothing. The config is what the official client shows.
 */
@Composable
fun JsonDialog(server: ServerProfile, onCopy: (String) -> Unit, onDismiss: () -> Unit) {
    val errFmt = stringResource(R.string.fmt_config_error)
    val json = remember(server, errFmt) {
        server.raw?.takeIf { server.protocol == cc.moon.internet.core.Protocol.WIREGUARD }
            ?.let { cc.moon.internet.core.WireGuardParser.configText(it) }
            // Hysteria2 likewise: the outbound sing-box runs, not xray's SOCKS dial into it.
            ?: server.takeIf { it.protocol == cc.moon.internet.core.Protocol.HYSTERIA2 }
                ?.let { cc.moon.internet.core.SingBoxHy2Config.outbound(it).toString(2) }
            ?: runCatching { XrayConfig.buildOutbound(server).toString(2) }.getOrElse { errFmt.format(it.message) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(server.label, color = Moon.TextPrimary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Surface(shape = RoundedCornerShape(10.dp), color = Moon.WinBg, modifier = Modifier.fillMaxWidth()) {
                Text(
                    json,
                    Modifier.padding(12.dp).heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),
                    color = Moon.TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp,
                )
            }
        },
        confirmButton = { TextButton({ onCopy(json) }) { Text(stringResource(R.string.sheets_008)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.sheets_009), color = Moon.TextSecondary) } },
        containerColor = Moon.Card,
    )
}

/** QR of a share link / subscription URL, drawn from our own encoder — no library needed. */
@Composable
fun QrDialog(title: String, url: String, onDismiss: () -> Unit) {
    val matrix = remember(url) { runCatching { QrCode.encode(url) }.getOrNull() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = Moon.TextPrimary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                if (matrix == null) {
                    Text(stringResource(R.string.sheets_010), color = Moon.TextSecondary, fontSize = 12.sp)
                } else {
                    Surface(color = Color.White, shape = RoundedCornerShape(10.dp)) {
                        Canvas(Modifier.padding(12.dp).size(240.dp)) {
                            val n = matrix.size
                            val cell = size.width / n
                            for (y in 0 until n) for (x in 0 until n) {
                                if (matrix[y][x]) drawRect(
                                    Color.Black,
                                    topLeft = Offset(x * cell, y * cell),
                                    size = Size(cell, cell),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(url, color = Moon.TextMuted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.sheets_009)) } },
        containerColor = Moon.Card,
    )
}

@Composable
internal fun SheetHeader(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 10.dp)) {
        Text(title, color = Moon.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
             maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, color = Moon.TextSecondary, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun SheetItem(icon: ImageVector, text: String, onClick: () -> Unit, tint: Color = Moon.TextPrimary) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(20.dp, 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(16.dp))
        Text(text, color = tint, fontSize = 14.sp)
    }
}


/**
 * Обновление — the dialog behind the tile on Home. Mirrors the desktop overlay: installed and
 * published versions, what changed, and a download that hands off to the browser.
 */
/**
 * "This server needs an engine you do not have."
 *
 * Asked before dialling rather than after: without it the attempt failed somewhere inside the core,
 * against a port nobody had opened, and the message that came back said nothing anybody could act
 * on. Отмена leaves everything as it was and does not connect.
 */
@Composable
fun CoreNeededDialog(
    coreTitle: String,
    protocol: String,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Moon.Card,
        shape = RoundedCornerShape(16.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Memory, null, tint = Moon.AccentText, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.cores_need_title), color = Moon.TextPrimary,
                     fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Text(stringResource(R.string.cores_need_body, protocol, coreTitle),
                 color = Moon.TextSecondary, fontSize = 13.sp, lineHeight = 18.sp)
        },
        confirmButton = {
            TextButton(onClick = onInstall) {
                Text(stringResource(R.string.cores_install), color = Moon.Accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settingsscreen_200), color = Moon.TextSecondary)
            }
        },
    )
}

/**
 * The screen that cannot be touched while an engine is being put in place.
 *
 * Blocking because it is a download measured in megabytes and starting a second one halfway
 * through is not a thing to allow. Not because anything restarts — the engine is a file in the
 * app's own data, so nothing is swapped out underneath the app while this is up.
 */
@Composable
fun CoreInstallingOverlay(percent: Int) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xE60D0A18))
            // Swallows everything, including the taps behind it.
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Determinate as soon as anything has arrived: this is megabytes over somebody's
            // mobile data, and a spinner that could mean five seconds or fifty is what makes
            // people force-quit halfway.
            if (percent > 0) {
                CircularProgressIndicator(progress = { percent / 100f },
                                          color = Moon.Accent, strokeWidth = 3.dp)
            } else {
                CircularProgressIndicator(color = Moon.Accent, strokeWidth = 3.dp)
            }
            Text(stringResource(R.string.cores_installing), color = Moon.TextPrimary,
                 fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                 modifier = Modifier.padding(top = 16.dp))
            Text(if (percent > 0) "$percent %" else stringResource(R.string.cores_wait),
                 color = Moon.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
fun UpdateDialog(
    currentVersion: String,
    release: cc.moon.internet.data.ReleaseInfo?,
    status: String,
    available: Boolean,
    progress: Int?,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Moon.Card,
        shape = RoundedCornerShape(16.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.FileDownload, null, tint = Moon.AccentText, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.sheets_011), color = Moon.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column {
                Row {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.sheets_012), color = Moon.TextSecondary, fontSize = 11.5.sp)
                        Text(currentVersion, color = Moon.TextPrimary, fontSize = 15.sp,
                             fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.sheets_013), color = Moon.TextSecondary, fontSize = 11.5.sp)
                        Text(release?.version ?: "—", color = Moon.TextPrimary, fontSize = 15.sp,
                             fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp))
                    }
                }
                progress?.let { p ->
                    if (p >= 0) LinearProgressIndicator({ p / 100f }, Modifier.fillMaxWidth().padding(top = 12.dp),
                                                        color = Moon.Accent, trackColor = Moon.BorderSoft)
                    else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp),
                                                 color = Moon.Accent, trackColor = Moon.BorderSoft)
                }
                if (status.isNotBlank()) {
                    Text(status, color = Moon.AccentText, fontSize = 12.5.sp,
                         modifier = Modifier.padding(top = 12.dp))
                }
                if (available && !release?.notes.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp), color = Color(0xFF0B0916),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2A2150)),
                        modifier = Modifier.padding(top = 12.dp).heightIn(max = 240.dp),
                    ) {
                        ReleaseNotesText(
                            release!!.notes,
                            Modifier.verticalScroll(rememberScrollState()).padding(14.dp, 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (available) {
                TextButton(onClick = onDownload, enabled = progress == null) {
                    Text(stringResource(R.string.sheets_014), color = Moon.Accent, fontWeight = FontWeight.SemiBold)
                }
            } else {
                TextButton(onClick = onCheck) { Text(stringResource(R.string.sheets_015), color = Moon.Accent) }
            }
        },
        dismissButton = {
            if (available) TextButton(onClick = onCheck) { Text(stringResource(R.string.sheets_015), color = Moon.TextSecondary) }
            else TextButton(onClick = onDismiss) { Text(stringResource(R.string.sheets_009), color = Moon.TextSecondary) }
        },
    )
}


/**
 * Log viewer. Same idea as the desktop overlay: the tail of the core's log, with a reload and a
 * copy. Read-only and selectable, because the usual next step is pasting it somewhere.
 */
@Composable
fun LogViewerDialog(
    title: String,
    text: String,
    onReload: () -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Not an AlertDialog: that caps its width at about 280dp, and a log line is 120 characters —
    // you could scroll sideways forever and still read a sliver at a time. The desktop gives the
    // log the whole card, so here it gets the whole screen.
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp), color = Moon.Card,
            border = androidx.compose.foundation.BorderStroke(1.dp, Moon.BorderSoft),
            modifier = Modifier.fillMaxSize().padding(10.dp),
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Description, null, tint = Moon.AccentText, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(title, color = Moon.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                         maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onDismiss, Modifier.size(30.dp)) {
                        Icon(Icons.Filled.Close, stringResource(R.string.sheets_009),
                             tint = Moon.TextSecondary, modifier = Modifier.size(18.dp))
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp), color = Color(0xFF0B0916),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2A2150)),
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 12.dp),
                ) {
                    SelectionContainer {
                        Text(
                            text.ifBlank { stringResource(R.string.sheets_016) },
                            color = Color(0xFFCFC7EC), fontSize = 10.sp, lineHeight = 14.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState())
                                .padding(12.dp),
                        )
                    }
                }

                // Buttons that look like buttons. Bare accent text reads as a caption — the
                // desktop puts two secondary buttons and a primary one under the log, so does this.
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    LogButton(stringResource(R.string.settingsscreen_086), onReload)
                    Spacer(Modifier.width(8.dp))
                    LogButton(stringResource(R.string.sheets_008), onCopy)
                    Spacer(Modifier.width(8.dp))
                    LogButton(stringResource(R.string.sheets_009), onDismiss, primary = true)
                }
            }
        }
    }
}

/** The desktop's SecondaryButton / PrimaryButton pair, for the log viewer's footer. */
@Composable
private fun LogButton(text: String, onClick: () -> Unit, primary: Boolean = false) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (primary) Moon.Accent else Moon.ChipBg,
        border = if (primary) null else androidx.compose.foundation.BorderStroke(1.dp, Moon.BorderSoft),
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
            color = if (primary) Color.White else Moon.TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * The release notes laid out rather than dumped.
 *
 * GitHub sends Markdown and we were putting the literal `##` and `**` on screen, which reads far
 * worse here than the same text does on the release page. The parsing lives in core so the desktop
 * and this one cannot drift apart about the same text.
 */
@Composable
fun ReleaseNotesText(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { cc.moon.internet.core.ReleaseNotes.parse(markdown) }
    Column(modifier) {
        blocks.forEach { b ->
            when (b.kind) {
                cc.moon.internet.core.NoteKind.HEADING -> Text(
                    b.text, color = Moon.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
                )
                cc.moon.internet.core.NoteKind.GROUP -> Text(
                    b.text, color = Moon.AccentText, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 9.dp, bottom = 3.dp),
                )
                // The dash is put back rather than kept, so every bullet lines up under the same
                // one whichever of the three the notes happened to use.
                cc.moon.internet.core.NoteKind.BULLET -> Text(
                    "–  ${b.text}", color = Color(0xFFCFC7EC), fontSize = 12.sp, lineHeight = 18.sp,
                    modifier = Modifier.padding(start = 6.dp, bottom = 4.dp),
                )
                else -> Text(
                    b.text, color = Color(0xFFCFC7EC), fontSize = 12.sp, lineHeight = 18.sp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
    }
}

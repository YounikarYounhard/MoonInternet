package cc.moon.internet.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
        SheetHeader(sub.name, "${sub.servers.size} серверов · ${sub.trafficText} · ${sub.expiryText}")
        SheetItem(Icons.Filled.Refresh, "Обновить", onUpdate)
        SheetItem(Icons.Filled.Speed, "Пинговать", onPing)
        SheetItem(Icons.Filled.ContentCopy, "Копировать URL", onCopy)
        SheetItem(Icons.Filled.QrCode2, "Показать QR-код", onQr)
        Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)
            .height(1.dp).background(Moon.BorderSoft))
        SheetItem(Icons.Filled.Delete, "Удалить", onDelete, Moon.Danger)
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
    onCopy: () -> Unit,
    onQr: () -> Unit,
    onJson: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Moon.Card) {
        SheetHeader(server.label, "${server.protocolLabel} · ${server.address}:${server.port}")
        SheetItem(Icons.Filled.PlayArrow, "Подключиться", onConnect)
        SheetItem(
            if (favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
            if (favorite) "Убрать из избранного" else "В избранное",
            onFavorite,
        )
        SheetItem(Icons.Filled.Speed, "Пинговать", onPing)
        SheetItem(Icons.Filled.Code, "Конфигурация", onJson)
        SheetItem(Icons.Filled.ContentCopy, "Копировать URL", onCopy)
        SheetItem(Icons.Filled.QrCode2, "Показать QR-код", onQr)
        Spacer(Modifier.height(20.dp))
    }
}

/** Read-only outbound JSON — the same thing the desktop menu shows, with a copy button. */
@Composable
fun JsonDialog(server: ServerProfile, onCopy: (String) -> Unit, onDismiss: () -> Unit) {
    val json = remember(server) {
        runCatching { XrayConfig.buildOutbound(server).toString(2) }.getOrElse { "— ошибка: ${it.message}" }
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
        confirmButton = { TextButton({ onCopy(json) }) { Text("Копировать") } },
        dismissButton = { TextButton(onDismiss) { Text("Закрыть", color = Moon.TextSecondary) } },
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
                    Text("Не удалось построить QR-код", color = Moon.TextSecondary, fontSize = 12.sp)
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
        confirmButton = { TextButton(onDismiss) { Text("Закрыть") } },
        containerColor = Moon.Card,
    )
}

@Composable
private fun SheetHeader(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 10.dp)) {
        Text(title, color = Moon.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
             maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, color = Moon.TextSecondary, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SheetItem(icon: ImageVector, text: String, onClick: () -> Unit, tint: Color = Moon.TextPrimary) {
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
@Composable
fun UpdateDialog(
    currentVersion: String,
    release: cc.moon.internet.data.ReleaseInfo?,
    status: String,
    available: Boolean,
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
                Text("Обновление", color = Moon.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column {
                Row {
                    Column(Modifier.weight(1f)) {
                        Text("Установлена", color = Moon.TextSecondary, fontSize = 11.5.sp)
                        Text(currentVersion, color = Moon.TextPrimary, fontSize = 15.sp,
                             fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text("На GitHub", color = Moon.TextSecondary, fontSize = 11.5.sp)
                        Text(release?.version ?: "—", color = Moon.TextPrimary, fontSize = 15.sp,
                             fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp))
                    }
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
                        Text(release!!.notes, color = Color(0xFFCFC7EC), fontSize = 12.sp, lineHeight = 18.sp,
                             modifier = Modifier.verticalScroll(rememberScrollState()).padding(14.dp, 12.dp))
                    }
                }
            }
        },
        confirmButton = {
            if (available) {
                TextButton(onClick = onDownload) {
                    Text("Скачать", color = Moon.Accent, fontWeight = FontWeight.SemiBold)
                }
            } else {
                TextButton(onClick = onCheck) { Text("Проверить", color = Moon.Accent) }
            }
        },
        dismissButton = {
            if (available) TextButton(onClick = onCheck) { Text("Проверить", color = Moon.TextSecondary) }
            else TextButton(onClick = onDismiss) { Text("Закрыть", color = Moon.TextSecondary) }
        },
    )
}

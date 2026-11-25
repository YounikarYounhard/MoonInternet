package cc.moon.internet.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cc.moon.internet.R
import cc.moon.internet.data.DevLog
import cc.moon.internet.data.SupportDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ISSUE_URL = "https://github.com/YounikarYounhard/MoonInternet/issues/new"

@Composable
internal fun SupportDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var description by rememberSaveable { mutableStateOf("") }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var includeDiagnostics by rememberSaveable { mutableStateOf(true) }
    var readingImage by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Int?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { selected ->
        if (selected != null) {
            readingImage = true
            scope.launch {
                val thumbnail = withContext(Dispatchers.IO) { loadPreview(context, selected) }
                if (thumbnail == null) notice = R.string.support_invalid_screenshot
                else {
                    preview = thumbnail
                    notice = null
                }
                readingImage = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_support)) },
        containerColor = Moon.Card,
        titleContentColor = Moon.TextPrimary,
        textContentColor = Moon.TextPrimary,
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = description,
                    onValueChange = { if (it.length <= 3000) description = it },
                    label = { Text(stringResource(R.string.support_description)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 5,
                )
                TextButton(
                    onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = !readingImage && !preparing,
                ) { Text(stringResource(R.string.support_choose_screenshot)) }
                preview?.let { image ->
                    Image(image.asImageBitmap(), stringResource(R.string.support_screenshot),
                        modifier = Modifier.fillMaxWidth().height(100.dp))
                    TextButton(
                        onClick = { preview = null; notice = null }, enabled = !preparing,
                    ) { Text(stringResource(R.string.support_remove_screenshot)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = includeDiagnostics, onCheckedChange = { includeDiagnostics = it })
                    Text(stringResource(R.string.support_include_diagnostics))
                }
                Text(stringResource(R.string.support_public), color = Moon.TextSecondary)
                Text(stringResource(R.string.support_browser_hint), color = Moon.TextSecondary)
                if (readingImage || preparing) {
                    Text(stringResource(R.string.support_preparing))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                notice?.let { Text(stringResource(it), color = Moon.AccentText) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !readingImage && !preparing && (description.isNotBlank() || preview != null),
                onClick = {
                    val hasScreenshot = preview != null
                    val includeEvents = includeDiagnostics
                    val message = description.trim().ifEmpty { context.getString(R.string.support_screenshot_only) }
                    preparing = true
                    notice = null
                    scope.launch {
                        try {
                            val events = if (includeEvents) withContext(Dispatchers.IO) {
                                SupportDiagnostics.summarize(DevLog.lines.value.asReversed())
                            } else ""
                            val body = buildString {
                                append(message)
                                append("\n\nMoon Internet $APP_VERSION · Android ${Build.VERSION.RELEASE}")
                                if (events.isNotEmpty()) {
                                    append("\n\n### ${context.getString(R.string.support_diagnostics)}")
                                    append("\n\n~~~\n$events\n~~~")
                                }
                            }
                            val clip = ClipData.newPlainText(context.getString(R.string.settings_support), body)
                            if (Build.VERSION.SDK_INT >= 33) {
                                clip.description.extras = PersistableBundle().apply {
                                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                                }
                            }
                            runCatching {
                                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                                uriHandler.openUri(ISSUE_URL)
                            }.onSuccess {
                                notice = if (hasScreenshot) R.string.support_paste_with_screenshot
                                    else R.string.support_paste
                            }.onFailure {
                                notice = R.string.settings_support_failed
                            }
                        } finally {
                            preparing = false
                        }
                    }
                },
            ) { Text(stringResource(R.string.support_open_issue)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_restart_cancel)) }
        },
    )
}

private fun loadPreview(context: Context, uri: Uri): Bitmap? = runCatching {
    val resolver = context.contentResolver
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    val longestEdge = maxOf(options.outWidth, options.outHeight)
    if (longestEdge <= 0) return null
    options.inSampleSize = Integer.highestOneBit((longestEdge / 360).coerceAtLeast(1))
    options.inJustDecodeBounds = false
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
}.getOrNull()

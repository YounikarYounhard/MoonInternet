package cc.moon.internet.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a release APK and hands it to the package installer.
 *
 * Android will not let an app install anything silently — the system installer asks, and on
 * Android 8+ the user also has to allow "install unknown apps" for us once. Both of those are
 * screens the user confirms; all we can do is get the file there and open the right screen.
 *
 * The file goes to the app's own cache: no storage permission, and the system clears it if space
 * runs short. We delete it ourselves as soon as the new version starts — see [cleanUp].
 */
object ApkInstaller {

    private fun dir(ctx: Context) = File(ctx.cacheDir, "updates").apply { mkdirs() }

    /** Progress in percent, or -1 when the length is unknown. */
    suspend fun download(ctx: Context, url: String, onProgress: (Int) -> Unit): File? =
        withContext(Dispatchers.IO) {
            runCatching {
                val out = File(dir(ctx), "moon-update.apk")
                out.delete()
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    setRequestProperty("User-Agent", "MoonInternet")
                    instanceFollowRedirects = true
                    connectTimeout = 20_000
                    readTimeout = 60_000
                }
                val total = c.contentLength.toLong()
                c.inputStream.use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var got = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            got += n
                            onProgress(if (total > 0) ((got * 100) / total).toInt() else -1)
                        }
                    }
                }
                c.disconnect()
                out.takeIf { it.length() > 0 }
            }.getOrNull()
        }

    /** True if the installer screen opened. False means the user has to allow it first. */
    fun install(ctx: Context, apk: File): Boolean = runCatching {
        val uri: Uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".updates", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrElse { false }

    /** Android 8+ gates installing from us behind a per-app switch; this opens that screen. */
    fun canInstall(ctx: Context): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O ||
            ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) = runCatching {
        ctx.startActivity(
            Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                   Uri.parse("package:" + ctx.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.isSuccess

    /**
     * Called at every launch. If we are running a build the downloaded file was meant to produce,
     * the file has done its job — and even if the install was abandoned, a stale APK in the cache
     * is nothing but dead weight.
     */
    fun cleanUp(ctx: Context) = runCatching { dir(ctx).listFiles()?.forEach { it.delete() } }
}

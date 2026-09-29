package cc.moon.internet.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Properties

/** One engine as the catalogue describes it. */
data class CoreRelease(
    val id: String,
    val version: String,
    val file: String,
    val size: Long,
    val sha256: String,
    val url: String,
)

/**
 * The engines that are not part of the app: where they live, how they arrive, what version is here.
 *
 * They live in the app's own data directory, which is the whole reason this works the way it does.
 * That directory is untouched by an app update and belongs to no version code, so an engine
 * installed once stays installed — and when a new build wants a newer engine, [outdated] says so
 * and it is fetched again. Nothing here installs a package or asks the user to approve anything:
 * an engine is a file.
 *
 * Downloaded code is checked against the digest in the catalogue before it is ever loaded. That is
 * not optional — without it, anything that can answer for the URL can hand the app code to run.
 */
object CoreStore {

    /**
     * The catalogue: one small file in the repository, listing every engine and where to fetch it.
     *
     * The download URL lives in the catalogue rather than being built here, so the binaries can sit
     * in the repository today and move to release assets later without touching the app.
     */
    private const val CATALOG =
        "https://raw.githubusercontent.com/YounikarYounhard/MoonInternet/master/cores-catalog/android-arm64.json"

    private const val VERSIONS = "versions.properties"

    fun dir(ctx: Context): File = File(ctx.filesDir, "cores").apply { mkdirs() }

    /** Where this engine's library is, or null for one that is part of the app. */
    fun fileOf(ctx: Context, core: MoonCore): File? = core.binary?.let { File(dir(ctx), it) }

    fun have(ctx: Context, core: MoonCore): Boolean = fileOf(ctx, core)?.exists() ?: true

    fun versionOf(ctx: Context, id: String): String? = versions(ctx).getProperty(id)

    /** True when the engine is here but older than the build expects — the after-an-update case. */
    fun outdated(ctx: Context, core: MoonCore, want: String): Boolean =
        have(ctx, core) && versionOf(ctx, core.id) != want

    suspend fun catalogue(): List<CoreRelease> = withContext(Dispatchers.IO) {
        runCatching {
            val body = open(CATALOG).use { it.bufferedReader().readText() }
            val arr = JSONObject(body).getJSONArray("cores")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                CoreRelease(
                    id = o.getString("id"),
                    version = o.getString("version"),
                    file = o.getString("file"),
                    size = o.optLong("size"),
                    sha256 = o.getString("sha256").lowercase(),
                    url = o.getString("url"),
                )
            }
        }.getOrElse {
            // Said out loud. A catalogue that cannot be read means nothing installs and nothing
            // updates, and swallowing that leaves an engine quietly missing with no trace of why.
            cc.moon.internet.data.DevLog.log("ядро", "список ядер не читается: ${it.message ?: it.javaClass.simpleName}")
            emptyList()
        }
    }

    /**
     * Fetches an engine and puts it in place. Null when it worked.
     *
     * Downloaded beside the target and renamed into it, so an install that is interrupted leaves
     * the previous engine working rather than half of a new one.
     */
    suspend fun install(ctx: Context, rel: CoreRelease, onProgress: (Int) -> Unit = {}): String? =
        withContext(Dispatchers.IO) {
            val target = File(dir(ctx), rel.file)
            val part = File(dir(ctx), rel.file + ".part")
            runCatching {
                val digest = MessageDigest.getInstance("SHA-256")
                open(rel.url).use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            digest.update(buf, 0, n)
                            out.write(buf, 0, n)
                            done += n
                            if (rel.size > 0) onProgress(((done * 100) / rel.size).toInt().coerceIn(0, 100))
                        }
                    }
                }
                val got = digest.digest().joinToString("") { "%02x".format(it) }
                if (got != rel.sha256) {
                    part.delete()
                    return@withContext "Ядро скачалось повреждённым"
                }
                // The engine in place is read-only, so it cannot simply be replaced — the rename
                // would be denied. (Learned the hard way: the second install failed with EACCES.)
                if (target.exists()) { target.setWritable(true); target.delete() }
                if (!part.renameTo(target)) return@withContext "Ядро не удалось положить на место"
                target.setReadOnly()
                versions(ctx).let { p ->
                    p.setProperty(rel.id, rel.version)
                    File(dir(ctx), VERSIONS).outputStream().use { p.store(it, null) }
                }
                null
            }.getOrElse {
                part.delete()
                "Ядро не скачалось: ${it.message ?: it.javaClass.simpleName}"
            }
        }

    fun remove(ctx: Context, core: MoonCore) {
        fileOf(ctx, core)?.let { it.setWritable(true); it.delete() }
        versions(ctx).let { p ->
            p.remove(core.id)
            runCatching { File(dir(ctx), VERSIONS).outputStream().use { s -> p.store(s, null) } }
        }
    }

    private fun versions(ctx: Context): Properties = Properties().apply {
        val f = File(dir(ctx), VERSIONS)
        if (f.exists()) runCatching { f.inputStream().use { load(it) } }
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        setRequestProperty("User-Agent", "MoonInternet")
        connectTimeout = 15_000
        readTimeout = 30_000
    }.inputStream
}

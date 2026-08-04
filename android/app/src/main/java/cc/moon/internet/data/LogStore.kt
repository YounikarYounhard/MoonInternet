package cc.moon.internet.data

import android.content.Context
import java.io.File

/**
 * The core's log file on disk.
 *
 * Until now the Логи page was decorative: the size was a hardcoded dash and "Очистить" only
 * printed a message, because nothing was ever written. The core does write a file when its
 * config names one — this is that path, plus the housekeeping the page promises.
 */
object LogStore {

    fun file(ctx: Context): File =
        File(ctx.filesDir, "logs").apply { mkdirs() }.let { File(it, "xray.log") }

    /** Human-readable size for the settings row, or a dash when there is nothing yet. */
    fun size(ctx: Context): String {
        val f = file(ctx)
        val bytes = if (f.exists()) f.length() else 0L
        if (bytes == 0L) return "—"
        // one formatter for the whole app, so the units follow the language switch here too
        return SubscriptionService.size(bytes)
    }

    fun clear(ctx: Context) {
        runCatching { file(ctx).writeText("") }
    }

    /** Last [lines] lines, newest last — what the log viewer shows. */
    fun tail(ctx: Context, lines: Int = 500): String {
        val f = file(ctx)
        if (!f.exists()) return ""
        return runCatching { f.readLines().takeLast(lines).joinToString("\n") }.getOrDefault("")
    }

    /**
     * Drops the file once it is older than the retention the user picked, and caps it so a debug
     * level left on overnight cannot fill the phone. Called at startup, before the core opens it.
     */
    fun prune(ctx: Context, keepDays: Int, maxBytes: Long = 8L * 1024 * 1024) {
        val f = file(ctx)
        if (!f.exists()) return
        val tooBig = f.length() > maxBytes
        val tooOld = keepDays > 0 &&
            System.currentTimeMillis() - f.lastModified() > keepDays * 24L * 3600_000
        if (tooBig || tooOld) runCatching { f.writeText("") }
    }
}

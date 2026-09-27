package cc.moon.internet.data

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the app did, and why.
 *
 * Written for one question in particular — "it changes servers by itself" — which no amount of
 * reading the code answers on its own, because the answer is a sequence of events on somebody
 * else's phone. Every line that moves the selection, raises the tunnel or drops it says so here
 * with its reason attached.
 *
 * Two outputs, one call. The last [CAP] lines stay in memory for the Отладка screen, and each one
 * also goes to logcat under [TAG], so a phone on a cable can be read with
 * `adb logcat -s MoonDev:I` without its owner doing anything at all.
 *
 * Always recording, whatever the developer switch says. That switch only decides whether the
 * screen is reachable — gating the recording itself would mean the one time you want the history
 * is the one time nobody kept it. It is a few hundred short strings; the app holds subscriptions
 * far larger without noticing.
 */
object DevLog {
    const val TAG = "MoonDev"
    private const val CAP = 300
    private const val FILE_CAP = 512L * 1024

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines = _lines.asStateFlow()

    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var file: File? = null

    /**
     * Point the journal at a file. Memory and logcat both forget: the app is restarted, the ring
     * buffer rolls, and the one event worth catching — a server changing on its own — can be days
     * away. A file outlives all of that.
     *
     * [Store] hands us the EXTERNAL files dir on purpose. The build on the phone is signed for
     * release, so `run-as` is refused and nothing under the private data dir can be read off a
     * cable; /sdcard/Android/data/<pkg>/files is reachable with a plain `adb pull`.
     */
    @Synchronized
    fun attach(dir: File) {
        if (file != null) return
        val f = File(dir, "dev.log")
        file = f
        runCatching { if (f.exists()) _lines.value = f.readLines().takeLast(CAP) }
        // Mark the restart. Half of "it changed servers by itself" is really "it was started
        // again", and without this line the two read identically.
        log("запуск", "приложение открыто")
    }

    /** [topic] is the part of the app speaking ("сервер", "туннель"), [message] what it did. */
    @Synchronized
    fun log(topic: String, message: String) {
        val line = "${clock.format(Date())}  $topic: $message"
        Log.i(TAG, line)
        _lines.value = (_lines.value + line).takeLast(CAP)
        // Appending one short line, on whatever thread called us. A journal that has to be posted
        // somewhere would reorder itself, and these lines are only worth reading in order.
        file?.let { f ->
            runCatching {
                if (f.length() > FILE_CAP) f.writeText("")   // start over; this is a tail, not an archive
                f.appendText(line + "\n")
            }
        }
    }

    @Synchronized
    fun clear() {
        _lines.value = emptyList()
        runCatching { file?.writeText("") }
    }

    fun dump(): String = _lines.value.joinToString("\n")
}

package cc.moon.internet.data

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines = _lines.asStateFlow()

    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** [topic] is the part of the app speaking ("сервер", "туннель"), [message] what it did. */
    @Synchronized
    fun log(topic: String, message: String) {
        val line = "${clock.format(Date())}  $topic: $message"
        Log.i(TAG, line)
        _lines.value = (_lines.value + line).takeLast(CAP)
    }

    @Synchronized
    fun clear() {
        _lines.value = emptyList()
    }

    fun dump(): String = _lines.value.joinToString("\n")
}

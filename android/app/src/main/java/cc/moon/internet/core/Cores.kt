package cc.moon.internet.core

import android.content.Context
import java.io.File

/**
 * The engines the app can run, and which of them are actually here.
 *
 * Not every protocol needs every engine, and two of them are most of the download: sing-box is 20 MB
 * for Hysteria2 alone and awgproxy 6 MB for AmneziaWG alone, against an app that is otherwise about
 * four. So an engine is something you have or do not have, and the app says so rather than pretending
 * a protocol is broken.
 *
 * "Here" means the binary is in the native library directory. That is the only place Android will
 * execute anything from — a file downloaded into the app's own storage cannot be run at all since
 * Android 10 — and it is filled by the installer from the APK and from any split APK added later.
 * Which is why the same check keeps working once the engines move out into splits: nothing about
 * this file changes, the binary simply arrives later.
 */
enum class MoonCore(
    val id: String,
    /** How it is written on the Ядра page. Product names, the same in every language. */
    val title: String,
    /** The file the installer puts in the native library directory, or null when it is a library. */
    val binary: String?,
    /** Always here. Ships with the app and cannot be removed, because something needs it. */
    val builtIn: Boolean = false,
) {
    // Declaration order is the order of the Ядра page, and the built-in ones come first because
    // the page shows them as their own group: what the app carries, then what can be added to it.
    /** The core everything else is built around; a library inside the APK, never separate. */
    Xray("xray", "Xray", null, builtIn = true),
    /**
     * Built in despite its 20 MB: Hysteria2 is in very nearly every subscription, so an install
     * without it is an install that cannot connect to half of what it is given.
     */
    SingBox("singbox", "sing-box", "libsingbox.so", builtIn = true),
    /**
     * First of the optional ones: Запрет is not another way to reach a server, it is a feature of
     * its own — no server, no subscription, nothing to choose between.
     */
    Zapret("zapret", "Zapret", "libbyedpi.so"),
    Amnezia("awg", "AmneziaWG", "libawgproxy.so"),
    ;

    /** True when the engine's binary is on the device and can be executed. */
    fun present(ctx: Context): Boolean =
        binary == null || File(ctx.applicationInfo.nativeLibraryDir, binary).exists()

    companion object {
        /**
         * The engine this server needs beyond what ships with the app, or null when it is already
         * covered.
         *
         * Only AmneziaWG, and only when the config really is obfuscated: plain WireGuard is one of
         * xray's own protocols, so awgproxy is needed for the obfuscation and nothing else.
         */
        fun forServer(s: ServerProfile): MoonCore? =
            if (s.wg?.isAmnezia == true) Amnezia else null

        /** Engines the user chooses to have; everything else ships with the app. */
        val optional = entries.filter { !it.builtIn }

        fun byId(id: String): MoonCore? = entries.firstOrNull { it.id == id }
    }
}

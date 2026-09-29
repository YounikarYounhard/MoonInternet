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
 * The optional ones are downloaded after the app is installed, into the app's own data directory,
 * and loaded as shared libraries. That is not a detail — it is the whole shape of this. Since
 * Android 10 an app may not *run* a file it wrote into its data directory, but it may *load* one,
 * so an engine that can be downloaded is a library with an entry point rather than a program. In
 * exchange the engine is an ordinary file: untouched by an app update, tied to no version code,
 * and installed without the user approving a package. See CoreStore and moonload.c.
 */
enum class MoonCore(
    val id: String,
    /** How it is written on the Ядра page. Product names, the same in every language. */
    val title: String,
    /** The file the installer puts in the native library directory, or null when it is a library. */
    val binary: String?,
    /** Always here. Ships with the app and cannot be removed, because something needs it. */
    val builtIn: Boolean = false,
    /**
     * Fetched over the network into app data rather than carried in the APK.
     *
     * Separate from [builtIn] because they answer different questions: builtIn is whether the user
     * gets a say, this is where the file comes from. Engines are moving across one at a time, so
     * for a while both kinds of optional engine exist.
     */
    val downloadable: Boolean = false,
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
    Amnezia("awg", "AmneziaWG", "libawgproxy.so", downloadable = true),
    ;

    /** True when the engine's code is on the device and can actually be started. */
    fun present(ctx: Context): Boolean = when {
        binary == null -> true
        // Downloaded engines live in app data, which survives an app update and belongs to no
        // version code — the reason they are libraries rather than programs.
        downloadable -> CoreStore.have(ctx, this)
        // What ships in the APK is unpacked into the native library directory by the installer.
        else -> File(ctx.applicationInfo.nativeLibraryDir, binary).exists()
    }

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

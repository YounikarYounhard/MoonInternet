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
) {
    /** The core everything else is built around; it is a library inside the APK, never separate. */
    Xray("xray", "Xray", null),
    SingBox("singbox", "sing-box", "libsingbox.so"),
    Amnezia("awg", "AmneziaWG", "libawgproxy.so"),
    Zapret("zapret", "Zapret", "libbyedpi.so"),
    ;

    /** True when the engine's binary is on the device and can be executed. */
    fun present(ctx: Context): Boolean =
        binary == null || File(ctx.applicationInfo.nativeLibraryDir, binary).exists()

    companion object {
        /**
         * Which engine a server needs, or null when xray carries it alone.
         *
         * Hysteria2 is here because xray's own implementation does not survive РФ filtering, and
         * AmneziaWG because xray never spoke it at all — see SingBoxRunner and AwgRunner.
         */
        fun forProtocol(p: Protocol): MoonCore? = when (p) {
            Protocol.HYSTERIA2 -> SingBox
            Protocol.WIREGUARD -> Amnezia
            else -> null
        }

        /** Engines the user chooses to have; the rest of the list is not optional. */
        val optional = listOf(SingBox, Amnezia, Zapret)

        fun byId(id: String): MoonCore? = entries.firstOrNull { it.id == id }
    }
}

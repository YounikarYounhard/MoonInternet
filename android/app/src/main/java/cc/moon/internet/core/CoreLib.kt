package cc.moon.internet.core

/**
 * The bridge to `libmoonload.so`, which loads engines that are not part of the app.
 *
 * Every engine exports the same two symbols, so this class is written once and does not know
 * which engine it is holding — see `android/moonload/moonload.c` for the contract and for why a
 * downloadable engine has to be a shared library rather than a program.
 *
 * Starting returns as soon as the engine's thread is running, which is not the same as the engine
 * working: whether it came up is answered by knocking on its port, as it always has been.
 */
object CoreLib {

    private val loaded = runCatching { System.loadLibrary("moonload") }.isSuccess

    /** Null when the engine's thread started, or a line explaining why it did not. */
    fun start(id: String, soPath: String, args: List<String>): String? {
        if (!loaded) return "Загрузчик ядер не установился"
        return runCatching { nativeStart(id, soPath, args.joinToString("\n")) }
            .getOrElse { "Ядро не запустилось: ${it.message ?: it.javaClass.simpleName}" }
    }

    fun stop(id: String) {
        if (loaded) runCatching { nativeStop(id) }
    }

    fun running(id: String): Boolean = loaded && runCatching { nativeRunning(id) }.getOrDefault(false)

    @JvmStatic private external fun nativeStart(id: String, soPath: String, args: String): String?
    @JvmStatic private external fun nativeStop(id: String)
    @JvmStatic private external fun nativeRunning(id: String): Boolean
}

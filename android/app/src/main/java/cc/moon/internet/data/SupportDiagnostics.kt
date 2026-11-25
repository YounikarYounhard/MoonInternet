package cc.moon.internet.data

internal object SupportDiagnostics {
    private val time = Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]")

    fun summarize(newestFirst: List<String>): String =
        newestFirst.asSequence().mapNotNull(::event).take(20).toList().asReversed().joinToString("\n")

    private fun event(line: String): String? {
        val separator = line.indexOf(": ")
        if (separator < 10 || line.substring(8, 10) != "  " || !time.matches(line.substring(0, 8)))
            return null

        val detail = line.substring(separator + 2)
        // Publish only these fixed event names. Journal messages may contain connection data.
        val code = when (line.substring(10, separator)) {
            "запуск" -> "app.started"
            "туннель" -> when {
                detail.startsWith("поднимаю:") -> "tunnel.requested"
                detail == "опущен" -> "tunnel.stopped"
                detail.startsWith("AmneziaWG: сеть сменилась") -> "tunnel.network_changed"
                else -> "tunnel.event"
            }
            "подписка" -> when {
                detail.startsWith("обновлена ") -> "subscription.updated"
                "не обновилась:" in detail -> "subscription.failed"
                else -> "subscription.event"
            }
            "сервер" -> "selection.changed"
            "ядро" -> "core.event"
            else -> return null
        }
        return "${line.substring(0, 8)} $code"
    }
}

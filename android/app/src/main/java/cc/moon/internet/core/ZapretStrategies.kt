package cc.moon.internet.core

/** One strategy, named exactly as the desktop names it. [args] is the byedpi command line. */
data class ZapretStrategy(val id: String, val args: List<String>)

/**
 * The запрет strategies for the phone.
 *
 * A word about what these are, because the honest version matters here. On Windows запрет is
 * zapret: it sits in the network stack behind a driver and rewrites packets. That needs root on
 * Android, so the phone runs byedpi instead — same idea, different engine, and its options are
 * nothing like zapret's. What lines up is the NAMES: pick «general (ALT2)» on either device and
 * you get that device's second alternative, not the same packets on the wire.
 *
 * So these are not translations of the .bat files. They are byedpi's own techniques spread across
 * the same list of names, arranged the way the desktop list is meant to be used: start at the top,
 * work down until something opens.
 */
object ZapretStrategies {

    /** Where byedpi listens. Our tunnel dials it; nothing outside the app can reach it. */
    const val PORT = 1080

    private fun s(id: String, vararg args: String) = ZapretStrategy(id, args.toList())

    /**
     * Same order as the desktop folder: «general» first, then the variants.
     *
     * -s split, -d disorder, -o out-of-band, -q disorder+oob, -f fake, -r TLS record split,
     * -t TTL for fakes, -A auto (retry on the listed failures), -n the SNI a fake claims.
     */
    val all: List<ZapretStrategy> = listOf(
        s("general",                    "-s1", "-At,r,s", "-r1+s"),
        s("general (ALT)",              "-d1", "-At,r,s", "-r1+s"),
        s("general (ALT2)",             "-f-1", "-t8"),
        s("general (ALT3)",             "-o1", "-Ar,s"),
        s("general (ALT4)",             "-q1", "-Ar"),
        s("general (ALT5)",             "-s1", "-r1+s", "-Kt,h"),
        s("general (ALT6)",             "-d3", "-Ar,s"),
        s("general (ALT7)",             "-f-1", "-t3", "-n", "www.google.com"),
        s("general (ALT8)",             "-s2", "-o1"),
        s("general (ALT9)",             "-s1", "-r1+s", "-g3"),
        s("general (ALT10)",            "-d1", "-f-1", "-t8"),
        s("general (ALT11)",            "-s1+s"),
        s("general (ALT12)",            "-d1+s", "-r1+s"),
        s("general (EXP)",              "-s1", "-d1", "-At,r,s", "-L3"),
        s("general (SIMPLE FAKE)",      "-f-1"),
        s("general (SIMPLE FAKE ALT)",  "-f-1", "-t5"),
        s("general (SIMPLE FAKE ALT2)", "-f1", "-t8"),
        s("general (FAKE TLS AUTO)",         "-f-1", "-Q1", "-At,r,s"),
        s("general (FAKE TLS AUTO ALT)",     "-f-1", "-n", "www.google.com", "-At,r"),
        s("general (FAKE TLS AUTO ALT2)",    "-f-1", "-Q1", "-t4"),
        s("general (FAKE TLS AUTO ALT3)",    "-f-1", "-Q1", "-r1+s"),
    )

    fun byId(id: String?): ZapretStrategy = all.firstOrNull { it.id == id } ?: all.first()

    /** The full command line: where to listen, then the strategy's own flags. */
    fun commandLine(exe: String, id: String?): List<String> =
        listOf(exe, "-i", "127.0.0.1", "-p", "$PORT") + byId(id).args
}

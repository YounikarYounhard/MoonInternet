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
 *
 * Every line here has been started on a real phone and checked to be listening. Options that
 * read well on paper are missing because they do not work: -L (auto-mode) never starts at any
 * value, and -Q (fake-tls-mod) wants a name where these were giving it a number. -t and -g, the
 * TTL knobs, started when probed on their own and refused when probed in a batch; nothing that
 * behaves differently depending on who is watching belongs in a list users pick from.
 */
object ZapretStrategies {

    /** Where byedpi listens. Our tunnel dials it; nothing outside the app can reach it. */
    const val PORT = 1080

    /**
     * The hostname a fake packet claims to be heading for. -f on its own never starts: byedpi wants
     * something to put inside the fake, and without -n or -l it exits before it ever listens. Half
     * of this list was written that way, and not one of those halves ran.
     */
    private const val FAKE_SNI = "www.google.com"

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
        s("general (ALT2)",             "-f-1", "-n", FAKE_SNI, "-At,r,s"),
        s("general (ALT3)",             "-o1", "-Ar,s"),
        s("general (ALT4)",             "-q1", "-Ar"),
        s("general (ALT5)",             "-s1", "-r1+s", "-Kt,h"),
        s("general (ALT6)",             "-d3", "-Ar,s"),
        s("general (ALT7)",             "-f-1", "-n", FAKE_SNI, "-r1+s"),
        s("general (ALT8)",             "-s2", "-Ar,s"),
        s("general (ALT9)",             "-s1", "-r2+s"),
        s("general (ALT10)",            "-d1", "-f-1", "-n", FAKE_SNI),
        s("general (ALT11)",            "-s1+s"),
        s("general (ALT12)",            "-d1+s", "-r1+s"),
        s("general (EXP)",              "-s1", "-d1", "-At,r,s"),
        s("general (SIMPLE FAKE)",      "-f-1", "-n", FAKE_SNI),
        s("general (SIMPLE FAKE ALT)",  "-f1", "-n", FAKE_SNI),
        s("general (SIMPLE FAKE ALT2)", "-f2", "-n", FAKE_SNI),
        s("general (FAKE TLS AUTO)",         "-f-1", "-n", FAKE_SNI, "-At,r,s", "-r1+s"),
        s("general (FAKE TLS AUTO ALT)",     "-f-1", "-n", FAKE_SNI, "-At,r"),
        s("general (FAKE TLS AUTO ALT2)",    "-f-1", "-n", FAKE_SNI, "-As"),
        s("general (FAKE TLS AUTO ALT3)",    "-f-1", "-n", FAKE_SNI, "-r1+s", "-Kt,h"),
    )

    fun byId(id: String?): ZapretStrategy = all.firstOrNull { it.id == id } ?: all.first()

    /** The full command line: where to listen, then the strategy's own flags. */
    fun commandLine(exe: String, id: String?): List<String> =
        listOf(exe, "-i", "127.0.0.1", "-p", "$PORT") + byId(id).args
}

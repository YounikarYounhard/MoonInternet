package cc.moon.internet.core

/** What a line of the release notes is, which is all we need to lay it out. */
enum class NoteKind {
    PARAGRAPH,

    /** A `##` section — "Что нового", "Что починено". */
    HEADING,

    /** A bold line on its own, which is how the notes label a group inside a section. */
    GROUP,
    BULLET,
}

data class NoteBlock(val kind: NoteKind, val text: String)

/**
 * Turns a GitHub release body into something worth looking at. Port of the desktop
 * `ReleaseNotes` — the two have to agree, since they are showing the same text.
 *
 * The notes are written as Markdown and both apps were showing it raw: literal `##` and `**` down
 * the middle of the screen, which reads far worse than the same text does on the release page.
 * Not a Markdown implementation and not trying to be — it is the four shapes our notes are
 * actually written in.
 */
object ReleaseNotes {

    fun parse(markdown: String?): List<NoteBlock> {
        if (markdown.isNullOrBlank()) return emptyList()
        val blocks = mutableListOf<NoteBlock>()
        for (raw in markdown.replace("\r\n", "\n").split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            // A horizontal rule separates the summary from the sections. The spacing already says
            // that, and a line drawn across a narrow screen says it twice.
            if (line.length >= 3 && line.all { it == '-' || it == '*' || it == '_' }) continue

            when {
                line.startsWith("#") ->
                    blocks += NoteBlock(NoteKind.HEADING, clean(line.trimStart('#').trim()))

                line.length > 4 && line.startsWith("**") && line.endsWith("**") &&
                    line.indexOf("**", 2) == line.length - 2 ->
                    blocks += NoteBlock(NoteKind.GROUP, clean(line))

                // The notes use an en dash; GitHub's own bullets use the other two.
                line.startsWith("– ") || line.startsWith("- ") || line.startsWith("* ") ->
                    blocks += NoteBlock(NoteKind.BULLET, clean(line.substring(2).trim()))

                else -> blocks += NoteBlock(NoteKind.PARAGRAPH, clean(line))
            }
        }
        return blocks
    }

    /**
     * The part of the notes meant for this app — port of the desktop's `ForPlatform`. One release
     * ships both apps, and the notes are written as a section per platform, `## ПК` and
     * `## Телефон`, so the phone shows what changed on the phone. Kept: the introduction
     * (everything before the first heading) and this platform's own section, without its heading.
     * Notes written without platform sections — every release before 0.9.6 — come back whole.
     */
    fun forPlatform(markdown: String?, phone: Boolean): List<NoteBlock> {
        val all = parse(markdown)
        if (all.none { it.kind == NoteKind.HEADING && platformOf(it.text) != null }) return all
        val mine = mutableListOf<NoteBlock>()
        var intro = true
        var ours = false
        for (b in all) {
            if (b.kind == NoteKind.HEADING) { intro = false; ours = platformOf(b.text) == phone; continue }
            if (intro || ours) mine += b
        }
        return mine
    }

    private val pcWords = setOf("пк", "компьютер", "компьютера", "windows", "pc", "desktop")
    private val phoneWords = setOf("телефон", "телефона", "смартфон", "android", "phone")

    /** True for the phone's heading, false for the desktop's, null for any other — by whole word. */
    private fun platformOf(heading: String): Boolean? {
        val words = heading.lowercase().split(Regex("""[^\p{L}]+""")).filter { it.isNotEmpty() }
        return when {
            words.any { it in pcWords } -> false
            words.any { it in phoneWords } -> true
            else -> null
        }
    }

    /**
     * Drops the emphasis markers. The emphasis is carried by the block's kind instead, so leaving
     * the asterisks in would only put them on screen.
     */
    private fun clean(s: String) = s.replace("**", "").replace("`", "").trim()
}

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
     * Drops the emphasis markers. The emphasis is carried by the block's kind instead, so leaving
     * the asterisks in would only put them on screen.
     */
    private fun clean(s: String) = s.replace("**", "").replace("`", "").trim()
}

package moe.antimony.hoshi.features.texthooker

/**
 * Which line the VN tab shows. While [followLatest] is set, new lines replace the displayed one
 * unless a lookup is in progress on it; otherwise the line stays pinned and the UI offers a jump.
 */
internal data class TextHookerLineSelection(
    val displayedLine: TextHookerLine? = null,
    val followLatest: Boolean = true,
) {
    fun reconcile(lines: List<TextHookerLine>, lookupActive: Boolean): TextHookerLineSelection {
        val latest = lines.lastOrNull() ?: return this
        val displayed = displayedLine ?: return TextHookerLineSelection(latest, followLatest = true)
        return if (followLatest && !lookupActive && latest.id != displayed.id) copy(displayedLine = latest) else this
    }

    fun select(line: TextHookerLine, lines: List<TextHookerLine>): TextHookerLineSelection =
        TextHookerLineSelection(line, followLatest = line.id == lines.lastOrNull()?.id)

    fun jumpToLatest(lines: List<TextHookerLine>): TextHookerLineSelection =
        lines.lastOrNull()?.let { TextHookerLineSelection(it, followLatest = true) } ?: this

    fun hasNewerLine(lines: List<TextHookerLine>): Boolean {
        val latest = lines.lastOrNull() ?: return false
        val displayed = displayedLine ?: return false
        return latest.id > displayed.id
    }
}

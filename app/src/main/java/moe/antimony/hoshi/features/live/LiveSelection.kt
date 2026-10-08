package moe.antimony.hoshi.features.live

/** The session the tab shows: the pinned one while it exists, else the most recently active. */
internal fun liveActiveSession(sessions: List<LiveSessionState>, pinnedKey: String?): LiveSessionState? =
    sessions.firstOrNull { it.key == pinnedKey } ?: sessions.maxByOrNull { it.activityAt }

/**
 * The cue being played: for timed sessions with playback, the last cue that started at or
 * before the (extrapolated) position; otherwise the newest cue.
 */
internal fun livePlayingCueId(session: LiveSessionState?, elapsedMillis: Long): Long? {
    session ?: return null
    val cues = session.cues
    if (!session.kind.isTimed || session.playback == null) return cues.lastOrNull()?.id
    val position = session.positionAt(elapsedMillis) ?: return null
    // Cues are sorted by start time; find the last one starting at or before the position.
    var low = 0
    var high = cues.lastIndex
    var found = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        val start = cues[mid].startMs
        if (start != null && start <= position) {
            found = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return cues.getOrNull(found)?.id
}

/**
 * Which cue the focus panel shows. While [followPlaying] is set the focus follows the playing
 * cue, except during a lookup, which pins the focused cue; picking another cue pins it until
 * "back to playing".
 */
internal data class LiveFocus(
    val sessionKey: String? = null,
    val cueId: Long? = null,
    val followPlaying: Boolean = true,
) {
    fun reconcile(session: LiveSessionState?, playingCueId: Long?, lookupActive: Boolean): LiveFocus {
        session ?: return LiveFocus()
        if (session.key != sessionKey) return LiveFocus(session.key, playingCueId, followPlaying = true)
        val focused = cueId
        if (focused == null || session.cues.none { it.id == focused }) {
            return LiveFocus(session.key, playingCueId, followPlaying = true)
        }
        if (followPlaying && !lookupActive && playingCueId != null && playingCueId != focused) {
            return copy(cueId = playingCueId)
        }
        return this
    }

    fun select(cueId: Long, playingCueId: Long?): LiveFocus =
        copy(cueId = cueId, followPlaying = cueId == playingCueId)

    fun backToPlaying(playingCueId: Long?): LiveFocus =
        copy(cueId = playingCueId ?: cueId, followPlaying = true)
}

/** `<title> (MMmSSs)`, the Context/title format of mpv-anki cards. */
internal fun liveTimestampedTitle(title: String, positionMs: Long): String {
    val seconds = (positionMs / 1000).coerceAtLeast(0)
    return "%s (%02dm%02ds)".format(title, seconds / 60, seconds % 60)
}

/** Transcript and header timestamps: `m:ss` or `h:mm:ss`. */
internal fun liveClockText(positionMs: Long): String {
    val total = (positionMs / 1000).coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

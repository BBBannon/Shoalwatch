package app.shoalwatch.data.playback

/** The controller's pure rules (positions, the finished mark, speeds), kept apart so the state machine reads as one. */
internal object PlaybackRules {
    /** The speeds offered; anything else is ignored. */
    val SPEEDS = setOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

    /** A jump larger than this between two ticks is a seek (from a headset, say), not playback crossing the finished mark. */
    const val MAX_TICK_STEP_MS = 3_000L

    /**
     * Where to start: [StartFrom.RESUME] starts at the saved position unless it is under 5% or over 90% of [runtimeMs] (then
     * the start); with no runtime known, at the saved position. The player seeks there; no start position goes to the server.
     */
    fun startAt(savedMs: Long, runtimeMs: Long?, from: StartFrom): Long = when {
        from == StartFrom.BEGINNING || savedMs <= 0 -> 0
        runtimeMs == null || runtimeMs <= 0 -> savedMs
        savedMs * 100 < runtimeMs * 5 || savedMs * 10 > runtimeMs * 9 -> 0
        else -> savedMs
    }

    /** SPEC 3.1: the credits (the Outro's start) or 90% of the runtime, whichever comes first; null when neither is known. */
    fun finishedAt(outroStartMs: Long?, durationMs: Long?): Long? = listOfNotNull(outroStartMs, durationMs?.let { it * 9 / 10 }).minOrNull()

    /** Playback (not a seek) carried the position from [beforeMs] to [nowMs] across [markMs]. */
    fun crossedByPlayback(beforeMs: Long, nowMs: Long, markMs: Long?): Boolean =
        markMs != null && beforeMs < markMs && nowMs >= markMs && nowMs - beforeMs <= MAX_TICK_STEP_MS

    fun subtitleChoice(request: PlaybackRequest?): SubtitleChoice =
        request?.burnInSubtitleIndex?.let { SubtitleChoice.On(it, SubtitleDelivery.BURNED_IN) }
            ?: request?.pictureSubtitleIndex?.let { SubtitleChoice.On(it, SubtitleDelivery.IN_STREAM) } ?: SubtitleChoice.Off
}

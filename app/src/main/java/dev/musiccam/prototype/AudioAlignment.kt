package dev.musiccam.prototype

/** All shifts come from measured session epochs, never from a fixed calibration offset. */
data class AudioAlignment(val sourceFrames: Long, val targetFrames: Long, val sourceOffsetFrames: Long) {
    val trimmedHeadFrames = sourceOffsetFrames.coerceIn(0, sourceFrames)
    val leadingSilenceFrames = (-sourceOffsetFrames).coerceIn(0, targetFrames)
    val trailingSilenceFrames = (targetFrames + sourceOffsetFrames - sourceFrames).coerceIn(0, targetFrames)
    val trimmedTailFrames = (sourceFrames - targetFrames - sourceOffsetFrames).coerceIn(0, sourceFrames)

    companion object {
        fun measured(sourceFrames: Long, videoDurationUs: Long, audioOriginNs: Long, videoOriginNs: Long): AudioAlignment {
            require(sourceFrames > 0 && videoDurationUs > 0 && audioOriginNs > 0 && videoOriginNs > 0)
            val rate = PlaybackCaptureService.SAMPLE_RATE.toLong()
            val target = (videoDurationUs * rate + 999_999) / 1_000_000
            val offset = Math.round((videoOriginNs - audioOriginNs) / 1e9 * rate)
            return AudioAlignment(sourceFrames, target, offset)
        }
    }
}

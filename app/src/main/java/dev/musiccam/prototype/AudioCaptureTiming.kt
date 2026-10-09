package dev.musiccam.prototype

import android.media.AudioRecord
import android.media.AudioTimestamp
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** BOOTTIME anchors describe PCM frame zero, including leading zero-valued PCM. */
class AudioCaptureTiming(private val file: File) {
    private val report = JSONObject().put("clock", "elapsedRealtimeNanos/TIMEBASE_BOOTTIME")
    private val anchors = ArrayList<Pair<Long, Long>>()
    private var nextPollNs = 0L
    private var firstReadNs = 0L
    private var firstReadFrames = 0L
    private var firstNonZeroFrame = -1L
    var startedNs = 0L
        private set

    fun beforeStart() { report.put("startCallBeforeNs", SystemClock.elapsedRealtimeNanos()) }
    fun afterStart() {
        startedNs = SystemClock.elapsedRealtimeNanos()
        report.put("startCallReturnedNs", startedNs)
        SessionStorage.writeJson(file, report)
    }

    fun pcm(record: AudioRecord, samples: ShortArray, count: Int, framesBefore: Long, readReturnedNs: Long) {
        val now = readReturnedNs
        if (firstReadNs == 0L) {
            firstReadNs = now
            firstReadFrames = count.toLong() / PlaybackCaptureService.CHANNELS
            report.put("firstPcmReceivedNs", now).put("firstPcmFrames", firstReadFrames)
        }
        if (firstNonZeroFrame < 0) {
            val index = (0 until count).firstOrNull { samples[it].toInt() != 0 }
            if (index != null) {
                firstNonZeroFrame = framesBefore + index / PlaybackCaptureService.CHANNELS
                report.put("firstNonZeroReceivedNs", now).put("firstNonZeroFrame", firstNonZeroFrame)
            }
        }
        if (now < nextPollNs) return
        val timestamp = AudioTimestamp()
        val code = record.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_BOOTTIME)
        report.put("lastTimestampResult", code).put("bufferSizeInFrames", record.bufferSizeInFrames)
        if (code == AudioRecord.SUCCESS && timestamp.nanoTime > 0 && timestamp.framePosition >= 0) {
            if (anchors.lastOrNull()?.first != timestamp.framePosition && anchors.size < 3600) {
                anchors.add(timestamp.framePosition to timestamp.nanoTime)
            }
            nextPollNs = now + 1_000_000_000L
        } else nextPollNs = now + 100_000_000L
    }

    fun finish(frames: Long, nonZero: Long, failure: String?) {
        val rate = PlaybackCaptureService.SAMPLE_RATE
        val origins = anchors.map { (frame, time) -> time - frame * 1_000_000_000L / rate }.sorted()
        val origin = if (origins.isNotEmpty()) origins[origins.size / 2] else
            firstReadNs - firstReadFrames * 1_000_000_000L / rate
        val rawAnchors = JSONArray()
        anchors.forEach { (frame, time) -> rawAnchors.put(JSONObject().put("framePosition", frame).put("nanoTime", time)) }
        report.put("anchors", rawAnchors).put("originEstimateNs", origin)
            .put("originMethod", if (origins.isNotEmpty()) "AudioRecord_BOOTTIME_frame_position" else "first_read_return_minus_block_duration_approximation")
            .put("originSpreadNs", if (origins.isEmpty()) JSONObject.NULL else origins.last() - origins.first())
            .put("firstNonZeroFrame", firstNonZeroFrame).put("frames", frames).put("nonZeroSamples", nonZero)
            .put("stopCompletedNs", SystemClock.elapsedRealtimeNanos()).put("complete", true)
            .put("error", failure ?: JSONObject.NULL)
        if (anchors.size > 1) {
            val first = anchors.first()
            val last = anchors.last()
            if (last.second > first.second) report.put("measuredFrameRateHz",
                (last.first - first.first) * 1e9 / (last.second - first.second))
        }
        SessionStorage.writeJson(file, report)
    }
}

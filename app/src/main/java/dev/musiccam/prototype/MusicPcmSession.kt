package dev.musiccam.prototype

import android.content.Context
import java.io.File

/** Owned only by the PCM worker. Discarded gaps advance the same continuous frame cursor. */
internal class MusicPcmSession(private val context: Context, val modeId: String,
                               private val captureStartedNs: Long,
                               private val publish: (PlaybackCaptureService.ClipStatus) -> Unit) {
    private data class Output(val id: String, val start: Long, val wav: WavFile,
                              val timing: AudioCaptureTiming, var nonZero: Long = 0)
    private var output: Output? = null
    var globalFrames = 0L
        private set
    val clipId get() = output?.id

    fun open(id: String) {
        check(output == null) { "上一片段尚未定稿" }
        val directory = SessionStorage.directory(context, id)
        check(!File(directory, "audio.wav").exists() && !File(directory, "audio.wav.part").exists()) {
            "片段已存在，拒绝覆盖原始音频"
        }
        val timing = AudioCaptureTiming(File(directory, "audio-timing.json"), globalFrames, modeId)
        val next = Output(id, globalFrames, WavFile(File(directory, "audio.wav"),
            PlaybackCaptureService.SAMPLE_RATE, PlaybackCaptureService.CHANNELS), timing)
        output = next
        timing.segmentOpened(captureStartedNs)
        publish(PlaybackCaptureService.ClipStatus(id, modeId, opened = true, startFrame = globalFrames))
        PocLog.event(context, "MUSIC_CLIP_OPEN mode=$modeId id=$id startFrame=$globalFrames")
    }

    fun pcm(samples: ShortArray, count: Int, readNs: Long, anchor: Pair<Long, Long>?) {
        require(count > 0 && count % PlaybackCaptureService.CHANNELS == 0)
        val before = globalFrames
        globalFrames += count / PlaybackCaptureService.CHANNELS
        val current = output ?: return // No WAV or PCM cache exists between clips.
        val local = before - current.start
        check(local == current.wav.dataBytes / 4) { "片段 PCM 帧序列不连续" }
        current.wav.append(samples, count)
        current.timing.observePcm(samples, count, local, readNs)
        anchor?.let { current.timing.observeTimestamp(it.first, it.second) }
        current.nonZero += (0 until count).count { samples[it].toInt() != 0 }
    }

    fun close(error: String? = null) {
        val current = output ?: return
        output = null
        var failure = error
        val frames = current.wav.dataBytes / 4
        try {
            if (current.wav.finish() == null) failure = failure ?: "片段未取得 PCM 数据"
        } catch (e: Exception) { failure = listOfNotNull(failure, "WAV 定稿失败：${e.message}").joinToString("；") }
        try { current.timing.finish(frames, current.nonZero, failure) }
        catch (e: Exception) { failure = listOfNotNull(failure, "时间记录失败：${e.message}").joinToString("；") }
        publish(PlaybackCaptureService.ClipStatus(current.id, modeId, opened = true, finalized = true,
            startFrame = current.start, endFrame = current.start + frames,
            nonZeroSamples = current.nonZero, error = failure))
        PocLog.event(context, "MUSIC_CLIP_FINALIZED mode=$modeId id=${current.id} startFrame=${current.start} endFrame=${current.start + frames} cursor=$globalFrames error=${failure ?: "none"}")
    }
}

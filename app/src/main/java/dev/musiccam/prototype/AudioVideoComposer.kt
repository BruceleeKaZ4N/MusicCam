package dev.musiccam.prototype

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.os.SystemClock
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Offline native AAC encoding + lossless H.264 remux. No context or capture permission needed. */
object AudioVideoComposer {
    private const val RATE = 48_000
    private const val FRAME_BYTES = 4
    private data class Video(val format: MediaFormat, val firstUs: Long, val lastUs: Long,
                             val durationUs: Long, val samples: Long, val rotation: Int)

    fun compose(directory: File, session: JSONObject): File {
        val videoFile = File(directory, "video.mp4")
        val wavFile = File(directory, "audio.wav")
        val audioTiming = SessionStorage.readJson(File(directory, "audio-timing.json"))
        check(audioTiming.optBoolean("complete")) { "音频文件尚未定稿" }
        check(audioTiming.isNull("error")) { "音频捕获异常：${audioTiming.optString("error")}" }
        val video = videoInfo(videoFile)
        val frames = wavFrames(wavFile)
        check(audioTiming.getLong("frames") == frames) { "PCM 帧数与时间记录不一致" }
        val videoOrigin = session.getLong("videoOriginEstimateNs")
        val audioOrigin = audioTiming.getLong("originEstimateNs")
        val alignment = AudioAlignment.measured(frames, video.durationUs, audioOrigin, videoOrigin)
        val report = JSONObject().put("clock", "elapsedRealtimeNanos/TIMEBASE_BOOTTIME")
            .put("videoOriginEstimateNs", videoOrigin).put("audioOriginEstimateNs", audioOrigin)
            .put("videoOriginMethod", "minimum(Status_receipt_minus_recorded_duration)_approximation")
            .put("audioOriginMethod", audioTiming.getString("originMethod"))
            .put("audioOriginSpreadNs", audioTiming.opt("originSpreadNs"))
            .put("sourceAudioFrames", frames).put("targetPcmFrames", alignment.targetFrames)
            .put("sourceOffsetFrames", alignment.sourceOffsetFrames)
            .put("trimmedHeadFrames", alignment.trimmedHeadFrames).put("leadingSilenceFrames", alignment.leadingSilenceFrames)
            .put("trailingSilenceFrames", alignment.trailingSilenceFrames).put("trimmedTailFrames", alignment.trimmedTailFrames)
            .put("videoFirstSourcePtsUs", video.firstUs).put("videoLastSourcePtsUs", video.lastUs)
            .put("videoDurationUs", video.durationUs).put("videoSamples", video.samples).put("rotation", video.rotation)
            .put("sourceAudioEndRelativeToVideoUs", (audioOrigin - videoOrigin) / 1000 + frames * 1_000_000 / RATE)
            .put("sourceAudioFirstNonZeroFrame", audioTiming.optLong("firstNonZeroFrame", -1))
            .put("nonZeroSamples", audioTiming.optLong("nonZeroSamples"))
            .put("limitation", "视频起点包含编码/事件交付延迟；AudioTimestamp 为系统最佳估计。未作固定偏移补偿；AAC 未报告的 priming 仍须通过同步事件测量。")
        val encodedAudio = File(directory, "audio-aac.mp4")
        val output = File(directory, "merged.mp4")
        try {
            encodeAac(wavFile, encodedAudio, alignment, video.durationUs, report)
            remux(videoFile, encodedAudio, output, video, report)
            report.put("complete", true)
            SessionStorage.writeJson(File(directory, "composition.json"), report)
            return output
        } catch (e: Exception) {
            report.put("complete", false).put("error", "${e.javaClass.simpleName}: ${e.message}")
            try { SessionStorage.writeJson(File(directory, "composition.json"), report) } catch (_: Exception) { }
            throw e
        }
    }

    private fun wavFrames(file: File): Long = RandomAccessFile(file, "r").use { input ->
        val bytes = ByteArray(44)
        input.readFully(bytes)
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        check(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 8, Charsets.US_ASCII) == "WAVEfmt " &&
            header.getInt(16) == 16 && header.getShort(20).toInt() == 1 && header.getShort(22).toInt() == 2 &&
            header.getInt(24) == RATE && header.getShort(32).toInt() == FRAME_BYTES && header.getShort(34).toInt() == 16 &&
            String(bytes, 36, 4, Charsets.US_ASCII) == "data") { "WAV 不是本项目的 48000Hz PCM16 立体声格式" }
        val data = header.getInt(40).toLong() and 0xffff_ffffL
        check(data > 0 && data % FRAME_BYTES == 0L && file.length() == data + 44 &&
            (header.getInt(4).toLong() and 0xffff_ffffL) == data + 36) { "WAV 数据长度无效" }
        data / FRAME_BYTES
    }

    private fun videoInfo(file: File): Video {
        val extractor = MediaExtractor()
        val retriever = MediaMetadataRetriever()
        try {
            extractor.setDataSource(file.path)
            val indices = (0 until extractor.trackCount).filter {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            check(indices.size == 1 && extractor.trackCount == 1) { "CameraX 输入必须为单一无音轨视频" }
            val format = extractor.getTrackFormat(indices.single())
            check(format.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_AVC) { "本 PoC 只封装 H.264；原始视频保留" }
            extractor.selectTrack(indices.single())
            val first = extractor.sampleTime
            check(first >= 0 && extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) { "视频没有有效的起始关键帧" }
            var last = first
            var previous = -1L
            var samples = 0L
            val intervals = ArrayList<Long>()
            while (extractor.sampleTime >= 0) {
                val pts = extractor.sampleTime
                check(pts > previous) { "视频 PTS 不是递增序列，本 PoC 不改写或重排 B 帧" }
                if (previous >= 0 && intervals.size < 300) intervals.add(pts - previous)
                previous = pts
                last = pts
                samples++
                extractor.advance()
            }
            check(samples > 0)
            val lastDuration = intervals.sorted().let { if (it.isEmpty()) 33_333L else it[it.size / 2] }
            // The fallback period is only for a one-frame file, not a synchronization offset.
            val declared = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) - first else 0L
            val duration = if (declared > last - first) declared else last - first + lastDuration
            retriever.setDataSource(file.path)
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            check(rotation in listOf(0, 90, 180, 270)) { "视频方向元数据无效" }
            return Video(format, first, last, duration, samples, rotation)
        } finally { retriever.release(); extractor.release() }
    }

    private fun encodeAac(wav: File, destination: File, layout: AudioAlignment, durationUs: Long, report: JSONObject) {
        val temporary = File(destination.path + ".part")
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var codecStarted = false
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var audioTrack = -1
        var cursor = 0L
        var inputEos = false
        var outputEos = false
        var lastPts = -1L
        var outputSamples = 0L
        var discardedPadding = 0L
        var lastProgress = SystemClock.elapsedRealtime()
        val info = MediaCodec.BufferInfo()
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096 * FRAME_BYTES)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            codecStarted = true
            report.put("aacEncoder", codec.name).put("aacInputEosUs", durationUs)
            muxer = MediaMuxer(temporary.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            RandomAccessFile(wav, "r").use { pcm ->
                while (!outputEos) {
                    check(SystemClock.elapsedRealtime() - lastProgress < 15_000) { "AAC 编码器 15 秒无进展" }
                    if (!inputEos) {
                        // Drain ready output without waiting for input buffers held by the codec.
                        val index = codec.dequeueInputBuffer(0)
                        if (index >= 0) {
                            val input = codec.getInputBuffer(index) ?: error("AAC 输入缓冲区不可用")
                            input.clear()
                            if (cursor == layout.targetFrames) {
                                codec.queueInputBuffer(index, 0, 0, durationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEos = true
                            } else {
                                val count = minOf(input.remaining().toLong() / FRAME_BYTES, layout.targetFrames - cursor).toInt()
                                check(count > 0) { "AAC 输入缓冲区过小" }
                                val block = ByteArray(count * FRAME_BYTES)
                                val source = cursor + layout.sourceOffsetFrames
                                val begin = maxOf(source, 0L)
                                val end = minOf(source + count, layout.sourceFrames)
                                if (end > begin) {
                                    pcm.seek(44 + begin * FRAME_BYTES)
                                    pcm.readFully(block, ((begin - source) * FRAME_BYTES).toInt(), ((end - begin) * FRAME_BYTES).toInt())
                                }
                                input.put(block)
                                codec.queueInputBuffer(index, 0, block.size, cursor * 1_000_000 / RATE, 0)
                                cursor += count
                            }
                            lastProgress = SystemClock.elapsedRealtime()
                        }
                    }
                    val index = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted) { "AAC 格式重复变化" }
                            val encodedFormat = codec.outputFormat
                            report.put("aacOutputFormat", encodedFormat.toString())
                            if (Build.VERSION.SDK_INT >= 30) {
                                if (encodedFormat.containsKey(MediaFormat.KEY_ENCODER_DELAY)) report.put("reportedEncoderDelayFrames", encodedFormat.getInteger(MediaFormat.KEY_ENCODER_DELAY))
                                if (encodedFormat.containsKey(MediaFormat.KEY_ENCODER_PADDING)) report.put("reportedEncoderPaddingFrames", encodedFormat.getInteger(MediaFormat.KEY_ENCODER_PADDING))
                            }
                            audioTrack = muxer.addTrack(encodedFormat)
                            muxer.start()
                            muxerStarted = true
                            lastProgress = SystemClock.elapsedRealtime()
                        }
                        index >= 0 -> {
                            try {
                                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                    check(muxerStarted)
                                    val pts = info.presentationTimeUs
                                    if (!report.has("aacFirstCodecPtsUs")) report.put("aacFirstCodecPtsUs", pts)
                                    check(pts >= 0) { "AAC 编码器返回负 PTS，保留原始数据，不静默平移时间戳" }
                                    if (pts < durationUs) {
                                        check(pts > lastPts) { "AAC 编码 PTS 非递增" }
                                        val data = codec.getOutputBuffer(index) ?: error("AAC 输出缓冲区不可用")
                                        muxer.writeSampleData(audioTrack, data, info)
                                        lastPts = pts
                                        outputSamples++
                                    } else discardedPadding++
                                }
                                outputEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            } finally { codec.releaseOutputBuffer(index, false) }
                            lastProgress = SystemClock.elapsedRealtime()
                        }
                    }
                }
            }
            check(muxerStarted && outputSamples > 0) { "AAC 编码没有有效样本" }
            info.set(0, 0, durationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            muxer.writeSampleData(audioTrack, ByteBuffer.allocate(0), info)
            muxerStarted = false
            muxer.stop()
            muxer.release()
            muxer = null
            check(temporary.renameTo(destination)) { "AAC 中间文件定稿失败" }
            report.put("aacSamples", outputSamples).put("aacLastPtsUs", lastPts).put("discardedAacPaddingPackets", discardedPadding)
        } finally {
            try { if (muxerStarted) muxer?.stop() } catch (_: Exception) { }
            muxer?.release()
            try { if (codecStarted) codec.stop() } finally { codec.release() }
        }
    }

    private fun remux(videoFile: File, audioFile: File, destination: File, video: Video, report: JSONObject) {
        val temporary = File(destination.path + ".part")
        val videoReader = MediaExtractor()
        val audioReader = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            videoReader.setDataSource(videoFile.path)
            audioReader.setDataSource(audioFile.path)
            videoReader.selectTrack(0)
            check(audioReader.trackCount == 1 && audioReader.getTrackFormat(0).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC)
            audioReader.selectTrack(0)
            muxer = MediaMuxer(temporary.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(video.rotation)
            video.format.removeKey(MediaFormat.KEY_ROTATION)
            val videoTrack = muxer.addTrack(video.format)
            val audioTrack = muxer.addTrack(audioReader.getTrackFormat(0))
            muxer.start()
            started = true
            val info = MediaCodec.BufferInfo()
            var buffer = ByteBuffer.allocateDirect(1024 * 1024)
            var videoSamples = 0L
            var audioSamples = 0L
            val lastTimes = longArrayOf(-1, -1)
            while (videoReader.sampleTime >= 0 || audioReader.sampleTime >= 0) {
                val v = if (videoReader.sampleTime >= 0) videoReader.sampleTime - video.firstUs else Long.MAX_VALUE
                val a = if (audioReader.sampleTime >= 0) audioReader.sampleTime else Long.MAX_VALUE
                val isVideo = v <= a
                val reader = if (isVideo) videoReader else audioReader
                val pts = if (isVideo) v else a
                val slot = if (isVideo) 0 else 1
                check(pts >= 0 && pts < video.durationUs && pts > lastTimes[slot]) { "复用输入 PTS 无效：$pts" }
                val size = reader.sampleSize
                check(size in 1..Int.MAX_VALUE.toLong()) { "媒体样本大小无效" }
                if (buffer.capacity() < size) buffer = ByteBuffer.allocateDirect(size.toInt())
                buffer.clear()
                val count = reader.readSampleData(buffer, 0)
                check(count == size.toInt()) { "未完整读取媒体样本" }
                check(reader.sampleFlags and (MediaExtractor.SAMPLE_FLAG_ENCRYPTED or MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) == 0) {
                    "输入是加密或分片样本，不作绕过或改写"
                }
                val flags = if (reader.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, count, pts, flags)
                muxer.writeSampleData(if (isVideo) videoTrack else audioTrack, buffer, info)
                lastTimes[slot] = pts
                if (isVideo) videoSamples++ else audioSamples++
                reader.advance()
            }
            check(videoSamples == video.samples && audioSamples > 0) { "复用时丢失媒体样本" }
            info.set(0, 0, video.durationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            muxer.writeSampleData(videoTrack, ByteBuffer.allocate(0), info)
            muxer.writeSampleData(audioTrack, ByteBuffer.allocate(0), info)
            started = false
            muxer.stop()
            muxer.release()
            muxer = null
            check(temporary.renameTo(destination)) { "合成文件定稿失败" }
            report.put("muxedVideoSamples", videoSamples).put("muxedAacSamples", audioSamples)
                .put("muxedLastVideoPtsUs", lastTimes[0]).put("muxedLastAacPtsUs", lastTimes[1])
        } finally {
            try { if (started) muxer?.stop() } catch (_: Exception) { }
            muxer?.release()
            audioReader.release()
            videoReader.release()
        }
    }
}

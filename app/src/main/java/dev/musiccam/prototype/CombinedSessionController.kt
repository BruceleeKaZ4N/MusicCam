package dev.musiccam.prototype

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executor

/** One owner coordinates both recorders, then joins finalized files before native composition. */
class CombinedSessionController private constructor(private val context: Application, val id: String,
                                                   private val journal: JSONObject) {
    enum class State { PREPARING, RECORDING, STOPPING, MERGING, DONE, FAILED }
    companion object {
        var active: CombinedSessionController? = null
            private set

        fun start(context: Context, video: VideoCapture<Recorder>, consent: Intent,
                  buttonNs: Long, authorization: JSONObject, configuration: String): CombinedSessionController {
            check(active == null && !PlaybackCaptureService.status.active && !PlaybackCaptureService.mode.active &&
                !PlaybackCaptureService.mode.stopping) { "已有录制会话，请先停止" }
            val controller = CombinedSessionController(context.applicationContext as Application, UUID.randomUUID().toString(),
                JSONObject().put("buttonClickNs", buttonNs).put("authorization", authorization)
                    .put("configuration", configuration).put("clock", "elapsedRealtimeNanos/TIMEBASE_BOOTTIME"))
            active = controller
            controller.begin(video, consent)
            return controller
        }

        fun startMusicClip(context: Context, video: VideoCapture<Recorder>, modeId: String,
                           buttonNs: Long, configuration: String): CombinedSessionController {
            val mode = PlaybackCaptureService.mode
            check(active == null && mode.id == modeId && mode.ready && !mode.stopping) { "音乐模式未就绪或上一片段正在处理" }
            val controller = CombinedSessionController(context.applicationContext as Application, UUID.randomUUID().toString(),
                JSONObject().put("musicModeId", modeId).put("buttonClickNs", buttonNs)
                    .put("configuration", configuration).put("clock", "elapsedRealtimeNanos/TIMEBASE_BOOTTIME"))
            active = controller
            controller.beginMusic(video)
            return controller
        }

        fun retry(context: Context, id: String): CombinedSessionController {
            check(active == null && !PlaybackCaptureService.status.active) { "请先停止当前会话" }
            val directory = SessionStorage.directory(context, id)
            val controller = CombinedSessionController(context.applicationContext as Application, id,
                SessionStorage.readJson(File(directory, "session.json")))
            active = controller
            controller.compose()
            return controller
        }
    }

    val directory = SessionStorage.directory(context, id)
    private val main = Handler(Looper.getMainLooper())
    private val preferences = context.getSharedPreferences("combined", Context.MODE_PRIVATE)
    private val failures = ArrayList<String>()
    private val observations = JSONArray()
    private var recording: Recording? = null
    private var videoDone = false
    private var audioDone = false
    private var seenAudio = false
    private var videoStarted = false
    private var audioStarted = false
    private var audioNonZero = 0L
    private var createdNs = SystemClock.elapsedRealtimeNanos()
    private var stopNs = 0L
    private var nextJournalNs = 0L
    private var bestVideoOrigin = Long.MAX_VALUE
    private var worstVideoOrigin = Long.MIN_VALUE
    private var journalFailed = false
    private var postProcessingStarted = false
    private var audioLaunchAccepted = false
    private val musicModeId = journal.optString("musicModeId").takeIf { it.isNotEmpty() }
    private var waitingVideo: VideoCapture<Recorder>? = null
    var listener: (() -> Unit)? = null
    var state = State.PREPARING
        private set
    var message = "正在同时启动视频与系统音频…"
        private set
    val capturing get() = state in listOf(State.PREPARING, State.RECORDING, State.STOPPING)
    val canStop get() = state == State.PREPARING || state == State.RECORDING

    private val poll = object : Runnable {
        override fun run() {
            if (!capturing) return
            val audio = PlaybackCaptureService.status
            if (musicModeId != null) {
                val mode = PlaybackCaptureService.mode
                val pcm = PlaybackCaptureService.clip
                if (pcm?.id == id && pcm.modeId == musicModeId) {
                    seenAudio = pcm.opened
                    audioStarted = pcm.opened
                    if (pcm.finalized) {
                        audioDone = true
                        audioNonZero = pcm.nonZeroSamples
                        journal.put("startFrameInclusive", pcm.startFrame).put("endFrameExclusive", pcm.endFrame)
                        pcm.error?.let { if ("音频：$it" !in failures) failures.add("音频：$it") }
                        if (state != State.STOPPING) stop("音频片段已结束")
                    } else if (pcm.opened && canStop && waitingVideo != null) {
                        val video = waitingVideo!!
                        waitingVideo = null
                        try { startVideo(video); saveOrThrow() }
                        catch (e: Exception) {
                            if (recording == null) videoDone = true
                            abort("视频启动失败：${describe(e)}")
                        }
                    }
                }
                if (mode.id != musicModeId || !mode.active || mode.stopping) musicModeEnded(musicModeId, mode.message)
            } else if (audio.sessionId == id) {
                seenAudio = true
                audioStarted = audio.recordingStartedNs > 0
                if (!audio.active) {
                    audioDone = true
                    audioNonZero = audio.nonZeroSamples
                    audio.error?.let { if (it !in failures) failures.add("音频：$it") }
                    if (state != State.STOPPING) stop("音频会话结束或授权撤回")
                } else if (state == State.STOPPING) PlaybackCaptureService.stop("统一会话停止", id)
            }
            if (state == State.PREPARING && audioStarted && videoStarted) transition(State.RECORDING, "正在录制视频和系统播放音频；停止后自动合成。")
            val now = SystemClock.elapsedRealtimeNanos()
            if (musicModeId != null && state == State.RECORDING && now - createdNs >= 600_000_000_000L)
                stop("本段达到 10 分钟上限")
            if (state == State.PREPARING && now - createdNs > 10_000_000_000L) {
                failures.add("10 秒内未能启动两路录制")
                stop("启动超时")
                if (musicModeId != null) PlaybackCaptureService.endMusicMode("片段启动超时", musicModeId)
            }
            if (state == State.STOPPING && now - stopNs > 20_000_000_000L) {
                if (musicModeId != null && !audioDone) PlaybackCaptureService.endMusicMode("片段定稿超时", musicModeId)
                if (!seenAudio && musicModeId == null) { failures.add("音频服务未确认启动，原始数据保留"); audioDone = true }
                if (!videoDone) {
                    failures.add("视频尚未确认定稿，保留原始文件")
                    if (musicModeId != null) PlaybackCaptureService.endMusicMode("视频定稿超时", musicModeId)
                    recording?.close()
                    recording = null
                    videoDone = true
                }
            }
            if (musicModeId != null && state == State.STOPPING && now - stopNs > 30_000_000_000L && !audioDone) {
                failAndPreserve("音频片段未确认定稿；保留文件，禁止继续本授权会话")
                return
            }
            join()
            if (capturing) { listener?.invoke(); main.postDelayed(this, 100) }
        }
    }

    private fun begin(video: VideoCapture<Recorder>, consent: Intent) {
        journal.put("id", id).put("state", state.name).put("createdNs", createdNs)
        preferences.edit().putString("recoverableId", id).apply()
        try {
            check(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "缺少摄像头或播放音频权限" }
            saveOrThrow()
            journal.put("audioServiceRequestNs", SystemClock.elapsedRealtimeNanos())
            context.startForegroundService(Intent(context, PlaybackCaptureService::class.java)
                .setAction(PlaybackCaptureService.ACTION_START).putExtra(PlaybackCaptureService.EXTRA_SESSION_ID, id)
                .putExtra(PlaybackCaptureService.EXTRA_CONSENT, consent))
            audioLaunchAccepted = true
            startVideo(video)
            saveOrThrow()
            PocLog.event(context, "PHASE3_BEGIN id=$id buttonNs=${journal.getLong("buttonClickNs")} videoRequestNs=${journal.getLong("videoRequestNs")}")
        } catch (e: Exception) {
            failures.add("启动失败：${describe(e)}")
            if (recording == null) videoDone = true
            if (!audioLaunchAccepted) audioDone = true
            stop("启动失败")
        } finally { main.post(poll) }
    }

    private fun beginMusic(video: VideoCapture<Recorder>) {
        journal.put("id", id).put("state", state.name).put("createdNs", createdNs)
        preferences.edit().putString("recoverableId", id).apply()
        try {
            check(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "录制权限已撤回" }
            saveOrThrow()
            waitingVideo = video
            PlaybackCaptureService.openMusicClip(musicModeId!!, id)
            audioLaunchAccepted = true
            PocLog.event(context, "MUSIC_CLIP_REQUEST mode=$musicModeId id=$id")
        } catch (e: Exception) {
            failures.add("片段启动失败：${describe(e)}")
            videoDone = true
            audioDone = !audioLaunchAccepted
            stop("片段启动失败")
            PlaybackCaptureService.endMusicMode("片段启动失败", musicModeId)
        } finally { main.post(poll) }
    }

    private fun startVideo(video: VideoCapture<Recorder>) {
        journal.put("videoRequestNs", SystemClock.elapsedRealtimeNanos())
        // Timestamp CameraX receipts exactly as Phase 3; no change to composition/alignment.
        recording = video.output.prepareRecording(context, FileOutputOptions.Builder(File(directory, "video.mp4")).build())
            .start(Executor { command -> command.run() }) { event ->
                val received = SystemClock.elapsedRealtimeNanos()
                main.post { videoEvent(event, received) }
            }
    }

    fun musicModeEnded(modeId: String, reason: String) {
        if (musicModeId != modeId || !capturing) return
        stop(reason)
    }

    private fun videoEvent(event: VideoRecordEvent, receivedNs: Long) {
        if (!capturing || postProcessingStarted) return
        when (event) {
            is VideoRecordEvent.Start -> {
                videoStarted = true
                journal.put("videoStartEventReceivedNs", receivedNs)
            }
            is VideoRecordEvent.Status -> {
                val duration = event.recordingStats.recordedDurationNanos
                if (event.recordingStats.numBytesRecorded > 0) {
                    if (!journal.has("videoFirstMediaObservedNs")) journal.put("videoFirstMediaObservedNs", receivedNs)
                    val estimate = receivedNs - duration
                    bestVideoOrigin = minOf(bestVideoOrigin, estimate)
                    worstVideoOrigin = maxOf(worstVideoOrigin, estimate)
                    journal.put("videoOriginEstimateNs", bestVideoOrigin).put("videoOriginCandidateSpreadNs", worstVideoOrigin - bestVideoOrigin)
                        .put("videoLatestDurationNs", duration)
                    if (receivedNs >= nextJournalNs) {
                        observations.put(JSONObject().put("receivedNs", receivedNs).put("durationNs", duration).put("bytes", event.recordingStats.numBytesRecorded))
                        journal.put("videoObservations", observations)
                        nextJournalNs = receivedNs + 1_000_000_000L
                        saveSafely()
                    }
                    if (state == State.RECORDING) message = "正在录制：${String.format(java.util.Locale.ROOT, "%.1f", duration / 1e9)} 秒\n视频与系统播放音频；停止后自动合成。"
                }
            }
            is VideoRecordEvent.Finalize -> {
                recording?.close()
                recording = null
                videoDone = true
                journal.put("videoFinalizeReceivedNs", receivedNs).put("videoError", event.error)
                    .put("videoFinalDurationNs", event.recordingStats.recordedDurationNanos)
                if (event.hasError()) failures.add("CameraX 定稿错误 ${event.error}：${event.cause?.javaClass?.simpleName ?: "无异常详情"}")
                if (musicModeId != null) {
                    PlaybackCaptureService.releaseMusicCamera(musicModeId)
                    PlaybackCaptureService.closeMusicClip(musicModeId, id)
                    if (event.hasError()) PlaybackCaptureService.endMusicMode("摄像头录制异常", musicModeId)
                }
                if (state != State.STOPPING) stop("视频会话结束")
                saveSafely()
                join()
            }
        }
    }

    fun stop(reason: String) {
        if (!canStop) return
        stopNs = SystemClock.elapsedRealtimeNanos()
        journal.put("stopRequestNs", stopNs).put("stopReason", reason)
        transition(State.STOPPING, "$reason；正在停止两路录制并保存中间文件…")
        if (musicModeId == null) PlaybackCaptureService.stop(reason, id)
        else if (recording == null) {
            waitingVideo = null
            videoDone = true
            PlaybackCaptureService.closeMusicClip(musicModeId, id)
        }
        try { recording?.stop() } catch (e: Exception) {
            failures.add("视频停止：${describe(e)}")
            if (musicModeId != null) PlaybackCaptureService.endMusicMode("视频停止失败", musicModeId)
        }
        saveSafely()
        PocLog.event(context, "PHASE3_STOP id=$id ns=$stopNs reason=$reason")
    }

    fun abort(reason: String) {
        if (!capturing) return
        failures.add(reason)
        stop(reason)
        if (musicModeId != null) PlaybackCaptureService.endMusicMode(reason, musicModeId)
    }

    private fun join() {
        if (postProcessingStarted || state != State.STOPPING || !videoDone || !audioDone) return
        if (musicModeId != null) PlaybackCaptureService.releaseMusicCamera(musicModeId)
        postProcessingStarted = true
        main.removeCallbacks(poll)
        journal.put("videoFinalized", journal.has("videoFinalizeReceivedNs"))
            .put("audioFinalized", seenAudio && audioDone).put("audioNonZeroSamples", audioNonZero)
            .put("failures", JSONArray(failures))
        if (failures.isEmpty() && journal.has("videoOriginEstimateNs") && File(directory, "audio.wav").isFile) compose()
        else failAndPreserve(failures.ifEmpty { listOf("没有取得有效媒体数据或视频时间记录") }.joinToString("；"))
    }

    private fun compose() {
        postProcessingStarted = true
        transition(State.MERGING, "录制已停止，正在编码 AAC 并自动合成 MP4…")
        saveSafely()
        Thread({
            try {
                val existing = journal.optString("pendingPublicationUri").takeIf { it.startsWith("content://") }?.let(Uri::parse)
                val outputUri = if (existing != null && SessionStorage.isPublished(context, existing)) {
                    Mp4Inspection.inspect(context, existing, 1)
                    existing
                } else {
                    if (existing != null) context.contentResolver.delete(existing, null, null)
                    val output = AudioVideoComposer.compose(directory, journal)
                    SessionStorage.publish(context, output, "MusicCam-AV-$id.mp4", 1) { uri ->
                        journal.put("pendingPublicationUri", uri.toString())
                        saveOrThrow()
                    }
                }
                val inspection = Mp4Inspection.inspect(context, outputUri, 1)
                journal.put("publishedUri", outputUri.toString()).put("state", State.DONE.name)
                    .put("publishedNs", SystemClock.elapsedRealtimeNanos())
                saveOrThrow()
                val silent = if (SessionStorage.readJson(File(directory, "audio-timing.json")).optLong("nonZeroSamples") == 0L)
                    "\n警告：原始 PCM 全静音，不能认定系统音频捕获成功。" else ""
                val notRemoved = SessionStorage.cleanupMedia(directory)
                val result = "已自动合成 MP4\nMovies/MusicCam/MusicCam-AV-$id.mp4\n$inspection\n" +
                    "同步采用测量起点的近似对齐，实际偏移需用同步事件验证。$silent" +
                    if (notRemoved.isEmpty()) "" else "\n部分中间文件未能清理，已保留。"
                preferences.edit().putString("uri", outputUri.toString()).putString("message", result)
                    .remove("recoverableId").apply()
                PocLog.event(context, "PHASE3_COMPOSITION_DONE id=$id $inspection cleanupRemaining=$notRemoved")
                finish(State.DONE, result)
            } catch (e: Exception) { failAndPreserve("合成失败：${describe(e)}") }
        }, "MusicCam-compose").start()
    }

    private fun failAndPreserve(error: String) {
        postProcessingStarted = true
        main.removeCallbacks(poll)
        Thread({
            var backup: Uri? = null
            var backupError: String? = null
            try {
                val old = journal.optString("backupVideoUri").takeIf { it.startsWith("content://") }?.let(Uri::parse)
                backup = if (old != null && SessionStorage.isPublished(context, old)) old else {
                    val source = File(directory, "video.mp4")
                    check(source.isFile && source.length() > 0) { "没有定稿原始视频" }
                    SessionStorage.publish(context, source, "MusicCam-source-$id.mp4", 0)
                }
                journal.put("backupVideoUri", backup.toString())
            } catch (e: Exception) { backupError = describe(e) }
            val result = "$error\n本次已取得的中间文件保留在应用内，未删除；数据完整时可重试合成。" +
                if (backup != null) "\n未合成的原始视频已保存到 Movies/MusicCam/MusicCam-source-$id.mp4。" else "\n原始视频导出未完成：$backupError"
            journal.put("state", State.FAILED.name).put("error", error)
            try { saveOrThrow() } catch (_: Exception) { }
            preferences.edit().putString("message", result).putString("uri", backup?.toString())
                .putString("recoverableId", id).apply()
            PocLog.event(context, "PHASE3_FAILED id=$id $result")
            finish(State.FAILED, result)
        }, "MusicCam-preserve").start()
    }

    private fun finish(value: State, result: String) = main.post {
        state = value
        message = result
        val notify = listener
        listener = null
        if (active === this) active = null
        // An accepted but not yet delivered service Intent must still see its cancellation.
        if (seenAudio || !audioLaunchAccepted) PlaybackCaptureService.forgetCancellation(id)
        notify?.invoke()
    }

    private fun transition(value: State, text: String) {
        state = value
        message = text
        journal.put("state", value.name)
        listener?.invoke()
    }

    private fun saveOrThrow() = SessionStorage.writeJson(File(directory, "session.json"), journal)
    private fun saveSafely() {
        try { saveOrThrow() } catch (e: Exception) {
            if (!journalFailed) { journalFailed = true; failures.add("会话日志保存失败：${describe(e)}"); stop("保存失败") }
        }
    }
    private fun describe(e: Exception) = "${e.javaClass.simpleName}: ${e.message ?: "无详细信息"}"
}

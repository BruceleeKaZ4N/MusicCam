package dev.musiccam.prototype

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.UUID
import kotlin.math.abs

/** One fresh authorization per foreground-service session; no microphone or virtual display. */
class PlaybackCaptureService : Service() {
    data class ModeStatus(val id: String? = null, val active: Boolean = false,
                          val ready: Boolean = false, val stopping: Boolean = false,
                          val message: String = "音乐模式未开启", val error: String? = null)
    data class ClipStatus(val id: String, val modeId: String, val opened: Boolean = false,
                          val finalized: Boolean = false, val startFrame: Long = 0,
                          val endFrame: Long = 0, val nonZeroSamples: Long = 0, val error: String? = null)
    private data class ClipCommand(val id: String, val open: Boolean)
    data class Status(val active: Boolean = false, val stopping: Boolean = false,
                      val message: String = "尚未开始录音", val file: File? = null,
                      val sessionId: String? = null, val recordingStartedNs: Long = 0,
                      val error: String? = null, val nonZeroSamples: Long = 0)

    companion object {
        const val ACTION_START = "dev.musiccam.prototype.START_CAPTURE"
        const val ACTION_STOP = "dev.musiccam.prototype.STOP_CAPTURE"
        private const val ACTION_STOP_CLIP = "dev.musiccam.prototype.STOP_CLIP"
        const val EXTRA_CONSENT = "consent"
        const val EXTRA_SESSION_ID = "session_id"
        private const val EXTRA_MODE_ID = "mode_id"
        const val IDLE_TIMEOUT_MS = 180_000L
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        @Volatile var status = Status()
            private set
        private var instance: PlaybackCaptureService? = null
        private val pendingStops = ConcurrentHashMap<String, String>()
        @Volatile var mode = ModeStatus()
            private set
        @Volatile var clip: ClipStatus? = null
            private set

        internal fun musicCamera() = instance?.cameraSession?.binding
        fun setMusicUiVisible(id: String?, visible: Boolean) {
            val service = instance ?: return
            if (id != null && service.modeId == id) service.uiVisible = visible
        }
        fun ownsMusicCamera(video: VideoCapture<Recorder>?) =
            video != null && instance?.cameraSession?.binding?.video === video

        fun backgroundNoticeVisible(): Boolean {
            val service = instance ?: return false
            val manager = service.getSystemService(NotificationManager::class.java)
            return manager.areNotificationsEnabled() &&
                manager.getNotificationChannel("capture")?.importance != NotificationManager.IMPORTANCE_NONE
        }

        internal fun adoptMusicCamera(id: String, owner: MusicCameraSession) {
            val service = instance ?: error("音乐模式未就绪")
            check(mode.id == id && mode.ready && !mode.stopping && service.modeId == id)
            check(owner.binding != null && service.cameraSession.binding == null) { "相机所有权不可交接" }
            check(service.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) { "摄像头权限已撤回" }
            // Called by the visible Activity before recording; never launch camera capture from background.
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (Build.VERSION.SDK_INT >= 30) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            service.startForeground(1, service.notification(camera = true), types)
            service.cameraSession.destroy()
            owner.onError = service::onCameraError
            service.cameraSession = owner
            // Keep the already running CameraX session and surfaces; transfer its owner only.
            PocLog.event(service, "MUSIC_CAMERA_BOUND mode=$id owner=foreground_service binding=reused")
        }

        fun releaseMusicCamera(id: String) {
            val service = instance ?: return
            if (service.modeId != id || service.cameraSession.binding == null) return
            service.cameraSession.release()
            PocLog.event(service, "MUSIC_CAMERA_RELEASED mode=$id")
            if (!service.stopping.get()) try {
                service.startForeground(1, service.notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } catch (e: Exception) { service.requestStop("前台服务更新失败：${e.message}", error = true) }
        }

        // UI/controller calls are confined to the main thread; PCM commands run on one worker.
        fun startMusicMode(context: Context, consent: Intent): String {
            check(instance == null && !status.active && !mode.active && !mode.stopping &&
                CombinedSessionController.active == null) { "上一会话尚未释放" }
            val id = UUID.randomUUID().toString()
            mode = ModeStatus(id, active = true, message = "正在开启音乐模式…")
            clip = null
            try {
                context.startForegroundService(Intent(context, PlaybackCaptureService::class.java)
                    .setAction(ACTION_START).putExtra(EXTRA_MODE_ID, id).putExtra(EXTRA_CONSENT, consent))
            } catch (e: Exception) {
                mode = ModeStatus(id, message = "音乐模式启动失败：${e.message}", error = e.message)
                throw e
            }
            return id
        }

        fun endMusicMode(reason: String, id: String? = mode.id) {
            if (id == null || mode.id != id || (!mode.active && !mode.stopping)) return
            val service = instance
            if (service?.modeId == id) service.requestStop(reason)
            else {
                mode = mode.copy(active = false, ready = false, stopping = true, message = "$reason；正在释放…")
                pendingStops[id] = reason
            }
        }

        fun openMusicClip(modeId: String, id: String) {
            val service = instance ?: error("音乐模式服务未就绪")
            check(mode.id == modeId && mode.ready && !mode.stopping && service.modeId == modeId &&
                (clip == null || clip?.finalized == true)) { "音乐模式不可用或上一片段未定稿" }
            clip = ClipStatus(id, modeId)
            service.idleSinceMs = 0L
            service.commands.add(ClipCommand(id, true))
            service.getSystemService(NotificationManager::class.java).notify(1, service.notification())
        }

        fun closeMusicClip(modeId: String, id: String) {
            val service = instance ?: return
            if (service.modeId == modeId && clip?.id == id && clip?.finalized != true)
                service.commands.add(ClipCommand(id, false))
        }

        fun stop(reason: String, sessionId: String? = null) {
            val service = instance
            if (service != null && (sessionId == null || service.sessionId == sessionId)) service.requestStop(reason)
            else if (sessionId != null) pendingStops[sessionId] = reason
        }
        fun forgetCancellation(sessionId: String) { pendingStops.remove(sessionId) }
    }

    private val main = Handler(Looper.getMainLooper())
    private val stopping = AtomicBoolean(false)
    private var started = false
    private var destroyed = false
    private var stopReason = "用户停止"
    private var projection: MediaProjection? = null
    private var sessionId: String? = null
    private var modeId: String? = null
    private val commands = ConcurrentLinkedQueue<ClipCommand>()
    private var idleSinceMs = 0L
    private var launchMs = 0L
    private var uiVisible = true
    @Volatile private var terminalError: String? = null
    private var screenReceiverRegistered = false
    private var cameraSession = MusicCameraSession(::onCameraError)
    private fun onCameraError(code: Int) {
        CombinedSessionController.active?.abort("摄像头异常 code=$code")
        requestStop("摄像头异常 code=$code", error = true)
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) requestStop("锁屏结束音乐模式")
        }
    }
    private val idleCheck = object : Runnable {
        override fun run() {
            if (stopping.get() || destroyed || modeId == null) return
            val elapsed = SystemClock.elapsedRealtime() - launchMs
            if (!uiVisible && !backgroundNoticeVisible()) requestStop("通知不可见，结束后台捕获", error = true)
            else if (!mode.ready && elapsed >= 10_000) requestStop("音乐模式启动超时", error = true)
            else if (elapsed >= 30 * 60_000) requestStop("音乐模式达到 30 分钟上限")
            else if (idleSinceMs > 0 && SystemClock.elapsedRealtime() - idleSinceMs >= IDLE_TIMEOUT_MS)
                requestStop("空闲 3 分钟，音乐模式已结束")
            else main.postDelayed(this, 1_000)
        }
    }
    private var recordingStartedNs = 0L
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            PocLog.event(this@PlaybackCaptureService, "PROJECTION_REVOKED onStop")
            requestStop("系统已撤销投影授权", error = true)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_CLIP) {
            if (intent.getStringExtra(EXTRA_MODE_ID) == modeId &&
                intent.getStringExtra(EXTRA_SESSION_ID) == CombinedSessionController.active?.id)
                CombinedSessionController.active?.stop("通知栏停止本段")
            if (!started) stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            val expected = intent.getStringExtra(EXTRA_MODE_ID)
            if (expected != null && expected != modeId) {
                if (!started) stopSelf()
                return START_NOT_STICKY
            }
            requestStop("通知栏停止")
            if (!started) stopSelf()
            return START_NOT_STICKY
        }
        if (started) {
            intent?.removeExtra(EXTRA_CONSENT)
            PocLog.event(this, "START_IGNORED session already active")
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) {
            stopSelf()
            return START_NOT_STICKY
        }
        started = true
        sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        modeId = intent.getStringExtra(EXTRA_MODE_ID)
        launchMs = SystemClock.elapsedRealtime()
        if (modeId == null) status = Status(active = true, message = "正在初始化系统音频捕获…", sessionId = sessionId)
        val cancelled = (modeId ?: sessionId)?.let { pendingStops.remove(it) }
        if (cancelled != null) {
            intent.removeExtra(EXTRA_CONSENT)
            complete("尚未开始采集，统一会话已取消：$cancelled", null, "会话在启动前取消")
            return START_NOT_STICKY
        }
        try {
            check(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                "缺少 RECORD_AUDIO 权限"
            }
            val consent = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(EXTRA_CONSENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_CONSENT)
            } ?: error("缺少本次用户授权，请重新开始")
            // Consent first, foreground service second, getMediaProjection third (target 34+).
            startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            PocLog.event(this, "FGS_STARTED type=mediaProjection")
            val session = getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(Activity.RESULT_OK, consent) ?: error("投影授权无效")
            projection = session
            session.registerCallback(callback, main)
            PocLog.event(this, "PROJECTION_READY callback registered; no virtual display")
            if (modeId != null) {
                if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver,
                    IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
                else registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
                screenReceiverRegistered = true
                main.postDelayed(idleCheck, 1_000)
            }
            Thread({ if (modeId != null) captureMusic(session) else capture(session) }, "MusicCam-PCM").start()
        } catch (e: Exception) {
            complete("录制启动失败：${describe(e)}", null, describe(e))
        } finally {
            // Never retain a consent Intent in our service's start Intent.
            intent.removeExtra(EXTRA_CONSENT)
        }
        return START_NOT_STICKY
    }

    private fun notification(camera: Boolean = cameraSession.binding != null): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("capture", getString(R.string.capture_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val openIntent = if (modeId != null) Intent(this, CameraActivity::class.java)
            .putExtra(CameraActivity.EXTRA_MUSIC_MODE, true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            else Intent(this, MainActivity::class.java)
        val open = PendingIntent.getActivity(this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, PlaybackCaptureService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_MODE_ID, modeId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, "capture")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(if (modeId != null) {
                if (stopping.get()) "正在结束音乐模式"
                else if (camera) "音乐模式：摄像头与系统音频正在录制" else "音乐模式：正在捕获系统音频"
            } else getString(R.string.capture_notification_title))
            .setContentText(if (modeId != null) {
                if (stopping.get()) "正在保存片段并释放资源"
                else if (camera) "切换应用继续录制；锁屏结束；可停止本段"
                else "片段间音频丢弃；空闲 3 分钟自动结束"
            } else getString(R.string.capture_notification_text))
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, if (modeId != null) "结束音乐模式" else getString(R.string.stop_recording), stop).build())
        if (modeId != null && camera) {
            val stopClip = PendingIntent.getService(this, 2,
                Intent(this, PlaybackCaptureService::class.java).setAction(ACTION_STOP_CLIP)
                    .putExtra(EXTRA_MODE_ID, modeId).putExtra(EXTRA_SESSION_ID, clip?.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(null, "停止本段并合成", stopClip).build())
        }
        return builder.build()
    }

    private fun requestStop(reason: String, error: Boolean = false) {
        if (!started || (modeId == null && !status.active) || stopping.get()) return
        stopReason = reason
        if (error) terminalError = reason
        // Requests run on the main thread; publish the reason before the worker sees the flag.
        stopping.set(true)
        if (modeId != null) {
            mode = mode.copy(active = false, ready = false, stopping = true, message = "$reason；正在释放…")
            CombinedSessionController.active?.musicModeEnded(modeId!!, reason)
        } else status = status.copy(stopping = true, message = "$reason；正在保存 WAV…")
        PocLog.event(this, "STOP_REQUEST reason=$reason")
        // Nonblocking reads let the worker observe this flag without stop/read/release races.
    }

    private fun createRecord(session: MediaProjection): Pair<AudioRecord, Int> {
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "设备不支持 48000 Hz / PCM16 / 立体声，getMinBufferSize=$minimum" }
        val bufferBytes = ((maxOf(minimum * 2, 19_200) + 3) / 4) * 4
        val config = AudioPlaybackCaptureConfiguration.Builder(session)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
        // Recheck on the worker: permission may be revoked after the UI/service check.
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("RECORD_AUDIO 权限已撤销")
        }
        val audio = AudioRecord.Builder().setAudioPlaybackCaptureConfig(config)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build())
            .setBufferSizeInBytes(bufferBytes).build()
        try {
            check(audio.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord 初始化失败" }
            check(audio.sampleRate == SAMPLE_RATE && audio.channelCount == CHANNELS &&
                audio.audioFormat == AudioFormat.ENCODING_PCM_16BIT) { "AudioRecord 返回的格式与预设不一致" }
            PocLog.event(this, "FORMAT rate=${audio.sampleRate} channels=${audio.channelCount} bits=16 minBuffer=$minimum buffer=$bufferBytes")
            return audio to bufferBytes
        } catch (e: Exception) { audio.release(); throw e }
    }


    private fun captureMusic(session: MediaProjection) {
        val id = modeId ?: return
        var record: AudioRecord? = null
        var writer: MusicPcmSession? = null
        var failure: String? = null
        try {
            val (audio, bufferBytes) = createRecord(session)
            record = audio
            if (stopping.get()) return
            audio.startRecording()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "AudioRecord 未进入录音状态" }
            recordingStartedNs = SystemClock.elapsedRealtimeNanos()
            val pcm = MusicPcmSession(this, id, recordingStartedNs) { update -> main.post {
                if (clip?.id == update.id && clip?.modeId == id) {
                    clip = update
                    if (update.finalized && !stopping.get()) idleSinceMs = SystemClock.elapsedRealtime()
                }
            } }
            writer = pcm
            main.post {
                if (!stopping.get() && !destroyed) {
                    mode = mode.copy(ready = true, message = "音乐模式已开启：持续捕获系统音频\n请点「开始音视频录制」拍摄；切换应用可继续，锁屏结束。")
                    if (!backgroundNoticeVisible()) mode = mode.copy(
                        message = "音乐模式已开启：持续捕获系统音频\n通知未开启，切换应用会结束模式。请点「开始音视频录制」。")
                    idleSinceMs = SystemClock.elapsedRealtime()
                }
            }
            PocLog.event(this, "MUSIC_MODE_READY mode=$id captureStartedNs=$recordingStartedNs")
            val samples = ShortArray(bufferBytes / 2)
            var lastReadMs = SystemClock.elapsedRealtime()
            var nextAnchorNs = 0L
            var lastAnchorFrame = -1L
            var lastAnchorNs = 0L
            var baselineLag: Long? = null
            var latestAnchor: Pair<Long, Long>? = null
            var latestAnchorReceivedNs = 0L
            var drainingSinceNs = 0L
            while (!stopping.get()) {
                val count = audio.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING)
                val now = SystemClock.elapsedRealtimeNanos()
                check(count >= 0) { "AudioRecord.read 失败，返回码=$count" }
                if (stopping.get()) break
                var anchor: Pair<Long, Long>? = null
                if (count > 0 && now >= nextAnchorNs) {
                    val ts = AudioTimestamp()
                    val result = audio.getTimestamp(ts, AudioTimestamp.TIMEBASE_BOOTTIME)
                    if (result == AudioRecord.SUCCESS && ts.nanoTime > 0 && ts.framePosition >= 0) {
                        check(ts.framePosition >= lastAnchorFrame && ts.nanoTime >= lastAnchorNs) { "连续音频时间戳回退" }
                        lastAnchorFrame = ts.framePosition
                        lastAnchorNs = ts.nanoTime
                        // Conservative drift/backlog guard. Do not silently splice a known discontinuity.
                        val lag = ts.framePosition - (pcm.globalFrames + count / CHANNELS)
                        if (now - recordingStartedNs > 1_000_000_000L) {
                            val previous = baselineLag
                            if (previous == null) baselineLag = lag
                            else check(abs(lag - previous) <= audio.bufferSizeInFrames * 2L + SAMPLE_RATE / 10) {
                                "连续 PCM 与时间戳偏离过大，可能丢帧；结束模式并保留文件"
                            }
                        }
                        anchor = ts.framePosition to ts.nanoTime
                        latestAnchor = anchor
                        latestAnchorReceivedNs = now
                        nextAnchorNs = now + 1_000_000_000L
                    } else nextAnchorNs = now + 100_000_000L
                }
                if (count > 0) {
                    pcm.pcm(samples, count, now, anchor ?: latestAnchor?.takeIf {
                        now - latestAnchorReceivedNs <= 1_500_000_000L
                    })
                    lastReadMs = SystemClock.elapsedRealtime()
                } else check(SystemClock.elapsedRealtime() - lastReadMs < 5_000) { "5 秒未读到 PCM 数据" }
                // Drain the returned block before opening: previously queued gap audio is discarded.
                while (true) {
                    if (commands.peek()?.open == true && count > 0) {
                        if (drainingSinceNs == 0L) drainingSinceNs = now
                        check(now - drainingSinceNs < 500_000_000L) { "无法排空片段间音频缓冲" }
                        break
                    }
                    val command = commands.poll() ?: break
                    drainingSinceNs = 0L
                    if (command.open) pcm.open(command.id)
                    else if (pcm.clipId == command.id) pcm.close()
                }
                if (count == 0) Thread.sleep(10)
            }
        } catch (e: Exception) {
            failure = describe(e)
            val reason = failure
            main.post { requestStop("音频异常：$reason") }
        } finally {
            try { record?.let { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() } }
            catch (e: Exception) { failure = listOfNotNull(failure, describe(e)).joinToString("；") }
            finally { record?.release() }
            writer?.close(failure ?: terminalError)
            commands.clear()
            val error = failure ?: terminalError
            main.post {
                val pending = clip
                if (pending?.modeId == id && !pending.finalized)
                    clip = pending.copy(finalized = true, error = error ?: "音乐模式在片段启动前结束")
                complete(error ?: stopReason, null, error)
            }
        }
    }

    private fun capture(session: MediaProjection) {
        var record: AudioRecord? = null
        var wav: WavFile? = null
        var saved: File? = null
        var failure: String? = null
        var nonZero = 0L
        var peak = 0
        var timing: AudioCaptureTiming? = null
        try {
            val (audio, bufferBytes) = createRecord(session)
            record = audio
            if (!stopping.get()) {
                val directory = sessionId?.let { SessionStorage.directory(this, it) } ?: File(filesDir, "recordings")
                check(directory.isDirectory || directory.mkdirs()) { "无法创建录音目录" }
                val output = WavFile(File(directory, if (sessionId == null) "capture-${System.currentTimeMillis()}.wav" else "audio.wav"), SAMPLE_RATE, CHANNELS)
                wav = output
                if (sessionId != null) timing = AudioCaptureTiming(File(directory, "audio-timing.json"))
                timing?.beforeStart()
                audio.startRecording()
                check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "AudioRecord 未进入录音状态" }
                recordingStartedNs = SystemClock.elapsedRealtimeNanos()
                timing?.afterStart()
                main.post {
                    if (!stopping.get() && !destroyed) status = status.copy(active = true,
                        recordingStartedNs = recordingStartedNs,
                        message = "正在捕获系统播放音频\n48000 Hz · 16-bit · 立体声\n可播放测试音，然后停止并检查结果。")
                }
                PocLog.event(this, "RECORDING_STARTED")
                val samples = ShortArray(bufferBytes / 2)
                var lastRead = SystemClock.elapsedRealtime()
                var lastUpdate = lastRead
                while (!stopping.get()) {
                    val count = audio.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING)
                    val readReturnedNs = if (timing != null) SystemClock.elapsedRealtimeNanos() else 0L
                    check(count >= 0) { "AudioRecord.read 失败，返回码=$count" }
                    if (stopping.get()) break
                    if (count == 0) {
                        check(SystemClock.elapsedRealtime() - lastRead < 5_000) { "5 秒未读到 PCM 数据" }
                        Thread.sleep(10)
                        continue
                    }
                    check(count % CHANNELS == 0) { "PCM 数据不是完整的立体声帧" }
                    val framesBefore = output.dataBytes / (CHANNELS * 2)
                    output.append(samples, count)
                    timing?.pcm(audio, samples, count, framesBefore, readReturnedNs)
                    for (i in 0 until count) {
                        val magnitude = abs(samples[i].toInt())
                        if (magnitude != 0) nonZero++
                        peak = maxOf(peak, magnitude)
                    }
                    lastRead = SystemClock.elapsedRealtime()
                    if (lastRead - lastUpdate >= 1_000) {
                        val seconds = output.dataBytes.toDouble() / (SAMPLE_RATE * CHANNELS * 2)
                        val update = String.format(Locale.ROOT, "正在录音：%.1f 秒 · %d PCM 字节\n48000 Hz / PCM16 / 立体声 · 峰值 %d", seconds, output.dataBytes, peak)
                        main.post { if (!stopping.get() && !destroyed) status = status.copy(active = true, message = update) }
                        lastUpdate = lastRead
                    }
                }
            }
        } catch (e: Exception) {
            failure = describe(e)
        } finally {
            try {
                record?.let { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() }
            } catch (e: Exception) {
                failure = listOfNotNull(failure, "停止失败：${describe(e)}").joinToString("；")
            } finally {
                record?.release()
            }
            try {
                saved = wav?.finish()
            } catch (e: Exception) {
                failure = listOfNotNull(failure, "WAV 保存失败：${describe(e)}").joinToString("；")
            }
            val bytes = wav?.dataBytes ?: 0L
            try { timing?.finish(bytes / (CHANNELS * 2), nonZero, failure) }
            catch (e: Exception) { failure = listOfNotNull(failure, "时间记录失败：${describe(e)}").joinToString("；") }
            val summary = when {
                saved == null -> "未生成 WAV：${failure ?: "没有读到音频数据（$stopReason）"}"
                else -> String.format(Locale.ROOT,
                    "%s\n%.2f 秒 · %d 字节（PCM %d）\n48000 Hz / 16-bit / 立体声\n非零样本 %d · 峰值 %d\n%s\n%s",
                    if (failure == null) "已保存 WAV（$stopReason）" else "录制失败，已保存部分 WAV：$failure",
                    bytes.toDouble() / (SAMPLE_RATE * CHANNELS * 2), saved.length(), bytes,
                    nonZero, peak, saved.name,
                    if (nonZero == 0L) "警告：全静音，不能判定捕获成功。请检查音源、usage、profile、会话和路由。"
                    else "检测到非静音数据；请回放确认音频内容。")
            }
            PocLog.event(this, "CAPTURE_FINISHED bytes=$bytes nonZero=$nonZero peak=$peak saved=${saved?.name} error=${failure ?: "none"} reason=$stopReason")
            val result = saved
            val error = failure
            val nonZeroResult = nonZero
            main.post { complete(summary, result, error, nonZeroResult) }
        }
    }

    private fun complete(message: String, file: File?, error: String? = null, nonZero: Long = 0) {
        releaseProjection()
        if (modeId != null) {
            main.removeCallbacks(idleCheck)
            mode = ModeStatus(modeId, stopping = true, message = "音乐模式已结束：$message", error = error)
            PocLog.event(this, "MUSIC_MODE_CLOSED mode=$modeId reason=$stopReason error=${error ?: "none"}")
            // Keep the camera lifecycle until its requested stop receives Finalize, bounded at 20s.
            val deadline = SystemClock.elapsedRealtime() + 20_000
            val finish = object : Runnable {
                override fun run() {
                    if (destroyed) return
                    if (cameraSession.binding != null && SystemClock.elapsedRealtime() < deadline) {
                        main.postDelayed(this, 100)
                        return
                    }
                    cameraSession.release()
                    mode = mode.copy(stopping = false)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    PocLog.event(this@PlaybackCaptureService, "MUSIC_RESOURCES_RELEASED mode=$modeId")
                }
            }
            main.post(finish)
            return
        }
        status = Status(message = message, file = if (sessionId == null) file else null,
            sessionId = sessionId, recordingStartedNs = recordingStartedNs, error = error, nonZeroSamples = nonZero)
        // A combined session's temporary WAV must not replace Phase 1's replay preference.
        if (sessionId == null) getSharedPreferences("capture", MODE_PRIVATE).edit()
            .putString("message", message).putString("file", file?.name).apply()
        PocLog.event(this, "SESSION_CLOSED $message")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseProjection() {
        val session = projection
        projection = null
        session?.unregisterCallback(callback)
        try { session?.stop() } catch (e: Exception) {
            PocLog.event(this, "PROJECTION_STOP_ERROR ${describe(e)}")
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        requestStop("应用任务被移除")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        destroyed = true
        requestStop("录音服务结束")
        releaseProjection()
        main.removeCallbacks(idleCheck)
        if (screenReceiverRegistered) unregisterReceiver(screenReceiver)
        cameraSession.destroy()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun describe(e: Exception) = "${e.javaClass.simpleName}: ${e.message ?: "无详细信息"}"
}

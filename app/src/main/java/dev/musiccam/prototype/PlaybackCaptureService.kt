package dev.musiccam.prototype

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** One fresh authorization per foreground-service session; no microphone or virtual display. */
class PlaybackCaptureService : Service() {
    data class Status(val active: Boolean = false, val stopping: Boolean = false,
                      val message: String = "尚未开始录音", val file: File? = null)

    companion object {
        const val ACTION_START = "dev.musiccam.prototype.START_CAPTURE"
        const val ACTION_STOP = "dev.musiccam.prototype.STOP_CAPTURE"
        const val EXTRA_CONSENT = "consent"
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        @Volatile var status = Status()
            private set
        private var instance: PlaybackCaptureService? = null

        fun stop(reason: String) { instance?.requestStop(reason) }
    }

    private val main = Handler(Looper.getMainLooper())
    private val stopping = AtomicBoolean(false)
    private var started = false
    private var destroyed = false
    private var stopReason = "用户停止"
    private var projection: MediaProjection? = null
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            PocLog.event(this@PlaybackCaptureService, "PROJECTION_REVOKED onStop")
            requestStop("系统已撤销投影授权")
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            requestStop("通知栏停止")
            if (!started) stopSelf()
            return START_NOT_STICKY
        }
        if (started) {
            PocLog.event(this, "START_IGNORED session already active")
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) {
            stopSelf()
            return START_NOT_STICKY
        }
        started = true
        status = Status(active = true, message = "正在初始化系统音频捕获…")
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
            Thread({ capture(session) }, "MusicCam-PCM").start()
        } catch (e: Exception) {
            complete("录制启动失败：${describe(e)}", null)
        } finally {
            // Never retain a consent Intent in our service's start Intent.
            intent.removeExtra(EXTRA_CONSENT)
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("capture", getString(R.string.capture_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, PlaybackCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "capture")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(getString(R.string.capture_notification_text))
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop_recording), stop).build())
            .build()
    }

    private fun requestStop(reason: String) {
        if (!started || !status.active || stopping.get()) return
        stopReason = reason
        // Requests run on the main thread; publish the reason before the worker sees the flag.
        stopping.set(true)
        status = status.copy(stopping = true, message = "$reason；正在保存 WAV…")
        PocLog.event(this, "STOP_REQUEST reason=$reason")
        // Nonblocking reads let the worker observe this flag without stop/read/release races.
    }

    private fun capture(session: MediaProjection) {
        var record: AudioRecord? = null
        var wav: WavFile? = null
        var saved: File? = null
        var failure: String? = null
        var nonZero = 0L
        var peak = 0
        try {
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
            record = audio
            check(audio.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord 初始化失败" }
            check(audio.sampleRate == SAMPLE_RATE && audio.channelCount == CHANNELS &&
                audio.audioFormat == AudioFormat.ENCODING_PCM_16BIT) { "AudioRecord 返回的格式与预设不一致" }
            PocLog.event(this, "FORMAT rate=${audio.sampleRate} channels=${audio.channelCount} bits=16 minBuffer=$minimum buffer=$bufferBytes")
            if (!stopping.get()) {
                val directory = File(filesDir, "recordings")
                check(directory.isDirectory || directory.mkdirs()) { "无法创建录音目录" }
                val output = WavFile(File(directory, "capture-${System.currentTimeMillis()}.wav"), SAMPLE_RATE, CHANNELS)
                wav = output
                audio.startRecording()
                check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "AudioRecord 未进入录音状态" }
                main.post {
                    if (!stopping.get() && !destroyed) status = Status(active = true,
                        message = "正在捕获系统播放音频\n48000 Hz · 16-bit · 立体声\n可播放测试音，然后停止并检查结果。")
                }
                PocLog.event(this, "RECORDING_STARTED")
                val samples = ShortArray(bufferBytes / 2)
                var lastRead = SystemClock.elapsedRealtime()
                var lastUpdate = lastRead
                while (!stopping.get()) {
                    val count = audio.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING)
                    check(count >= 0) { "AudioRecord.read 失败，返回码=$count" }
                    if (stopping.get()) break
                    if (count == 0) {
                        check(SystemClock.elapsedRealtime() - lastRead < 5_000) { "5 秒未读到 PCM 数据" }
                        Thread.sleep(10)
                        continue
                    }
                    check(count % CHANNELS == 0) { "PCM 数据不是完整的立体声帧" }
                    output.append(samples, count)
                    for (i in 0 until count) {
                        val magnitude = abs(samples[i].toInt())
                        if (magnitude != 0) nonZero++
                        peak = maxOf(peak, magnitude)
                    }
                    lastRead = SystemClock.elapsedRealtime()
                    if (lastRead - lastUpdate >= 1_000) {
                        val seconds = output.dataBytes.toDouble() / (SAMPLE_RATE * CHANNELS * 2)
                        val update = String.format(Locale.ROOT, "正在录音：%.1f 秒 · %d PCM 字节\n48000 Hz / PCM16 / 立体声 · 峰值 %d", seconds, output.dataBytes, peak)
                        main.post { if (!stopping.get() && !destroyed) status = Status(active = true, message = update) }
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
            main.post { complete(summary, result) }
        }
    }

    private fun complete(message: String, file: File?) {
        releaseProjection()
        status = Status(message = message, file = file)
        getSharedPreferences("capture", MODE_PRIVATE).edit()
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
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun describe(e: Exception) = "${e.javaClass.simpleName}: ${e.message ?: "无详细信息"}"
}

package dev.musiccam.prototype

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/** Small PoC UI. The service owns capture; test playback is explicit and independent. */
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView
    private lateinit var sourceView: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var replayButton: Button
    private var pending = false
    private var startingService = false
    private var beforeStart: PlaybackCaptureService.Status? = null
    private var lastCaptureStatus = PlaybackCaptureService.status
    private var localMessage: String? = null
    private var tone: AudioTrack? = null
    private var player: MediaPlayer? = null
    private val refresh = object : Runnable {
        override fun run() {
            render()
            main.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val errorWrite = Log.e(PocLog.TAG, "PHASE1_LOG_PROBE_ERROR intentional_probe_not_failure pid=${android.os.Process.myPid()}")
        PocLog.event(this, "PHASE1_LOG_PROBE onCreate package=$packageName api=${Build.VERSION.SDK_INT} errorWrite=$errorWrite infoLoggable=${Log.isLoggable(PocLog.TAG, Log.INFO)}")
        pending = savedInstanceState?.getBoolean("pending") ?: false
        val spacing = (20 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(spacing, spacing, spacing, spacing)
        }
        content.addView(TextView(this).apply { setText(R.string.phase_one_intro); textSize = 18f })
        button(content, R.string.open_combined) {
            stopPlayback()
            startActivity(Intent(this, CameraActivity::class.java).putExtra(CameraActivity.EXTRA_COMBINED, true))
        }
        button(content, R.string.open_camera) {
            startActivity(Intent(this, CameraActivity::class.java))
        }
        startButton = button(content, R.string.start_recording) { begin() }
        stopButton = button(content, R.string.stop_recording) {
            PlaybackCaptureService.stop("用户停止")
            render()
        }
        statusView = TextView(this).apply { textSize = 16f; setTextIsSelectable(true) }
        content.addView(statusView)
        content.addView(TextView(this).apply { setText(R.string.baseline_instructions) })
        button(content, R.string.play_allowed_tone) { playTone(true) }
        button(content, R.string.play_denied_tone) { playTone(false) }
        button(content, R.string.stop_playback) { stopPlayback() }
        sourceView = TextView(this).apply { setText(R.string.source_idle) }
        content.addView(sourceView)
        replayButton = button(content, R.string.replay_wav) { replay() }
        button(content, R.string.exit_app) {
            pending = false
            PlaybackCaptureService.stop("用户退出应用")
            stopPlayback()
            finishAndRemoveTask()
        }
        val scroll = ScrollView(this).apply {
            addView(content)
            setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                        insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                }
                insets
            }
        }
        setContentView(scroll)
        scroll.requestApplyInsets()
    }

    private fun button(parent: LinearLayout, label: Int, action: () -> Unit): Button =
        Button(this).apply { setText(label); setOnClickListener { action() }; parent.addView(this) }

    private fun begin() {
        if (pending || startingService || PlaybackCaptureService.status.active || CombinedSessionController.active != null) return
        stopReplay()
        pending = true
        localMessage = null
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            localMessage = getString(R.string.waiting_audio_permission)
            PocLog.event(this, "RECORD_AUDIO_REQUEST")
            val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            requestPermissions(permissions.toTypedArray(), 1)
        } else requestProjection()
        render()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 1 || !pending || isFinishing) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            PocLog.event(this, "RECORD_AUDIO_GRANTED notifications=${Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED}")
            requestProjection()
        } else {
            pending = false
            localMessage = getString(R.string.audio_permission_denied)
            PocLog.event(this, "RECORD_AUDIO_DENIED")
        }
        render()
    }

    private fun requestProjection() {
        try {
            localMessage = getString(R.string.waiting_projection)
            PocLog.event(this, "PROJECTION_CONSENT_REQUEST fresh=true")
            @Suppress("DEPRECATION")
            startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), 2)
        } catch (e: Exception) {
            pending = false
            reportError("无法打开系统授权", e)
        }
    }

    @Deprecated("Platform Activity result callback retained for this dependency-free PoC")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 2 || !pending || isFinishing) return
        pending = false
        if (resultCode != RESULT_OK || data == null) {
            localMessage = getString(R.string.projection_denied)
            PocLog.event(this, "PROJECTION_CONSENT_DENIED")
        } else {
            try {
                localMessage = null
                startingService = true
                beforeStart = PlaybackCaptureService.status
                startForegroundService(Intent(this, PlaybackCaptureService::class.java)
                    .setAction(PlaybackCaptureService.ACTION_START)
                    .putExtra(PlaybackCaptureService.EXTRA_CONSENT, data))
                PocLog.event(this, "PROJECTION_CONSENT_GRANTED start foreground service")
            } catch (e: Exception) {
                startingService = false
                reportError("前台服务启动失败，请重新授权", e)
            }
        }
        render()
    }

    private fun currentFile(): File? {
        PlaybackCaptureService.status.file?.let { return it.takeIf(File::isFile) }
        val name = getSharedPreferences("capture", MODE_PRIVATE).getString("file", null) ?: return null
        return File(File(filesDir, "recordings"), name).takeIf(File::isFile)
    }

    private fun render() {
        val state = PlaybackCaptureService.status
        if (state !== lastCaptureStatus && !state.active && lastCaptureStatus.active) localMessage = null
        lastCaptureStatus = state
        if (startingService && state !== beforeStart) startingService = false
        val busy = state.active || pending || startingService
        startButton.isEnabled = !busy
        stopButton.isEnabled = state.active && !state.stopping
        replayButton.isEnabled = !busy && currentFile() != null
        val previous = getSharedPreferences("capture", MODE_PRIVATE).getString("message", null)
        val interrupted = File(filesDir, "recordings").listFiles()?.any { it.name.endsWith(".part") } == true
        statusView.text = when {
            state.active -> state.message
            startingService -> getString(R.string.starting_service)
            pending -> localMessage ?: getString(R.string.waiting_projection)
            localMessage != null -> localMessage
            interrupted -> getString(R.string.incomplete_file_warning) + "\n" + (previous ?: state.message)
            else -> previous ?: state.message
        }
    }

    private fun playTone(allowed: Boolean) {
        stopPlayback()
        var audio: AudioTrack? = null
        try {
            val rate = PlaybackCaptureService.SAMPLE_RATE
            val samples = ShortArray(rate * 2)
            for (frame in 0 until rate) {
                samples[frame * 2] = (5_000 * sin(2 * PI * 440 * frame / rate)).toInt().toShort()
                samples[frame * 2 + 1] = (5_000 * sin(2 * PI * 880 * frame / rate)).toInt().toShort()
            }
            getSystemService(AudioManager::class.java).setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL)
            val policy = if (allowed) AudioAttributes.ALLOW_CAPTURE_BY_ALL else AudioAttributes.ALLOW_CAPTURE_BY_NONE
            audio = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).setAllowedCapturePolicy(policy).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(samples.size * 2).build()
            // MODE_STATIC is STATE_NO_STATIC_DATA until the first successful write.
            check(audio.state != AudioTrack.STATE_UNINITIALIZED) { "测试播放器初始化失败，state=${audio.state}" }
            check(audio.write(samples, 0, samples.size) == samples.size) { "测试音写入不完整" }
            check(audio.state == AudioTrack.STATE_INITIALIZED) { "测试音写入后播放器未就绪，state=${audio.state}" }
            check(audio.setLoopPoints(0, rate, -1) == AudioTrack.SUCCESS) { "测试音循环设置失败" }
            audio.play()
            tone = audio
            sourceView.setText(if (allowed) R.string.allowed_tone_playing else R.string.denied_tone_playing)
            PocLog.event(this, "TEST_TONE_STARTED usage=MEDIA policy=${if (allowed) "ALL" else "NONE"} left=440Hz right=880Hz rate=$rate")
            main.postDelayed({
                tone?.routedDevice?.let { PocLog.event(this, "TEST_TONE_ROUTE type=${it.type}") }
            }, 1_000)
        } catch (e: Exception) {
            audio?.release()
            tone = null
            reportError("测试音播放失败", e)
        }
    }

    private fun replay() {
        if (pending || startingService || PlaybackCaptureService.status.active) return
        val file = currentFile() ?: return
        stopPlayback()
        val playback = MediaPlayer()
        player = playback
        try {
            playback.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            playback.setDataSource(file.path)
            playback.setOnPreparedListener {
                if (player === it) {
                    it.start()
                    sourceView.setText(R.string.wav_playing)
                    PocLog.event(this, "WAV_PLAYBACK_STARTED file=${file.name} durationMs=${it.duration}")
                }
            }
            playback.setOnCompletionListener {
                PocLog.event(this, "WAV_PLAYBACK_COMPLETED")
                stopReplay()
                sourceView.setText(R.string.source_idle)
            }
            playback.setOnErrorListener { _, what, extra ->
                stopReplay()
                localMessage = "WAV 回放失败：what=$what extra=$extra"
                PocLog.event(this, "WAV_PLAYBACK_ERROR what=$what extra=$extra")
                render()
                true
            }
            playback.prepareAsync()
        } catch (e: Exception) {
            stopReplay()
            reportError("WAV 回放失败", e)
        }
    }

    private fun stopReplay() { player?.release(); player = null }

    private fun stopPlayback() {
        stopReplay()
        tone?.let { try { it.stop() } finally { it.release() } }
        tone = null
        if (::sourceView.isInitialized) sourceView.setText(R.string.source_idle)
        PocLog.event(this, "TEST_PLAYBACK_STOPPED")
    }

    private fun reportError(prefix: String, e: Exception) {
        localMessage = "$prefix：${e.javaClass.simpleName}: ${e.message ?: "无详细信息"}"
        PocLog.event(this, "UI_ERROR $localMessage")
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pending", pending)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() { super.onResume(); main.post(refresh) }
    override fun onPause() { main.removeCallbacks(refresh); super.onPause() }
    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        stopPlayback()
        if (isFinishing) PlaybackCaptureService.stop("应用页面退出")
        super.onDestroy()
    }
}

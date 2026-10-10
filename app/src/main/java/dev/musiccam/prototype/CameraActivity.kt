package dev.musiccam.prototype

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.SystemClock
import android.view.View
import org.json.JSONObject
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Range
import android.view.Surface
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraInfo
import androidx.camera.core.DynamicRange
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import java.util.Locale
import java.util.UUID

/** Both modes keep CameraX as the video owner; the combined controller owns audio and composition. */
class CameraActivity : ComponentActivity() {
    companion object { const val EXTRA_COMBINED = "combined" }
    private val combined get() = intent.getBooleanExtra(EXTRA_COMBINED, false)
    private var combinedPending = false
    private var combinedPermissionsPending = false
    private var projectionPending = false
    private var consent: Intent? = null // In-memory, single use; never save an authorization Intent.
    private var buttonNs = 0L
    private var authorization = JSONObject()
    private var attachedController: CombinedSessionController? = null
    private var syncProbe: SyncProbe? = null
    private lateinit var syncView: TextView
    private var retryButton: Button? = null
    private var probeButton: Button? = null
    private val combinedPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        combinedPermissionsPending = false
        authorization.put("permissionsResultNs", SystemClock.elapsedRealtimeNanos())
        if (!hasCameraPermission() || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            combinedPending = false
            statusView.text = "摄像头或播放音频权限被拒绝；未开始录制。永久拒绝时请在应用设置授权。"
            PocLog.event(this, "PHASE3_PERMISSION_DENIED no_capture=true")
        } else continueCombinedStart()
        renderControls()
    }
    private val projectionResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        projectionPending = false
        authorization.put("projectionResultNs", SystemClock.elapsedRealtimeNanos())
        if (!combinedPending || isFinishing) return@registerForActivityResult
        if (result.resultCode != RESULT_OK || result.data == null) {
            combinedPending = false
            statusView.setText(R.string.projection_denied)
            PocLog.event(this, "PHASE3_PROJECTION_DENIED no_capture=true")
        } else {
            consent = result.data
            PocLog.event(this, "PHASE3_PROJECTION_GRANTED fresh=true")
            continueCombinedStart()
        }
        renderControls()
    }
    private lateinit var previewView: PreviewView
    private lateinit var statusView: TextView
    private lateinit var resultView: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var switchButton: Button
    private lateinit var permissionButton: Button
    private lateinit var openButton: Button
    private var provider: ProcessCameraProvider? = null
    private var loading = false
    private var permissionPending = false
    private var preview: Preview? = null
    private var capture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var finalizing = false
    private var stopping = false
    private var ready = false
    private var resumed = false
    private var observedCamera: CameraInfo? = null
    private var lens = CameraSelector.LENS_FACING_BACK
    private var configuration = ""
    private var stopReason = "用户停止"
    private val preferences by lazy { getSharedPreferences(if (combined) "combined" else "camera", MODE_PRIVATE) }
    private val resultsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> renderResult() }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionPending = false
        PocLog.event(this, "PHASE2_CAMERA_PERMISSION granted=$granted")
        if (granted) {
            statusView.text = "摄像头已授权，正在准备预览…"
            if (isResumed()) bindCamera()
        } else {
            statusView.text = "摄像头权限被拒绝，未开始录像。可再次授权；永久拒绝时请在应用设置中允许摄像头。"
        }
        renderControls()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lens = savedInstanceState?.getInt("lens") ?: CameraSelector.LENS_FACING_BACK
        combinedPending = savedInstanceState?.getBoolean("combinedPending") ?: false
        combinedPermissionsPending = savedInstanceState?.getBoolean("combinedPermissionsPending") ?: false
        projectionPending = savedInstanceState?.getBoolean("projectionPending") ?: false
        buttonNs = savedInstanceState?.getLong("buttonNs") ?: 0L
        savedInstanceState?.getString("authorization")?.let { authorization = JSONObject(it) }
        // A granted but not yet consumed Intent is deliberately discarded on reconstruction.
        if (combinedPending && !projectionPending && !combinedPermissionsPending) combinedPending = false
        val spacing = (12 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(spacing, spacing, spacing, spacing)
        }
        content.addView(TextView(this).apply { setText(if (combined) R.string.combined_intro else R.string.camera_intro); textSize = 16f })
        syncView = TextView(this).apply { visibility = View.GONE }
        content.addView(syncView, LinearLayout.LayoutParams(-1, (64 * resources.displayMetrics.density).toInt()))
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
        content.addView(previewView, LinearLayout.LayoutParams(-1, 0, 1f))
        val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        statusView = TextView(this).apply { text = "点击授权摄像头以开始预览。" }
        details.addView(statusView)
        permissionButton = button(details, R.string.camera_permission) {
            if (hasCameraPermission()) bindCamera() else {
                permissionPending = true
                renderControls()
                cameraPermission.launch(Manifest.permission.CAMERA)
            }
        }
        val controls = LinearLayout(this)
        startButton = button(controls, if (combined) R.string.combined_start else R.string.camera_start) {
            if (combined) beginCombined() else startRecording()
        }
        stopButton = button(controls, if (combined) R.string.combined_stop else R.string.camera_stop) { stopRecording("用户停止") }
        switchButton = button(controls, R.string.camera_switch) {
            lens = if (lens == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            bindCamera()
        }
        details.addView(controls)
        if (combined) for (index in 0 until controls.childCount) {
            controls.getChildAt(index).layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        resultView = TextView(this).apply { setTextIsSelectable(true) }
        details.addView(resultView)
        openButton = button(details, R.string.camera_open_video) { openVideo() }
        if (combined) {
            retryButton = button(details, R.string.combined_retry) {
                val id = preferences.getString("recoverableId", null)
                if (id != null && !busy()) try {
                    CombinedSessionController.retry(this, id)
                    attachCombined()
                } catch (e: Exception) { reportError("无法重试，原始数据仍保留", e) }
            }
            probeButton = button(details, R.string.combined_probe) {
                val active = CombinedSessionController.active
                if (active?.state == CombinedSessionController.State.RECORDING) try {
                    if (syncProbe != null) stopProbe() else {
                        SyncProbe(syncView, active.directory).also { it.start(); syncProbe = it }
                    }
                } catch (e: Exception) { reportError("声光测试失败", e) }
            }
        }
        button(details, R.string.camera_back) { finish() }
        content.addView(ScrollView(this).apply { addView(details) },
            LinearLayout.LayoutParams(-1, minOf(320 * resources.displayMetrics.density,
                resources.displayMetrics.heightPixels * 0.45f).toInt()))
        content.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(spacing + bars.left, spacing + bars.top, spacing + bars.right, spacing + bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(spacing + insets.systemWindowInsetLeft, spacing + insets.systemWindowInsetTop,
                    spacing + insets.systemWindowInsetRight, spacing + insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(content)
        content.requestApplyInsets()
        preferences.registerOnSharedPreferenceChangeListener(resultsListener)
        renderResult()
        renderControls()
        attachCombined()
        PocLog.event(this, "PHASE2_CAMERA_PAGE api=${Build.VERSION.SDK_INT} audioEnabled=false")
    }

    private fun button(parent: LinearLayout, label: Int, action: () -> Unit) = Button(this).apply {
        setText(label)
        setOnClickListener { action() }
        parent.addView(this)
    }

    private fun hasCameraPermission() = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun isResumed() = resumed && !isFinishing && !isDestroyed
    private fun busy() = recording != null || finalizing ||
        (combined && (combinedPending || CombinedSessionController.active != null))

    private fun bindCamera() {
        if (!isResumed() || recording != null || finalizing || loading || !hasCameraPermission() ||
            (combined && CombinedSessionController.active != null)) return
        if (provider == null) {
            loading = true
            statusView.text = "正在初始化摄像头…"
            renderControls()
            val future = ProcessCameraProvider.getInstance(this)
            future.addListener({
                loading = false
                if (isDestroyed) return@addListener
                try { provider = future.get(); bindCamera() }
                catch (e: Exception) { reportError("摄像头初始化失败", e) }
                renderControls()
            }, mainExecutor)
            return
        }
        ready = false
        val cameras = provider ?: return
        unbindCamera()
        try {
            val selector = CameraSelector.Builder().requireLensFacing(lens).build()
            check(cameras.hasCamera(selector)) { "设备没有${lensName()}摄像头，请切换或重试" }
            val info = cameras.getCameraInfo(selector)
            val supported = Recorder.getVideoCapabilities(info).getSupportedQualities(DynamicRange.SDR)
            val qualities = listOf(Quality.FHD, Quality.HD, Quality.SD).filter { it in supported }
                .ifEmpty { supported.takeLast(1) }
            check(qualities.isNotEmpty()) { "摄像头没有可用的 SDR 录像配置" }
            val frameRates = info.supportedFrameRateRanges
            val fps = if (frameRates.any { it.contains(30) }) Range(30, 30) else
                frameRates.minByOrNull { kotlin.math.abs(it.upper - 30) }
            var lastError: Exception? = null
            // A camera may advertise FHD yet reject the Preview + VideoCapture combination.
            val attempts = qualities.flatMap { quality ->
                (if (fps == null) listOf(null) else listOf(fps, null)).map { rate -> quality to rate }
            }
            for ((quality, requestedRate) in attempts) {
                try {
                    val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(quality)).build()
                    val builder = VideoCapture.Builder(recorder).setDynamicRange(DynamicRange.SDR)
                        .setTargetRotation(previewView.display?.rotation ?: Surface.ROTATION_0)
                    if (requestedRate != null) builder.setTargetFrameRate(requestedRate)
                    val video = builder.build()
                    val viewfinder = Preview.Builder().build()
                    viewfinder.setSurfaceProvider(previewView.surfaceProvider)
                    val camera = cameras.bindToLifecycle(this, selector, viewfinder, video)
                    preview = viewfinder
                    capture = video
                    val qualityName = when (quality) {
                        Quality.FHD -> "1080p"
                        Quality.HD -> "720p"
                        Quality.SD -> "480p"
                        Quality.UHD -> "4K"
                        else -> "设备画质"
                    }
                    val frameRateName = requestedRate?.let {
                        if (it.lower == it.upper) "目标 ${it.upper}fps" else "目标 ${it.lower}–${it.upper}fps"
                    } ?: "设备默认帧率"
                    configuration = "${lensName()} · $qualityName · $frameRateName"
                    ready = true
                    statusView.text = "预览已绑定：$configuration\n实际分辨率和帧率以输出文件为准。"
                    observedCamera = camera.cameraInfo
                    camera.cameraInfo.cameraState.observe(this) { state ->
                        state.error?.let { error ->
                            ready = false
                            if (combined) CombinedSessionController.active?.abort("摄像头异常 code=" + error.code)
                            else stopRecording("摄像头异常")
                            statusView.text = "摄像头不可用：code=${error.code}；请停止后重试预览。"
                            PocLog.event(this, "PHASE2_CAMERA_ERROR code=${error.code}")
                            renderControls()
                        }
                    }
                    PocLog.event(this, "PHASE2_CAMERA_BOUND $configuration supported=$supported frameRates=$frameRates")
                    renderControls()
                    continueCombinedStart()
                    return
                } catch (e: Exception) {
                    lastError = e
                    cameras.unbindAll()
                    PocLog.event(this, "PHASE2_BIND_FALLBACK quality=$quality frameRate=$requestedRate error=${e.javaClass.simpleName}")
                }
            }
            throw lastError ?: IllegalStateException("预览与录像组合不可用")
        } catch (e: Exception) { reportError("摄像头绑定失败", e) }
    }

    private fun beginCombined() {
        if (busy() || !isResumed() || PlaybackCaptureService.status.active) return
        combinedPending = true
        buttonNs = SystemClock.elapsedRealtimeNanos()
        authorization = JSONObject()
        consent = null
        val permissions = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO) +
            if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            combinedPermissionsPending = true
            authorization.put("permissionsRequestNs", SystemClock.elapsedRealtimeNanos())
            try { combinedPermissions.launch(missing.toTypedArray()) }
            catch (e: Exception) { reportError("无法请求权限", e) }
        } else continueCombinedStart()
        renderControls()
    }

    private fun continueCombinedStart() {
        if (!combined || !combinedPending || !isResumed() || combinedPermissionsPending || projectionPending) return
        if (!hasCameraPermission() || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            combinedPending = false
            statusView.text = "权限已撤回；未开始录制。"
            renderControls()
            return
        }
        if (!ready) { bindCamera(); return }
        val freshConsent = consent
        if (freshConsent == null) {
            try {
                projectionPending = true
                authorization.put("projectionRequestNs", SystemClock.elapsedRealtimeNanos())
                statusView.setText(R.string.waiting_projection)
                val manager = getSystemService(MediaProjectionManager::class.java)
                // This only configures the consent dialog; CameraX remains the video source.
                val request = if (Build.VERSION.SDK_INT >= 34) {
                    manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                } else manager.createScreenCaptureIntent()
                projectionResult.launch(request)
                PocLog.event(this, "PHASE3_PROJECTION_REQUEST fresh=true scope=${if (Build.VERSION.SDK_INT >= 34) "default_display" else "legacy"}")
            } catch (e: Exception) {
                projectionPending = false
                combinedPending = false
                reportError("无法打开系统授权", e)
            }
        } else {
            consent = null
            combinedPending = false
            try {
                val video = capture ?: error("摄像头未就绪")
                video.targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
                CombinedSessionController.start(this, video, freshConsent, buttonNs, authorization, configuration)
                attachCombined()
            } catch (e: Exception) { reportError("无法启动统一录制；请重新授权", e) }
        }
        renderControls()
    }

    private fun attachCombined() {
        if (!combined) return
        val active = CombinedSessionController.active
        if (attachedController !== active) attachedController?.listener = null
        attachedController = active
        active?.listener = {
            statusView.text = active.message
            if (!active.canStop) stopProbe()
            if (active.canStop && isResumed()) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            renderResult()
        }
        active?.listener?.invoke()
    }

    private fun stopProbe() {
        val probe = syncProbe ?: return
        syncProbe = null
        try { probe.stop() } catch (e: Exception) { PocLog.event(this, "PHASE3_PROBE_SAVE_FAILED " + e.javaClass.simpleName) }
    }

    private fun startRecording() {
        if (busy() || !ready || !isResumed()) return
        if (!hasCameraPermission()) { ready = false; reportError("无法录像", SecurityException("CAMERA 权限已撤回")); return }
        val video = capture ?: return
        val name = "MusicCam-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MusicCam")
        }
        try {
            video.targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
            stopping = false
            stopReason = "用户停止"
            val output = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(values).build()
            // Never call withAudioEnabled(): RECORD_AUDIO belongs only to Phase 1 playback capture.
            recording = video.output.prepareRecording(this, output).start(mainExecutor) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        if (!stopping) statusView.text = "正在录像：$configuration\n无音轨"
                        PocLog.event(this, "PHASE2_RECORDING_STARTED file=$name audioEnabled=false")
                    }
                    is VideoRecordEvent.Status -> if (!stopping && !isDestroyed) {
                        statusView.text = String.format(Locale.ROOT, "正在录像：%.1f 秒 · %.1f MB\n%s · 无音轨",
                            event.recordingStats.recordedDurationNanos / 1e9,
                            event.recordingStats.numBytesRecorded / 1e6, configuration)
                    }
                    is VideoRecordEvent.Finalize -> finalizeRecording(event, name)
                }
            }
            statusView.text = "正在开始录像…"
            preferences.edit().putString("pending", name).apply()
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            PocLog.event(this, "PHASE2_RECORDING_REQUEST file=$name $configuration")
        } catch (e: Exception) {
            recording = null
            reportError("录像启动失败", e)
        }
        renderControls()
    }

    private fun stopRecording(reason: String) {
        if (combined) {
            stopProbe()
            CombinedSessionController.active?.stop(reason)
            renderControls()
            return
        }
        val active = recording ?: return
        if (stopping) return
        stopping = true
        stopReason = reason
        statusView.text = "$reason；正在定稿 MP4…"
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        try { active.stop() } catch (e: Exception) { reportError("停止录像失败，等待定稿回调", e) }
        PocLog.event(this, "PHASE2_STOP_REQUEST reason=$reason")
        renderControls()
    }

    private fun finalizeRecording(event: VideoRecordEvent.Finalize, name: String) {
        recording?.close()
        recording = null
        stopping = false
        finalizing = true
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!isDestroyed) { statusView.text = "正在检查 MP4 输出轨道…"; renderControls() }
        val uri = event.outputResults.outputUri
        val context = applicationContext
        val reason = stopReason
        PocLog.event(context, "PHASE2_FINALIZE file=$name error=${event.error} durationNs=${event.recordingStats.recordedDurationNanos} bytes=${event.recordingStats.numBytesRecorded} reason=$reason")
        // Container parsing and provider I/O stay off the UI thread; completion survives Activity destruction.
        Thread({
            var playableUri: String? = null
            val result = try {
                check(uri != Uri.EMPTY) { "未返回输出文件" }
                val inspection = Mp4Inspection.inspect(context, uri)
                playableUri = uri.toString()
                val label = if (event.hasError()) "录像异常（code=${event.error}），保留可解析的部分视频" else "已保存 MP4（$reason）"
                "$label\nMovies/MusicCam/$name\n$inspection"
            } catch (e: Exception) {
                "录像未通过文件检查：${e.message ?: e.javaClass.simpleName}（CameraX code=${event.error}）\n" +
                    "${if (uri == Uri.EMPTY) "没有可用输出文件" else "输出保留在 Movies/MusicCam/$name，不能视为成功录像"}"
            }
            val saved = context.getSharedPreferences("camera", MODE_PRIVATE)
            val edit = saved.edit().putString("message", result).putString("uri", playableUri)
            if (saved.getString("pending", null) == name) edit.remove("pending")
            edit.apply()
            PocLog.event(context, "PHASE2_FILE_INSPECTION $result")
            mainExecutor.execute {
                finalizing = false
                if (!isDestroyed && !isFinishing) {
                    statusView.text = "录像已结束；可开始新的独立会话。"
                    renderResult()
                    if (isResumed()) bindCamera()
                    renderControls()
                }
            }
        }, "MusicCam-MP4-check").start()
    }

    private fun renderResult() {
        if (combined) {
            val recovery = preferences.getString("recoverableId", null)
            resultView.text = preferences.getString("message", "停止后自动保存到 Movies/MusicCam。") +
                if (recovery != null && CombinedSessionController.active == null)
                    "\n存在未完成会话；原始数据保留在应用内，卸载会丢失。可尝试重新合成。" else ""
            renderControls()
            return
        }
        val pending = preferences.getString("pending", null)
        resultView.text = (if (pending == null) "" else "会话尚未确认定稿：$pending。进程终止后不能视为成功录像。\n") +
            preferences.getString("message", "视频将保存在 Movies/MusicCam，相册或文件管理器可访问。")
        renderControls()
    }

    private fun renderControls() {
        startButton.isEnabled = !busy() && isResumed() &&
            if (combined) !PlaybackCaptureService.status.active else ready && hasCameraPermission()
        if (combined) {
            stopButton.isEnabled = CombinedSessionController.active?.canStop == true
            retryButton?.isEnabled = !busy() && !PlaybackCaptureService.status.active &&
                preferences.getString("recoverableId", null) != null
            probeButton?.isEnabled = CombinedSessionController.active?.state == CombinedSessionController.State.RECORDING
        } else {
            stopButton.isEnabled = recording != null && !stopping
        }
        switchButton.isEnabled = !busy() && !loading && !permissionPending && hasCameraPermission()
        permissionButton.isEnabled = !busy() && !loading && !permissionPending
        openButton.isEnabled = !busy() && preferences.getString("uri", null) != null
    }

    private fun openVideo() {
        val uri = preferences.getString("uri", null)?.let(Uri::parse) ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) { reportError("无法打开播放器，请从相册或文件管理器打开 MP4", e) }
    }

    private fun lensName() = if (lens == CameraSelector.LENS_FACING_BACK) "后置" else "前置"
    private fun reportError(prefix: String, error: Exception) {
        if (combinedPending && CombinedSessionController.active == null) {
            combinedPending = false
            combinedPermissionsPending = false
            projectionPending = false
            consent = null
        }
        statusView.text = "$prefix：${error.javaClass.simpleName}: ${error.message ?: "无详细信息"}"
        PocLog.event(this, "PHASE2_ERROR ${statusView.text}")
        renderControls()
    }

    private fun unbindCamera() {
        observedCamera?.cameraState?.removeObservers(this)
        observedCamera = null
        preview?.let { provider?.unbind(it) }
        capture?.let { provider?.unbind(it) }
        preview = null
        capture = null
        ready = false
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (!hasCameraPermission()) { ready = false; unbindCamera() }
        else if (CombinedSessionController.active == null && recording == null && !finalizing) bindCamera()
        attachCombined()
        continueCombinedStart()
        renderResult()
    }

    override fun onPause() {
        resumed = false
        stopRecording(if (isChangingConfigurations) "页面配置变化" else "离开录像页面")
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("lens", lens)
        outState.putBoolean("combinedPending", combinedPending)
        outState.putBoolean("combinedPermissionsPending", combinedPermissionsPending)
        outState.putBoolean("projectionPending", projectionPending)
        outState.putLong("buttonNs", buttonNs)
        outState.putString("authorization", authorization.toString())
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        consent = null
        attachedController?.listener = null
        stopProbe()
        stopRecording("录像页面销毁")
        unbindCamera()
        preferences.unregisterOnSharedPreferenceChangeListener(resultsListener)
        super.onDestroy()
    }
}

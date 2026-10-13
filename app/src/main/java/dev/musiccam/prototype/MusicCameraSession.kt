package dev.musiccam.prototype

import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/** A service-owned CameraX lifecycle; a paused Activity may retain its preview until destroyed. */
internal class MusicCameraSession(var onError: (Int) -> Unit = {}) : LifecycleOwner {
    private val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.CREATED }
    override val lifecycle: Lifecycle get() = registry
    data class Binding(val provider: ProcessCameraProvider, val preview: Preview,
                       val video: VideoCapture<Recorder>, val camera: Camera,
                       val lens: Int, val configuration: String)
    var binding: Binding? = null
        private set

    fun bind(provider: ProcessCameraProvider, preview: Preview, video: VideoCapture<Recorder>,
             lens: Int, configuration: String): Camera {
        check(binding == null) { "上一片段相机尚未释放" }
        provider.unbind(preview, video)
        registry.currentState = Lifecycle.State.RESUMED
        try {
            val selector = CameraSelector.Builder().requireLensFacing(lens).build()
            val camera = provider.bindToLifecycle(this, selector, preview, video)
            binding = Binding(provider, preview, video, camera, lens, configuration)
            camera.cameraInfo.cameraState.observe(this) { state -> state.error?.let { onError(it.code) } }
            return camera
        } catch (e: Exception) {
            registry.currentState = Lifecycle.State.CREATED
            provider.unbind(preview, video)
            throw e
        }
    }

    fun release() {
        val current = binding ?: return
        binding = null
        current.camera.cameraInfo.cameraState.removeObservers(this)
        current.preview.setSurfaceProvider(null)
        registry.currentState = Lifecycle.State.CREATED
        current.provider.unbind(current.preview, current.video)
    }

    fun destroy() { release(); onError = {}; registry.currentState = Lifecycle.State.DESTROYED }
}

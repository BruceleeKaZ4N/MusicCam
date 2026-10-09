package dev.musiccam.prototype

import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/** A physical mirror can put this flash in camera frames. UI overlays alone are not camera data.
 * Playback-head-driven flashes measure the whole playback/capture/display/camera path, including
 * Bluetooth and rendering delay; they do not establish a precise sensor acquisition timestamp.
 */
class SyncProbe(private val view: TextView, private val directory: File) {
    private val report = JSONObject().put("clock", "elapsedRealtimeNanos")
        .put("toneHz", 1000).put("periodFrames", 48000).put("pulseFrames", 9600)
        .put("method", "display_flash_from_AudioTrack_playback_head; film_physical_mirror")
    private val events = JSONArray()
    private var audio: AudioTrack? = null
    private var bright: Boolean? = null
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val track = audio ?: return
            val head = track.playbackHeadPosition.toLong() and 0xffffffffL
            val on = head % 48000 < 9600
            if (on != bright) {
                bright = on
                events.put(JSONObject().put("displaySubmitNs", SystemClock.elapsedRealtimeNanos())
                    .put("playbackHeadFrame", head).put("bright", on)
                    .put("routeType", track.routedDevice?.type ?: JSONObject.NULL))
                view.setBackgroundColor(if (on) Color.GREEN else Color.DKGRAY)
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start() {
        var track: AudioTrack? = null
        try {
            val samples = ShortArray(48000 * 2)
            for (i in 0 until 9600) {
                val sample = (8000 * sin(2 * PI * 1000 * i / 48000)).toInt().toShort()
                samples[i * 2] = sample
                samples[i * 2 + 1] = sample
            }
            track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(samples.size * 2).build()
            check(track.state != AudioTrack.STATE_UNINITIALIZED && track.write(samples, 0, samples.size) == samples.size)
            check(track.setLoopPoints(0, 48000, -1) == AudioTrack.SUCCESS)
            report.put("playCallBeforeNs", SystemClock.elapsedRealtimeNanos())
            track.play()
            report.put("playCallReturnedNs", SystemClock.elapsedRealtimeNanos())
            audio = track
            view.visibility = View.VISIBLE
            view.text = "声光同步测试：请用镜子让摄像头拍到此色块"
            Choreographer.getInstance().postFrameCallback(frame)
        } catch (e: Exception) { track?.release(); throw e }
    }

    fun stop() {
        Choreographer.getInstance().removeFrameCallback(frame)
        audio?.let { try { it.stop() } finally { it.release() } }
        audio = null
        view.visibility = View.GONE
        report.put("stoppedNs", SystemClock.elapsedRealtimeNanos()).put("events", events)
        SessionStorage.writeJson(File(directory, "sync-probe.json"), report)
    }
}

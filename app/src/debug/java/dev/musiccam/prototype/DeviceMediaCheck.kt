package dev.musiccam.prototype

import android.media.MediaExtractor
import android.media.MediaFormat
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Debug-only shell runner: tests real native codecs with synthetic files, never requests capture
 * permissions or authorization. Not an exported Android component or a production code path.
 */
object DeviceMediaCheck {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 2) { "usage: DeviceMediaCheck <fixture-directory> <success|failure>" }
        val directory = File(args[0])
        val sources = listOf(File(directory, "video.mp4"), File(directory, "audio.wav"))
        val before = sources.map { digest(it) }
        var failure: Exception? = null
        var output: File? = null
        try { output = AudioVideoComposer.compose(directory, SessionStorage.readJson(File(directory, "session.json"))) }
        catch (e: Exception) { failure = e }
        check(before == sources.map { digest(it) }) { "原始数据被修改或丢失" }
        if (args[1] == "failure") {
            check(failure != null && output == null) { "无效输入应拒绝合成" }
            println(JSONObject().put("passed", true).put("expectedFailure", failure.message).put("sourcesUnchanged", true))
        } else {
            if (failure != null) throw failure
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(checkNotNull(output).path)
                check(extractor.trackCount == 2)
                val mimes = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
                check(mimes.count { it == MediaFormat.MIMETYPE_VIDEO_AVC } == 1 && mimes.count { it == MediaFormat.MIMETYPE_AUDIO_AAC } == 1)
                println(JSONObject().put("passed", true).put("mimes", mimes).put("sourcesUnchanged", true)
                    .put("composition", SessionStorage.readJson(File(directory, "composition.json"))))
            } finally { extractor.release() }
        }
    }

    private fun digest(file: File): String = file.inputStream().use { stream ->
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) { val count = stream.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
        hash.digest().joinToString("") { "%02x".format(it) }
    }
}

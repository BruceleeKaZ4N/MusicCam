package dev.musiccam.prototype

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Exercise the actual PCM writer with distinct clip/gap contents, without projection consent. */
internal object MusicPcmCheck {
    fun run(context: Context): JSONObject {
        val mode = UUID.randomUUID().toString()
        val ids = List(5) { UUID.randomUUID().toString() }
        val receipts = ArrayList<PlaybackCaptureService.ClipStatus>()
        val reports = JSONArray()
        val epoch = 10_000_000_000L
        val writer = MusicPcmSession(context, mode, epoch, receipts::add)
        fun block(value: Int, frames: Int) {
            val end = writer.globalFrames + frames
            val time = epoch + end * 1_000_000_000L / 48_000
            writer.pcm(ShortArray(frames * 2) { value.toShort() }, frames * 2, time, end to time)
        }
        fun data(id: String): ByteArray = File(SessionStorage.directory(context, id), "audio.wav").readBytes()
        val frozen = ArrayList<Pair<File, ByteArray>>()
        try {
            for (index in 0..2) {
                block(-900 - index, 4800 + index * 1000) // Must never enter a clip.
                val start = writer.globalFrames
                writer.open(ids[index])
                val frames = 9600 + index * 4800
                block(100 + index, frames)
                writer.close()
                val directory = SessionStorage.directory(context, ids[index])
                val reportFile = File(directory, "audio-timing.json")
                val report = SessionStorage.readJson(reportFile)
                val wav = data(ids[index])
                check(wav.size == 44 + frames * 4)
                val pcm = ByteBuffer.wrap(wav, 44, wav.size - 44).order(ByteOrder.LITTLE_ENDIAN)
                while (pcm.hasRemaining()) check(pcm.short.toInt() == 100 + index) { "gap/other clip leaked" }
                check(report.getLong("startFrameInclusive") == start)
                check(report.getLong("endFrameExclusive") == start + frames)
                check(report.getLong("frames") == frames.toLong())
                check(report.getLong("firstNonZeroFrame") == 0L)
                check(kotlin.math.abs(report.getLong("originEstimateNs") -
                    (epoch + start * 1_000_000_000L / 48_000)) <= 1L)
                check(report.getString("musicModeId") == mode && report.isNull("error"))
                frozen.forEach { (file, bytes) -> check(file.readBytes().contentEquals(bytes)) }
                frozen.add(reportFile to reportFile.readBytes())
                reports.put(JSONObject().put("clip", index).put("start", start).put("end", start + frames).put("passed", true))
            }
            writer.open(ids[3])
            block(404, 4800)
            writer.close("synthetic capture failure")
            val failed = SessionStorage.readJson(File(SessionStorage.directory(context, ids[3]), "audio-timing.json"))
            check(failed.getString("error") == "synthetic capture failure" && data(ids[3]).size == 44 + 4800 * 4)
            val before = data(ids[3])
            var overwriteRejected = false
            try { writer.open(ids[3]) } catch (_: IllegalStateException) { overwriteRejected = true }
            check(overwriteRejected && before.contentEquals(data(ids[3])))
            writer.open(ids[4])
            writer.close()
            check(receipts.last().finalized && receipts.last().error != null) { "empty clip falsely succeeded" }
            frozen.forEach { (file, bytes) -> check(file.readBytes().contentEquals(bytes)) }
            return JSONObject().put("passed", true).put("clips", reports)
                .put("gapDiscard", true).put("boottimeMapping", true).put("reportsFrozen", true)
                .put("failurePreserved", true).put("overwriteRejected", true).put("emptyRejected", true)
        } finally {
            writer.close("test teardown")
            // Only these generated synthetic UUIDs; never production recordings or sessions.
            ids.forEach { File(File(context.filesDir, "sessions"), it).deleteRecursively() }
        }
    }
}

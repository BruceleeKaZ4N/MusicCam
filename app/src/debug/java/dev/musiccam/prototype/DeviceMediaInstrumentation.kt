package dev.musiccam.prototype

import android.app.Instrumentation
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import android.net.Uri
import android.provider.MediaStore
import java.security.MessageDigest

/** Framework instrumentation needs no test framework/library or MediaProjection permission. */
class DeviceMediaInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val reports = JSONArray()
        var failed = false
        val root = File(targetContext.filesDir, "native-checks")
        for (name in listOf("aligned", "audio_early", "audio_late", "invalid_timing")) {
            try {
                DeviceMediaCheck.main(arrayOf(File(root, name).path, if (name == "invalid_timing") "failure" else "success"))
                reports.put(JSONObject().put("fixture", name).put("passed", true))
            } catch (e: Exception) {
                failed = true
                reports.put(JSONObject().put("fixture", name).put("passed", false).put("error", e.toString()))
            }
        }
        if (!failed) try {
            val source = File(root, "aligned/merged.mp4")
            val hash = MessageDigest.getInstance("SHA-256").digest(source.readBytes())
            var pending: Uri? = null
            var rejected = false
            try { SessionStorage.publish(targetContext, source, "MusicCam-synthetic-reject.mp4", 0) { pending = it } }
            catch (_: IllegalStateException) { rejected = true }
            check(rejected && pending != null) { "轨道不符的 pending 输出必须拒绝" }
            val remains = targetContext.contentResolver.query(checkNotNull(pending),
                arrayOf(MediaStore.Video.Media._ID), null, null, null)?.use { it.moveToFirst() } ?: false
            check(!remains) { "合成失败后未删除本轮 pending 媒体项" }
            check(hash.contentEquals(MessageDigest.getInstance("SHA-256").digest(source.readBytes())))
            var published: Uri? = null
            try {
                published = SessionStorage.publish(targetContext, source, "MusicCam-synthetic-check.mp4", 1)
                check(SessionStorage.isPublished(targetContext, published))
                Mp4Inspection.inspect(targetContext, published, 1)
            } finally {
                // Only delete this test's own freshly inserted synthetic media, never user recordings.
                published?.let { check(targetContext.contentResolver.delete(it, null, null) == 1) }
            }
            reports.put(JSONObject().put("fixture", "publication_and_failure_cleanup").put("passed", true))
        } catch (e: Exception) {
            failed = true
            reports.put(JSONObject().put("fixture", "publication_and_failure_cleanup").put("passed", false).put("error", e.toString()))
        }
        val result = JSONObject().put("passed", !failed).put("reports", reports)
        try { SessionStorage.writeJson(File(root, "report.json"), result) }
        catch (e: Exception) { failed = true; result.put("reportError", e.toString()) }
        finish(if (failed) 1 else -1, Bundle().apply { putString("stream", result.toString(2)) })
    }
}

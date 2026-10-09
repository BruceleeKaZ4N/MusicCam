package dev.musiccam.prototype

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

/** Only this session's private intermediates and newly inserted pending media are modified. */
object SessionStorage {
    fun directory(context: Context, id: String): File {
        require(UUID.fromString(id).toString() == id) { "会话标识无效" }
        return File(File(context.filesDir, "sessions"), id).also {
            check(it.isDirectory || it.mkdirs()) { "无法创建会话目录" }
        }
    }

    fun writeJson(file: File, value: JSONObject) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(value.toString(2).toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    }

    fun readJson(file: File): JSONObject = JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() })

    fun publish(context: Context, file: File, name: String, audioTracks: Int,
                onPending: (Uri) -> Unit = {}): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MusicCam")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("无法创建输出媒体项")
        var published = false
        try {
            onPending(uri)
            resolver.openFileDescriptor(uri, "w")?.use { descriptor ->
                FileOutputStream(descriptor.fileDescriptor).use { output ->
                    FileInputStream(file).use { it.copyTo(output) }
                    output.flush()
                    output.fd.sync()
                }
            } ?: error("无法打开输出媒体项")
            Mp4Inspection.inspect(context, uri, audioTracks)
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null) == 1) {
                "媒体发布失败"
            }
            published = true
            return uri
        } finally {
            if (!published) {
                // Never remove the private original or an already published successful result.
                try { resolver.delete(uri, null, null) } catch (_: Exception) { /* journal retains the URI */ }
            }
        }
    }

    fun isPublished(context: Context, uri: Uri): Boolean = context.contentResolver.query(uri,
        arrayOf(MediaStore.Video.Media.IS_PENDING, MediaStore.Video.Media.SIZE), null, null, null)?.use {
        it.moveToFirst() && it.getInt(0) == 0 && it.getLong(1) > 0
    } ?: false

    fun cleanupMedia(directory: File): List<String> = listOf("video.mp4", "audio.wav", "audio-aac.mp4",
        "audio-aac.mp4.part", "merged.mp4", "merged.mp4.part").filter { name ->
        val file = File(directory, name)
        try { file.exists() && !file.delete() } catch (_: Exception) { true }
    }
}

package dev.musiccam.prototype

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri

/** Checks the finalized container, not whether a person sees correct or smooth pictures. */
object Mp4Inspection {
    fun inspect(context: Context, uri: Uri): String {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            var videoIndex = -1
            var audioTracks = 0
            var videoTracks = 0
            for (index in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) audioTracks++
                if (mime.startsWith("video/")) { videoTracks++; videoIndex = index }
            }
            check(audioTracks == 0) { "检测到非预期音轨：$audioTracks 条" }
            check(videoTracks == 1) { "视频轨数量异常：$videoTracks" }
            val format = extractor.getTrackFormat(videoIndex)
            extractor.selectTrack(videoIndex)
            check(extractor.sampleTime >= 0 && extractor.sampleSize > 0) { "视频轨没有有效样本" }
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                check(duration > 0) { "视频时长无效" }
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) ?: "未知"
                val fps = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) format.getInteger(MediaFormat.KEY_FRAME_RATE).toString() else "未报告"
                return "容器检查：${duration / 1000.0} 秒 · ${format.getInteger(MediaFormat.KEY_WIDTH)} × ${format.getInteger(MediaFormat.KEY_HEIGHT)}\n" +
                    "${format.getString(MediaFormat.KEY_MIME)} · 帧率元数据 $fps · 旋转 $rotation°\n视频轨 1 · 音轨 0；请播放确认画面和时长。"
            } finally {
                retriever.release()
            }
        } finally {
            extractor.release()
        }
    }
}

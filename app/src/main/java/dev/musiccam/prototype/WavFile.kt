package dev.musiccam.prototype

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** PCM16 little-endian RIFF/WAVE. Only complete frames enter the committed data length. */
class WavFile(private val destination: File, val sampleRate: Int, val channels: Int) {
    private val temporary = File(destination.path + ".part")
    private val output = RandomAccessFile(temporary, "rw")
    var dataBytes = 0L
        private set
    private var closed = false

    init {
        try {
            output.setLength(0)
            output.write(header(0))
        } catch (e: Exception) {
            output.close()
            throw e
        }
    }

    fun append(samples: ShortArray, count: Int) {
        require(count in 0..samples.size && count % channels == 0)
        val bytes = count * 2
        check(dataBytes + bytes <= 0xffff_ffffL - 36) { "WAV 已达到 RIFF 长度上限" }
        val pcm = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) pcm.putShort(samples[i])
        output.write(pcm.array())
        dataBytes += bytes
    }

    fun finish(): File? {
        check(!closed)
        try {
            // A failed disk write may have written a partial block; discard that block.
            output.setLength(44 + dataBytes)
            output.seek(0)
            output.write(header(dataBytes))
            output.fd.sync()
        } finally {
            closed = true
            output.close()
        }
        if (dataBytes == 0L) {
            check(temporary.delete()) { "无法移除空录音" }
            return null
        }
        check(temporary.renameTo(destination)) { "WAV 定稿失败，保留 .part 文件" }
        return destination
    }

    private fun header(length: Long): ByteArray = ByteBuffer.allocate(44)
        .order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt((36 + length).toInt())
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1.toShort()) // PCM
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(sampleRate * channels * 2)
            putShort((channels * 2).toShort())
            putShort(16.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(length.toInt())
        }.array()
}

package com.example.videoeditorapp.utils

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AudioWaveformUtils {

    private val waveformCache = ConcurrentHashMap<String, List<Float>>()

    suspend fun getWaveform(filePath: String, targetPoints: Int = 1200): List<Float> =
        withContext(Dispatchers.IO) {

            val cacheKey = "$filePath-$targetPoints"
            waveformCache[cacheKey]?.let { return@withContext it }

            val peaks = mutableListOf<Float>()
            val extractor = MediaExtractor()

            try {
                extractor.setDataSource(filePath)
                val audioTrackIndex = findAudioTrack(extractor)
                if (audioTrackIndex < 0) return@withContext emptyList<Float>()

                extractor.selectTrack(audioTrackIndex)
                val format = extractor.getTrackFormat(audioTrackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: return@withContext emptyList()
                val codec = MediaCodec.createDecoderByType(mime)
                codec.configure(format, null, null, 0)
                codec.start()

                val info = MediaCodec.BufferInfo()
                var isInputEOS = false
                var isOutputEOS = false

                val durationUs = format.getLong(MediaFormat.KEY_DURATION)
                val sampleWindowUs = durationUs / targetPoints

                var currentWindowAcc = 0f
                var sampleCount = 0
                var nextWindowTimeUs = sampleWindowUs

                while (!isOutputEOS) {

                    if (!isInputEOS) {
                        val inputIndex = codec.dequeueInputBuffer(10000)
                        if (inputIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inputIndex) ?: continue
                            val size = extractor.readSampleData(inputBuffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(
                                    inputIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                isInputEOS = true
                            } else {
                                codec.queueInputBuffer(
                                    inputIndex, 0, size,
                                    extractor.sampleTime, 0
                                )
                                extractor.advance()
                            }
                        }
                    }

                    val outputIndex = codec.dequeueOutputBuffer(info, 10000)
                    if (outputIndex >= 0) {

                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0)
                            isOutputEOS = true

                        val buffer = codec.getOutputBuffer(outputIndex) ?: continue
                        val rms = calculateRMS(buffer, info.size)

                        currentWindowAcc += rms
                        sampleCount++

                        if (info.presentationTimeUs >= nextWindowTimeUs || isOutputEOS) {
                            peaks.add(currentWindowAcc / sampleCount)
                            currentWindowAcc = 0f
                            sampleCount = 0
                            nextWindowTimeUs += sampleWindowUs
                        }

                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }

                codec.stop()
                codec.release()
                extractor.release()

            } catch (e: Exception) {
                Log.e("AudioWaveformUtils", "Waveform error: ${e.message}")
                return@withContext emptyList<Float>()
            }

            val result = smooth(normalize(peaks), radius = 2)
            waveformCache[cacheKey] = result
            result
        }

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                ?.takeIf { it.startsWith("audio/") }
                ?.let { return i }
        }
        return -1
    }

    private fun calculateRMS(buffer: ByteBuffer, size: Int): Float {
        buffer.position(0)
        var sum = 0.0
        var count = 0

        while (buffer.remaining() >= 2) {
            val s = buffer.short.toInt()
            sum += s * s
            count++
        }

        return if (count > 0)
            Math.sqrt(sum / count).toFloat() / Short.MAX_VALUE
        else 0f
    }

    private fun normalize(peaks: List<Float>): List<Float> {
        val max = peaks.maxOrNull() ?: return peaks
        if (max == 0f) return peaks
        return peaks.map { it / max }
    }

    private fun smooth(peaks: List<Float>, radius: Int): List<Float> {
        val out = MutableList(peaks.size) { 0f }
        for (i in peaks.indices) {
            var acc = 0f
            var count = 0
            for (r in -radius..radius) {
                val idx = i + r
                if (idx in peaks.indices) {
                    acc += peaks[idx]
                    count++
                }
            }
            out[i] = acc / count
        }
        return out
    }

    fun clearCache() = waveformCache.clear()
}
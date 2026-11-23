package com.vibecoder.vibedownloader.engine

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Joins a video stream and an audio stream into one MP4 with Android's own
 * MediaExtractor + MediaMuxer. Nothing is re-encoded, so it is quick.
 */
object Merger {
    private class Input(val extractor: MediaExtractor, val muxTrack: Int, var done: Boolean = false)

    /** [inputs]: file + which kind of track to take from it ("video/" or "audio/"). */
    fun mux(inputs: List<Pair<File, String>>, out: File) {
        out.delete()
        val extractors = mutableListOf<MediaExtractor>()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            val m = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            val streams = mutableListOf<Input>()
            var maxInput = 1 shl 20
            for ((file, prefix) in inputs) {
                val ex = MediaExtractor()
                extractors += ex
                ex.setDataSource(file.path)
                val track = (0 until ex.trackCount).firstOrNull {
                    ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true
                } ?: continue
                ex.selectTrack(track)
                val format = ex.getTrackFormat(track)
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    maxInput = maxOf(maxInput, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                }
                if (prefix == "video/" && format.containsKey("rotation-degrees")) {
                    runCatching { m.setOrientationHint(format.getInteger("rotation-degrees")) }
                }
                streams += Input(ex, m.addTrack(format))
            }
            if (streams.none()) throw IOException("No playable tracks found")
            if (inputs.any { it.second == "video/" } && streams.size < inputs.count { it.second == "video/" }) {
                throw IOException("The video track could not be read")
            }
            m.start()
            started = true
            val buffer = ByteBuffer.allocateDirect(maxOf(maxInput, 4 shl 20))
            val info = MediaCodec.BufferInfo()
            // write samples in time order across the tracks (interleaved)
            while (true) {
                val next = streams.filter { !it.done }.minByOrNull { it.extractor.sampleTime.let { t -> if (t < 0) Long.MAX_VALUE else t } } ?: break
                val ex = next.extractor
                buffer.clear()
                val size = ex.readSampleData(buffer, 0)
                if (size < 0) { next.done = true; continue }
                val flags = if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, size, ex.sampleTime.coerceAtLeast(0), flags)
                m.writeSampleData(next.muxTrack, buffer, info)
                if (!ex.advance()) next.done = true
            }
            started = false
            m.stop()   // a failure here means a broken file: let it throw
        } catch (e: Exception) {
            out.delete()
            throw if (e is IOException) e else IOException("Merging failed: ${e.message}", e)
        } finally {
            extractors.forEach { runCatching { it.release() } }
            val mx = muxer
            if (mx != null) {
                if (started) runCatching { mx.stop() }
                runCatching { mx.release() }
            }
        }
        if (!out.exists() || out.length() == 0L) throw IOException("Merging produced no file")
    }

    fun merge(video: File, audio: File, out: File) = mux(listOf(video to "video/", audio to "audio/"), out)

    /** Rewrites a stream into a clean MP4/M4A container (same data, no re-encode). */
    fun remux(src: File, out: File, audioOnly: Boolean) =
        mux(if (audioOnly) listOf(src to "audio/") else listOf(src to "video/", src to "audio/"), out)
}

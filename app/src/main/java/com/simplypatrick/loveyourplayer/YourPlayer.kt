package com.simplypatrick.loveyourplayer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaSync
import android.media.PlaybackParams
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

val MediaFormat.channelMask: Int
    get() {
        try {
            return getInteger(MediaFormat.KEY_CHANNEL_MASK)
        } catch (_: Exception) {
            // use channel count
        }
        return when (getInteger(MediaFormat.KEY_CHANNEL_COUNT)) {
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            6 -> AudioFormat.CHANNEL_OUT_5POINT1
            else -> AudioFormat.CHANNEL_INVALID
        }
    }

class YourPlayer {
    companion object {
        const val TAG = "YourPlayer"
    }

    private var videoDec: MediaCodec? = null
    private var videoTrackIdx = -2

    private var audioDec: MediaCodec? = null
    private var audioTrackIdx = -2
    private var audioTrack: AudioTrack? = null

    private val mediaSync = MediaSync()
    private var extractor = MediaExtractor()

    private var activeTracks = 0

    fun init(outputSurface: Surface, dataSource: String): Boolean {
        try {
            extractor.setDataSource(dataSource)

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                when {
                    mime.startsWith("video/") -> {
                        videoDec = MediaCodec.createDecoderByType(mime)
                        videoDec?.run {
                            mediaSync.setSurface(outputSurface)
                            val inputSurface = mediaSync.createInputSurface()

                            configure(format, inputSurface, null, 0)
                            start()

                            extractor.selectTrack(i)
                            videoTrackIdx = i
                            activeTracks = activeTracks.or(1 shl videoTrackIdx)
                        }
                    }
                    mime.startsWith("audio/") -> {
                        audioDec = MediaCodec.createDecoderByType(mime)
                        audioDec?.run {
                            configure(format, null, null, 0)
                            start()

                            extractor.selectTrack(i)
                            audioTrackIdx = i
                            activeTracks = activeTracks.or(1 shl audioTrackIdx)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract media samples", e)
            return false
        }
        return true
    }

    private fun processInputSample(extractor: MediaExtractor, dec: MediaCodec) {
        val ibx = dec.dequeueInputBuffer(0)
        if (ibx > 0) {
            val ib = dec.getInputBuffer(ibx)
            val sampleSize = extractor.readSampleData(ib, 0)
            if (sampleSize > 0) {
                dec.queueInputBuffer(ibx, 0, sampleSize, extractor.sampleTime, 0)
            } else {
                dec.queueInputBuffer(ibx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            extractor.advance()
        } else {
            Thread.yield() // input buffer may be available later
        }
    }

    private fun renderVideoSample(bi: MediaCodec.BufferInfo, dec: MediaCodec): Boolean {
        val obx = dec.dequeueOutputBuffer(bi, 1000)
        when (obx) {
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                Log.d(TAG, "Video INFO_OUTPUT_FORMAT_CHANGED")
            }
            MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                Log.d(TAG, "Video INFO_OUTPUT_BUFFERS_CHANGED")
            }
            MediaCodec.INFO_TRY_AGAIN_LATER -> {
                Log.d(TAG, "Video INFO_TRY_AGAIN_LATER")
                Thread.yield()
            }
            else -> {
                dec.releaseOutputBuffer(obx, 1000 * bi.presentationTimeUs)
            }
        }
        if (bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
            return false
        }
        return true
    }

    private fun buildAudioTrack(format: MediaFormat): AudioTrack? {
        var audioTrack: AudioTrack? = null
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelMask = format.channelMask
        if (channelMask != AudioFormat.CHANNEL_INVALID) {
            val pcmEncoding = format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelMask, pcmEncoding)
            audioTrack = AudioTrack.Builder().run {
                setAudioAttributes(AudioAttributes.Builder().run {
                    setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    build()
                })
                setAudioFormat(AudioFormat.Builder().run {
                    setSampleRate(sampleRate)
                    setChannelMask(channelMask)
                    setEncoding(pcmEncoding)
                    build()
                })
                setBufferSizeInBytes(minBufferSize)
                setTransferMode(AudioTrack.MODE_STREAM)
                build()
            }
        }
        return audioTrack
    }

    private fun renderAudioSample(bi: MediaCodec.BufferInfo, dec: MediaCodec): Boolean {
        val obx = dec.dequeueOutputBuffer(bi, 1000)
        when (obx) {
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                Log.d(TAG, "Audio INFO_OUTPUT_FORMAT_CHANGED")
                audioTrack = buildAudioTrack(dec.outputFormat)?.apply { play() }

                with(mediaSync) {
                    setAudioTrack(audioTrack)
                    setCallback(object : MediaSync.Callback() {
                        override fun onAudioBufferConsumed(sync: MediaSync?, buf: ByteBuffer?, bufId: Int) {
                            audioDec?.releaseOutputBuffer(bufId, false)
                        }
                    }, null)
                    // TODO: what will happen if no audio codec?
                    playbackParams = PlaybackParams().setSpeed(1.0f)
                }
            }
            MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                Log.d(TAG, "Audio INFO_OUTPUT_BUFFERS_CHANGED")
            }
            MediaCodec.INFO_TRY_AGAIN_LATER -> {
                Log.d(TAG, "Audio INFO_TRY_AGAIN_LATER")
                Thread.yield()
            }
            else -> {
                val ob = dec.getOutputBuffer(obx)
                mediaSync.queueAudio(ob, obx, bi.presentationTimeUs)
            }
        }
        if (bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
            return false
        }
        return true
    }

    fun playbackLoop() {
        val bi = MediaCodec.BufferInfo()
        try {
            while (true) {
                when (extractor.sampleTrackIndex) {
                    videoTrackIdx -> {
                        videoDec?.let {
                            processInputSample(extractor, it)
                        }
                    }
                    audioTrackIdx -> {
                        audioDec?.let {
                            processInputSample(extractor, it)
                        }
                    }
                    -1 -> {
                        return
                    }
                }

                videoDec?.let {
                    val more = renderVideoSample(bi, it)
                    if (!more) {
                        activeTracks = activeTracks.and((1 shl videoTrackIdx).inv())
                    }
                }
                audioDec?.let {
                    val more = renderAudioSample(bi, it)
                    if (!more) {
                        activeTracks = activeTracks.and((1 shl audioTrackIdx).inv())
                    }
                }

                if (activeTracks == 0 || Thread.interrupted()) {
                    return
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process sample", e)
        } finally {
            mediaSync.release()
            videoDec?.stop()
            videoDec?.release()
            audioDec?.stop()
            audioDec?.release()
            extractor.release()
        }
    }

    fun stop() {
        activeTracks = 0
    }
}
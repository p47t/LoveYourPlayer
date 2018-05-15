package com.simplypatrick.loveyourplayer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import android.view.Surface

class YourPlayer {
    companion object {
        const val TAG = "YourPlayer"
    }

    private var videoDec: MediaCodec? = null
    private var videoTrackIdx = -2

    private var audioDec: MediaCodec? = null
    private var audioTrackIdx = -2
    private var audioTrack: AudioTrack? = null

    private val extractor = MediaExtractor()
    private var activeTracks = 0

    fun init(surface: Surface, dataSource: String) {
        try {
            extractor.setDataSource(dataSource)

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                when {
                    mime.startsWith("video/") -> {
                        videoDec = MediaCodec.createDecoderByType(mime)
                        videoDec?.run {
                            configure(format, surface, null, 0)
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
        }
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
                Log.d(TAG, "INFO_OUTPUT_FORMAT_CHANGED")
            }
            MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                Log.d(TAG, "INFO_OUTPUT_BUFFERS_CHANGED")
            }
            MediaCodec.INFO_TRY_AGAIN_LATER -> {
                Log.d(TAG, "INFO_TRY_AGAIN_LATER")
                Thread.yield()
            }
            else -> {
                dec.releaseOutputBuffer(obx, true)
            }
        }
        if (bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
            return false
        }
        return true
    }

    private fun renderAudioSample(bi: MediaCodec.BufferInfo, dec: MediaCodec): Boolean {
        val obx = dec.dequeueOutputBuffer(bi, 1000)
        when (obx) {
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                Log.d(TAG, "INFO_OUTPUT_FORMAT_CHANGED")
                val format = dec.outputFormat

                val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val channelMask = when (channelCount) {
                    2 -> AudioFormat.CHANNEL_OUT_STEREO
                    6 -> AudioFormat.CHANNEL_OUT_5POINT1
                    else -> AudioFormat.CHANNEL_INVALID
                }
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
                    audioTrack?.play()
                }
            }
            MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                Log.d(TAG, "INFO_OUTPUT_BUFFERS_CHANGED")
            }
            MediaCodec.INFO_TRY_AGAIN_LATER -> {
                Log.d(TAG, "INFO_TRY_AGAIN_LATER")
                Thread.yield()
            }
            else -> {
                val ob = dec.getOutputBuffer(obx)
                audioTrack?.write(ob, ob.limit(), AudioTrack.WRITE_BLOCKING)
                dec.releaseOutputBuffer(obx, true)
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

                if (activeTracks == 0) {
                    return // all tracks reached EOS
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process sample", e)
        } finally {
            videoDec?.stop()
            videoDec?.release()
            audioDec?.stop()
            audioDec?.release()
            extractor.release()
        }
    }
}
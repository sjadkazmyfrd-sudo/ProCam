package com.example.procam

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.Image
import android.util.Log
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Encodes CameraX YUV frames to H.264 using the device's hardware video encoder.
 * The encoder is initialized lazily from the first ImageProxy so its dimensions
 * always match the actual camera stream selected by CameraX.
 */
internal class H264CameraEncoder(
    private val onEncodedFrame: (ByteArray, Long) -> Unit,
    private val onCodecConfiguration: (ByteArray, ByteArray) -> Unit,
    private val onVideoDimensions: (Int, Int) -> Unit,
    private val onError: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "ProCamH264"
        private const val FRAME_RATE = 15
        private const val BIT_RATE = 1_500_000
        private const val I_FRAME_INTERVAL = 1
    }

    private val running = AtomicBoolean(false)
    @Volatile private var codec: MediaCodec? = null
    @Volatile private var outputThread: Thread? = null
    @Volatile private var outputWidth = 0
    @Volatile private var outputHeight = 0
    @Volatile private var sourceRotation = -1
    @Volatile private var lastPresentationTimeUs = 0L

    /** Enable frame processing after CameraX has been rebound with ImageAnalysis. */
    fun start() {
        running.set(true)
    }

    fun offer(proxy: ImageProxy) {
        if (!running.get()) return
        try {
            ensureEncoder(proxy)
            val activeCodec = codec ?: return
            val inputIndex = activeCodec.dequeueInputBuffer(0)
            if (inputIndex < 0) return

            val inputImage = try {
                activeCodec.getInputImage(inputIndex)
            } catch (_: Exception) {
                null
            }
            if (inputImage == null) {
                running.set(false)
                try { onError("This phone's H.264 encoder does not expose flexible YUV input") } catch (_: Exception) {}
                return
            }

            try {
                copyImage(proxy, inputImage, sourceRotation)
            } finally {
                inputImage.close()
            }

            val pts = max(proxy.imageInfo.timestamp / 1_000L, lastPresentationTimeUs + 1L)
            lastPresentationTimeUs = pts
            val size = outputWidth * outputHeight * 3 / 2
            activeCodec.queueInputBuffer(inputIndex, 0, size, pts, 0)
        } catch (e: Exception) {
            Log.w(TAG, "Dropping camera frame", e)
        }
    }

    @Synchronized
    private fun ensureEncoder(proxy: ImageProxy) {
        if (codec != null || !running.get()) return
        val rotation = ((proxy.imageInfo.rotationDegrees % 360) + 360) % 360
        sourceRotation = rotation
        outputWidth = if (rotation == 90 || rotation == 270) proxy.height else proxy.width
        outputHeight = if (rotation == 90 || rotation == 270) proxy.width else proxy.height
        outputWidth = outputWidth and 1.inv()
        outputHeight = outputHeight and 1.inv()
        try { onVideoDimensions(outputWidth, outputHeight) } catch (_: Exception) {}

        var created: MediaCodec? = null
        try {
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                outputWidth,
                outputHeight
            ).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfoCompat.COLOR_FORMAT_YUV420_FLEXIBLE)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            }

            val activeCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            created = activeCodec
            activeCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            activeCodec.start()
            codec = activeCodec

            outputThread = Thread({ drainOutput(activeCodec) }, "ProCam-H264-Encoder").apply {
                isDaemon = true
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unable to initialize H.264 encoder", e)
            running.set(false)
            try { created?.release() } catch (_: Exception) {}
            codec = null
            try { onError(e.message ?: "H.264 encoder is unavailable on this phone") } catch (_: Exception) {}
        }
    }

    private fun copyImage(source: ImageProxy, destination: Image, rotation: Int) {
        val sourcePlanes = source.planes
        val destinationPlanes = destination.planes
        if (sourcePlanes.size < 3 || destinationPlanes.size < 3) {
            throw IllegalStateException("Camera frame does not contain three YUV planes")
        }

        for (planeIndex in 0..2) {
            val src = sourcePlanes[planeIndex]
            val dst = destinationPlanes[planeIndex]
            val sourceWidth = if (planeIndex == 0) source.width else (source.width + 1) / 2
            val sourceHeight = if (planeIndex == 0) source.height else (source.height + 1) / 2
            val targetWidth = if (planeIndex == 0) outputWidth else outputWidth / 2
            val targetHeight = if (planeIndex == 0) outputHeight else outputHeight / 2
            val srcBuffer = src.buffer.duplicate()
            val dstBuffer = dst.buffer.duplicate()
            val srcBase = srcBuffer.position()
            val dstBase = dstBuffer.position()

            for (dy in 0 until targetHeight) {
                for (dx in 0 until targetWidth) {
                    val (sx, sy) = when (rotation) {
                        90 -> dy to (sourceHeight - 1 - dx)
                        180 -> (sourceWidth - 1 - dx) to (sourceHeight - 1 - dy)
                        270 -> (sourceWidth - 1 - dy) to dx
                        else -> dx to dy
                    }
                    if (sx !in 0 until sourceWidth || sy !in 0 until sourceHeight) continue
                    val srcIndex = srcBase + sy * src.rowStride + sx * src.pixelStride
                    val dstIndex = dstBase + dy * dst.rowStride + dx * dst.pixelStride
                    if (srcIndex < srcBuffer.limit() && dstIndex < dstBuffer.limit()) {
                        dstBuffer.put(dstIndex, srcBuffer.get(srcIndex))
                    }
                }
            }
        }
    }

    private fun drainOutput(activeCodec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running.get()) {
            try {
                when (val index = activeCodec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> updateCodecConfig(activeCodec.outputFormat)
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> if (index >= 0) {
                        val output = activeCodec.getOutputBuffer(index)
                        if (output != null && info.size > 0 &&
                            (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            val copy = ByteArray(info.size)
                            val view = output.duplicate()
                            view.position(info.offset)
                            view.limit(info.offset + info.size)
                            view.get(copy)
                            try {
                                onEncodedFrame(copy, info.presentationTimeUs)
                            } catch (e: Exception) {
                                Log.w(TAG, "Frame callback failed", e)
                            }
                        } else if (output != null && info.size > 0) {
                            val copy = ByteArray(info.size)
                            val view = output.duplicate()
                            view.position(info.offset)
                            view.limit(info.offset + info.size)
                            view.get(copy)
                            updateCodecConfigFromBytes(copy)
                        }
                        activeCodec.releaseOutputBuffer(index, false)
                    }
                }
            } catch (e: IllegalStateException) {
                if (running.get()) Log.w(TAG, "Encoder output loop stopped", e)
                break
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "Encoder output error", e)
            }
        }
    }

    private fun updateCodecConfig(format: MediaFormat) {
        val csd0 = format.getByteBuffer("csd-0")?.let(::copyRemaining)
        val csd1 = format.getByteBuffer("csd-1")?.let(::copyRemaining)
        val all = ArrayList<ByteArray>()
        if (csd0 != null) all += splitAnnexB(csd0)
        if (csd1 != null) all += splitAnnexB(csd1)
        val sps = all.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 7 }
        val pps = all.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 8 }
        if (sps != null && pps != null) {
            try { onCodecConfiguration(sps, pps) } catch (_: Exception) {}
        }
    }

    private fun updateCodecConfigFromBytes(data: ByteArray) {
        val nals = splitAnnexB(data)
        val sps = nals.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 7 }
        val pps = nals.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 8 }
        if (sps != null && pps != null) {
            try { onCodecConfiguration(sps, pps) } catch (_: Exception) {}
        }
    }

    private fun copyRemaining(buffer: ByteBuffer): ByteArray {
        val copy = buffer.duplicate()
        val bytes = ByteArray(copy.remaining())
        copy.get(bytes)
        return bytes
    }

    private fun splitAnnexB(data: ByteArray): List<ByteArray> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 3 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                (data[i + 2] == 1.toByte() ||
                    (data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()))
            ) {
                starts += i
                i += if (data[i + 2] == 1.toByte()) 3 else 4
            } else i++
        }
        if (starts.isEmpty()) return if (data.isNotEmpty()) listOf(data) else emptyList()
        val result = ArrayList<ByteArray>()
        for (n in starts.indices) {
            val start = starts[n] + if (data[starts[n] + 2] == 1.toByte()) 3 else 4
            val end = if (n + 1 < starts.size) starts[n + 1] else data.size
            if (end > start) result += data.copyOfRange(start, end)
        }
        return result
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false) && codec == null) return
        try { outputThread?.join(250) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        outputThread = null
        val old = codec
        codec = null
        try { old?.stop() } catch (_: Exception) {}
        try { old?.release() } catch (_: Exception) {}
        outputWidth = 0
        outputHeight = 0
        sourceRotation = -1
        lastPresentationTimeUs = 0L
    }

    private object MediaCodecInfoCompat {
        const val COLOR_FORMAT_YUV420_FLEXIBLE = 0x7F420888
    }
}

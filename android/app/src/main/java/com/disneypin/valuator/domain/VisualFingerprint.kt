package com.disneypin.valuator.domain

import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure-Kotlin 200-byte visual descriptor (model_name = "hsv-dhash-v1"):
 * - 8-byte 64-bit dHash
 * - 48-float (192-byte) L2-normalized HSV histogram (24 H + 12 S + 12 V)
 */
object VisualFingerprint {

    const val MODEL_NAME = "hsv-dhash-v1"
    const val BYTE_SIZE = 200

    fun compute(bitmap: Bitmap): ByteArray {
        val hashBmp = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
        val hashPixels = IntArray(9 * 8)
        hashBmp.getPixels(hashPixels, 0, 9, 0, 0, 9, 8)

        val histBmp = Bitmap.createScaledBitmap(bitmap, 64, 64, true)
        val histPixels = IntArray(64 * 64)
        histBmp.getPixels(histPixels, 0, 64, 0, 0, 64, 64)

        return computeFromResizedPixels(hashPixels, histPixels)
    }

    fun computeFromResizedPixels(hashPixels9x8: IntArray, histPixels64x64: IntArray): ByteArray {
        var dHash = 0L
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val leftLuma = luma(hashPixels9x8[y * 9 + x])
                val rightLuma = luma(hashPixels9x8[y * 9 + (x + 1)])
                if (leftLuma > rightLuma) {
                    dHash = dHash or (1L shl (y * 8 + x))
                }
            }
        }

        val hist = FloatArray(48)
        val margin = (64 * 0.12f).toInt()
        for (y in margin until (64 - margin)) {
            for (x in margin until (64 - margin)) {
                val argb = histPixels64x64[y * 64 + x]
                val (h, s, v) = rgbToHsv(argb)
                if (s >= 0.15f) {
                    val hBin = ((h / 360f) * 24f).toInt().coerceIn(0, 23)
                    hist[hBin] += s
                }
                val sBin = (s * 12f).toInt().coerceIn(0, 11)
                val vBin = (v * 12f).toInt().coerceIn(0, 11)
                hist[24 + sBin] += 1f
                hist[36 + vBin] += 1f
            }
        }

        var normSq = 0f
        for (v in hist) normSq += v * v
        val norm = sqrt(normSq).coerceAtLeast(1e-6f)
        for (i in hist.indices) hist[i] /= norm

        val buffer = ByteBuffer.allocate(BYTE_SIZE).order(ByteOrder.BIG_ENDIAN)
        buffer.putLong(dHash)
        for (f in hist) buffer.putFloat(f)
        return buffer.array()
    }

    fun similarity(a: ByteArray, b: ByteArray): Double {
        if (a.size != BYTE_SIZE || b.size != BYTE_SIZE) return 0.0
        val bufA = ByteBuffer.wrap(a).order(ByteOrder.BIG_ENDIAN)
        val bufB = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN)

        val hashA = bufA.long
        val hashB = bufB.long
        val hamming = java.lang.Long.bitCount(hashA xor hashB)
        val dHashSim = 1.0 - (hamming / 64.0)

        var dot = 0.0
        for (i in 0 until 48) {
            dot += bufA.float * bufB.float
        }
        val hsvSim = dot.coerceIn(0.0, 1.0)
        return (0.40 * dHashSim + 0.60 * hsvSim).coerceIn(0.0, 1.0)
    }

    private fun luma(argb: Int): Double {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return 0.299 * r + 0.587 * g + 0.114 * b
    }

    private fun rgbToHsv(argb: Int): Triple<Float, Float, Float> {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val cMax = max(r, max(g, b))
        val cMin = min(r, min(g, b))
        val delta = cMax - cMin

        val hue = when {
            delta < 1e-5f -> 0f
            cMax == r -> 60f * (((g - b) / delta) % 6f)
            cMax == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }.let { if (it < 0f) it + 360f else it }

        val sat = if (cMax < 1e-5f) 0f else delta / cMax
        return Triple(hue, sat, cMax)
    }
}

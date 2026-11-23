package com.disneypin.valuator

import com.disneypin.valuator.domain.VisualFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualFingerprintTest {

    @Test
    fun `identical descriptors have similarity 1_0 and distinct colors score low`() {
        val redPixels9x8 = IntArray(9 * 8) { idx ->
            if (idx % 2 == 0) 0xFFFF2020.toInt() else 0xFFCC1010.toInt()
        }
        val redPixels64x64 = IntArray(64 * 64) { 0xFFFF2020.toInt() }
        val descRed = VisualFingerprint.computeFromResizedPixels(redPixels9x8, redPixels64x64)

        val bluePixels9x8 = IntArray(9 * 8) { idx ->
            if (idx % 3 == 0) 0xFF1020FF.toInt() else 0xFF4060EE.toInt()
        }
        val bluePixels64x64 = IntArray(64 * 64) { 0xFF1020FF.toInt() }
        val descBlue = VisualFingerprint.computeFromResizedPixels(bluePixels9x8, bluePixels64x64)

        assertEquals(VisualFingerprint.BYTE_SIZE, descRed.size)
        assertEquals(1.0, VisualFingerprint.similarity(descRed, descRed), 1e-6)
        assertTrue(VisualFingerprint.similarity(descRed, descBlue) < 0.70)
    }
}

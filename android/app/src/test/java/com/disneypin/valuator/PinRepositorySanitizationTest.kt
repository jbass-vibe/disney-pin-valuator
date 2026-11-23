package com.disneypin.valuator

import com.disneypin.valuator.data.db.PinSanitizer
import org.junit.Assert.assertEquals
import org.junit.Test

class PinRepositorySanitizationTest {

    @Test
    fun `sanitizeRarity maps LE and AP strings to valid SQLite CHECK values`() {
        assertEquals("Super Chaser", PinSanitizer.sanitizeRarity("LE 300"))
        assertEquals("Super Chaser", PinSanitizer.sanitizeRarity("Artist Proof (AP)"))
        assertEquals("Chaser / Completer", PinSanitizer.sanitizeRarity("LE 1500"))
        assertEquals("Chaser / Completer", PinSanitizer.sanitizeRarity("Chaser"))
        assertEquals("Common", PinSanitizer.sanitizeRarity("Open Edition"))
    }

    @Test
    fun `sanitizePriceRange clamps negatives and swaps inverted bounds`() {
        assertEquals(20.0 to 40.0, PinSanitizer.sanitizePriceRange(40.0, 20.0))
        assertEquals(0.0 to 15.0, PinSanitizer.sanitizePriceRange(-5.0, 15.0))
    }

    @Test
    fun `formatGeminiPinId formats zero-padded GEM-XXXX`() {
        assertEquals("GEM-0001", PinSanitizer.formatGeminiPinId(1))
        assertEquals("GEM-0042", PinSanitizer.formatGeminiPinId(42))
    }

    @Test
    fun `sanitizeConfidence and sanitizeScrapperRisk normalize arbitrary strings`() {
        assertEquals("Verified", PinSanitizer.sanitizePriceConfidence("verified"))
        assertEquals("Peer Est.", PinSanitizer.sanitizePriceConfidence("estimated"))
        assertEquals("Low Conf", PinSanitizer.sanitizePriceConfidence("unknown"))
        assertEquals("High", PinSanitizer.sanitizeScrapperRisk("High Risk / Likely Scrapper"))
        assertEquals("Medium", PinSanitizer.sanitizeScrapperRisk("Moderate"))
        assertEquals("Low", PinSanitizer.sanitizeScrapperRisk("clean"))
    }
}

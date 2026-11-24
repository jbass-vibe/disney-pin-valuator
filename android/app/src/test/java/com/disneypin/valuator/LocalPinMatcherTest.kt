package com.disneypin.valuator

import com.disneypin.valuator.data.model.BoundingBoxNorm
import com.disneypin.valuator.data.model.DetectedPinCandidate
import com.disneypin.valuator.data.model.Pin
import com.disneypin.valuator.domain.LocalPinMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LocalPinMatcherTest {

    private val hippoPin = Pin(
        pinId = "PIN-0518",
        pinName = "Hippo",
        collection = "Hidden Disney 2025",
        subSet = "Disneyland D",
        resort = "DLR",
        rarity = "Super Chaser",
        marketLowUsd = 50.0,
        marketHighUsd = 65.0,
        estMarketValueUsd = 57.5,
        priceTier = "Grail ($50+)",
        priceUpdated = "2026-11-01",
        visualTags = "purple hippo, Disneyland D frame, Hidden Mickey"
    )

    private val unrelatedPin = Pin(
        pinId = "PIN-0100",
        pinName = "Stitch Ice Cream",
        collection = "Disney Ice Cream",
        subSet = "Cones",
        resort = "WDW",
        rarity = "Common",
        marketLowUsd = 10.0,
        marketHighUsd = 15.0,
        estMarketValueUsd = 12.5,
        priceTier = "Standard (<$20)",
        priceUpdated = "2026-11-01",
        visualTags = "blue alien, waffle cone"
    )

    @Test
    fun `matchByMetadata matches Hippo Disneyland D to PIN-0518 and rejects unrelated`() {
        val candidate = DetectedPinCandidate(
            tempId = 0,
            box2d = BoundingBoxNorm(100, 200, 300, 400),
            pinName = "Hippo",
            character = "Hippo",
            collectionGuess = "Hidden Disney",
            subSetGuess = "Disneyland D",
            resortGuess = "DLR",
            visualTags = "purple hippo, Disneyland D frame"
        )
        val match = LocalPinMatcher.matchByMetadata(candidate, listOf(unrelatedPin, hippoPin))
        assertNotNull(match)
        assertEquals("PIN-0518", match!!.first.pinId)
        assertTrue(match.second >= LocalPinMatcher.METADATA_MATCH_THRESHOLD)

        val noMatch = LocalPinMatcher.matchByMetadata(candidate, listOf(unrelatedPin))
        assertNull(noMatch)
    }

    @Test
    fun `matchByMetadata matches descriptive Marie Ear Headband to catalog Marie`() {
        val mariePin = Pin(
            pinId = "PIN-0081",
            pinName = "Marie",
            collection = "Hidden Disney",
            subSet = "Ear Headbands",
            resort = "DLR",
            rarity = "Common",
            marketLowUsd = 14.0,
            marketHighUsd = 20.0,
            estMarketValueUsd = 17.0,
            priceTier = "Standard (<$20)",
            priceUpdated = "2026-11-01"
        )
        val candidate = DetectedPinCandidate(
            tempId = 1,
            box2d = BoundingBoxNorm(200, 100, 350, 250),
            pinName = "Marie Ear Headband",
            character = "Marie",
            collectionGuess = "Hidden Disney",
            subSetGuess = "Ear Headbands"
        )
        val match = LocalPinMatcher.matchByMetadata(candidate, listOf(unrelatedPin, mariePin))
        assertNotNull(match)
        assertEquals("PIN-0081", match!!.first.pinId)
    }

    @Test
    fun `isPriceFresh respects 60 day threshold`() {
        val today = LocalDate.of(2026, 11, 23)
        assertTrue(LocalPinMatcher.isPriceFresh("2026-10-24", today, 60))
        assertFalse(LocalPinMatcher.isPriceFresh("2026-08-25", today, 60))
    }
}

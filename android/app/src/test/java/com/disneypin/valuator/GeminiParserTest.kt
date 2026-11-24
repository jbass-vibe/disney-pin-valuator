package com.disneypin.valuator

import com.disneypin.valuator.data.model.BoundingBoxNorm
import com.disneypin.valuator.data.model.DetectedPinCandidate
import com.disneypin.valuator.data.model.MatchSource
import com.disneypin.valuator.data.model.ScannedPinResult
import com.disneypin.valuator.data.remote.GeminiResponseParser
import com.disneypin.valuator.domain.PinScanOrchestrator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiParserTest {

    @Test
    fun `parsePass1Response parses multi-pin detection JSON with initial estimates and fallback defaults`() {
        val raw = """
            {
              "candidates": [{
                "content": {
                  "parts": [{
                    "text": "{\"pins\":[{\"box_2d\":[100,150,250,300],\"pin_name\":\"Hippo\",\"character\":\"Hippo\",\"collection_guess\":\"Hidden Disney\",\"sub_set_guess\":\"Disneyland D\",\"resort_guess\":\"DLR\",\"rarity_hint\":\"Super Chaser\",\"est_low_usd\":45.0,\"est_high_usd\":65.0,\"needs_web_verification\":true,\"visual_tags\":\"purple hippo, D frame\",\"has_hidden_mickey\":true,\"confidence\":0.92,\"scrapper_risk_hint\":\"Low\",\"scrapper_notes\":\"\"},{\"box_2d\":[300,400,500,600],\"pin_name\":\"Mater\",\"character\":\"Mater\",\"collection_guess\":\"Cars\",\"sub_set_guess\":\"\",\"resort_guess\":\"DLR\",\"rarity_hint\":\"Common\",\"visual_tags\":\"brown tow truck\",\"has_hidden_mickey\":false,\"confidence\":0.88,\"scrapper_risk_hint\":\"Low\",\"scrapper_notes\":\"\"}]}"
                  }]
                }
              }]
            }
        """.trimIndent()

        val parsed = GeminiResponseParser.parsePass1Response(raw)
        assertEquals(2, parsed.size)
        assertEquals("Hippo", parsed[0].pinName)
        assertEquals(45.0, parsed[0].estLowUsd, 0.01)
        assertEquals(65.0, parsed[0].estHighUsd, 0.01)
        assertTrue(parsed[0].needsWebVerification)

        // Mater omitted est_low_usd / est_high_usd / needs_web_verification -> fallback defaults
        assertEquals("Mater", parsed[1].pinName)
        assertEquals(10.0, parsed[1].estLowUsd, 0.01)
        assertEquals(16.0, parsed[1].estHighUsd, 0.01)
        assertFalse(parsed[1].needsWebVerification)
    }

    @Test
    fun `parsePass2Response handles markdown fences, swapped prices, and groundingChunks`() {
        val raw = """
            {
              "candidates": [{
                "content": {
                  "parts": [{
                    "text": "Here are the valuations:\n```json\n{\"valuations\":[{\"temp_id\":0,\"pin_name\":\"Donald Cupcake\",\"collection\":\"Disney Cupcakes\",\"rarity\":\"LE 300\",\"market_low_usd\":28.0,\"market_high_usd\":18.0,\"price_confidence\":\"Verified\",\"scrapper_risk\":\"Low\",\"visual_tags\":\"donald duck cupcake\",\"notes\":\"Recent eBay sold comps\"}]}\n```\nHope this helps!"
                  }]
                },
                "groundingMetadata": {
                  "groundingChunks": [
                    {"web": {"uri": "https://www.ebay.com/itm/123", "title": "eBay Sold Listing"}},
                    {"web": {"uri": "https://www.pinpics.com/pin/999", "title": "PinPics"}}
                  ]
                }
              }]
            }
        """.trimIndent()

        val parsed = GeminiResponseParser.parsePass2Response(raw)
        assertEquals(1, parsed.size)
        val v = parsed.first()
        assertEquals("Donald Cupcake", v.pinName)
        assertEquals("Super Chaser", v.rarity)
        assertEquals(18.0, v.marketLowUsd, 0.01)
        assertEquals(28.0, v.marketHighUsd, 0.01)
        assertEquals(2, v.citations.size)
        assertTrue(v.citations[0].url.contains("ebay.com"))
    }

    @Test
    fun `selectTopForVerification caps at 4 highest est_high_usd pins`() {
        val items = (1..7).map { idx ->
            val cand = DetectedPinCandidate(
                tempId = idx,
                box2d = BoundingBoxNorm(0, 0, 100, 100),
                pinName = "Pin $idx",
                estLowUsd = idx * 5.0,
                estHighUsd = idx * 10.0,
                needsWebVerification = true
            )
            ScannedPinResult(
                detectionId = "p0_t$idx",
                photoIndex = 0,
                box2d = cand.box2d,
                candidate = cand,
                matchSource = MatchSource.PENDING_SEARCH,
                marketLowUsd = cand.estLowUsd,
                marketHighUsd = cand.estHighUsd
            )
        }
        val selected = PinScanOrchestrator.selectTopForVerification(items)
        assertEquals(4, selected.size)
        assertEquals(listOf("p0_t7", "p0_t6", "p0_t5", "p0_t4"), selected.map { it.detectionId })
    }
}

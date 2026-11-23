package com.disneypin.valuator

import com.disneypin.valuator.data.remote.GeminiResponseParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiParserTest {

    @Test
    fun `parsePass1Response parses multi-pin detection JSON`() {
        val raw = """
            {
              "candidates": [{
                "content": {
                  "parts": [{
                    "text": "{\"pins\":[{\"box_2d\":[100,150,250,300],\"pin_name\":\"Hippo\",\"character\":\"Hippo\",\"collection_guess\":\"Hidden Disney\",\"sub_set_guess\":\"Disneyland D\",\"resort_guess\":\"DLR\",\"rarity_hint\":\"Super Chaser\",\"visual_tags\":\"purple hippo, D frame\",\"has_hidden_mickey\":true,\"confidence\":0.92,\"scrapper_risk_hint\":\"Low\",\"scrapper_notes\":\"\"},{\"box_2d\":[300,400,500,600],\"pin_name\":\"Mater\",\"character\":\"Mater\",\"collection_guess\":\"Cars\",\"sub_set_guess\":\"\",\"resort_guess\":\"DLR\",\"rarity_hint\":\"Common\",\"visual_tags\":\"brown tow truck\",\"has_hidden_mickey\":false,\"confidence\":0.88,\"scrapper_risk_hint\":\"Low\",\"scrapper_notes\":\"\"}]}"
                  }]
                }
              }]
            }
        """.trimIndent()

        val parsed = GeminiResponseParser.parsePass1Response(raw)
        assertEquals(2, parsed.size)
        assertEquals("Hippo", parsed[0].pinName)
        assertEquals(100, parsed[0].box2d.ymin)
        assertEquals("Super Chaser", parsed[0].rarityHint)
        assertEquals("Mater", parsed[1].pinName)
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
}

package com.disneypin.valuator

import com.disneypin.valuator.data.model.BackstampStatus
import com.disneypin.valuator.data.model.EnamelQualityStatus
import com.disneypin.valuator.data.model.Pin
import com.disneypin.valuator.data.model.ScrapperAssessmentInput
import com.disneypin.valuator.data.model.ScrapperRiskLevel
import com.disneypin.valuator.data.model.TradeListEntry
import com.disneypin.valuator.data.model.TradeListType
import com.disneypin.valuator.data.model.WafflePatternStatus
import com.disneypin.valuator.domain.EvaluationEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvaluationEngineTest {

    @Test
    fun `extractOcrTags parses FAC code, Limited Edition size, and Disney copyright`() {
        val text = "© DISNEY MADE IN CHINA FAC-036223-21045 LIMITED EDITION OF 500"
        val tags = EvaluationEngine.extractOcrTags(text)
        assertTrue(tags.any { it.contains("FAC-036223-21045") })
        assertTrue(tags.any { it.contains("LE 500") })
        assertTrue(tags.any { it.contains("Copyright Present") })
    }

    @Test
    fun `clean authentic pin scores Low Risk`() {
        val result = EvaluationEngine.assessScrapperRisk(
            ScrapperAssessmentInput(
                wafflePattern = WafflePatternStatus.CRISP,
                enamelQuality = EnamelQualityStatus.SMOOTH_LEVEL,
                backstampStatus = BackstampStatus.CRISP_OFFICIAL,
                weightGrams = 11.5,
                thicknessMm = 1.8,
                isMagnetic = false,
                ocrBackstampText = "© DISNEY FAC-036223-21045"
            )
        )
        assertEquals(ScrapperRiskLevel.LOW, result.riskLevel)
        assertEquals(0, result.riskScore)
    }

    @Test
    fun `magnetic blurry pin with enamel bleed scores High Risk`() {
        val result = EvaluationEngine.assessScrapperRisk(
            ScrapperAssessmentInput(
                wafflePattern = WafflePatternStatus.BLURRY_OR_MISSING,
                enamelQuality = EnamelQualityStatus.BLEED_OR_PINHOLES,
                backstampStatus = BackstampStatus.INCORRECT_OR_MISSING,
                weightGrams = 4.2,
                thicknessMm = 1.0,
                isMagnetic = true
            )
        )
        assertEquals(ScrapperRiskLevel.HIGH, result.riskLevel)
        assertTrue(result.riskScore >= 80)
        assertTrue(result.reasons.size >= 4)
    }

    @Test
    fun `appraisePin applies condition grade and original card multiplier`() {
        val appraisal = EvaluationEngine.appraisePin(
            baseFmvUsd = 40.0,
            conditionGrade = 10,
            hasOriginalCard = true,
            scrapperInput = ScrapperAssessmentInput(),
            rarity = "Super Chaser"
        )
        assertEquals(48.0, appraisal.adjustedFmvUsd, 0.01)
        assertEquals(ScrapperRiskLevel.LOW, appraisal.scrapperAssessment.riskLevel)
        assertTrue(appraisal.liquidityScore >= 90)
    }

    @Test
    fun `matchTrades pairs complementary traders and ISO pins within parity window`() {
        val traderPin = Pin(
            pinId = "PIN-001",
            pinName = "Haunted Mansion Stretching Portrait",
            collection = "Haunted Mansion",
            marketLowUsd = 35.0,
            marketHighUsd = 45.0,
            estMarketValueUsd = 40.0,
            priceTier = "High ($30-$49)",
            priceUpdated = "2026-01-01"
        )
        val isoPin = Pin(
            pinId = "PIN-002",
            pinName = "Figment Imagination",
            collection = "EPCOT",
            marketLowUsd = 36.0,
            marketHighUsd = 42.0,
            estMarketValueUsd = 39.0,
            priceTier = "High ($30-$49)",
            priceUpdated = "2026-01-01"
        )
        val matches = EvaluationEngine.matchTrades(
            traders = listOf(TradeListEntry(1L, traderPin, TradeListType.TRADER, 8, "2026-01-01")),
            isoPins = listOf(isoPin)
        )
        assertEquals(1, matches.size)
        assertTrue(matches.first().isFairMatch)
        assertTrue(matches.first().parityPercent >= 95)
    }
}

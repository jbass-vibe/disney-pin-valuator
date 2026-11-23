package com.disneypin.valuator.domain

import com.disneypin.valuator.data.model.AppraisalResult
import com.disneypin.valuator.data.model.BackstampStatus
import com.disneypin.valuator.data.model.EnamelQualityStatus
import com.disneypin.valuator.data.model.Pin
import com.disneypin.valuator.data.model.ScrapperAssessmentInput
import com.disneypin.valuator.data.model.ScrapperAssessmentResult
import com.disneypin.valuator.data.model.ScrapperRiskLevel
import com.disneypin.valuator.data.model.TradeListEntry
import com.disneypin.valuator.data.model.TradeMatch
import com.disneypin.valuator.data.model.WafflePatternStatus
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure domain engine implementing:
 * - FR-1.3: Backstamp OCR tag extraction (LE, FAC, © Disney)
 * - FR-2.1..2.4: Counterfeit / Scrapper risk scoring
 * - FR-3.1..3.2: 10-point condition grading & packaging multiplier
 * - FR-4.2..4.3: Fair Market Value (FMV) & liquidity calculation
 * - FR-5.2: Fair-value trade parity matching
 */
object EvaluationEngine {

    private val facRegex = Regex("""FAC-\d{6}-\d{5}""", RegexOption.IGNORE_CASE)
    private val leRegex = Regex("""LIMITED\s+EDITION\s+(?:OF\s+)?(\d+)""", RegexOption.IGNORE_CASE)
    private val disneyCopyrightRegex = Regex("""©\s*DISNEY""", RegexOption.IGNORE_CASE)

    fun extractOcrTags(ocrText: String): List<String> {
        if (ocrText.isBlank()) return emptyList()
        val tags = mutableListOf<String>()
        facRegex.find(ocrText)?.let { tags.add("FAC Code: ${it.value.uppercase()}") }
        leRegex.find(ocrText)?.let { tags.add("Edition Size: LE ${it.groupValues[1]}") }
        if (disneyCopyrightRegex.containsMatchIn(ocrText)) {
            tags.add("© Disney Copyright Present")
        }
        if (ocrText.contains("AP", ignoreCase = true) && Regex("""\bAP\b""").containsMatchIn(ocrText)) {
            tags.add("Artist Proof (AP) Stamp")
        }
        if (ocrText.contains("PP", ignoreCase = true) && Regex("""\bPP\b""").containsMatchIn(ocrText)) {
            tags.add("Pre-Production (PP) Stamp")
        }
        return tags
    }

    fun assessScrapperRisk(input: ScrapperAssessmentInput): ScrapperAssessmentResult {
        var score = 0
        val reasons = mutableListOf<String>()

        score += input.wafflePattern.riskPoints
        if (input.wafflePattern != WafflePatternStatus.CRISP) {
            reasons.add("Back pattern: ${input.wafflePattern.label}")
        }

        score += input.enamelQuality.riskPoints
        if (input.enamelQuality != EnamelQualityStatus.SMOOTH_LEVEL) {
            reasons.add("Enamel inspection: ${input.enamelQuality.label}")
        }

        score += input.backstampStatus.riskPoints
        if (input.backstampStatus != BackstampStatus.CRISP_OFFICIAL) {
            reasons.add("Backstamp check: ${input.backstampStatus.label}")
        }

        if (input.isMagnetic) {
            score += 25
            reasons.add("Magnetic alloy detected (authentic Disney pins are typically non-magnetic zinc/brass)")
        }

        input.weightGrams?.let { weight ->
            if (weight > 0.0 && weight < 6.0) {
                score += 15
                reasons.add("Low weight (${weight}g) may indicate thin counterfeit casting")
            }
        }

        input.thicknessMm?.let { thickness ->
            if (thickness > 0.0 && thickness < 1.2) {
                score += 12
                reasons.add("Thin profile (${thickness}mm) below standard 1.5mm+ cloisonné/enamel spec")
            }
        }

        when (input.baselineCatalogRisk.lowercase()) {
            "high" -> {
                score += 12
                reasons.add("Catalog note: High counterfeit prevalence reported for this release")
            }
            "medium" -> {
                score += 5
                reasons.add("Catalog note: Moderate counterfeit prevalence reported for this release")
            }
        }

        val ocrTags = extractOcrTags(input.ocrBackstampText)
        if (ocrTags.isNotEmpty()) {
            score = max(0, score - 5)
        }

        val clampedScore = score.coerceIn(0, 100)
        val level = when {
            clampedScore >= 55 -> ScrapperRiskLevel.HIGH
            clampedScore >= 25 -> ScrapperRiskLevel.MODERATE
            else -> ScrapperRiskLevel.LOW
        }

        if (reasons.isEmpty()) {
            reasons.add("All physical and visual indicators align with authentic Disney Parks production.")
        }

        return ScrapperAssessmentResult(
            riskScore = clampedScore,
            riskLevel = level,
            reasons = reasons,
            detectedOcrTags = ocrTags
        )
    }

    fun gradeLabel(grade: Int): String = when (grade.coerceIn(1, 10)) {
        10 -> "Grade 10 — Gem Mint"
        8, 9 -> "Grade $grade — Near Mint / Excellent"
        5, 6, 7 -> "Grade $grade — Good / Fine"
        else -> "Grade ${grade.coerceIn(1, 10)} — Poor / Damaged"
    }

    fun conditionMultiplier(grade: Int, hasOriginalCard: Boolean): Double {
        val base = when (grade.coerceIn(1, 10)) {
            10 -> 1.15
            9 -> 1.05
            8 -> 1.00
            7 -> 0.88
            6 -> 0.78
            5 -> 0.68
            4 -> 0.52
            3 -> 0.40
            2 -> 0.28
            else -> 0.20
        }
        val cardBonus = if (hasOriginalCard && grade >= 7) 0.05 else 0.0
        return base + cardBonus
    }

    fun appraisePin(
        baseFmvUsd: Double,
        conditionGrade: Int,
        hasOriginalCard: Boolean,
        scrapperInput: ScrapperAssessmentInput,
        rarity: String = "Common"
    ): AppraisalResult {
        val scrapperResult = assessScrapperRisk(scrapperInput)
        val condMult = conditionMultiplier(conditionGrade, hasOriginalCard)
        val riskMult = when (scrapperResult.riskLevel) {
            ScrapperRiskLevel.LOW -> 1.0
            ScrapperRiskLevel.MODERATE -> 0.75
            ScrapperRiskLevel.HIGH -> 0.15
        }
        val adjusted = ((baseFmvUsd * condMult * riskMult) * 100.0).roundToInt() / 100.0

        val baseLiquidity = when (rarity) {
            "Super Chaser" -> 90
            "Chaser / Completer" -> 80
            else -> 65
        }
        val liquidityPenalty = when (scrapperResult.riskLevel) {
            ScrapperRiskLevel.LOW -> 0
            ScrapperRiskLevel.MODERATE -> 20
            ScrapperRiskLevel.HIGH -> 55
        }
        val gradeBoost = if (conditionGrade >= 9) 8 else if (conditionGrade <= 4) -15 else 0
        val liquidity = (baseLiquidity - liquidityPenalty + gradeBoost).coerceIn(5, 99)

        return AppraisalResult(
            baseFmvUsd = baseFmvUsd,
            adjustedFmvUsd = adjusted,
            conditionGrade = conditionGrade.coerceIn(1, 10),
            gradeLabel = gradeLabel(conditionGrade),
            hasOriginalCard = hasOriginalCard,
            liquidityScore = liquidity,
            scrapperAssessment = scrapperResult
        )
    }

    fun matchTrades(
        traders: List<TradeListEntry>,
        isoPins: List<Pin>,
        maxDifferenceRatio: Double = 0.25
    ): List<TradeMatch> {
        if (traders.isEmpty() || isoPins.isEmpty()) return emptyList()
        val matches = mutableListOf<TradeMatch>()
        for (trader in traders) {
            val traderVal = trader.pin.estMarketValueUsd * conditionMultiplier(trader.conditionGrade, false)
            for (iso in isoPins) {
                if (trader.pin.pinId == iso.pinId) continue
                val isoVal = iso.estMarketValueUsd
                val maxVal = max(traderVal, isoVal).coerceAtLeast(1.0)
                val diff = traderVal - isoVal
                val ratio = abs(diff) / maxVal
                if (ratio <= maxDifferenceRatio) {
                    val parity = ((1.0 - ratio) * 100.0).roundToInt().coerceIn(0, 100)
                    matches.add(
                        TradeMatch(
                            yourTrader = trader,
                            targetIso = iso,
                            yourValueUsd = (traderVal * 100.0).roundToInt() / 100.0,
                            targetValueUsd = (isoVal * 100.0).roundToInt() / 100.0,
                            valueDiffUsd = (diff * 100.0).roundToInt() / 100.0,
                            parityPercent = parity,
                            isFairMatch = parity >= 85
                        )
                    )
                }
            }
        }
        return matches.sortedByDescending { it.parityPercent }
    }
}

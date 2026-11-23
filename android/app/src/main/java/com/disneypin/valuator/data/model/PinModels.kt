package com.disneypin.valuator.data.model

data class Pin(
    val pinId: String,
    val pinName: String,
    val collection: String,
    val subSet: String = "",
    val resort: String = "",
    val yearWave: String = "",
    val rarity: String = "Common",
    val marketLowUsd: Double,
    val marketHighUsd: Double,
    val estMarketValueUsd: Double,
    val priceTier: String,
    val priceUpdated: String,
    val priceConfidence: String = "Verified",
    val scrapperRisk: String = "Low",
    val visualTags: String = "",
    val pinpicsId: String = "",
    val sourceFile: String = "",
    val notes: String = ""
)

data class PriceHistoryEntry(
    val id: Long,
    val pinId: String,
    val lowUsd: Double,
    val highUsd: Double,
    val recordedAt: String
)

data class CatalogStats(
    val totalPins: Int,
    val grailCount: Int,
    val highValueCount: Int,
    val avgMarketValueUsd: Double,
    val resorts: List<String>
)

data class PortfolioItem(
    val id: Long,
    val pin: Pin,
    val purchasePriceUsd: Double,
    val conditionGrade: Int,
    val hasOriginalCard: Boolean,
    val notes: String,
    val addedAt: String
)

enum class TradeListType {
    TRADER,
    ISO
}

data class TradeListEntry(
    val id: Long,
    val pin: Pin,
    val listType: TradeListType,
    val conditionGrade: Int,
    val addedAt: String
)

data class TradeMatch(
    val yourTrader: TradeListEntry,
    val targetIso: Pin,
    val yourValueUsd: Double,
    val targetValueUsd: Double,
    val valueDiffUsd: Double,
    val parityPercent: Int,
    val isFairMatch: Boolean
)

enum class WafflePatternStatus(val label: String, val riskPoints: Int) {
    CRISP("Crisp Waffle / Clean Edge", 0),
    SOFT_OR_BORDERS("Soft Pattern / Slight Border", 18),
    BLURRY_OR_MISSING("Blurry / Missing Nubs", 38)
}

enum class EnamelQualityStatus(val label: String, val riskPoints: Int) {
    SMOOTH_LEVEL("Smooth & Level Enamel", 0),
    MINOR_DIPS("Minor Dips / Light Marks", 15),
    BLEED_OR_PINHOLES("Paint Bleed / Pinholes / Rough", 35)
}

enum class BackstampStatus(val label: String, val riskPoints: Int) {
    CRISP_OFFICIAL("Crisp © Disney / FAC Stamp", 0),
    FADED_FONT("Faded / Uneven Font", 16),
    INCORRECT_OR_MISSING("Incorrect / Missing Stamp", 32)
}

enum class ScrapperRiskLevel(val label: String) {
    LOW("Low Risk"),
    MODERATE("Moderate Risk"),
    HIGH("High Risk / Likely Scrapper")
}

data class ScrapperAssessmentInput(
    val wafflePattern: WafflePatternStatus = WafflePatternStatus.CRISP,
    val enamelQuality: EnamelQualityStatus = EnamelQualityStatus.SMOOTH_LEVEL,
    val backstampStatus: BackstampStatus = BackstampStatus.CRISP_OFFICIAL,
    val weightGrams: Double? = null,
    val thicknessMm: Double? = null,
    val isMagnetic: Boolean = false,
    val ocrBackstampText: String = "",
    val baselineCatalogRisk: String = "Low"
)

data class ScrapperAssessmentResult(
    val riskScore: Int,
    val riskLevel: ScrapperRiskLevel,
    val reasons: List<String>,
    val detectedOcrTags: List<String>
)

data class AppraisalResult(
    val baseFmvUsd: Double,
    val adjustedFmvUsd: Double,
    val conditionGrade: Int,
    val gradeLabel: String,
    val hasOriginalCard: Boolean,
    val liquidityScore: Int,
    val scrapperAssessment: ScrapperAssessmentResult
)

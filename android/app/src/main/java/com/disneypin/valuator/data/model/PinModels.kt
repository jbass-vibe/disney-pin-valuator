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
    val notes: String = "",
    val dataSource: String = "Seeded",
    val referenceImagePath: String = "",
    val valuationSources: String = ""
)

data class PriceHistoryEntry(
    val historyId: Long,
    val pinId: String,
    val lowUsd: Double,
    val highUsd: Double,
    val estMarketValueUsd: Double,
    val recordedAt: String,
    val source: String
)

data class CatalogStats(
    val totalPins: Int,
    val grailCount: Int,
    val highValueCount: Int,
    val avgMarketValueUsd: Double,
    val resorts: List<String>,
    val seededCount: Int = totalPins,
    val learnedCount: Int = 0,
    val embeddingsCount: Int = 0
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

// --- v2.0 Photo-First Hybrid Pipeline Models ---

data class AppSettings(
    val geminiApiKey: String = "",
    val geminiModel: String = "gemini-2.5-flash",
    val stalePriceDays: Int = 60
)

data class BoundingBoxNorm(
    val ymin: Int,
    val xmin: Int,
    val ymax: Int,
    val xmax: Int
) {
    val centerX: Float get() = (xmin + xmax) / 2000f
    val bottomY: Float get() = ymax / 1000f
    val topY: Float get() = ymin / 1000f
}

data class DetectedPinCandidate(
    val tempId: Int,
    val box2d: BoundingBoxNorm,
    val pinName: String,
    val character: String = "",
    val collectionGuess: String = "",
    val subSetGuess: String = "",
    val resortGuess: String = "",
    val rarityHint: String = "Common",
    val visualTags: String = "",
    val hasHiddenMickey: Boolean = false,
    val confidence: Double = 0.8,
    val scrapperRiskHint: String = "Low",
    val scrapperNotes: String = ""
)

data class PinValuationSource(
    val title: String,
    val url: String
)

enum class MatchSource(val label: String) {
    LOCAL_VISUAL("Local Visual"),
    LOCAL_DB("Local DB"),
    GEMINI_SEARCH("Gemini Web"),
    PENDING_SEARCH("Searching...")
}

data class ScannedPinResult(
    val detectionId: String,
    val photoIndex: Int,
    val box2d: BoundingBoxNorm,
    val cropImagePath: String = "",
    val candidate: DetectedPinCandidate,
    val matchedPin: Pin? = null,
    val matchSource: MatchSource = MatchSource.PENDING_SEARCH,
    val matchConfidence: Double = 0.0,
    val displayTitle: String = candidate.pinName,
    val displayCollection: String = candidate.collectionGuess,
    val marketLowUsd: Double = 0.0,
    val marketHighUsd: Double = 0.0,
    val estMarketValueUsd: Double = 0.0,
    val priceTier: String = "Standard (<$20)",
    val scrapperRisk: String = candidate.scrapperRiskHint,
    val priceConfidence: String = "Low Conf",
    val citations: List<PinValuationSource> = emptyList(),
    val isRefreshing: Boolean = false,
    val errorNote: String? = null
)

data class PhotoScanResult(
    val photoIndex: Int,
    val imagePath: String,
    val scanId: Long? = null,
    val pins: List<ScannedPinResult> = emptyList(),
    val errorMessage: String? = null
)

data class ScanBatchState(
    val isScanning: Boolean = false,
    val statusMessage: String = "",
    val photos: List<PhotoScanResult> = emptyList(),
    val selectedPhotoIndex: Int = 0,
    val selectedDetectionId: String? = null,
    val errorMessage: String? = null
) {
    val allPinsRanked: List<ScannedPinResult>
        get() = photos.flatMap { it.pins }
            .sortedWith(
                compareByDescending<ScannedPinResult> { it.estMarketValueUsd }
                    .thenBy { it.matchSource == MatchSource.PENDING_SEARCH }
            )
}

data class BoardScanSummary(
    val scanId: Long,
    val imageFilename: String,
    val imagePath: String,
    val location: String,
    val scannedAt: String,
    val pinCount: Int,
    val totalLowUsd: Double,
    val totalHighUsd: Double,
    val totalMidUsd: Double,
    val status: String
)

data class PendingScanItem(
    val id: Long,
    val imagePath: String,
    val queuedAt: String,
    val status: String
)

data class PinEmbeddingRecord(
    val embeddingId: Long,
    val pinId: String,
    val imagePath: String,
    val modelName: String,
    val embeddingBlob: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PinEmbeddingRecord) return false
        return embeddingId == other.embeddingId &&
            pinId == other.pinId &&
            modelName == other.modelName &&
            embeddingBlob.contentEquals(other.embeddingBlob)
    }

    override fun hashCode(): Int {
        var result = embeddingId.hashCode()
        result = 31 * result + pinId.hashCode()
        result = 31 * result + modelName.hashCode()
        result = 31 * result + embeddingBlob.contentHashCode()
        return result
    }
}

data class LearnedPinInput(
    val pinName: String,
    val collection: String,
    val subSet: String = "",
    val resort: String = "",
    val yearWave: String = "",
    val rarity: String = "Common",
    val marketLowUsd: Double,
    val marketHighUsd: Double,
    val priceConfidence: String = "Peer Est.",
    val scrapperRisk: String = "Low",
    val visualTags: String = "",
    val notes: String = "",
    val dataSource: String = "Gemini Search",
    val referenceImagePath: String = "",
    val valuationSourcesJson: String = ""
)

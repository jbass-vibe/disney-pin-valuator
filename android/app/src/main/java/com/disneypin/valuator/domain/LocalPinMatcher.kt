package com.disneypin.valuator.domain

import com.disneypin.valuator.data.model.DetectedPinCandidate
import com.disneypin.valuator.data.model.Pin
import com.disneypin.valuator.data.model.PinEmbeddingRecord
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.max

sealed class MatchOutcome {
    data class LocalVisualFresh(val pin: Pin, val confidence: Double) : MatchOutcome()
    data class LocalMetadataFresh(val pin: Pin, val confidence: Double) : MatchOutcome()
    data class LocalStale(val pin: Pin, val confidence: Double) : MatchOutcome()
    data object Unmatched : MatchOutcome()
}

object LocalPinMatcher {

    const val VISUAL_MATCH_THRESHOLD = 0.84
    const val VISUAL_GAP_THRESHOLD = 0.04
    const val METADATA_MATCH_THRESHOLD = 0.72

    fun matchByVisual(
        cropEmbedding: ByteArray,
        storedEmbeddings: List<PinEmbeddingRecord>
    ): Pair<String, Double>? {
        if (storedEmbeddings.isEmpty()) return null
        val scored = storedEmbeddings
            .map { it.pinId to VisualFingerprint.similarity(cropEmbedding, it.embeddingBlob) }
            .sortedByDescending { it.second }

        val best = scored.firstOrNull() ?: return null
        if (best.second < VISUAL_MATCH_THRESHOLD) return null
        val second = scored.getOrNull(1)
        if (second != null && second.first != best.first && (best.second - second.second) < VISUAL_GAP_THRESHOLD) {
            return null
        }
        return best
    }

    fun scoreCandidate(candidate: DetectedPinCandidate, pin: Pin): Double {
        val nameCand = "${candidate.pinName} ${candidate.character}"
        val nameScore = tokenOverlapWithSubstringBonus(nameCand, pin.pinName)

        val colCand = "${candidate.collectionGuess} ${candidate.subSetGuess}"
        val colTarget = "${pin.collection} ${pin.subSet}"
        val colScore = if (colCand.isBlank()) 0.5 else tokenOverlapWithSubstringBonus(colCand, colTarget)

        val tagCand = "${candidate.visualTags} ${candidate.resortGuess}"
        val tagTarget = "${pin.visualTags} ${pin.resort}"
        val tagScore = if (tagCand.isBlank()) 0.5 else tokenOverlapWithSubstringBonus(tagCand, tagTarget)

        return (0.50 * nameScore + 0.30 * colScore + 0.20 * tagScore).coerceIn(0.0, 1.0)
    }

    fun matchByMetadata(
        candidate: DetectedPinCandidate,
        catalogCandidates: List<Pin>
    ): Pair<Pin, Double>? {
        if (catalogCandidates.isEmpty()) return null
        val best = catalogCandidates
            .map { it to scoreCandidate(candidate, it) }
            .maxByOrNull { it.second } ?: return null

        return if (best.second >= METADATA_MATCH_THRESHOLD) best else null
    }

    fun isPriceFresh(
        priceUpdated: String,
        today: LocalDate = LocalDate.now(),
        staleThresholdDays: Int = 60
    ): Boolean {
        val parsed = runCatching { LocalDate.parse(priceUpdated.trim()) }.getOrNull() ?: return false
        val days = ChronoUnit.DAYS.between(parsed, today)
        return days <= staleThresholdDays
    }

    fun buildSearchQueries(candidate: DetectedPinCandidate): List<String> {
        val queries = mutableListOf<String>()
        val charAndName = "${candidate.character} ${candidate.pinName}".trim()
        if (charAndName.isNotEmpty()) queries.add(charAndName)
        if (candidate.character.isNotBlank()) queries.add(candidate.character.trim())
        if (candidate.subSetGuess.isNotBlank()) queries.add(candidate.subSetGuess.trim())
        return queries.distinct()
    }

    private fun tokenOverlapWithSubstringBonus(a: String, b: String): Double {
        val tokensA = tokenize(a)
        val tokensB = tokenize(b)
        if (tokensA.isEmpty() || tokensB.isEmpty()) return 0.0

        val intersection = tokensA.intersect(tokensB).size.toDouble()
        val union = tokensA.union(tokensB).size.toDouble()
        val jaccard = if (union > 0.0) intersection / union else 0.0

        val normA = a.lowercase().trim()
        val normB = b.lowercase().trim()
        val substringBonus = when {
            normA.isNotEmpty() && normB.contains(normA) -> 0.35
            normB.isNotEmpty() && normA.contains(normB) -> 0.35
            intersection >= 1.0 && tokensA.any { it.length >= 4 && it in tokensB } -> 0.20
            else -> 0.0
        }
        return max(jaccard, (jaccard * 0.75 + substringBonus)).coerceIn(0.0, 1.0)
    }

    private fun tokenize(text: String): Set<String> {
        val stopWords = setOf("the", "and", "of", "pin", "disney", "disneyland", "wdw", "dlr")
        return text.lowercase()
            .split(Regex("""[^a-z0-9]+"""))
            .filter { it.length >= 2 && it !in stopWords }
            .toSet()
    }
}

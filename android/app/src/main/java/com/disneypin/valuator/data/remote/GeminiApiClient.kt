package com.disneypin.valuator.data.remote

import com.disneypin.valuator.data.db.PinSanitizer
import com.disneypin.valuator.data.model.BoundingBoxNorm
import com.disneypin.valuator.data.model.DetectedPinCandidate
import com.disneypin.valuator.data.model.PinValuationSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class GeminiRateLimitException(val httpCode: Int, message: String) : IOException(message)

data class Pass2CropRequest(
    val tempId: Int,
    val jpegBase64: String,
    val pass1Hint: String,
    val existingCatalogName: String? = null
)

data class Pass2PinValuation(
    val tempId: Int,
    val pinName: String,
    val collection: String,
    val subSet: String,
    val resort: String,
    val yearWave: String,
    val rarity: String,
    val marketLowUsd: Double,
    val marketHighUsd: Double,
    val priceConfidence: String,
    val scrapperRisk: String,
    val visualTags: String,
    val notes: String,
    val citations: List<PinValuationSource>
)

object GeminiResponseParser {

    fun extractResponseText(rawBody: String): String {
        val root = JSONObject(rawBody)
        val candidates = root.optJSONArray("candidates") ?: return ""
        if (candidates.length() == 0) return ""
        val content = candidates.optJSONObject(0)?.optJSONObject("content") ?: return ""
        val parts = content.optJSONArray("parts") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val text = parts.optJSONObject(i)?.optString("text", "") ?: ""
            if (text.isNotEmpty()) sb.append(text)
        }
        return sb.toString()
    }

    fun extractJsonObject(rawText: String): JSONObject {
        val cleaned = stripCodeFences(rawText)
        val firstObj = cleaned.indexOf('{')
        val firstArr = cleaned.indexOf('[')
        if (firstArr in 0 until firstObj || (firstObj < 0 && firstArr >= 0)) {
            val lastArr = cleaned.lastIndexOf(']')
            if (lastArr > firstArr) {
                val arr = JSONArray(cleaned.substring(firstArr, lastArr + 1))
                return JSONObject().put("valuations", arr)
            }
        }
        val lastBrace = cleaned.lastIndexOf('}')
        if (firstObj < 0 || lastBrace <= firstObj) {
            throw IllegalArgumentException("No JSON object found in Gemini response: ${cleaned.take(180)}")
        }
        return JSONObject(cleaned.substring(firstObj, lastBrace + 1))
    }

    private fun stripCodeFences(rawText: String): String {
        var cleaned = rawText.trim()
        cleaned = cleaned.replace(Regex("""^```(?:json)?\s*""", RegexOption.IGNORE_CASE), "")
        cleaned = cleaned.replace(Regex("""\s*```$"""), "")
        return cleaned.trim()
    }

    fun extractGroundingCitations(rawBody: String): List<PinValuationSource> {
        return try {
            val root = JSONObject(rawBody)
            val candidates = root.optJSONArray("candidates") ?: return emptyList()
            if (candidates.length() == 0) return emptyList()
            val meta = candidates.optJSONObject(0)?.optJSONObject("groundingMetadata") ?: return emptyList()
            val chunks = meta.optJSONArray("groundingChunks") ?: return emptyList()
            val seen = mutableSetOf<String>()
            val result = mutableListOf<PinValuationSource>()
            for (i in 0 until chunks.length()) {
                val web = chunks.optJSONObject(i)?.optJSONObject("web") ?: continue
                val url = web.optString("uri", "").trim()
                if (url.isEmpty() || !seen.add(url)) continue
                val title = web.optString("title", url).trim().ifEmpty { url }
                result.add(PinValuationSource(title = title, url = url))
                if (result.size >= 5) break
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun parsePass1Response(rawBody: String): List<DetectedPinCandidate> {
        val text = extractResponseText(rawBody)
        val obj = extractJsonObject(text)
        val arr = obj.optJSONArray("pins") ?: JSONArray()
        val list = mutableListOf<DetectedPinCandidate>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val boxArr = item.optJSONArray("box_2d") ?: continue
            if (boxArr.length() < 4) continue
            val ymin = boxArr.optInt(0, 0).coerceIn(0, 1000)
            val xmin = boxArr.optInt(1, 0).coerceIn(0, 1000)
            val ymax = boxArr.optInt(2, 1000).coerceIn(ymin + 1, 1000)
            val xmax = boxArr.optInt(3, 1000).coerceIn(xmin + 1, 1000)

            val rawLow = item.optDouble("est_low_usd", 10.0).let { if (it.isNaN() || it <= 0.0) 10.0 else it }
            val rawHigh = item.optDouble("est_high_usd", 16.0).let { if (it.isNaN() || it <= 0.0) rawLow.coerceAtLeast(16.0) else it }
            val (safeLow, safeHigh) = PinSanitizer.sanitizePriceRange(rawLow, rawHigh)

            list.add(
                DetectedPinCandidate(
                    tempId = i,
                    box2d = BoundingBoxNorm(ymin, xmin, ymax, xmax),
                    pinName = item.optString("pin_name", "Unknown Pin").ifBlank { "Unknown Pin" },
                    character = item.optString("character", ""),
                    collectionGuess = item.optString("collection_guess", ""),
                    subSetGuess = item.optString("sub_set_guess", ""),
                    resortGuess = item.optString("resort_guess", ""),
                    rarityHint = PinSanitizer.sanitizeRarity(item.optString("rarity_hint", "Common")),
                    estLowUsd = safeLow,
                    estHighUsd = safeHigh,
                    needsWebVerification = item.optBoolean("needs_web_verification", false),
                    visualTags = item.optString("visual_tags", ""),
                    hasHiddenMickey = item.optBoolean("has_hidden_mickey", false),
                    confidence = item.optDouble("confidence", 0.8).coerceIn(0.0, 1.0),
                    scrapperRiskHint = PinSanitizer.sanitizeScrapperRisk(item.optString("scrapper_risk_hint", "Low")),
                    scrapperNotes = item.optString("scrapper_notes", "")
                )
            )
        }
        return list
    }

    fun parsePass2Response(rawBody: String): List<Pass2PinValuation> {
        val citations = extractGroundingCitations(rawBody)
        val text = extractResponseText(rawBody)
        val obj = extractJsonObject(text)
        val arr = obj.optJSONArray("valuations") ?: JSONArray()
        val list = mutableListOf<Pass2PinValuation>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val rawTempId = item.opt("temp_id")
            val tempId = when (rawTempId) {
                is Number -> rawTempId.toInt()
                is String -> Regex("""\d+""").find(rawTempId)?.value?.toIntOrNull() ?: i
                else -> i
            }
            val rawLow = item.optDouble("market_low_usd", 12.0)
            val rawHigh = item.optDouble("market_high_usd", rawLow)
            val (safeLow, safeHigh) = PinSanitizer.sanitizePriceRange(rawLow, rawHigh)

            list.add(
                Pass2PinValuation(
                    tempId = tempId,
                    pinName = item.optString("pin_name", "Disney Pin").ifBlank { "Disney Pin" },
                    collection = item.optString("collection", "Disney Parks").ifBlank { "Disney Parks" },
                    subSet = item.optString("sub_set", ""),
                    resort = item.optString("resort", "DLR/WDW"),
                    yearWave = item.optString("year_wave", ""),
                    rarity = PinSanitizer.sanitizeRarity(item.optString("rarity", "Common")),
                    marketLowUsd = safeLow,
                    marketHighUsd = safeHigh,
                    priceConfidence = PinSanitizer.sanitizePriceConfidence(item.optString("price_confidence", "Peer Est.")),
                    scrapperRisk = PinSanitizer.sanitizeScrapperRisk(item.optString("scrapper_risk", "Low")),
                    visualTags = item.optString("visual_tags", ""),
                    notes = item.optString("notes", ""),
                    citations = citations
                )
            )
        }
        return list
    }
}

class GeminiApiClient {

    private val pass1Client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val pass2Client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val paceMutex = Mutex()
    private var lastRequestEndMs: Long = 0L
    private val minRequestIntervalMs: Long = 4_000L

    suspend fun testConnection(apiKey: String, model: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().put("text", "Reply with OK"))
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("maxOutputTokens", 8)
                })
            }
            executeWithPacingAndRetry(pass1Client, apiKey, model, payload)
            "Connected to $model"
        }
    }

    suspend fun detectPinsInPhoto(
        jpegBase64: String,
        apiKey: String,
        model: String
    ): List<DetectedPinCandidate> = withContext(Dispatchers.IO) {
        val genConfig = JSONObject().apply {
            put("temperature", 0.1)
            put("maxOutputTokens", 4096)
            put("responseMimeType", "application/json")
            buildThinkingConfig(model)?.let { put("thinkingConfig", it) }
        }
        val payload = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().put("inline_data", JSONObject().apply {
                            put("mime_type", "image/jpeg")
                            put("data", jpegBase64)
                        }))
                        put(JSONObject().put("text", PASS_1_PROMPT))
                    })
                })
            })
            put("generationConfig", genConfig)
        }
        val raw = executeWithPacingAndRetry(pass1Client, apiKey, model, payload)
        GeminiResponseParser.parsePass1Response(raw)
    }

    suspend fun researchPinValuations(
        crops: List<Pass2CropRequest>,
        apiKey: String,
        model: String
    ): List<Pass2PinValuation> = withContext(Dispatchers.IO) {
        if (crops.isEmpty()) return@withContext emptyList()
        val parts = JSONArray()
        crops.forEach { crop ->
            val hintLine = buildString {
                append("Pin #${crop.tempId}: hint=\"${crop.pass1Hint}\"")
                if (!crop.existingCatalogName.isNullOrBlank()) {
                    append(", existing_catalog_name=\"${crop.existingCatalogName}\"")
                }
            }
            parts.put(JSONObject().put("text", hintLine))
            parts.put(JSONObject().put("inline_data", JSONObject().apply {
                put("mime_type", "image/jpeg")
                put("data", crop.jpegBase64)
            }))
        }
        parts.put(JSONObject().put("text", PASS_2_PROMPT))

        val genConfig = JSONObject().apply {
            put("temperature", 0.2)
            put("maxOutputTokens", 2048)
            buildThinkingConfig(model)?.let { put("thinkingConfig", it) }
        }

        val payload = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().put("parts", parts))
            })
            put("tools", JSONArray().apply {
                put(JSONObject().put("google_search", JSONObject()))
            })
            put("generationConfig", genConfig)
        }

        val raw = executeWithPacingAndRetry(pass2Client, apiKey, model, payload)
        GeminiResponseParser.parsePass2Response(raw)
    }

    private fun buildThinkingConfig(model: String): JSONObject? {
        return if (model.contains("2.5")) {
            JSONObject().put("thinkingBudget", 0)
        } else null
    }

    private suspend fun executeWithPacingAndRetry(
        client: OkHttpClient,
        apiKey: String,
        model: String,
        payload: JSONObject
    ): String {
        val cleanModel = model.trim()
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$cleanModel:generateContent"
        val mediaType = "application/json; charset=utf-8".toMediaType()

        var attempt = 0
        while (true) {
            val waitBeforeCallMs = paceMutex.withLock {
                val elapsed = System.currentTimeMillis() - lastRequestEndMs
                if (lastRequestEndMs > 0L && elapsed < minRequestIntervalMs) {
                    minRequestIntervalMs - elapsed
                } else 0L
            }
            if (waitBeforeCallMs > 0L) {
                delay(waitBeforeCallMs)
            }

            val request = Request.Builder()
                .url(url)
                .addHeader("x-goog-api-key", apiKey.trim())
                .post(payload.toString().toRequestBody(mediaType))
                .build()

            var retryWaitMs: Long? = null
            try {
                client.newCall(request).execute().use { resp ->
                    val bodyStr = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) return bodyStr

                    val code = resp.code
                    val errMsg = runCatching {
                        JSONObject(bodyStr).optJSONObject("error")?.optString("message")
                    }.getOrNull().orEmpty().ifEmpty { "HTTP $code" }

                    if (code == 404) {
                        throw IOException("Model '$cleanModel' not found — check Settings")
                    }
                    if (code in listOf(400, 401, 403)) {
                        throw IOException("Gemini API error ($code): $errMsg")
                    }
                    if (code == 429 || code == 503) {
                        if (attempt < 1) {
                            val retryAfterSec = resp.header("Retry-After")?.toLongOrNull()
                            retryWaitMs = retryAfterSec?.coerceIn(5L, 25L)?.times(1000L) ?: 10_000L
                        } else {
                            throw GeminiRateLimitException(code, "Gemini rate limit ($code): $errMsg")
                        }
                    } else {
                        throw IOException("Gemini API error ($code): $errMsg")
                    }
                }
            } catch (e: SocketTimeoutException) {
                throw IOException("Request timed out — using local/initial estimates", e)
            } catch (e: InterruptedIOException) {
                throw IOException("Request timed out — using local/initial estimates", e)
            } finally {
                paceMutex.withLock {
                    lastRequestEndMs = System.currentTimeMillis()
                }
            }

            if (retryWaitMs != null) {
                attempt++
                delay(retryWaitMs)
            }
        }
    }

    companion object {
        private val PASS_1_PROMPT = """
            You are a Disneyland / Walt Disney World collectible pin expert. Detect EVERY Disney trading pin visible in this image (up to 25 pins) AND provide an immediate realistic USD secondary-market estimate for each pin. Return ONLY valid JSON matching:
            { "pins": [ { "box_2d": [ymin, xmin, ymax, xmax], "pin_name": "short character or subject name", "character": "main Disney character if any", "collection_guess": "e.g. Hidden Disney, Cast Hidden Disney, Open Edition, Limited Edition", "sub_set_guess": "e.g. Ear Headbands, Ear Hats, Disneyland D, Film Anniversaries, Cupcakes, Sundaes", "resort_guess": "DLR or WDW or Unknown", "rarity_hint": "Common | Chaser / Completer | Super Chaser", "est_low_usd": 10.0, "est_high_usd": 16.0, "needs_web_verification": false, "visual_tags": "comma-separated visual traits", "has_hidden_mickey": true, "confidence": 0.85, "scrapper_risk_hint": "Low | Medium | High", "scrapper_notes": "brief visual note if defects visible, else empty" } ] }
            Rules: box_2d coordinates MUST be integers normalized to 0..1000 ([ymin, xmin, ymax, xmax]). Do not merge adjacent pins into one box. Set needs_web_verification: true ONLY if the pin appears rare, Limited Edition, Chaser/Super Chaser, likely worth >= $25 USD, OR your identification confidence is < 0.70. Set needs_web_verification: false for standard/common open-edition or lanyard pins.
        """.trimIndent()

        private val PASS_2_PROMPT = """
            Use Google Search to quickly verify each numbered Disney pin crop and its current USD secondary market value (eBay sold listings, Mercari, PinPics). Keep search concise and return ONLY a single compact JSON object (no prose before or after):
            { "valuations": [ { "temp_id": 0, "pin_name": "Official or commonly traded pin name", "collection": "Series or collection name", "sub_set": "Sub-set name or empty string", "resort": "DLR | WDW | DLR/WDW | Other", "year_wave": "Year or wave e.g. 2025 Wave A", "rarity": "Common | Chaser / Completer | Super Chaser", "market_low_usd": 14.0, "market_high_usd": 22.0, "price_confidence": "Verified | Peer Est. | Low Conf", "scrapper_risk": "Low | Medium | High", "visual_tags": "concise comma-separated visual descriptors", "notes": "1-sentence valuation & edition summary" } ] }
        """.trimIndent()
    }
}

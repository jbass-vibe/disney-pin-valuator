package com.disneypin.valuator.data.db

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.disneypin.valuator.data.model.BoardScanSummary
import com.disneypin.valuator.data.model.BoundingBoxNorm
import com.disneypin.valuator.data.model.CatalogStats
import com.disneypin.valuator.data.model.DetectedPinCandidate
import com.disneypin.valuator.data.model.LearnedPinInput
import com.disneypin.valuator.data.model.MatchSource
import com.disneypin.valuator.data.model.PendingScanItem
import com.disneypin.valuator.data.model.Pin
import com.disneypin.valuator.data.model.PinEmbeddingRecord
import com.disneypin.valuator.data.model.PinValuationSource
import com.disneypin.valuator.data.model.PortfolioItem
import com.disneypin.valuator.data.model.PriceHistoryEntry
import com.disneypin.valuator.data.model.ScannedPinResult
import com.disneypin.valuator.data.model.TradeListEntry
import com.disneypin.valuator.data.model.TradeListType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.max

object PinSanitizer {
    fun sanitizeRarity(raw: String): String {
        val norm = raw.trim().lowercase()
        return when {
            norm == "super chaser" -> "Super Chaser"
            norm == "chaser / completer" || norm == "chaser" || norm == "completer" -> "Chaser / Completer"
            norm.contains("ap") || norm.contains("pp") || norm.contains("artist proof") || norm.contains("pre-production") -> "Super Chaser"
            norm.contains("le") || norm.contains("limited edition") -> {
                val num = Regex("""\d+""").find(norm)?.value?.toIntOrNull()
                if (num != null && num <= 500) "Super Chaser" else "Chaser / Completer"
            }
            norm.contains("cast") || norm.contains("limited release") || norm.contains("lr") -> "Chaser / Completer"
            else -> "Common"
        }
    }

    fun sanitizePriceConfidence(raw: String): String {
        return when (raw.trim().lowercase()) {
            "verified" -> "Verified"
            "peer est.", "peer est", "estimated", "medium" -> "Peer Est."
            else -> "Low Conf"
        }
    }

    fun sanitizeScrapperRisk(raw: String): String {
        val norm = raw.trim().lowercase()
        return when {
            norm.startsWith("high") -> "High"
            norm.startsWith("med") || norm.startsWith("mod") -> "Medium"
            else -> "Low"
        }
    }

    fun sanitizePriceRange(low: Double, high: Double): Pair<Double, Double> {
        val a = low.coerceAtLeast(0.0)
        val b = high.coerceAtLeast(0.0)
        return minOf(a, b) to maxOf(a, b)
    }

    fun formatGeminiPinId(nextNumber: Int): String = "GEM-%04d".format(nextNumber.coerceAtLeast(1))

    fun parseCitationsJson(json: String): List<PinValuationSource> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { idx ->
                val obj = arr.optJSONObject(idx) ?: return@mapNotNull null
                val url = obj.optString("url", "").trim()
                if (url.isEmpty()) return@mapNotNull null
                val title = obj.optString("title", url).ifBlank { url }
                PinValuationSource(title = title, url = url)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun encodeCitationsJson(citations: List<PinValuationSource>): String {
        val arr = JSONArray()
        citations.take(5).forEach { c ->
            arr.put(JSONObject().apply {
                put("title", c.title)
                put("url", c.url)
            })
        }
        return arr.toString()
    }
}

class PinRepository(private val dbHelper: PinDatabaseHelper) {

    suspend fun getCatalogStats(): CatalogStats = withContext(Dispatchers.IO) {
        val db = dbHelper.getReadableDatabase()
        var total = 0
        var grails = 0
        var high = 0
        var avg = 0.0
        var seeded = 0
        var learned = 0
        db.rawQuery(
            """
            SELECT COUNT(*),
                   SUM(CASE WHEN price_tier LIKE 'Grail%' THEN 1 ELSE 0 END),
                   SUM(CASE WHEN price_tier LIKE 'High%' THEN 1 ELSE 0 END),
                   AVG(est_market_value_usd),
                   SUM(CASE WHEN data_source = 'Seeded' THEN 1 ELSE 0 END),
                   SUM(CASE WHEN data_source != 'Seeded' THEN 1 ELSE 0 END)
            FROM pins
            """.trimIndent(),
            null
        ).use { c ->
            if (c.moveToFirst()) {
                total = c.getInt(0)
                grails = c.getInt(1)
                high = c.getInt(2)
                avg = c.getDouble(3)
                seeded = c.getInt(4)
                learned = c.getInt(5)
            }
        }
        val resorts = mutableListOf<String>()
        db.rawQuery(
            "SELECT DISTINCT resort FROM pins WHERE resort IS NOT NULL AND TRIM(resort) != '' ORDER BY resort",
            null
        ).use { c ->
            while (c.moveToNext()) {
                resorts.add(c.getString(0))
            }
        }
        val embeddingsCount = db.rawQuery("SELECT COUNT(*) FROM pin_embeddings", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
        CatalogStats(
            totalPins = total,
            grailCount = grails,
            highValueCount = high,
            avgMarketValueUsd = avg,
            resorts = resorts,
            seededCount = seeded,
            learnedCount = learned,
            embeddingsCount = embeddingsCount
        )
    }

    suspend fun searchPins(
        query: String = "",
        priceTierFilter: String? = null,
        rarityFilter: String? = null,
        resortFilter: String? = null,
        sourceFilter: String? = null,
        limit: Int = 150
    ): List<Pin> = withContext(Dispatchers.IO) {
        val db = dbHelper.getReadableDatabase()
        val trimmed = query.trim()

        val whereClauses = mutableListOf<String>()
        val args = mutableListOf<String>()

        if (!priceTierFilter.isNullOrBlank() && priceTierFilter != "All") {
            whereClauses.add("p.price_tier = ?")
            args.add(priceTierFilter)
        }
        if (!rarityFilter.isNullOrBlank() && rarityFilter != "All") {
            whereClauses.add("p.rarity = ?")
            args.add(rarityFilter)
        }
        if (!resortFilter.isNullOrBlank() && resortFilter != "All") {
            whereClauses.add("p.resort = ?")
            args.add(resortFilter)
        }
        when (sourceFilter) {
            "Seeded" -> whereClauses.add("p.data_source = 'Seeded'")
            "Gemini Learned" -> whereClauses.add("p.data_source != 'Seeded'")
        }

        if (trimmed.isNotEmpty()) {
            val ftsResult = tryFtsSearch(db, trimmed, whereClauses, args, limit)
            if (ftsResult != null) return@withContext ftsResult

            val likePattern = "%$trimmed%"
            whereClauses.add(
                "(p.pin_name LIKE ? OR p.collection LIKE ? OR p.visual_tags LIKE ? OR p.pinpics_id LIKE ? OR p.pin_id LIKE ?)"
            )
            repeat(5) { args.add(likePattern) }
        }

        val whereSql = if (whereClauses.isEmpty()) "" else "WHERE ${whereClauses.joinToString(" AND ")}"
        val sql = """
            SELECT $PIN_SELECT_COLUMNS
            FROM pins p
            $whereSql
            ORDER BY p.est_market_value_usd DESC, p.pin_name ASC
            LIMIT $limit
        """.trimIndent()

        val pins = mutableListOf<Pin>()
        db.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                pins.add(cursorToPin(c))
            }
        }
        pins
    }

    private fun tryFtsSearch(
        db: SQLiteDatabase,
        query: String,
        extraWhere: List<String>,
        extraArgs: List<String>,
        limit: Int
    ): List<Pin>? {
        return try {
            val tokens = query.split(Regex("\\s+"))
                .map { it.replace("\"", "").replace("*", "").trim() }
                .filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return null
            val ftsQuery = tokens.joinToString(" AND ") { "\"$it\"*" }

            val allWhere = mutableListOf("pins_fts MATCH ?")
            allWhere.addAll(extraWhere)
            val allArgs = mutableListOf(ftsQuery)
            allArgs.addAll(extraArgs)

            val sql = """
                SELECT $PIN_SELECT_COLUMNS
                FROM pins_fts f
                JOIN pins p ON f.rowid = p.rowid
                WHERE ${allWhere.joinToString(" AND ")}
                ORDER BY p.est_market_value_usd DESC, p.pin_name ASC
                LIMIT $limit
            """.trimIndent()

            val pins = mutableListOf<Pin>()
            db.rawQuery(sql, allArgs.toTypedArray()).use { c ->
                while (c.moveToNext()) {
                    pins.add(cursorToPin(c))
                }
            }
            pins
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getPinById(pinId: String): Pin? = withContext(Dispatchers.IO) {
        val db = dbHelper.getReadableDatabase()
        db.rawQuery(
            "SELECT $PIN_SELECT_COLUMNS FROM pins p WHERE p.pin_id = ?",
            arrayOf(pinId)
        ).use { c ->
            if (c.moveToFirst()) cursorToPin(c) else null
        }
    }

    suspend fun getPriceHistory(pinId: String): List<PriceHistoryEntry> = withContext(Dispatchers.IO) {
        val db = dbHelper.getReadableDatabase()
        val list = mutableListOf<PriceHistoryEntry>()
        db.rawQuery(
            """
            SELECT history_id, pin_id, market_low_usd, market_high_usd, est_market_value_usd, observed_date, source
            FROM price_history
            WHERE pin_id = ?
            ORDER BY observed_date DESC, history_id DESC
            LIMIT 20
            """.trimIndent(),
            arrayOf(pinId)
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    PriceHistoryEntry(
                        historyId = c.getLong(0),
                        pinId = c.getString(1) ?: "",
                        lowUsd = c.getDouble(2),
                        highUsd = c.getDouble(3),
                        estMarketValueUsd = c.getDouble(4),
                        recordedAt = c.getString(5) ?: "",
                        source = c.getString(6) ?: "Market Benchmark"
                    )
                )
            }
        }
        list
    }

    suspend fun upsertLearnedPin(candidate: LearnedPinInput): Pin = withContext(Dispatchers.IO) {
        val db = dbHelper.getWritableDatabase()
        val (safeLow, safeHigh) = PinSanitizer.sanitizePriceRange(candidate.marketLowUsd, candidate.marketHighUsd)
        val safeRarity = PinSanitizer.sanitizeRarity(candidate.rarity)
        val safeConf = PinSanitizer.sanitizePriceConfidence(candidate.priceConfidence)
        val safeRisk = PinSanitizer.sanitizeScrapperRisk(candidate.scrapperRisk)
        val today = LocalDate.now().toString()

        val existingId = db.rawQuery(
            """
            SELECT pin_id FROM pins
            WHERE LOWER(TRIM(pin_name)) = LOWER(TRIM(?))
              AND LOWER(TRIM(collection)) = LOWER(TRIM(?))
              AND LOWER(TRIM(sub_set)) = LOWER(TRIM(?))
            LIMIT 1
            """.trimIndent(),
            arrayOf(candidate.pinName, candidate.collection, candidate.subSet)
        ).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

        if (existingId != null) {
            updatePinValuationInternal(
                db = db,
                pinId = existingId,
                lowUsd = safeLow,
                highUsd = safeHigh,
                confidence = safeConf,
                scrapperRisk = safeRisk,
                sourcesJson = candidate.valuationSourcesJson,
                referenceImagePath = candidate.referenceImagePath
            )
            return@withContext getPinByIdInternal(db, existingId)!!
        }

        val maxNum = db.rawQuery(
            "SELECT MAX(CAST(SUBSTR(pin_id, 5) AS INTEGER)) FROM pins WHERE pin_id LIKE 'GEM-%'",
            null
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else 0
        }
        val newPinId = PinSanitizer.formatGeminiPinId(maxNum + 1)

        val values = ContentValues().apply {
            put("pin_id", newPinId)
            put("pin_name", candidate.pinName.trim())
            put("collection", candidate.collection.trim().ifEmpty { "Disney Parks" })
            put("sub_set", candidate.subSet.trim())
            put("resort", candidate.resort.trim())
            put("year_wave", candidate.yearWave.trim())
            put("rarity", safeRarity)
            put("market_low_usd", safeLow)
            put("market_high_usd", safeHigh)
            put("price_updated", today)
            put("price_confidence", safeConf)
            put("scrapper_risk", safeRisk)
            put("visual_tags", candidate.visualTags.trim())
            put("notes", candidate.notes.trim())
            put("data_source", candidate.dataSource)
            put("reference_image_path", candidate.referenceImagePath)
            put("valuation_sources", candidate.valuationSourcesJson)
        }
        db.insertOrThrow("pins", null, values)

        val midUsd = ((safeLow + safeHigh) / 2.0 * 100.0).toInt() / 100.0
        val histValues = ContentValues().apply {
            put("pin_id", newPinId)
            put("observed_date", today)
            put("market_low_usd", safeLow)
            put("market_high_usd", safeHigh)
            put("est_market_value_usd", midUsd)
            put("source", candidate.dataSource)
        }
        db.insert("price_history", null, histValues)

        getPinByIdInternal(db, newPinId)!!
    }

    suspend fun updatePinValuation(
        pinId: String,
        lowUsd: Double,
        highUsd: Double,
        confidence: String,
        scrapperRisk: String,
        sourcesJson: String,
        referenceImagePath: String? = null
    ): Pin? = withContext(Dispatchers.IO) {
        val db = dbHelper.getWritableDatabase()
        updatePinValuationInternal(db, pinId, lowUsd, highUsd, confidence, scrapperRisk, sourcesJson, referenceImagePath)
        getPinByIdInternal(db, pinId)
    }

    private fun updatePinValuationInternal(
        db: SQLiteDatabase,
        pinId: String,
        lowUsd: Double,
        highUsd: Double,
        confidence: String,
        scrapperRisk: String,
        sourcesJson: String,
        referenceImagePath: String?
    ) {
        val (safeLow, safeHigh) = PinSanitizer.sanitizePriceRange(lowUsd, highUsd)
        val today = LocalDate.now().toString()
        val values = ContentValues().apply {
            put("market_low_usd", safeLow)
            put("market_high_usd", safeHigh)
            put("price_updated", today)
            put("price_confidence", PinSanitizer.sanitizePriceConfidence(confidence))
            put("scrapper_risk", PinSanitizer.sanitizeScrapperRisk(scrapperRisk))
            if (sourcesJson.isNotBlank()) put("valuation_sources", sourcesJson)
            if (!referenceImagePath.isNullOrBlank()) put("reference_image_path", referenceImagePath)
        }
        db.update("pins", values, "pin_id = ?", arrayOf(pinId))
    }

    suspend fun updatePinManually(
        pinId: String,
        pinName: String,
        collection: String,
        lowUsd: Double,
        highUsd: Double,
        rarity: String,
        scrapperRisk: String
    ): Pin? = withContext(Dispatchers.IO) {
        val db = dbHelper.getWritableDatabase()
        val (safeLow, safeHigh) = PinSanitizer.sanitizePriceRange(lowUsd, highUsd)
        val today = LocalDate.now().toString()
        val values = ContentValues().apply {
            put("pin_name", pinName.trim())
            put("collection", collection.trim())
            put("market_low_usd", safeLow)
            put("market_high_usd", safeHigh)
            put("rarity", PinSanitizer.sanitizeRarity(rarity))
            put("scrapper_risk", PinSanitizer.sanitizeScrapperRisk(scrapperRisk))
            put("price_updated", today)
            put("price_confidence", "Peer Est.")
        }
        db.update("pins", values, "pin_id = ?", arrayOf(pinId))
        getPinByIdInternal(db, pinId)
    }

    suspend fun upsertPinEmbedding(
        pinId: String,
        imagePath: String,
        modelName: String = "hsv-dhash-v1",
        embeddingBytes: ByteArray
    ) = withContext(Dispatchers.IO) {
        val db = dbHelper.getWritableDatabase()
        db.delete("pin_embeddings", "pin_id = ? AND model_name = ?", arrayOf(pinId, modelName))
        val values = ContentValues().apply {
            put("pin_id", pinId)
            put("image_path", imagePath)
            put("model_name", modelName)
            put("embedding_blob", embeddingBytes)
        }
        db.insert("pin_embeddings", null, values)
        if (imagePath.isNotBlank()) {
            val pinUpdate = ContentValues().apply { put("reference_image_path", imagePath) }
            db.update("pins", pinUpdate, "pin_id = ? AND reference_image_path = ''", arrayOf(pinId))
        }
    }

    suspend fun getAllPinEmbeddings(modelName: String = "hsv-dhash-v1"): List<PinEmbeddingRecord> =
        withContext(Dispatchers.IO) {
            val db = dbHelper.getReadableDatabase()
            val list = mutableListOf<PinEmbeddingRecord>()
            db.rawQuery(
                "SELECT embedding_id, pin_id, image_path, model_name, embedding_blob FROM pin_embeddings WHERE model_name = ?",
                arrayOf(modelName)
            ).use { c ->
                while (c.moveToNext()) {
                    list.add(
                        PinEmbeddingRecord(
                            embeddingId = c.getLong(0),
                            pinId = c.getString(1) ?: "",
                            imagePath = c.getString(2) ?: "",
                            modelName = c.getString(3) ?: modelName,
                            embeddingBlob = c.getBlob(4)
                        )
                    )
                }
            }
            list
        }

    suspend fun saveBoardScan(
        imagePath: String,
        imageFilename: String,
        detections: List<ScannedPinResult>,
        location: String = "Disney Parks"
    ): Long = withContext(Dispatchers.IO) {
        val db = dbHelper.getWritableDatabase()
        val totalLow = detections.sumOf { it.marketLowUsd }
        val totalHigh = detections.sumOf { it.marketHighUsd }
        val totalMid = detections.sumOf { it.estMarketValueUsd }

        db.beginTransaction()
        try {
            val scanValues = ContentValues().apply {
                put("image_filename", imageFilename)
                put("image_path", imagePath)
                put("location", location)
                put("pin_count", detections.size)
                put("total_low_usd", totalLow)
                put("total_high_usd", totalHigh)
                put("total_mid_usd", totalMid)
                put("status", "COMPLETED")
            }
            val scanId = db.insert("board_scans", null, scanValues)

            detections.forEachIndexed { idx, det ->
                val boxJson = JSONObject().apply {
                    put("ymin", det.box2d.ymin)
                    put("xmin", det.box2d.xmin)
                    put("ymax", det.box2d.ymax)
                    put("xmax", det.box2d.xmax)
                }.toString()
                val detValues = ContentValues().apply {
                    put("scan_id", scanId)
                    put("pin_id", det.matchedPin?.pinId)
                    put("grid_pos", "Pin #${idx + 1}")
                    put("confidence", det.matchConfidence)
                    put("is_low_conf", if (det.matchConfidence < 0.72) 1 else 0)
                    put("box_json", boxJson)
                    put("match_source", det.matchSource.name)
                    put("detected_label", det.displayTitle)
                    put("market_low_usd", det.marketLowUsd)
                    put("market_high_usd", det.marketHighUsd)
                }
                db.insert("scan_detections", null, detValues)
            }
            db.setTransactionSuccessful()
            scanId
        } finally {
            db.endTransaction()
        }
    }

    suspend fun getRecentBoardScans(limit: Int = 15): List<BoardScanSummary> = withContext(Dispatchers.IO) {
        val db = dbHelper.getReadableDatabase()
        val list = mutableListOf<BoardScanSummary>()
        db.rawQuery(
            """
            SELECT scan_id, image_filename, image_path, location, scanned_at,
                   pin_count, total_low_usd, total_high_usd, total_mid_usd, status
            FROM board_scans
            WHERE image_path != ''
            ORDER BY scan_id DESC
            LIMIT $limit
            """.trimIndent(),
            null
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    BoardScanSummary(
                        scanId = c.getLong(0),
                        imageFilename = c.getString(1) ?: "",
                        imagePath = c.getString(2) ?: "",
                        location = c.getString(3) ?: "",
                        scannedAt = c.getString(4) ?: "",
                        pinCount = c.getInt(5),
                        totalLowUsd = c.getDouble(6),
                        totalHighUsd = c.getDouble(7),
                        totalMidUsd = c.getDouble(8),
                        status = c.getString(9) ?: "COMPLETED"
                    )
                )
            }
        }
        list
    }

    suspend fun getScanDetections(scanId: Long): List<ScannedPinResult> = withContext(Dispatchers.IO) {
        val db = dbHelper.getReadableDatabase()
        val list = mutableListOf<ScannedPinResult>()
        db.rawQuery(
            """
            SELECT sd.detection_id, sd.pin_id, sd.confidence, sd.box_json, sd.match_source,
                   sd.detected_label, sd.market_low_usd, sd.market_high_usd
            FROM scan_detections sd
            WHERE sd.scan_id = ?
            ORDER BY sd.detection_id ASC
            """.trimIndent(),
            arrayOf(scanId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val detId = c.getLong(0)
                val pinId = c.getString(1)
                val conf = c.getDouble(2)
                val boxJson = c.getString(3) ?: ""
                val sourceStr = c.getString(4) ?: MatchSource.LOCAL_DB.name
                val label = c.getString(5) ?: "Pin"
                val low = c.getDouble(6)
                val high = c.getDouble(7)
                val matchedPin = if (!pinId.isNullOrBlank()) getPinByIdInternal(db, pinId) else null
                val box = parseBoxJson(boxJson)
                val mid = matchedPin?.estMarketValueUsd ?: ((low + high) / 2.0)
                val tier = matchedPin?.priceTier ?: computePriceTier(mid)
                val citations = matchedPin?.let { PinSanitizer.parseCitationsJson(it.valuationSources) } ?: emptyList()

                list.add(
                    ScannedPinResult(
                        detectionId = "saved-$scanId-$detId",
                        photoIndex = 0,
                        box2d = box,
                        cropImagePath = matchedPin?.referenceImagePath.orEmpty(),
                        candidate = DetectedPinCandidate(
                            tempId = detId.toInt(),
                            box2d = box,
                            pinName = label
                        ),
                        matchedPin = matchedPin,
                        matchSource = runCatching { MatchSource.valueOf(sourceStr) }.getOrDefault(MatchSource.LOCAL_DB),
                        matchConfidence = conf,
                        displayTitle = matchedPin?.pinName ?: label,
                        displayCollection = matchedPin?.collection ?: "",
                        marketLowUsd = matchedPin?.marketLowUsd ?: low,
                        marketHighUsd = matchedPin?.marketHighUsd ?: high,
                        estMarketValueUsd = mid,
                        priceTier = tier,
                        scrapperRisk = matchedPin?.scrapperRisk ?: "Low",
                        priceConfidence = matchedPin?.priceConfidence ?: "Peer Est.",
                        citations = citations
                    )
                )
            }
        }
        list
    }

    suspend fun queuePendingScan(imagePath: String): Long = withContext(Dispatchers.IO) {
        val db = dbHelper.getWritableDatabase()
        val values = ContentValues().apply {
            put("image_path", imagePath)
            put("status", "PENDING")
        }
        db.insert("pending_scans", null, values)
    }

    suspend fun getPendingScans(): List<PendingScanItem> = withContext(Dispatchers.IO) {
        val db = dbHelper.getReadableDatabase()
        val list = mutableListOf<PendingScanItem>()
        db.rawQuery(
            "SELECT id, image_path, queued_at, status FROM pending_scans ORDER BY id ASC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    PendingScanItem(
                        id = c.getLong(0),
                        imagePath = c.getString(1) ?: "",
                        queuedAt = c.getString(2) ?: "",
                        status = c.getString(3) ?: "PENDING"
                    )
                )
            }
        }
        list
    }

    suspend fun deletePendingScan(id: Long) = withContext(Dispatchers.IO) {
        val db = dbHelper.getWritableDatabase()
        db.delete("pending_scans", "id = ?", arrayOf(id.toString()))
    }

    suspend fun getPortfolioItems(): List<PortfolioItem> = withContext(Dispatchers.IO) {
        val db = dbHelper.getReadableDatabase()
        val items = mutableListOf<PortfolioItem>()
        db.rawQuery(
            """
            SELECT pi.id, pi.purchase_price_usd, pi.condition_grade, pi.has_original_card, pi.notes, pi.added_at,
                   $PIN_SELECT_COLUMNS
            FROM portfolio_items pi
            JOIN pins p ON pi.pin_id = p.pin_id
            ORDER BY pi.id DESC
            """.trimIndent(),
            null
        ).use { c ->
            while (c.moveToNext()) {
                val pin = cursorToPin(c, offset = 6)
                items.add(
                    PortfolioItem(
                        id = c.getLong(0),
                        pin = pin,
                        purchasePriceUsd = c.getDouble(1),
                        conditionGrade = c.getInt(2),
                        hasOriginalCard = c.getInt(3) == 1,
                        notes = c.getString(4) ?: "",
                        addedAt = c.getString(5) ?: ""
                    )
                )
            }
        }
        items
    }

    suspend fun addToPortfolio(
        pinId: String,
        purchasePriceUsd: Double,
        conditionGrade: Int,
        hasOriginalCard: Boolean,
        notes: String = ""
    ) = withContext(Dispatchers.IO) {
        val db = dbHelper.getWritableDatabase()
        val values = ContentValues().apply {
            put("pin_id", pinId)
            put("purchase_price_usd", purchasePriceUsd)
            put("condition_grade", conditionGrade.coerceIn(1, 10))
            put("has_original_card", if (hasOriginalCard) 1 else 0)
            put("notes", notes)
        }
        db.insert("portfolio_items", null, values)
    }

    suspend fun removeFromPortfolio(itemId: Long) = withContext(Dispatchers.IO) {
        val db = dbHelper.getWritableDatabase()
        db.delete("portfolio_items", "id = ?", arrayOf(itemId.toString()))
    }

    suspend fun getTradeList(listType: TradeListType): List<TradeListEntry> = withContext(Dispatchers.IO) {
        val db = dbHelper.getReadableDatabase()
        val items = mutableListOf<TradeListEntry>()
        db.rawQuery(
            """
            SELECT tw.id, tw.list_type, tw.condition_grade, tw.added_at,
                   $PIN_SELECT_COLUMNS
            FROM trade_wishlist tw
            JOIN pins p ON tw.pin_id = p.pin_id
            WHERE tw.list_type = ?
            ORDER BY tw.id DESC
            """.trimIndent(),
            arrayOf(listType.name)
        ).use { c ->
            while (c.moveToNext()) {
                val pin = cursorToPin(c, offset = 4)
                items.add(
                    TradeListEntry(
                        id = c.getLong(0),
                        pin = pin,
                        listType = TradeListType.valueOf(c.getString(1)),
                        conditionGrade = c.getInt(2),
                        addedAt = c.getString(3) ?: ""
                    )
                )
            }
        }
        items
    }

    suspend fun addToTradeList(
        pinId: String,
        listType: TradeListType,
        conditionGrade: Int = 8
    ) = withContext(Dispatchers.IO) {
        val db = dbHelper.getWritableDatabase()
        val values = ContentValues().apply {
            put("pin_id", pinId)
            put("list_type", listType.name)
            put("condition_grade", conditionGrade.coerceIn(1, 10))
        }
        db.insertWithOnConflict("trade_wishlist", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    suspend fun removeFromTradeList(entryId: Long) = withContext(Dispatchers.IO) {
        val db = dbHelper.getWritableDatabase()
        db.delete("trade_wishlist", "id = ?", arrayOf(entryId.toString()))
    }

    private fun getPinByIdInternal(db: SQLiteDatabase, pinId: String): Pin? {
        return db.rawQuery(
            "SELECT $PIN_SELECT_COLUMNS FROM pins p WHERE p.pin_id = ?",
            arrayOf(pinId)
        ).use { c ->
            if (c.moveToFirst()) cursorToPin(c) else null
        }
    }

    private fun parseBoxJson(json: String): BoundingBoxNorm {
        return try {
            val obj = JSONObject(json)
            BoundingBoxNorm(
                ymin = obj.optInt("ymin", 0),
                xmin = obj.optInt("xmin", 0),
                ymax = obj.optInt("ymax", 1000),
                xmax = obj.optInt("xmax", 1000)
            )
        } catch (_: Exception) {
            BoundingBoxNorm(0, 0, 1000, 1000)
        }
    }

    private fun computePriceTier(mid: Double): String = when {
        mid >= 50.0 -> "Grail ($50+)"
        mid >= 30.0 -> "High ($30-$49)"
        mid >= 20.0 -> "Good ($20-$29)"
        else -> "Standard (<$20)"
    }

    private fun cursorToPin(c: Cursor, offset: Int = 0): Pin = Pin(
        pinId = c.getString(offset) ?: "",
        pinName = c.getString(offset + 1) ?: "",
        collection = c.getString(offset + 2) ?: "",
        subSet = c.getString(offset + 3) ?: "",
        resort = c.getString(offset + 4) ?: "",
        yearWave = c.getString(offset + 5) ?: "",
        rarity = c.getString(offset + 6) ?: "Common",
        marketLowUsd = c.getDouble(offset + 7),
        marketHighUsd = c.getDouble(offset + 8),
        estMarketValueUsd = c.getDouble(offset + 9),
        priceTier = c.getString(offset + 10) ?: "",
        priceUpdated = c.getString(offset + 11) ?: "",
        priceConfidence = c.getString(offset + 12) ?: "Verified",
        scrapperRisk = c.getString(offset + 13) ?: "Low",
        visualTags = c.getString(offset + 14) ?: "",
        pinpicsId = c.getString(offset + 15) ?: "",
        sourceFile = c.getString(offset + 16) ?: "",
        notes = c.getString(offset + 17) ?: "",
        dataSource = c.getString(offset + 18) ?: "Seeded",
        referenceImagePath = c.getString(offset + 19) ?: "",
        valuationSources = c.getString(offset + 20) ?: ""
    )

    companion object {
        private const val PIN_SELECT_COLUMNS = """
            p.pin_id, p.pin_name, p.collection, p.sub_set, p.resort, p.year_wave,
            p.rarity, p.market_low_usd, p.market_high_usd, p.est_market_value_usd,
            p.price_tier, p.price_updated, p.price_confidence, p.scrapper_risk,
            p.visual_tags, p.pinpics_id, p.source_file, p.notes,
            p.data_source, p.reference_image_path, p.valuation_sources
        """
    }
}

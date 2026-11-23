package com.disneypin.valuator.data.db

import android.content.ContentValues
import android.database.Cursor
import com.disneypin.valuator.data.model.CatalogStats
import com.disneypin.valuator.data.model.Pin
import com.disneypin.valuator.data.model.PortfolioItem
import com.disneypin.valuator.data.model.PriceHistoryEntry
import com.disneypin.valuator.data.model.TradeListEntry
import com.disneypin.valuator.data.model.TradeListType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PinRepository(private val dbHelper: PinDatabaseHelper) {

    suspend fun getCatalogStats(): CatalogStats = withContext(Dispatchers.IO) {
        val db = dbHelper.getReadableDatabase()
        var total = 0
        var grails = 0
        var high = 0
        var avg = 0.0
        db.rawQuery(
            """
            SELECT COUNT(*),
                   SUM(CASE WHEN price_tier LIKE 'Grail%' THEN 1 ELSE 0 END),
                   SUM(CASE WHEN price_tier LIKE 'High%' THEN 1 ELSE 0 END),
                   AVG(est_market_value_usd)
            FROM pins
            """.trimIndent(),
            null
        ).use { c ->
            if (c.moveToFirst()) {
                total = c.getInt(0)
                grails = c.getInt(1)
                high = c.getInt(2)
                avg = c.getDouble(3)
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
        CatalogStats(
            totalPins = total,
            grailCount = grails,
            highValueCount = high,
            avgMarketValueUsd = avg,
            resorts = resorts
        )
    }

    suspend fun searchPins(
        query: String = "",
        priceTierFilter: String? = null,
        rarityFilter: String? = null,
        resortFilter: String? = null,
        limit: Int = 100
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
            SELECT p.pin_id, p.pin_name, p.collection, p.sub_set, p.resort, p.year_wave,
                   p.rarity, p.market_low_usd, p.market_high_usd, p.est_market_value_usd,
                   p.price_tier, p.price_updated, p.price_confidence, p.scrapper_risk,
                   p.visual_tags, p.pinpics_id, p.source_file, p.notes
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
        db: android.database.sqlite.SQLiteDatabase,
        query: String,
        extraWhere: List<String>,
        extraArgs: List<String>,
        limit: Int
    ): List<Pin>? {
        return try {
            val tokens = query.split(Regex("\\s+"))
                .map { it.replace("\"", "").trim() }
                .filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return null
            val ftsQuery = tokens.joinToString(" AND ") { "\"$it\"*" }

            val allWhere = mutableListOf("pins_fts MATCH ?")
            allWhere.addAll(extraWhere)
            val allArgs = mutableListOf(ftsQuery)
            allArgs.addAll(extraArgs)

            val sql = """
                SELECT p.pin_id, p.pin_name, p.collection, p.sub_set, p.resort, p.year_wave,
                       p.rarity, p.market_low_usd, p.market_high_usd, p.est_market_value_usd,
                       p.price_tier, p.price_updated, p.price_confidence, p.scrapper_risk,
                       p.visual_tags, p.pinpics_id, p.source_file, p.notes
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
            """
            SELECT pin_id, pin_name, collection, sub_set, resort, year_wave,
                   rarity, market_low_usd, market_high_usd, est_market_value_usd,
                   price_tier, price_updated, price_confidence, scrapper_risk,
                   visual_tags, pinpics_id, source_file, notes
            FROM pins WHERE pin_id = ?
            """.trimIndent(),
            arrayOf(pinId)
        ).use { c ->
            if (c.moveToFirst()) cursorToPin(c) else null
        }
    }

    suspend fun getPriceHistory(pinId: String): List<PriceHistoryEntry> = withContext(Dispatchers.IO) {
        val db = dbHelper.getReadableDatabase()
        val list = mutableListOf<PriceHistoryEntry>()
        try {
            db.rawQuery(
                "SELECT id, pin_id, old_low_usd, old_high_usd, changed_at FROM price_history WHERE pin_id = ? ORDER BY changed_at DESC LIMIT 20",
                arrayOf(pinId)
            ).use { c ->
                while (c.moveToNext()) {
                    list.add(
                        PriceHistoryEntry(
                            id = c.getLong(0),
                            pinId = c.getString(1),
                            lowUsd = c.getDouble(2),
                            highUsd = c.getDouble(3),
                            recordedAt = c.getString(4)
                        )
                    )
                }
            }
        } catch (_: Exception) {
            // Gracefully handle schema variations in price_history
        }
        list
    }

    suspend fun getPortfolioItems(): List<PortfolioItem> = withContext(Dispatchers.IO) {
        val db = dbHelper.getReadableDatabase()
        val items = mutableListOf<PortfolioItem>()
        db.rawQuery(
            """
            SELECT pi.id, pi.purchase_price_usd, pi.condition_grade, pi.has_original_card, pi.notes, pi.added_at,
                   p.pin_id, p.pin_name, p.collection, p.sub_set, p.resort, p.year_wave,
                   p.rarity, p.market_low_usd, p.market_high_usd, p.est_market_value_usd,
                   p.price_tier, p.price_updated, p.price_confidence, p.scrapper_risk,
                   p.visual_tags, p.pinpics_id, p.source_file, p.notes
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
                   p.pin_id, p.pin_name, p.collection, p.sub_set, p.resort, p.year_wave,
                   p.rarity, p.market_low_usd, p.market_high_usd, p.est_market_value_usd,
                   p.price_tier, p.price_updated, p.price_confidence, p.scrapper_risk,
                   p.visual_tags, p.pinpics_id, p.source_file, p.notes
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
        db.insertWithOnConflict("trade_wishlist", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
    }

    suspend fun removeFromTradeList(entryId: Long) = withContext(Dispatchers.IO) {
        val db = dbHelper.getWritableDatabase()
        db.delete("trade_wishlist", "id = ?", arrayOf(entryId.toString()))
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
        notes = c.getString(offset + 17) ?: ""
    )
}

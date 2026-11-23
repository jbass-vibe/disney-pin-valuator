package com.disneypin.valuator.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream

class PinDatabaseHelper(private val context: Context) {

    companion object {
        private const val DB_NAME = "disney_pin_database.sqlite"
        private const val ASSET_NAME = "disney_pin_database.sqlite"
        private const val CURRENT_SCHEMA_VERSION = 2
    }

    private var database: SQLiteDatabase? = null

    @Synchronized
    fun getReadableDatabase(): SQLiteDatabase = openOrCopyDatabase()

    @Synchronized
    fun getWritableDatabase(): SQLiteDatabase = openOrCopyDatabase()

    private fun openOrCopyDatabase(): SQLiteDatabase {
        database?.let {
            if (it.isOpen) return it
        }
        val dbFile: File = context.getDatabasePath(DB_NAME)
        if (!dbFile.exists()) {
            dbFile.parentFile?.mkdirs()
            context.assets.open(ASSET_NAME).use { input ->
                FileOutputStream(dbFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
        val db = SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE
        )
        db.execSQL("PRAGMA foreign_keys = ON;")
        ensureAppTables(db)
        migrateIfNeeded(db)
        database = db
        return db
    }

    private fun ensureAppTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS portfolio_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                pin_id TEXT NOT NULL,
                purchase_price_usd REAL NOT NULL DEFAULT 0.0,
                condition_grade INTEGER NOT NULL DEFAULT 8,
                has_original_card INTEGER NOT NULL DEFAULT 0,
                notes TEXT NOT NULL DEFAULT '',
                added_at TEXT NOT NULL DEFAULT (DATE('now'))
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS trade_wishlist (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                pin_id TEXT NOT NULL,
                list_type TEXT NOT NULL CHECK (list_type IN ('TRADER', 'ISO')),
                condition_grade INTEGER NOT NULL DEFAULT 8,
                added_at TEXT NOT NULL DEFAULT (DATE('now')),
                UNIQUE(pin_id, list_type)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS pending_scans (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                image_path TEXT NOT NULL,
                queued_at TEXT NOT NULL DEFAULT (DATETIME('now')),
                status TEXT NOT NULL DEFAULT 'PENDING'
            )
            """.trimIndent()
        )
    }

    private fun migrateIfNeeded(db: SQLiteDatabase) {
        val currentVersion = db.rawQuery("PRAGMA user_version", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
        if (currentVersion < CURRENT_SCHEMA_VERSION) {
            addColumnIfMissing(db, "pins", "data_source", "TEXT NOT NULL DEFAULT 'Seeded'")
            addColumnIfMissing(db, "pins", "reference_image_path", "TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing(db, "pins", "valuation_sources", "TEXT NOT NULL DEFAULT ''")

            addColumnIfMissing(db, "board_scans", "image_path", "TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing(db, "board_scans", "status", "TEXT NOT NULL DEFAULT 'COMPLETED'")

            addColumnIfMissing(db, "scan_detections", "box_json", "TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing(db, "scan_detections", "match_source", "TEXT NOT NULL DEFAULT 'LOCAL_DB'")
            addColumnIfMissing(db, "scan_detections", "detected_label", "TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing(db, "scan_detections", "market_low_usd", "REAL NOT NULL DEFAULT 0.0")
            addColumnIfMissing(db, "scan_detections", "market_high_usd", "REAL NOT NULL DEFAULT 0.0")

            db.execSQL("PRAGMA user_version = $CURRENT_SCHEMA_VERSION")
        }
    }

    private fun addColumnIfMissing(
        db: SQLiteDatabase,
        table: String,
        column: String,
        definition: String
    ) {
        val existingColumns = mutableSetOf<String>()
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val nameIdx = c.getColumnIndex("name")
            while (c.moveToNext()) {
                if (nameIdx >= 0) existingColumns.add(c.getString(nameIdx))
            }
        }
        if (column !in existingColumns) {
            db.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
        }
    }

    @Synchronized
    fun close() {
        database?.close()
        database = null
    }
}

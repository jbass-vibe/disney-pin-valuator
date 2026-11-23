package com.disneypin.valuator.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream

class PinDatabaseHelper(private val context: Context) {

    companion object {
        private const val DB_NAME = "disney_pin_database.sqlite"
        private const val ASSET_NAME = "disney_pin_database.sqlite"
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
        ensureAppTables(db)
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
    }

    @Synchronized
    fun close() {
        database?.close()
        database = null
    }
}

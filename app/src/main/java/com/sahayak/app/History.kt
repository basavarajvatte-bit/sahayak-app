package com.sahayak.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Purane kaam ka khoj sakne wala record. Sirf phone par rehta hai. */
object History {
    private const val MAX_ROWS = 5_000
    private const val MAX_TEXT = 400
    private const val PRUNE_EVERY = 200L
    private const val TOKEN_ROWS = "40"
    private const val MAX_TOKENS = 6

    private class Db(context: Context) : SQLiteOpenHelper(context, "sahayak_history.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE VIRTUAL TABLE past USING fts4(kind, body, at, notindexed=kind, notindexed=at)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    @Volatile private var db: Db? = null

    private fun open(context: Context): Db = db ?: synchronized(this) {
        db ?: Db(context.applicationContext).also { db = it }
    }

    fun add(context: Context, kind: String, text: String) {
        if (!DailyLearning.isEnabled(context)) return
        val clean = BrainLogic.scrub(text, MAX_TEXT) ?: return
        try {
            val base = open(context).writableDatabase
            val row = base.insert("past", null, ContentValues().apply {
                put("kind", kind)
                put("body", clean)
                put("at", SimpleDateFormat("d MMM yyyy HH:mm", Locale.ENGLISH).format(Date()))
            })
            if (row % PRUNE_EVERY == 0L) {
                base.execSQL("DELETE FROM past WHERE rowid NOT IN (SELECT rowid FROM past ORDER BY rowid DESC LIMIT $MAX_ROWS)")
            }
        } catch (e: SQLiteException) {
            RunLog.add(context, "history not saved")
        }
    }

    /** Jis line mein zyada shabd milen woh pehle, barabar ho to nayi wali pehle. */
    fun search(context: Context, query: String, limit: Int): List<String> {
        val tokens = BrainLogic.tokens(query).take(MAX_TOKENS)
        val found = HashMap<Long, Pair<Int, String>>()
        try {
            val base = open(context).readableDatabase
            tokens.forEach { token ->
                base.rawQuery(
                    "SELECT rowid, kind, body, at FROM past WHERE past MATCH ? ORDER BY rowid DESC LIMIT $TOKEN_ROWS",
                    arrayOf("$token*")
                ).use { row ->
                    while (row.moveToNext()) {
                        val id = row.getLong(0)
                        val line = row.getString(3) + " " + row.getString(1) + ": " + row.getString(2)
                        found[id] = (found[id]?.first ?: 0) + 1 to line
                    }
                }
            }
        } catch (e: SQLiteException) {
            RunLog.add(context, "history search failed")
        }
        return found.entries.sortedWith(compareBy({ -it.value.first }, { -it.key })).take(limit).map { it.value.second }
    }

    fun recent(context: Context, limit: Int): List<String> = try {
        val lines = ArrayList<String>()
        open(context).readableDatabase.rawQuery(
            "SELECT kind, body, at FROM past ORDER BY rowid DESC LIMIT $limit", null
        ).use { row ->
            while (row.moveToNext()) lines.add(row.getString(2) + " " + row.getString(0) + ": " + row.getString(1))
        }
        lines
    } catch (e: SQLiteException) {
        emptyList()
    }

    fun count(context: Context): Int = try {
        open(context).readableDatabase.rawQuery("SELECT count(*) FROM past", null).use { row ->
            if (row.moveToFirst()) row.getInt(0) else 0
        }
    } catch (e: SQLiteException) {
        0
    }

    fun clear(context: Context) {
        try {
            open(context).writableDatabase.execSQL("DELETE FROM past")
        } catch (e: SQLiteException) {
            RunLog.add(context, "history not cleared")
        }
    }
}

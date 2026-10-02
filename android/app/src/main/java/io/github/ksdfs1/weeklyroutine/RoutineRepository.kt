package io.github.ksdfs1.weeklyroutine

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Reads the routine from the same Cloudflare Worker the web app saves to, and caches it on disk. */
object RoutineRepository {
    // same backend as API_BASE in ../../index.html
    const val API_BASE = "https://weekly-routine-api.ksdfs1.workers.dev"
    const val APP_URL = "https://ksdfs1.github.io/Weekly-Routine/"

    private const val CACHE_FILE = "state.json"
    private const val STALE_MS = 60 * 60 * 1000L

    @Volatile private var memo: Triple<Long, String, Routine?>? = null

    /** cached routine with this device's case picks applied, or null if nothing was ever fetched */
    fun load(ctx: Context): Routine? {
        val f = File(ctx.filesDir, CACHE_FILE)
        if (!f.exists()) return null
        val overrides = Prefs.of(ctx).getString(Prefs.CASE_OVERRIDES, "") ?: ""
        memo?.let { if (it.first == f.lastModified() && it.second == overrides) return it.third }
        val r = Routine.parse(f.readText())?.withCaseOverrides(parseOverrides(overrides))
        memo = Triple(f.lastModified(), overrides, r)
        return r
    }

    fun lastSync(ctx: Context): Long = Prefs.of(ctx).getLong(Prefs.LAST_SYNC, 0L)

    fun isStale(ctx: Context): Boolean = System.currentTimeMillis() - lastSync(ctx) > STALE_MS

    /** blocking — call off the main thread. Returns true when fresh data was stored. */
    fun fetch(ctx: Context): Boolean = fetchResult(ctx) != FetchResult.FAILED

    enum class FetchResult { CHANGED, SAME, FAILED }

    /** blocking. Like [fetch], but also tells whether the routine differed from the cache. */
    fun fetchResult(ctx: Context): FetchResult {
        val body = get() ?: return FetchResult.FAILED
        if (Routine.parse(body) == null) return FetchResult.FAILED   // "null" = nothing saved on the server yet
        return if (store(ctx, body)) FetchResult.CHANGED else FetchResult.SAME
    }

    /* ---- picking a day's case from the widget ---- */

    fun token(ctx: Context): String = Prefs.of(ctx).getString(Prefs.TOKEN, "")?.trim() ?: ""

    /** applies the pick on this device right away; [saveCase] then tries to move it to the server */
    fun setCaseOverride(ctx: Context, dayIndex: Int, caseId: String?) {
        val p = Prefs.of(ctx)
        val o = parseOverrides(p.getString(Prefs.CASE_OVERRIDES, "") ?: "").toMutableMap()
        if (caseId == null) o.remove(dayIndex) else o[dayIndex] = caseId
        val json = JSONObject().apply { o.forEach { (k, v) -> put(k.toString(), v) } }
        p.edit().putString(Prefs.CASE_OVERRIDES, if (o.isEmpty()) "" else json.toString()).apply()
    }

    enum class SaveResult { SAVED, NO_TOKEN, BAD_TOKEN, FAILED }

    /**
     * blocking. Sets the day's case on top of the latest saved routine and saves it with the edit
     * token; on success this device's override for that day is dropped (the server now agrees).
     */
    fun saveCase(ctx: Context, dayIndex: Int, caseId: String): SaveResult {
        val token = token(ctx)
        if (token.isEmpty()) return SaveResult.NO_TOKEN
        val latest = get() ?: return SaveResult.FAILED
        val body = try {
            val o = JSONObject(latest)
            o.getJSONArray("days").getJSONObject(dayIndex).put("activeCaseId", caseId)
            o.toString()
        } catch (e: Exception) {
            return SaveResult.FAILED
        }
        val conn = URL("$API_BASE/state").openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            when (conn.responseCode) {
                200 -> {
                    store(ctx, body)
                    // only if it wasn't changed again meanwhile
                    if (parseOverrides(Prefs.of(ctx).getString(Prefs.CASE_OVERRIDES, "") ?: "")[dayIndex] == caseId)
                        setCaseOverride(ctx, dayIndex, null)
                    SaveResult.SAVED
                }
                401 -> SaveResult.BAD_TOKEN
                else -> SaveResult.FAILED
            }
        } catch (e: Exception) {
            SaveResult.FAILED
        } finally {
            conn.disconnect()
        }
    }

    private fun get(): String? {
        val conn = URL("$API_BASE/state").openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.useCaches = false
            if (conn.responseCode != 200) return null
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * writes a routine JSON (fetched, or handed over by the app's web page) as the cache.
     * Returns false when it's the same as what's cached — then the file (and the parsed copy in
     * [memo]) is left alone, so nothing downstream needs to redraw.
     */
    fun store(ctx: Context, body: String): Boolean {
        Prefs.of(ctx).edit().putLong(Prefs.LAST_SYNC, System.currentTimeMillis()).apply()
        val file = File(ctx.filesDir, CACHE_FILE)
        if (file.exists() && file.length() == body.toByteArray(Charsets.UTF_8).size.toLong() && file.readText() == body) return false
        val tmp = File(ctx.filesDir, "$CACHE_FILE.tmp")
        tmp.writeText(body)
        tmp.renameTo(file)
        return true
    }

    /** changes whenever the routine the widget shows could have changed (new data or a case picked here) */
    fun version(ctx: Context): String {
        val f = File(ctx.filesDir, CACHE_FILE)
        return f.lastModified().toString() + "|" + (Prefs.of(ctx).getString(Prefs.CASE_OVERRIDES, "") ?: "")
    }

    private fun parseOverrides(s: String): Map<Int, String> = try {
        if (s.isEmpty()) emptyMap() else JSONObject(s).let { o ->
            o.keys().asSequence().associate { it.toInt() to o.getString(it) }
        }
    } catch (e: Exception) {
        emptyMap()
    }
}

object Prefs {
    const val NOTIFY = "notify"
    const val SOUND = "sound"
    const val VIBRATE = "vibrate"
    const val NOTIFY_GAPS = "notify_gaps"
    const val LAST_SYNC = "last_sync"
    const val LAST_KEY = "last_key"
    const val TOKEN = "write_token"
    const val CASE_OVERRIDES = "case_overrides"
    const val VIEW_DAY = "view_day"
    const val VIEW_DAY_AT = "view_day_at"
    const val LIST_SCROLL_KEY = "list_scroll_key"
    const val LIST_DATA_KEY = "list_data_key"
    const val SEL_DAY = "sel_day"
    const val SEL_START = "sel_start"
    const val SEL_AT = "sel_at"
    const val REFRESHING_AT = "refreshing_at"

    fun of(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
}

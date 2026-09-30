package io.github.ksdfs1.weeklyroutine

import android.content.Context
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

    @Volatile private var memo: Pair<Long, Routine?>? = null

    /** cached routine, or null if nothing was ever fetched (or the server has no data yet) */
    fun load(ctx: Context): Routine? {
        val f = File(ctx.filesDir, CACHE_FILE)
        if (!f.exists()) return null
        memo?.let { if (it.first == f.lastModified()) return it.second }
        val r = Routine.parse(f.readText())
        memo = f.lastModified() to r
        return r
    }

    fun lastSync(ctx: Context): Long = Prefs.of(ctx).getLong(Prefs.LAST_SYNC, 0L)

    fun isStale(ctx: Context): Boolean = System.currentTimeMillis() - lastSync(ctx) > STALE_MS

    /** blocking — call off the main thread. Returns true when fresh data was stored. */
    fun fetch(ctx: Context): Boolean {
        val conn = URL("$API_BASE/state").openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.useCaches = false
            if (conn.responseCode != 200) return false
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (Routine.parse(body) == null) return false   // "null" = nothing saved on the server yet
            val tmp = File(ctx.filesDir, "$CACHE_FILE.tmp")
            tmp.writeText(body)
            tmp.renameTo(File(ctx.filesDir, CACHE_FILE))
            Prefs.of(ctx).edit().putLong(Prefs.LAST_SYNC, System.currentTimeMillis()).apply()
            true
        } catch (e: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }
}

object Prefs {
    const val NOTIFY = "notify"
    const val SOUND = "sound"
    const val VIBRATE = "vibrate"
    const val NOTIFY_GAPS = "notify_gaps"
    const val LAST_SYNC = "last_sync"
    const val LAST_KEY = "last_key"

    fun of(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
}

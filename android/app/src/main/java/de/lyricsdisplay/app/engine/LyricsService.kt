package de.lyricsdisplay.app.engine

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Ergebnis für den Player: source = "lrclib" | "custom" | "none" | "rate_limited". */
data class LyricsResult(val synced: List<LyricLine>?, val plain: String?, val source: String)

private data class LrcLibLyrics(val synced: List<LyricLine>?, val plain: String?, val raw: String?)
private data class FetchAttempt(val lyrics: LrcLibLyrics?, val rateLimited: Boolean)

/**
 * Lyrics für einen Track: erst persistenter Store, dann LRCLib-Kaskade.
 * Gefundene Lyrics werden auf der Platte gespeichert (kein RAM-Cache).
 * Port von lyricsService.js. Blockierende Netz-I/O; Aufrufer müssen einen
 * Hintergrund-Dispatcher verwenden.
 */
class LyricsService(private val store: LyricsStore) {

    companion object {
        private const val LRCLIB_BASE = "https://lrclib.net/api"
        // LRCLib bittet um einen identifizierenden User-Agent (kein API-Key nötig)
        private const val USER_AGENT = "SpotifyLyricsDisplay/1.0 (+https://lrclib.net)"
        private const val TIMEOUT_MS = 5000
    }

    fun getLyrics(title: String, artist: String, album: String?, durationMs: Long?): LyricsResult {
        store.find(title, artist)?.let { return it.toLyricsResult() }

        var rateLimited = false

        var attempt = fetchLRCLib(title, artist, album, durationMs)
        rateLimited = rateLimited || attempt.rateLimited

        if (attempt.lyrics == null) {
            attempt = fetchLRCLib(title, artist, null, durationMs)
            rateLimited = rateLimited || attempt.rateLimited
        }

        if (attempt.lyrics == null) {
            attempt = searchLRCLib(title, artist)
            rateLimited = rateLimited || attempt.rateLimited
        }

        val lyrics = attempt.lyrics
        if (lyrics != null) {
            try {
                store.save(
                    name = title,
                    artist = artist,
                    album = album,
                    durationMs = durationMs,
                    synced = lyrics.synced,
                    plain = lyrics.plain,
                    raw = lyrics.raw,
                    source = "lrclib",
                )
            } catch (e: Exception) {
                // Store-Fehler dürfen die Anzeige nicht blockieren
            }
            return LyricsResult(lyrics.synced, lyrics.plain, "lrclib")
        }

        // Rate-Limit nicht persistieren - nächster Song / Neustart probiert erneut
        return LyricsResult(null, null, if (rateLimited) "rate_limited" else "none")
    }

    private fun fetchLRCLib(title: String, artist: String, album: String?, durationMs: Long?): FetchAttempt {
        return try {
            val params = mutableListOf("track_name" to title, "artist_name" to artist)
            if (album != null) params.add("album_name" to album)
            if (durationMs != null && durationMs > 0) params.add("duration" to (durationMs / 1000).toString())

            val url = "$LRCLIB_BASE/get?" + buildQuery(params)
            val (code, body) = httpGet(url)

            if (code == 429) return FetchAttempt(null, rateLimited = true)
            if (code !in 200..299 || body == null) return FetchAttempt(null, rateLimited = false)

            FetchAttempt(parseLRCLibResponse(JSONObject(body)), rateLimited = false)
        } catch (e: Exception) {
            FetchAttempt(null, rateLimited = false)
        }
    }

    private fun searchLRCLib(title: String, artist: String): FetchAttempt {
        return try {
            val url = "$LRCLIB_BASE/search?" + buildQuery(listOf("q" to "$artist $title"))
            val (code, body) = httpGet(url)

            if (code == 429) return FetchAttempt(null, rateLimited = true)
            if (code !in 200..299 || body == null) return FetchAttempt(null, rateLimited = false)

            val results = JSONArray(body)
            if (results.length() == 0) return FetchAttempt(null, rateLimited = false)

            // Ergebnisse mit synced Lyrics bevorzugen
            var chosen: JSONObject? = null
            for (i in 0 until results.length()) {
                val r = results.getJSONObject(i)
                if (!r.isNull("syncedLyrics") && r.optString("syncedLyrics").isNotEmpty()) {
                    chosen = r
                    break
                }
            }
            if (chosen == null) chosen = results.getJSONObject(0)

            FetchAttempt(parseLRCLibResponse(chosen), rateLimited = false)
        } catch (e: Exception) {
            FetchAttempt(null, rateLimited = false)
        }
    }

    private fun parseLRCLibResponse(data: JSONObject): LrcLibLyrics? {
        val syncedLyrics = data.optString("syncedLyrics", "").ifEmpty { null }
        val plainLyrics = data.optString("plainLyrics", "").ifEmpty { null }

        val synced = syncedLyrics?.let { Lrc.parseLRC(it) }
        val plain = plainLyrics

        if (synced == null && plain == null) return null

        // raw = Originaltext für den Editor auf der Settings-Seite
        return LrcLibLyrics(synced, plain, syncedLyrics ?: plainLyrics)
    }

    private fun buildQuery(params: List<Pair<String, String>>): String =
        params.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }

    /** Liefert (HTTP-Status, Body) oder (-1, null) bei Netzwerkfehler. */
    private fun httpGet(urlString: String): Pair<Int, String?> {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            code to body
        } finally {
            connection.disconnect()
        }
    }
}

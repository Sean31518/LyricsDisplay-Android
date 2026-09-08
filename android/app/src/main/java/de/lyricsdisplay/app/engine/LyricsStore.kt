package de.lyricsdisplay.app.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList

/** Ein gespeicherter Lyrics-Eintrag. Port von lyricsStore.js. */
data class LyricsEntry(
    val id: String,
    val name: String,
    val artist: String,
    val album: String?,
    val durationMs: Long?,
    val source: String, // "lrclib" | "custom"
    val synced: List<LyricLine>?,
    val plain: String?,
    val raw: String?,
    val addedAt: String,
    val updatedAt: String,
)

/** Listenansicht ohne Lyrics-Text (bleibt auch bei vielen Einträgen leicht). */
data class LyricsListItem(
    val id: String,
    val name: String,
    val artist: String,
    val source: String,
    val hasSynced: Boolean,
    val lineCount: Int?,
    val addedAt: String,
    val updatedAt: String,
)

/**
 * Persistenter Lyrics-Speicher: eine JSON-Datei pro Song unter <baseDir>/lyrics/.
 * Bewusst ohne In-Memory-Cache - Lookups lesen von der Platte. Blockierende
 * File-I/O; von Aufrufern auf einem Hintergrund-Dispatcher auszuführen.
 */
class LyricsStore(baseDir: File) {

    interface Listener {
        fun onChanged(entry: LyricsEntry)
        fun onDeleted(entry: LyricsEntry)
    }

    private val lyricsDir = File(baseDir, "lyrics").apply { mkdirs() }
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun addListener(l: Listener) = listeners.add(l).let {}
    fun removeListener(l: Listener) = listeners.remove(l).let {}

    private fun normalize(s: String?): String =
        (s ?: "").lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()

    private fun primaryArtist(artist: String?): String =
        (artist ?: "").split(",")[0].trim()

    /** Stabiler Identifier: sha1(normalisierte(r) Titel + Interpret). */
    fun idFor(name: String?, artist: String?): String {
        val input = "${normalize(artist)}::${normalize(name)}"
        val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun file(id: String) = File(lyricsDir, "$id.json")

    fun get(id: String): LyricsEntry? {
        val f = file(id)
        if (!f.exists()) return null
        return try {
            fromJson(id, JSONObject(f.readText(Charsets.UTF_8)))
        } catch (e: Exception) {
            null
        }
    }

    /** Lookup für die Wiedergabe: exakter Key, dann Erst-Interpret als Fallback. */
    fun find(name: String, artist: String): LyricsEntry? {
        get(idFor(name, artist))?.let { return it }
        val primary = primaryArtist(artist)
        if (primary.isNotEmpty() && primary != artist) {
            return get(idFor(name, primary))
        }
        return null
    }

    /** True wenn die Entry-ID zum Track passt (inkl. Erst-Interpret-Variante). */
    fun matchesTrack(id: String, name: String, artist: String): Boolean =
        id == idFor(name, artist) || id == idFor(name, primaryArtist(artist))

    fun list(): List<LyricsListItem> {
        val files = lyricsDir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()
        return files.mapNotNull { f ->
            val id = f.name.removeSuffix(".json")
            get(id)?.let { entry ->
                LyricsListItem(
                    id = entry.id,
                    name = entry.name,
                    artist = entry.artist,
                    source = entry.source,
                    hasSynced = !entry.synced.isNullOrEmpty(),
                    lineCount = entry.synced?.size,
                    addedAt = entry.addedAt,
                    updatedAt = entry.updatedAt,
                )
            }
        }.sortedByDescending { it.updatedAt }
    }

    /**
     * Anlegen oder Überschreiben (Key = Titel+Interpret).
     */
    fun save(
        name: String,
        artist: String,
        album: String?,
        durationMs: Long?,
        synced: List<LyricLine>?,
        plain: String?,
        raw: String?,
        source: String,
    ): LyricsEntry {
        val id = idFor(name, artist)
        val existing = get(id)
        val now = isoFormat.format(Date())
        val entry = LyricsEntry(
            id = id,
            name = name,
            artist = artist,
            album = album ?: existing?.album,
            durationMs = durationMs ?: existing?.durationMs,
            source = source,
            synced = synced,
            plain = plain,
            raw = raw,
            addedAt = existing?.addedAt ?: now,
            updatedAt = now,
        )
        file(id).writeText(toJson(entry).toString(2), Charsets.UTF_8)
        listeners.forEach { it.onChanged(entry) }
        return entry
    }

    /** Bearbeiten; bei geändertem Titel/Interpret wandert der Eintrag auf neuen Key. */
    fun update(
        id: String,
        name: String?,
        artist: String?,
        synced: List<LyricLine>?,
        plain: String?,
        raw: String?,
        source: String?,
    ): LyricsEntry? {
        val existing = get(id) ?: return null
        val mergedName = name ?: existing.name
        val mergedArtist = artist ?: existing.artist
        val newId = idFor(mergedName, mergedArtist)
        if (newId != id) {
            file(id).delete()
            listeners.forEach { it.onDeleted(existing) }
        }
        return save(
            name = mergedName,
            artist = mergedArtist,
            album = existing.album,
            durationMs = existing.durationMs,
            synced = synced,
            plain = plain,
            raw = raw,
            source = source ?: existing.source,
        )
    }

    fun remove(id: String): LyricsEntry? {
        val existing = get(id) ?: return null
        file(id).delete()
        listeners.forEach { it.onDeleted(existing) }
        return existing
    }

    private fun toJson(entry: LyricsEntry): JSONObject = JSONObject().apply {
        put("name", entry.name)
        put("artist", entry.artist)
        put("album", entry.album)
        put("duration_ms", entry.durationMs)
        put("source", entry.source)
        put("synced", entry.synced?.let { lines ->
            JSONArray().apply {
                lines.forEach { line ->
                    put(JSONObject().apply {
                        put("time_ms", line.timeMs)
                        put("text", line.text)
                    })
                }
            }
        })
        put("plain", entry.plain)
        put("raw", entry.raw)
        put("addedAt", entry.addedAt)
        put("updatedAt", entry.updatedAt)
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key)) null else optString(key, null)

    private fun fromJson(id: String, json: JSONObject): LyricsEntry {
        val syncedArr = json.optJSONArray("synced")
        val synced = if (syncedArr != null) {
            (0 until syncedArr.length()).map { i ->
                val line = syncedArr.getJSONObject(i)
                LyricLine(line.getLong("time_ms"), line.optString("text", ""))
            }
        } else null

        return LyricsEntry(
            id = id,
            name = json.getString("name"),
            artist = json.getString("artist"),
            album = json.optNullableString("album"),
            durationMs = if (json.isNull("duration_ms")) null else json.optLong("duration_ms"),
            source = json.optString("source", "lrclib"),
            synced = synced,
            plain = json.optNullableString("plain"),
            raw = json.optNullableString("raw"),
            addedAt = json.optString("addedAt", ""),
            updatedAt = json.optString("updatedAt", ""),
        )
    }
}

/** Store-Eintrag -> Anzeige-Format des Players (analog store.entryToLyrics in lyricsStore.js). */
fun LyricsEntry.toLyricsResult(): LyricsResult =
    LyricsResult(synced = synced, plain = plain, source = source.ifEmpty { "lrclib" })

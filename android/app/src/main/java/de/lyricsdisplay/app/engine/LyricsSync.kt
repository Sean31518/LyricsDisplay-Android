package de.lyricsdisplay.app.engine

/**
 * Aktive Zeile zu einem Zeitpunkt bestimmen - von Notification (Schritt 8)
 * und Widget (Schritt 7) genutzt, damit beide exakt dieselbe Logik wie
 * player.js' syncLyrics() verwenden.
 */
object LyricsSync {
    fun activeLineIndex(synced: List<LyricLine>?, progressMs: Long): Int {
        val lines = synced ?: return -1
        var idx = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= progressMs) idx = i else break
        }
        return idx
    }

    fun currentLine(lyrics: LyricsResult?, progressMs: Long): String? {
        if (lyrics == null) return null
        val synced = lyrics.synced
        if (!synced.isNullOrEmpty()) {
            val idx = activeLineIndex(synced, progressMs)
            return if (idx >= 0) synced[idx].text else null
        }
        return lyrics.plain?.lineSequence()?.firstOrNull()
    }
}

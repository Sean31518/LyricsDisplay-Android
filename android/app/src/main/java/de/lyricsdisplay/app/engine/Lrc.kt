package de.lyricsdisplay.app.engine

import java.util.regex.Pattern

/** Eine Zeile mit Zeitstempel (ms) und Text. Port von lrc.js. */
data class LyricLine(val timeMs: Long, val text: String)

data class ParsedLyrics(val synced: List<LyricLine>?, val plain: String?)

object Lrc {
    private val TIMESTAMP: Pattern =
        Pattern.compile("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?\\]")

    /**
     * Parst LRC-Format zu einer nach Zeit sortierten Liste.
     * Unterstützt [m:ss], [mm:ss.xx], [mm:ss.xxx] und mehrere Timestamps
     * pro Zeile ("[00:12.00][01:02.00]repeated line"). Metadata-Tags wie
     * [ar:...] werden ignoriert, da sie nicht direkt am Zeilenanfang stehen.
     */
    fun parseLRC(text: String): List<LyricLine>? {
        val lines = mutableListOf<LyricLine>()

        for (rawLine in text.split("\n")) {
            val matcher = TIMESTAMP.matcher(rawLine)
            val stamps = mutableListOf<Long>()
            var textStart = 0

            // Nur führende, direkt aufeinanderfolgende Timestamps einsammeln
            while (matcher.find(textStart)) {
                if (matcher.start() != textStart) break
                val minutes = matcher.group(1)!!.toInt()
                val seconds = matcher.group(2)!!.toInt()
                val fractionRaw = matcher.group(3)
                val fraction = if (fractionRaw != null) fractionRaw.padEnd(3, '0').toInt() else 0
                stamps.add((minutes * 60L + seconds) * 1000L + fraction)
                textStart = matcher.end()
            }

            if (stamps.isEmpty()) continue
            val lineText = rawLine.substring(textStart).trim()
            for (timeMs in stamps) {
                lines.add(LyricLine(timeMs, lineText))
            }
        }

        lines.sortBy { it.timeMs }
        return lines.ifEmpty { null }
    }

    /**
     * Auto-Erkennung synced (LRC) vs. Plain-Text.
     * Bei synced wird plain aus den Zeilentexten abgeleitet (Fallback-Anzeige).
     */
    fun detectAndParse(text: String): ParsedLyrics {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ParsedLyrics(null, null)

        val synced = parseLRC(trimmed)
        // Mindestens 2 getimte Zeilen, sonst ist ein zufälliges "[1:23]" kein LRC
        if (synced != null && synced.size >= 2) {
            val plain = synced.mapNotNull { it.text.ifEmpty { null } }.joinToString("\n").ifEmpty { null }
            return ParsedLyrics(synced, plain)
        }

        return ParsedLyrics(null, trimmed)
    }
}

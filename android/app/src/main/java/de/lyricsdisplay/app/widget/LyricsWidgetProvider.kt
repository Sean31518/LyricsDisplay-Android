package de.lyricsdisplay.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import de.lyricsdisplay.app.MainActivity
import de.lyricsdisplay.app.R
import de.lyricsdisplay.app.engine.EngineBus
import de.lyricsdisplay.app.engine.LyricsSync

/**
 * Baut die Zeilen-Ansicht bei jedem Update direkt per addView() zusammen -
 * KEINE ListView/RemoteViewsService/setScrollPosition() (siehe widget_lyrics.xml
 * für die Begründung: setScrollPosition() wurde auf dem Testgerät trotz
 * korrekt feuerndem Aufruf nicht zuverlässig umgesetzt, die aktive Zeile
 * blieb dauerhaft außerhalb des sichtbaren Bereichs). Stattdessen wird bei
 * jedem Update ein festes Fenster aus Zeilen UM die aktive Zeile herum direkt
 * gerendert - die aktive Zeile landet dadurch immer exakt in der Mitte,
 * unabhängig von Launcher-spezifischem Scroll-Verhalten.
 */
class LyricsWidgetProvider : AppWidgetProvider() {

    companion object {
        // Song-Info wächst mit der Lyric-Schriftgröße mit, bleibt aber immer kleiner
        private const val TRACK_FONT_RATIO = 0.75f

        // Grobe Zeilenhöhe (Schrift + vertikales Padding aus widget_lyrics_item.xml)
        // zur Schätzung, wie viele Zeilen in die verfügbare Widget-Höhe passen.
        // Muss nicht exakt sein: eine zu hoch geschätzte Zeilenzahl führt nur zu
        // ein paar am Rand abgeschnittenen Zeilen, eine zu niedrige nur zu etwas
        // Leerraum - anders als bei der vorherigen Scroll-Lösung ist ein
        // Schätzfehler hier rein kosmetisch, die aktive Zeile bleibt in jedem
        // Fall an der gleichen festen Position (siehe ACTIVE_ROW_OFFSET).
        // MIN_ROWS bewusst 1 (nicht z.B. 3): bei kleinen Widget-Größen (z.B. 3x1)
        // erzwang eine höhere Untergrenze immer mindestens 3 Zeilen, obwohl nur
        // 1-2 tatsächlich Platz hatten - die überzähligen wurden unten
        // abgeschnitten, was bei ACTIVE_ROW_OFFSET=1 ausgerechnet die aktive
        // Zeile treffen konnte. Mit MIN_ROWS=1 zeigt ein sehr kleines Widget
        // notfalls nur die aktive Zeile, statt Zeilen abzuschneiden.
        private const val ROW_HEIGHT_FACTOR = 1.75f
        private const val MIN_ROWS = 1

        // Aktive Zeile als 2. Zeile von oben (Index 1) statt mittig - lässt mehr
        // kommende Zeilen sichtbar, zum Vorbereiten auf das, was als Nächstes kommt.
        private const val ACTIVE_ROW_OFFSET = 1
        private const val MAX_ROWS = 13

        // Per `adb shell dumpsys appwidget` auf dem Testgerät (Samsung One UI)
        // gemessen: Zeilenspanne 1 -> appWidgetMaxHeight=95, Zeilenspanne 2 ->
        // 215 (nicht die generische 70dp/Zelle-AOSP-Formel). 150 liegt sicher
        // dazwischen. Unterhalb dieser Schwelle gilt das Widget als "Höhe 1" -
        // die Titelzeile wird dann ausgeblendet, um den knappen Platz für die
        // Lyric-Zeile freizugeben.
        private const val TRACK_MIN_HEIGHT_DP = 150

        // (Track-ID, aktive Zeile) je Widget - verhindert, dass bei JEDEM
        // 1s-Progress-Tick unnötig neu gerendert wird, obwohl sich nichts
        // geändert hat.
        private val lastState = HashMap<Int, Pair<String?, Int>>()

        fun updateAll(context: Context, force: Boolean = false) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, LyricsWidgetProvider::class.java))
            for (id in ids) updateWidget(context, manager, id, force)
        }

        private fun updateWidget(context: Context, manager: AppWidgetManager, appWidgetId: Int, force: Boolean = false) {
            val track = EngineBus.currentTrack
            val lyrics = EngineBus.currentLyrics
            val progressMs = EngineBus.currentProgressMs
            val synced = lyrics?.synced

            val activeIdx = LyricsSync.activeLineIndex(synced, progressMs)
            val stateKey = track?.id to activeIdx
            if (!force && lastState[appWidgetId] == stateKey) return
            lastState[appWidgetId] = stateKey

            val fontSizeSp = WidgetPrefs.getFontSizeSp(context, appWidgetId)
            val heightDp = manager.getAppWidgetOptions(appWidgetId)
                .getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
                .takeIf { it > 0 } ?: 180
            val showTrack = heightDp >= TRACK_MIN_HEIGHT_DP

            val views = RemoteViews(context.packageName, R.layout.widget_lyrics)
            views.setViewVisibility(R.id.widget_track, if (showTrack) View.VISIBLE else View.GONE)
            if (showTrack) {
                views.setTextViewText(
                    R.id.widget_track,
                    track?.let { "${it.name} – ${it.artist}" } ?: "Lyrics Display",
                )
                views.setTextViewTextSize(R.id.widget_track, TypedValue.COMPLEX_UNIT_SP, fontSizeSp * TRACK_FONT_RATIO)
            }

            views.removeAllViews(R.id.widget_lines)
            for (rowText in buildRows(context, heightDp, showTrack, lyrics, synced, activeIdx, fontSizeSp)) {
                views.addView(R.id.widget_lines, rowText)
            }

            val openAppIntent = Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pendingIntent = PendingIntent.getActivity(
                context, 0, openAppIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

            manager.updateAppWidget(appWidgetId, views)
        }

        private fun buildRows(
            context: Context,
            heightDp: Int,
            showTrack: Boolean,
            lyrics: de.lyricsdisplay.app.engine.LyricsResult?,
            synced: List<de.lyricsdisplay.app.engine.LyricLine>?,
            activeIdx: Int,
            fontSizeSp: Float,
        ): List<RemoteViews> {
            // Bei ausgeblendeter Titelzeile (Höhe 1, siehe TRACK_MIN_HEIGHT_DP) fest
            // auf 1 Zeile gehen statt die Schätzformel entscheiden zu lassen - der
            // dadurch frei gewordene Platz brachte die Schätzung sonst auf 2 Zeilen
            // (vorherige + aktive), wodurch die aktive Zeile wieder als zweite/
            // untere Zeile landete statt allein sichtbar zu sein.
            val rowCount = if (showTrack) estimateRowCount(heightDp, fontSizeSp) else 1

            if (!synced.isNullOrEmpty()) {
                // Aktive Zeile bewusst nicht mittig, sondern nah oben (2. Zeile) -
                // so bleiben mehrere kommende Zeilen sichtbar, an denen man sich
                // vorbereiten kann, statt nur an der bereits laufenden Zeile.
                val offset = ACTIVE_ROW_OFFSET.coerceAtMost(rowCount - 1)
                return (0 until rowCount).map { i ->
                    val lineIdx = activeIdx - offset + i
                    val text = synced.getOrNull(lineIdx)?.text?.ifEmpty { "…" } ?: ""
                    makeRow(context, text, isActive = lineIdx == activeIdx && activeIdx >= 0, fontSizeSp)
                }
            }

            val plainLines = lyrics?.plain?.lineSequence()?.filter { it.isNotBlank() }?.toList()
            if (!plainLines.isNullOrEmpty()) {
                return (0 until rowCount).map { i ->
                    makeRow(context, plainLines.getOrNull(i).orEmpty(), isActive = false, fontSizeSp)
                }
            }

            return listOf(makeRow(context, "Warte auf Wiedergabe…", isActive = false, fontSizeSp))
        }

        private fun makeRow(context: Context, text: String, isActive: Boolean, fontSizeSp: Float): RemoteViews {
            val row = RemoteViews(context.packageName, R.layout.widget_lyrics_item)
            row.setTextViewText(R.id.widget_item_text, text)
            row.setTextViewTextSize(R.id.widget_item_text, TypedValue.COMPLEX_UNIT_SP, fontSizeSp)
            val colorRes = if (isActive) R.color.widget_accent else R.color.widget_text
            row.setTextColor(R.id.widget_item_text, androidx.core.content.ContextCompat.getColor(context, colorRes))
            return row
        }

        // Nur aufgerufen, wenn die Titelzeile sichtbar ist (siehe buildRows) -
        // bei ausgeblendeter Titelzeile geht buildRows fest auf 1 Zeile.
        private fun estimateRowCount(heightDp: Int, fontSizeSp: Float): Int {
            // Innenabstand + Titelzeile grob abziehen, Rest durch geschätzte Zeilenhöhe teilen
            val usableDp = (heightDp - 40).coerceAtLeast(20)
            val rowHeightDp = fontSizeSp * ROW_HEIGHT_FACTOR
            return (usableDp / rowHeightDp).toInt().coerceIn(MIN_ROWS, MAX_ROWS)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) updateWidget(context, appWidgetManager, id, force = true)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        updateWidget(context, appWidgetManager, appWidgetId, force = true)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            WidgetPrefs.removeFontSize(context, id)
            lastState.remove(id)
        }
    }
}

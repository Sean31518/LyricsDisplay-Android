package de.lyricsdisplay.app.widget

import android.content.Context

/** Pro Widget-Instanz gespeicherte Lyric-Zeilen-Schriftgröße (sp), einstellbar über [LyricsWidgetConfigActivity]. */
object WidgetPrefs {
    const val MIN_SP = 10
    const val MAX_SP = 32
    const val DEFAULT_SP = 16f

    private const val PREFS_NAME = "widget_prefs"

    fun getFontSizeSp(context: Context, appWidgetId: Int): Float =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat("font_size_$appWidgetId", DEFAULT_SP)

    fun setFontSizeSp(context: Context, appWidgetId: Int, sp: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putFloat("font_size_$appWidgetId", sp)
            .apply()
    }

    fun removeFontSize(context: Context, appWidgetId: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove("font_size_$appWidgetId")
            .apply()
    }
}

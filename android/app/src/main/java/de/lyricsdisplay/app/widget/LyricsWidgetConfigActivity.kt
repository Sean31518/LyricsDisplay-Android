package de.lyricsdisplay.app.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import de.lyricsdisplay.app.R

/**
 * Widget-Konfigurationsmenü: einmal beim Platzieren automatisch geöffnet
 * (siehe android:configure in lyrics_widget_info.xml), danach über
 * "Widget konfigurieren" im Homescreen-Menü erneut erreichbar. Schriftgröße
 * wird pro Widget-Instanz gespeichert (WidgetPrefs), damit mehrere Widgets
 * unterschiedlich groß dargestellt werden können.
 */
class LyricsWidgetConfigActivity : Activity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        setContentView(R.layout.activity_widget_config)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val preview = findViewById<TextView>(R.id.config_preview)
        val seekBar = findViewById<SeekBar>(R.id.config_seekbar)
        val saveBtn = findViewById<Button>(R.id.config_save)

        val range = WidgetPrefs.MAX_SP - WidgetPrefs.MIN_SP
        val initialSp = WidgetPrefs.getFontSizeSp(this, appWidgetId)
        seekBar.max = range
        seekBar.progress = (initialSp - WidgetPrefs.MIN_SP).toInt().coerceIn(0, range)
        preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, initialSp)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, (WidgetPrefs.MIN_SP + progress).toFloat())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        saveBtn.setOnClickListener {
            val sp = (WidgetPrefs.MIN_SP + seekBar.progress).toFloat()
            WidgetPrefs.setFontSizeSp(this, appWidgetId, sp)
            LyricsWidgetProvider.updateAll(this, force = true)

            val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            setResult(RESULT_OK, resultValue)
            finish()
        }
    }
}

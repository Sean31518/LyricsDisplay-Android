package de.lyricsdisplay.app.plugin

import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import de.lyricsdisplay.app.engine.Engine
import de.lyricsdisplay.app.engine.EngineBus
import de.lyricsdisplay.app.engine.Lrc
import de.lyricsdisplay.app.engine.LyricsEntry
import de.lyricsdisplay.app.engine.LyricsForegroundService
import de.lyricsdisplay.app.engine.LyricsListItem
import de.lyricsdisplay.app.engine.LyricsResult
import de.lyricsdisplay.app.engine.LyricLine
import de.lyricsdisplay.app.engine.SpotifyAuthManager
import de.lyricsdisplay.app.engine.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Einziger Kotlin<->JS-Kontaktpunkt: bindet die WebView an [EngineBus]
 * (Live-Events, Pendant zu den bisherigen WebSocket-Broadcasts) und an
 * [Engine] (Auth/Lyrics-CRUD, Pendant zu routes/auth.js + routes/api.js).
 * Fängt außerdem den Spotify-OAuth-Redirect über [handleOnNewIntent] ab -
 * Capacitor ruft diesen Hook für jedes registrierte Plugin automatisch auf.
 */
@CapacitorPlugin(name = "LyricsEngine")
class LyricsEnginePlugin : Plugin() {

    private lateinit var scope: CoroutineScope
    private var eventsJob: Job? = null

    override fun load() {
        Engine.init(context)
        // Prozess kann neu gestartet worden sein (App-Neustart, vom System
        // gekillt) ohne dass gerade ein Login stattfand - Service bei
        // bestehender Anmeldung explizit (wieder-)anstoßen statt nur nach
        // erfolgreichem OAuth-Redirect.
        if (Engine.auth.isAuthenticated()) LyricsForegroundService.start(context)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        eventsJob = scope.launch {
            EngineBus.events.collect { event -> handleEngineEvent(event) }
        }
    }

    override fun handleOnDestroy() {
        eventsJob?.cancel()
        scope.cancel()
        super.handleOnDestroy()
    }

    private fun handleEngineEvent(event: EngineBus.Event) {
        when (event) {
            is EngineBus.Event.TrackUpdate -> notifyListeners("track", trackToJson(event.track))
            is EngineBus.Event.LyricsUpdate -> notifyListeners("lyrics", lyricsToJson(event.lyrics, event.loading))
            is EngineBus.Event.ProgressUpdate -> notifyListeners("progress", JSObject().put("progress_ms", event.progressMs))
            EngineBus.Event.Stopped -> notifyListeners("stopped", JSObject())
        }
    }

    // ── Status / Auth ────────────────────────────────────────────────────────

    @PluginMethod
    fun getStatus(call: PluginCall) {
        val ret = JSObject()
        ret.put("configured", Engine.auth.isConfigured())
        ret.put("authenticated", Engine.auth.isAuthenticated())
        ret.put("engineRunning", EngineBus.isEngineRunning)
        call.resolve(ret)
    }

    @PluginMethod
    fun getRedirectUri(call: PluginCall) {
        call.resolve(JSObject().put("uri", SpotifyAuthManager.REDIRECT_URI))
    }

    @PluginMethod
    fun getClientId(call: PluginCall) {
        call.resolve(JSObject().put("clientId", Engine.auth.clientId))
    }

    @PluginMethod
    fun setClientId(call: PluginCall) {
        val clientId = call.getString("clientId")
        if (clientId.isNullOrBlank()) {
            call.reject("clientId fehlt")
            return
        }
        Engine.auth.clientId = clientId
        call.resolve()
    }

    @PluginMethod
    fun login(call: PluginCall) {
        if (!Engine.auth.isConfigured()) {
            call.reject("not_configured")
            return
        }
        Engine.auth.startLogin(activity)
        call.resolve()
    }

    @PluginMethod
    fun logout(call: PluginCall) {
        LyricsForegroundService.stop(context)
        Engine.auth.logout()
        EngineBus.emitStopped()
        call.resolve()
    }

    /**
     * Öffnet eine externe URL in einer Custom Tab (bleibt "in der App" statt
     * in einem separaten Browser-Tab zu landen) - z.B. der LRCLib-Such-Link
     * in den Settings. Gleicher Mechanismus wie der Spotify-Login.
     */
    @PluginMethod
    fun openUrl(call: PluginCall) {
        val url = call.getString("url")
        if (url.isNullOrBlank()) {
            call.reject("url fehlt")
            return
        }
        CustomTabsIntent.Builder().build().launchUrl(activity, Uri.parse(url))
        call.resolve()
    }

    // ── Player ───────────────────────────────────────────────────────────────

    /**
     * Aktueller Stand für den Player beim (Wieder-)Öffnen der WebView und für
     * "aktuellen Song übernehmen" im Settings-Editor - Pendant zum initialen
     * State, den server.js früher bei jeder neuen WS-Verbindung schickte.
     */
    @PluginMethod
    fun getCurrentTrack(call: PluginCall) {
        val ret = JSObject()
        EngineBus.currentTrack?.let { ret.put("track", trackToJson(it)) }
        EngineBus.currentLyrics?.let { ret.put("lyrics", lyricsToJson(it, loading = false)) }
        call.resolve(ret)
    }

    // ── Lyrics-Verwaltung (Pendant zu routes/api.js) ─────────────────────────

    @PluginMethod
    fun listLyrics(call: PluginCall) {
        scope.launch(Dispatchers.IO) {
            try {
                val arr = JSArray()
                Engine.lyricsStore.list().forEach { arr.put(listItemToJson(it)) }
                call.resolve(JSObject().put("entries", arr))
            } catch (e: Exception) {
                call.reject(e.message ?: "list failed")
            }
        }
    }

    @PluginMethod
    fun getLyricsEntry(call: PluginCall) {
        val id = call.getString("id")
        if (id == null || !isValidId(id)) {
            call.reject("invalid id")
            return
        }
        scope.launch(Dispatchers.IO) {
            val entry = Engine.lyricsStore.get(id)
            if (entry == null) call.reject("not found") else call.resolve(entryToJson(entry))
        }
    }

    // IDs sind sha1-Hex - alles andere ablehnen (verhindert Pfad-Spielereien)
    private fun isValidId(id: String): Boolean = Regex("^[a-f0-9]{40}$").matches(id)

    @PluginMethod
    fun saveLyrics(call: PluginCall) {
        val name = call.getString("name")?.trim().orEmpty()
        val artist = call.getString("artist")?.trim().orEmpty()
        val text = call.getString("text").orEmpty()
        if (name.isEmpty() || artist.isEmpty()) {
            call.reject("name und artist sind Pflicht")
            return
        }
        val parsed = Lrc.detectAndParse(text)
        if (parsed.synced == null && parsed.plain == null) {
            call.reject("lyrics sind leer")
            return
        }
        // Eigener Eintrag überschreibt einen bestehenden mit gleichem Titel+Interpret -
        // so lassen sich falsche LRCLib-Lyrics ersetzen
        scope.launch(Dispatchers.IO) {
            try {
                val entry = Engine.lyricsStore.save(
                    name = name, artist = artist, album = null, durationMs = null,
                    synced = parsed.synced, plain = parsed.plain, raw = text.trim(), source = "custom",
                )
                call.resolve(entryToJson(entry))
            } catch (e: Exception) {
                call.reject(e.message ?: "save failed")
            }
        }
    }

    @PluginMethod
    fun updateLyrics(call: PluginCall) {
        val id = call.getString("id")
        if (id == null || !isValidId(id)) {
            call.reject("invalid id")
            return
        }
        val text = call.getString("text").orEmpty()
        val parsed = Lrc.detectAndParse(text)
        if (parsed.synced == null && parsed.plain == null) {
            call.reject("lyrics sind leer")
            return
        }
        val name = call.getString("name")?.trim()?.ifEmpty { null }
        val artist = call.getString("artist")?.trim()?.ifEmpty { null }
        scope.launch(Dispatchers.IO) {
            // von Hand bearbeitet = eigener Eintrag
            val entry = Engine.lyricsStore.update(
                id = id, name = name, artist = artist,
                synced = parsed.synced, plain = parsed.plain, raw = text.trim(), source = "custom",
            )
            if (entry == null) call.reject("not found") else call.resolve(entryToJson(entry))
        }
    }

    @PluginMethod
    fun deleteLyrics(call: PluginCall) {
        val id = call.getString("id")
        if (id == null || !isValidId(id)) {
            call.reject("invalid id")
            return
        }
        scope.launch(Dispatchers.IO) {
            val removed = Engine.lyricsStore.remove(id)
            if (removed == null) call.reject("not found") else call.resolve()
        }
    }

    // ── OAuth-Redirect ───────────────────────────────────────────────────────

    override fun handleOnNewIntent(intent: Intent) {
        super.handleOnNewIntent(intent)
        val uri = intent.data ?: return
        if (uri.scheme != Uri.parse(SpotifyAuthManager.REDIRECT_URI).scheme) return

        // Token-Exchange ist blockierende Netz-I/O - onNewIntent läuft auf dem
        // Main-Thread, das würde sonst mit NetworkOnMainThreadException scheitern
        scope.launch(Dispatchers.IO) {
            when (val result = Engine.auth.handleRedirect(uri)) {
                is SpotifyAuthManager.AuthResult.Success -> {
                    LyricsForegroundService.start(context)
                    notifyListeners("authResult", JSObject().put("success", true))
                }
                is SpotifyAuthManager.AuthResult.Failure -> {
                    notifyListeners("authResult", JSObject().put("success", false).put("reason", result.reason))
                }
            }
        }
    }

    // ── JSON-Mapping (snake_case Feldnamen wie Spotify/das bisherige Backend,
    //    damit player.js/settings.js unverändert bleiben) ─────────────────────

    private fun linesToJson(lines: List<LyricLine>?): JSArray? {
        if (lines == null) return null
        val arr = JSArray()
        lines.forEach { arr.put(JSObject().put("time_ms", it.timeMs).put("text", it.text)) }
        return arr
    }

    private fun trackToJson(track: Track): JSObject = JSObject().apply {
        put("id", track.id)
        put("name", track.name)
        put("artist", track.artist)
        put("album", track.album)
        put("cover", track.cover)
        put("cover_small", track.coverSmall)
        put("duration_ms", track.durationMs)
        put("progress_ms", track.progressMs)
        put("is_playing", track.isPlaying)
    }

    private fun lyricsToJson(lyrics: LyricsResult?, loading: Boolean): JSObject = JSObject().apply {
        put("loading", loading)
        put("synced", linesToJson(lyrics?.synced))
        put("plain", lyrics?.plain)
        put("source", lyrics?.source)
    }

    private fun listItemToJson(item: LyricsListItem): JSObject = JSObject().apply {
        put("id", item.id)
        put("name", item.name)
        put("artist", item.artist)
        put("source", item.source)
        put("hasSynced", item.hasSynced)
        put("lineCount", item.lineCount)
        put("addedAt", item.addedAt)
        put("updatedAt", item.updatedAt)
    }

    private fun entryToJson(entry: LyricsEntry): JSObject = JSObject().apply {
        put("id", entry.id)
        put("name", entry.name)
        put("artist", entry.artist)
        put("album", entry.album)
        put("source", entry.source)
        put("synced", linesToJson(entry.synced))
        put("plain", entry.plain)
        put("raw", entry.raw)
        put("addedAt", entry.addedAt)
        put("updatedAt", entry.updatedAt)
    }
}

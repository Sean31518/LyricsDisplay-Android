package de.lyricsdisplay.app.engine

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Prozessweiter Event-/State-Relay zwischen [LyricsForegroundService] und dem
 * Capacitor-Plugin - Pendant zu den WebSocket-Broadcasts in server.js, nur
 * innerhalb des Prozesses. Vom Service-Lebenszyklus entkoppelt: die WebView
 * kann sich jederzeit anhängen (auch bevor der Service startet) und liest
 * über [currentTrack]/[currentLyrics]/[isEngineRunning] sofort den letzten
 * bekannten Stand, statt auf das nächste Event warten zu müssen.
 */
object EngineBus {

    sealed class Event {
        data class TrackUpdate(val track: Track) : Event()
        data class LyricsUpdate(val lyrics: LyricsResult?, val loading: Boolean) : Event()
        data class ProgressUpdate(val progressMs: Long) : Event()
        object Stopped : Event()
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 64)
    val events: SharedFlow<Event> = _events

    @Volatile var currentTrack: Track? = null
        private set

    @Volatile var currentLyrics: LyricsResult? = null
        private set

    /** Live-Fortschritt (Fortschritts-Interpolation), nicht das ggf. veraltete track.progressMs. */
    @Volatile var currentProgressMs: Long = 0
        private set

    @Volatile var isEngineRunning: Boolean = false

    fun emitTrack(track: Track) {
        currentTrack = track
        currentProgressMs = track.progressMs
        _events.tryEmit(Event.TrackUpdate(track))
    }

    fun emitLyrics(lyrics: LyricsResult?, loading: Boolean) {
        currentLyrics = lyrics
        _events.tryEmit(Event.LyricsUpdate(lyrics, loading))
    }

    fun emitProgress(progressMs: Long) {
        currentProgressMs = progressMs
        _events.tryEmit(Event.ProgressUpdate(progressMs))
    }

    fun emitStopped() {
        currentTrack = null
        currentLyrics = null
        currentProgressMs = 0
        _events.tryEmit(Event.Stopped)
    }
}

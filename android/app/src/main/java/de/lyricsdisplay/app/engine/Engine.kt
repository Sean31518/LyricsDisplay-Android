package de.lyricsdisplay.app.engine

import android.content.Context

/**
 * App-weite Singletons für Auth/Lyrics-Store/-Service. Sowohl das
 * Capacitor-Plugin (Schritt 4, synchrone CRUD-Aufrufe/Login) als auch der
 * Foreground Service (Poller-Lifecycle) greifen auf dieselben Instanzen zu -
 * beide sind reine Dateispeicher (EncryptedSharedPreferences/JSON-Dateien),
 * ein zusätzlicher In-Memory-Cache wäre hier eine unnötige Fehlerquelle.
 */
object Engine {
    lateinit var auth: SpotifyAuthManager
        private set
    lateinit var lyricsStore: LyricsStore
        private set
    lateinit var lyricsService: LyricsService
        private set
    lateinit var poller: SpotifyPoller
        private set

    @Volatile private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val appContext = context.applicationContext
        auth = SpotifyAuthManager(appContext)
        lyricsStore = LyricsStore(appContext.filesDir)
        lyricsService = LyricsService(lyricsStore)
        poller = SpotifyPoller(auth)
        initialized = true
    }
}

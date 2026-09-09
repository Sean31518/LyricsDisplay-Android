package de.lyricsdisplay.app.engine

/**
 * Zuletzt beobachteter Poll-Status - für die Diagnose-Ansicht in den
 * Einstellungen, damit sich Probleme (z.B. Spotify-Rate-Limit, abgelaufene
 * Tokens) direkt in der App nachvollziehen lassen, ohne adb/logcat zu
 * brauchen. Rein prozesslokal, kein Storage - Werte gelten seit dem letzten
 * Service-Start.
 */
object EngineDiagnostics {
    @Volatile var lastPollAt: Long = 0
        private set

    @Volatile var lastSuccessAt: Long = 0
        private set

    @Volatile var lastError: String? = null
        private set

    @Volatile var lastErrorAt: Long = 0
        private set

    /** Timestamp (epoch ms), bis zu dem der Poller wegen HTTP 429 pausiert - 0 = nicht aktiv. */
    @Volatile var rateLimitedUntil: Long = 0
        private set

    fun recordPollAttempt() {
        lastPollAt = System.currentTimeMillis()
    }

    fun recordSuccess() {
        lastSuccessAt = System.currentTimeMillis()
        rateLimitedUntil = 0
    }

    fun recordError(message: String) {
        lastError = message
        lastErrorAt = System.currentTimeMillis()
    }

    fun recordRateLimited(untilTimestamp: Long, message: String) {
        rateLimitedUntil = untilTimestamp
        recordError(message)
    }
}

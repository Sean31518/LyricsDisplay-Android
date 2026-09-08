package de.lyricsdisplay.app.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Track(
    val id: String,
    val name: String,
    val artist: String,
    val album: String,
    val cover: String?,
    val coverSmall: String?,
    val durationMs: Long,
    val progressMs: Long,
    val isPlaying: Boolean,
)

sealed class PollerEvent {
    data class TrackChanged(val track: Track) : PollerEvent()
    data class Progress(val progressMs: Long) : PollerEvent()
    object Stopped : PollerEvent()
}

/**
 * Pollt Spotifys "currently playing" alle 3s, interpoliert den Fortschritt
 * jede Sekunde. Port von spotifyPoller.js - läuft als Coroutine in dem Scope
 * des LyricsForegroundService, nicht mehr an WebSocket-Clients gebunden.
 */
class SpotifyPoller(private val auth: SpotifyAuthManager) {

    companion object {
        private const val TRACK_POLL_INTERVAL_MS = 3000L
        private const val PROGRESS_POLL_INTERVAL_MS = 1000L
        private const val TIMEOUT_MS = 8000
    }

    private var job: Job? = null

    @Volatile private var currentState: Track? = null
    @Volatile private var isPlaying = false
    @Volatile private var lastProgressMs = 0L
    @Volatile private var lastTimestamp = 0L

    private val _events = MutableSharedFlow<PollerEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<PollerEvent> = _events

    fun getCurrentState(): Track? = currentState

    fun isRunning(): Boolean = job != null

    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch {
            launch { trackLoop() }
            launch { progressLoop() }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        if (currentState != null) {
            currentState = null
            isPlaying = false
            _events.tryEmit(PollerEvent.Stopped)
        }
    }

    private suspend fun trackLoop() {
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val track = fetchCurrentlyPlaying()

            if (track == null) {
                if (currentState != null) {
                    currentState = null
                    isPlaying = false
                    _events.emit(PollerEvent.Stopped)
                }
            } else {
                val prevId = currentState?.id
                currentState = track
                lastProgressMs = track.progressMs
                lastTimestamp = System.currentTimeMillis()
                isPlaying = track.isPlaying

                if (track.id != prevId) {
                    _events.emit(PollerEvent.TrackChanged(track))
                }
            }

            delay(TRACK_POLL_INTERVAL_MS)
        }
    }

    private suspend fun progressLoop() {
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            delay(PROGRESS_POLL_INTERVAL_MS)
            val state = currentState
            if (!isPlaying || state == null) continue

            val elapsed = System.currentTimeMillis() - lastTimestamp
            val estimated = minOf(lastProgressMs + elapsed, state.durationMs)
            _events.emit(PollerEvent.Progress(estimated))
        }
    }

    private fun fetchCurrentlyPlaying(): Track? {
        if (!auth.refreshIfNeeded()) return null
        val tokens = auth.getTokens() ?: return null

        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("https://api.spotify.com/v1/me/player/currently-playing")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer ${tokens.accessToken}")
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
            }

            val code = connection.responseCode
            when {
                code == 204 || code == 404 -> null // nichts läuft gerade
                code == 401 -> {
                    // Access-Token trotz Refresh ungültig (z.B. Autorisierung entzogen)
                    auth.logout()
                    null
                }
                code !in 200..299 -> null
                else -> {
                    val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    parseCurrentlyPlaying(body)
                }
            }
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseCurrentlyPlaying(body: String): Track? {
        val data = JSONObject(body)
        if (data.isNull("item") || !data.has("item")) return null
        val item = data.getJSONObject("item")

        val artistsArr = item.optJSONArray("artists")
        val artist = if (artistsArr != null) {
            (0 until artistsArr.length()).joinToString(", ") { i -> artistsArr.getJSONObject(i).getString("name") }
        } else ""

        val album = item.optJSONObject("album")
        val images = album?.optJSONArray("images")
        val cover = images?.optJSONObject(0)?.optString("url")
        val coverSmall = images?.optJSONObject(2)?.optString("url") ?: cover

        return Track(
            id = item.getString("id"),
            name = item.getString("name"),
            artist = artist,
            album = album?.optString("name") ?: "",
            cover = cover,
            coverSmall = coverSmall,
            durationMs = item.optLong("duration_ms", 0L),
            progressMs = data.optLong("progress_ms", 0L),
            isPlaying = data.optBoolean("is_playing", false),
        )
    }
}

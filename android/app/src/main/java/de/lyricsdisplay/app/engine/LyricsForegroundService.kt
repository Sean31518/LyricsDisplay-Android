package de.lyricsdisplay.app.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import de.lyricsdisplay.app.MainActivity
import de.lyricsdisplay.app.widget.LyricsWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground Service = das Gegenstück zu server.js: hält den Poller am
 * Laufen, auch wenn die WebView im Hintergrund/geschlossen ist, damit das
 * Homescreen-Widget live bleibt. Startet bei Login, stoppt bei Logout. Reine
 * Lebenszyklus-/Notification-Hülle um [Engine] - der eigentliche State/
 * State-Verteilung läuft über [EngineBus].
 *
 * Die Notification hier ist bewusst minimal: Android verlangt für einen
 * Foreground Service zwingend eine Notification (startForeground() nimmt
 * keinen null-Wert), mehr soll sie nicht leisten - die eigentliche
 * Lyrics-Anzeige im Hintergrund übernimmt das Widget.
 */
class LyricsForegroundService : Service() {

    companion object {
        const val ACTION_STOP = "de.lyricsdisplay.app.action.STOP"
        private const val CHANNEL_ID = "lyrics_playback"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, LyricsForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, LyricsForegroundService::class.java).setAction(ACTION_STOP),
            )
        }
    }

    private lateinit var scope: CoroutineScope
    private var pollerCollectJob: Job? = null
    private var storeListener: LyricsStore.Listener? = null

    override fun onCreate() {
        super.onCreate()
        Engine.init(applicationContext)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        createNotificationChannel()
        registerLyricsStoreListener()
        collectPollerEvents()
    }

    /**
     * Wird ein gerade laufender Song in den Settings bearbeitet/gelöscht,
     * aktualisieren sich alle Anzeigen sofort - Pendant zu
     * lyricsStore.on('changed'/'deleted') in server.js.
     */
    private fun registerLyricsStoreListener() {
        val listener = object : LyricsStore.Listener {
            override fun onChanged(entry: LyricsEntry) {
                val track = EngineBus.currentTrack ?: return
                if (!Engine.lyricsStore.matchesTrack(entry.id, track.name, track.artist)) return
                val lyrics = entry.toLyricsResult()
                EngineBus.emitLyrics(lyrics, loading = false)
                updateWidget()
            }

            override fun onDeleted(entry: LyricsEntry) {
                val track = EngineBus.currentTrack ?: return
                if (!Engine.lyricsStore.matchesTrack(entry.id, track.name, track.artist)) return
                EngineBus.emitLyrics(LyricsResult(null, null, "none"), loading = false)
                updateWidget()
            }
        }
        storeListener = listener
        Engine.lyricsStore.addListener(listener)
    }

    private fun collectPollerEvents() {
        pollerCollectJob = scope.launch {
            Engine.poller.events.collect { event ->
                when (event) {
                    is PollerEvent.TrackChanged -> handleTrackChanged(event.track)
                    is PollerEvent.Progress -> handleProgress(event.progressMs)
                    PollerEvent.Stopped -> handleStopped()
                }
            }
        }
    }

    private suspend fun handleTrackChanged(track: Track) {
        EngineBus.emitTrack(track)
        EngineBus.emitLyrics(null, loading = true)
        updateNotification()
        updateWidget()

        val trackIdAtFetchStart = track.id
        val lyrics = withContext(Dispatchers.IO) {
            Engine.lyricsService.getLyrics(track.name, track.artist, track.album, track.durationMs)
        }
        // Ein neuerer Track kann diesen Fetch währenddessen überholt haben
        if (EngineBus.currentTrack?.id != trackIdAtFetchStart) return

        EngineBus.emitLyrics(lyrics, loading = false)
        updateNotification()
        updateWidget()
    }

    private fun handleProgress(progressMs: Long) {
        EngineBus.emitProgress(progressMs)
        updateWidget()
    }

    private fun handleStopped() {
        EngineBus.emitStopped()
        // Engine wird gerade (durch Logout) beendet - keine Idle-Notification
        // mehr nachschieben, die stopForeground() gerade erst entfernt hat
        if (!EngineBus.isEngineRunning) return
        updateNotification()
        updateWidget()
    }

    /** Startet Poller + Foreground-Notification. Aufgerufen nach erfolgreichem Login. */
    private fun startEngine() {
        startForeground(NOTIFICATION_ID, buildNotification())
        EngineBus.isEngineRunning = true
        if (!Engine.poller.isRunning()) Engine.poller.start(scope)
    }

    /**
     * Aufgerufen bei Logout: Poller stoppen, Foreground-Status beenden.
     * isEngineRunning MUSS vor poller.stop() auf false gesetzt werden: das
     * emittiert PollerEvent.Stopped, das der Collector oben asynchron
     * verarbeitet - handleStopped() prüft das Flag, um zu vermeiden, dass es
     * die gerade entfernte Notification erneut postet.
     */
    private fun stopEngine() {
        EngineBus.isEngineRunning = false
        Engine.poller.stop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEngine()
            stopSelf()
            return START_NOT_STICKY
        }
        if (Engine.auth.isAuthenticated()) startEngine()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        storeListener?.let { Engine.lyricsStore.removeListener(it) }
        pollerCollectJob?.cancel()
        EngineBus.isEngineRunning = false
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Lyrics-Wiedergabe",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Hält die Anzeige im Hintergrund aktiv"
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val track = EngineBus.currentTrack

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle("Lyrics Display")
            .setContentText(track?.let { "${it.name} – ${it.artist}" } ?: "Warte auf Wiedergabe…")
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun updateWidget() {
        LyricsWidgetProvider.updateAll(applicationContext)
    }
}

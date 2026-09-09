# 🎵 Lyrics Display

On-device Android-App für synchronisierte Spotify-Lyrics (Capacitor-Hybrid +
native Kotlin-Engine) — kein Server, keine Cloud, alles läuft direkt auf dem
Gerät. Verwandtes Projekt: [`../LyricsDisplay`](../LyricsDisplay) ist die
ursprüngliche self-hosted Docker/Browser-Variante (Server + WebSocket, für
TV/Browser-Nutzung ohne Handy).

> 🤖 **KI-Hinweis**: Diese App wurde mit Unterstützung von KI (Claude)
> entwickelt — Konzept, Design und Tests stammen von Sean Corcoran, ein
> Großteil des Codes wurde mit KI-Hilfe geschrieben. Der Quellcode ist
> vollständig einsehbar.

## Features

- **Synchronisierte Lyrics** — line-by-line Karaoke-Highlighting via LRCLib
- **Läuft komplett on-device** — kein eigener Server, kein Backend; die App
  spricht direkt mit Spotify (PKCE, eigene Client-ID pro Nutzer) und LRCLib
- **Hintergrund-Sync** — Foreground Service hält Track/Lyrics aktuell, auch
  wenn die App geschlossen ist
- **Homescreen-Widget** — zeigt die aktuelle Lyric-Zeile, Größe und
  Schriftgröße einstellbar
- **Lokale Lyrics-Verwaltung** — geladene Lyrics liegen persistent auf dem
  Gerät, ansehen/bearbeiten/löschen/manuell ergänzen (LRC oder Plain-Text)
- **Verschlüsselte Speicherung** — Spotify-Tokens und Client-ID via
  `EncryptedSharedPreferences`, nirgendwo sonst

## Build

Voraussetzungen: Node.js, Android SDK (Platform 36), JDK 21.

```bash
npm install
npx cap sync android
cd android
./gradlew assembleDebug
```

APK liegt danach unter `android/app/build/outputs/apk/debug/`.

## Architektur

- **UI** (`www/`): HTML/CSS/Vanilla-JS, läuft in der Capacitor-WebView
- **Engine** (`android/app/.../engine/`): Kotlin, Spotify-Polling,
  Token-Refresh, LRCLib-Fetch, lokaler Lyrics-Store
- **Bridge**: eigenes Capacitor-Plugin (`LyricsEnginePlugin`) verbindet
  WebView und Engine (Events statt WebSocket, Methodenaufrufe statt REST)
- **Foreground Service**: hält die Engine im Hintergrund am Laufen, füttert
  das Homescreen-Widget

## Datenschutz

- Keine eigene Server-Komponente, keine Analytics/Tracking
- Spotify-Tokens und Client-ID liegen verschlüsselt nur auf dem Gerät
  (`EncryptedSharedPreferences`)
- Lyrics werden lokal in den App-Daten gespeichert, jederzeit über
  Einstellungen einsehbar/löschbar
- Netzwerkverbindungen gehen ausschließlich an die Spotify-API und LRCLib

## Anti-Features (F-Droid)

- **NonFreeNet**: Die App kommuniziert mit der proprietären Spotify-Web-API
  (`user-read-currently-playing`), um den aktuell laufenden Song zu erkennen.
  Ohne einen (kostenlosen) Spotify-Account und eine selbst angelegte
  Spotify-Developer-App (Setup-Guide in der App) funktioniert die
  Kernfunktion nicht. Kein Play-Store-Login, keine Google Play Services,
  keine sonstigen proprietären Abhängigkeiten.

## Lizenz

MIT, siehe [LICENSE](LICENSE).

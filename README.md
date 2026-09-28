# sScrobbler

<p align="center">
  <img src="art/icon.png" width="128" height="128" alt="sScrobbler Icon" style="border-radius: 28px;" />
</p>

<p align="center">
  <b>A lightweight, precise Last.fm scrobbler for Android.</b><br>
  Built with 100% Kotlin, Jetpack Compose, and Material 3 Expressive.
</p>

<p align="center">
  <a href="https://github.com/sevennnoff/sScrobbler/releases/latest"><img src="https://img.shields.io/badge/Download-Latest%20Release-6750A4?style=for-the-badge&logo=android&logoColor=white" alt="Download APK" /></a>
</p>

---

## Why sScrobbler?

Most Android scrobblers suffer from two extremes: they either blast Last.fm the exact second a track hits 50% (scrobbling tracks you skipped right after), or run clunky web views with outdated Holo-era settings.

**sScrobbler** takes a clean, deterministic approach:
- **Eligible first, submitted on finalize:** Tracks reach `eligible` status when you listen to `min(50%, 4 min)` (minimum 30s). The scrobble is only dispatched to Last.fm when the track actually ends, repeats, or transitions to the next song.
- **Immediate Now Playing cleanup:** When you pause a song, Now Playing status in your Last.fm profile is wiped immediately rather than hanging for minutes.
- **Repeat loop detection:** Seamlessly detects single-track loops and repeated plays, logging each play as an independent scrobble.
- **Full offline resilience:** If your connection drops or you are on a restricted network/VPN, scrobbles queue in local SQLite storage and flush automatically once reachability returns.

---

## Features

- **Material 3 Expressive UI:** Custom OLED Dark Purple palette (`#141218` background, `#D0BCFF` accents), fluid spring-physics transitions, and zero sluggish tween fades.
- **Direct Last.fm History:** The history tab mirrors your actual Last.fm profile feed via `user.getRecentTracks` with instant image thumbnails and real-time scrobble status.
- **High-Resolution Artwork:** Extracts album covers directly from active `MediaSession` instances with automatic fallback to Last.fm's CDN.
- **Granular Player Control:** Automatically discovers active media players on your device (Spotify, Qobuz, YouTube Music, Apple Music, Symfonium, Poweramp, etc.) with per-app toggles and batch actions.
- **Pixel-Ready Adaptive Icon:** Official vector adaptive launcher icon + monochrome themed icon support for Android 13+.
- **Low Footprint Background Daemon:** Uses `NotificationListenerService` and a lightweight foreground service to prevent aggressive OS battery killers from killing scrobbles without draining your battery.

---

## Download

Get the signed release APK from GitHub Releases:

📥 **[Download Latest APK](https://github.com/sevennnoff/sScrobbler/releases/latest)**

> *Note for Pixel / Android 14+ users:* If Play Protect shows "Unrecognized developer", tap **More details → Install anyway**.

---

## Architecture & Stack

- **UI:** Jetpack Compose + Material 3 Expressive (Motion & Typography)
- **Concurrency:** Kotlin Coroutines & StateFlow
- **Storage:** Room Database (Offline Queue, Local Cache) + Jetpack DataStore Preferences
- **Media Tracking:** Android `MediaSessionManager` + `NotificationListenerService`
- **Networking:** OkHttp 4 + Kotlinx Serialization
- **Image Loading:** Coil Compose
- **Background Work:** Android WorkManager

---

## Building from Source

1. Clone the repository:
   ```bash
   git clone https://github.com/sevennnoff/sScrobbler.git
   cd sScrobbler
   ```

2. Create `local.properties` in the project root:
   ```properties
   sdk.dir=/path/to/android-sdk
   LASTFM_API_KEY=your_api_key_here
   LASTFM_API_SECRET=your_api_secret_here
   ```

3. Build the release APK:
   ```bash
   ./gradlew assembleRelease
   ```
   The APK will be located at `app/build/outputs/apk/release/app-release.apk`.

---

## License

MIT License. See [LICENSE](LICENSE) for details.

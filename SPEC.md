# ТЗ: Android Last.fm Scrobbler

## Цель

Сделать Android-приложение для Last.fm, которое **не отправляет скроббл сразу после достижения 50% трека**, а ждёт, пока пользователь реально закончит слушать трек.

Главная логика:

```text
трек набрал нужное время прослушивания
→ пометить как eligible

сменился трек / трек закончился / playback остановлен
→ finalize

если eligible
→ отправить scrobble в Last.fm
```

Пауза сама по себе не должна сразу отправлять скроббл.

---

# Стек

Полностью Kotlin:

- Kotlin
- Jetpack Compose
- Material 3 / Material 3 Expressive
- Coroutines + Flow
- NotificationListenerService
- MediaSessionManager
- MediaController
- Room
- DataStore
- WorkManager
- OkHttp
- Kotlin Serialization

Минимальный Android:

```text
minSdk 26
```

UI — полностью Jetpack Compose.

---

# Архитектура

Не переусложнять.

```text
app/
├── ui/
├── media/
├── scrobble/
├── lastfm/
├── database/
└── settings/
```

Основной поток:

```text
MediaSession
→ PlaybackTracker
→ ScrobbleEngine
→ Room Queue
→ Last.fm API
```

---

# Получение текущего трека

Использовать:

```text
NotificationListenerService
MediaSessionManager
MediaController.Callback
```

Приложению нужен доступ к уведомлениям, чтобы видеть активные MediaSession других приложений.

Отслеживать:

```text
onMetadataChanged()
onPlaybackStateChanged()
onSessionDestroyed()
```

Данные трека:

```kotlin
data class Track(
    val artist: String,
    val title: String,
    val album: String?,
    val albumArtist: String?,
    val durationMs: Long?,
    val sourcePackage: String
)
```

---

# Поддерживаемые приложения

Работать со всеми приложениями, которые нормально публикуют MediaSession:

- Qobuz
- Spotify
- Apple Music
- YouTube Music
- Symfonium
- Poweramp
- VLC
- другие Android-плееры

В настройках должен быть список приложений:

```text
Scrobble from:

Qobuz          ON
Spotify        ON
YouTube Music  ON
Telegram       OFF
YouTube        OFF
```

---

# PlaybackSession

Для текущего трека хранить:

```kotlin
data class PlaybackSession(
    val track: Track,
    val startedAtUnix: Long,
    var listenedMs: Long,
    var lastPlayStartedElapsedMs: Long?,
    var eligible: Boolean
)
```

Для измерения реально прослушанного времени использовать:

```kotlin
SystemClock.elapsedRealtime()
```

НЕ использовать просто:

```text
currentTime - trackStart
```

потому что паузы не должны считаться.

---

# Учёт времени

При:

```text
PAUSED → PLAYING
```

запомнить:

```kotlin
lastPlayStartedElapsedMs = SystemClock.elapsedRealtime()
```

При:

```text
PLAYING → PAUSED
PLAYING → STOPPED
смене трека
```

добавить:

```kotlin
listenedMs += now - lastPlayStartedElapsedMs
```

После чего:

```kotlin
lastPlayStartedElapsedMs = null
```

---

# Порог скроббла

Трек можно скробблить, если пользователь реально прослушал:

```text
min(50% длительности, 4 минуты)
```

Минимальная длина трека:

```text
30 секунд
```

Пример:

```text
3:00 → нужно 1:30
10:00 → нужно 4:00
```

Когда threshold достигнут:

```kotlin
eligible = true
```

НО API Last.fm в этот момент НЕ вызывать.

---

# Когда отправлять скроббл

## 1. Смена трека

```text
Track A
→
Track B
```

Выполнить:

```text
finalize Track A
```

Если Track A eligible:

```text
queue scrobble
```

---

## 2. PlaybackState = STOPPED

```text
PLAYING
→ STOPPED
```

Выполнить finalize.

---

## 3. MediaSession уничтожена

```text
onSessionDestroyed()
```

Finalize текущего трека.

---

## 4. Естественный конец трека

Если:

```text
position >= duration - 3000 ms
```

и playback больше не PLAYING:

```text
finalize
```

---

# Пауза

Обычная:

```text
PLAYING → PAUSED
```

не отправляет скроббл.

Добавить timeout:

```text
Finalize paused track after:
Never
5 min
15 min
30 min
1 hour
```

По умолчанию:

```text
30 min
```

Если трек 30 минут стоит на паузе:

```text
finalize()
```

---

# Skip

Если пользователь переключил трек раньше threshold:

```text
Track A
→ Track B
```

Track A получает:

```text
Skipped
```

и НЕ отправляется в Last.fm.

---

# Seek

Перемотка не должна увеличивать listened time.

Пример:

```text
0:00 → 0:30
seek → 3:30
3:30 → 4:00
```

Реально прослушано:

```text
60 секунд
```

Поэтому listened time измеряется системным monotonic clock, а не playback position.

---

# Повтор одного трека

Нельзя определять новый трек только через:

```text
artist + title
```

Потому что может быть:

```text
Track A
Track A
```

два раза подряд.

Считать новым прослушиванием, если:

- position резко сбросилась к началу;
- был конец предыдущего трека;
- изменился queue item id;
- MediaSession дала новые metadata.

Пример:

```text
old position > 70%
new position < 5 sec
```

→ новый play.

---

# Несколько MediaSession

Если одновременно есть:

```text
Spotify PAUSED
Qobuz PLAYING
YouTube PAUSED
```

считать только Qobuz.

Если несколько PLAYING одновременно:

- использовать последнюю активированную session;
- не допускать двойного счёта времени.

---

# Now Playing

При начале воспроизведения можно отправлять:

```text
track.updateNowPlaying
```

Это отдельная функция.

Настройка:

```text
Send Now Playing
ON
```

Скроббл всё равно отправляется только после finalize.

---

# Last.fm API

Использовать:

```text
https://ws.audioscrobbler.com/2.0/
```

Нужные методы:

```text
auth.getToken
auth.getSession
track.updateNowPlaying
track.scrobble
```

При скроббле отправлять:

```text
artist
track
album
albumArtist
duration
timestamp
api_key
sk
api_sig
```

`timestamp` — время начала прослушивания трека.

---

# Авторизация Last.fm

Для MVP использовать обычную Last.fm desktop auth схему.

Flow:

```text
1. приложение вызывает auth.getToken
2. открывает браузер:
   https://www.last.fm/api/auth/?api_key=...&token=...
3. пользователь разрешает доступ
4. приложение вызывает auth.getSession
5. получает session key
6. сохраняет session key локально
```

Не просить Last.fm пароль внутри приложения.

---

# API key / secret

Для первой версии можно хранить:

```text
LASTFM_API_KEY
LASTFM_API_SECRET
```

в:

```text
local.properties
→ BuildConfig
```

Например:

```properties
LASTFM_API_KEY=...
LASTFM_API_SECRET=...
```

В Gradle:

```kotlin
buildConfigField(
    "String",
    "LASTFM_API_KEY",
    "\"${localProperties["LASTFM_API_KEY"]}\""
)
```

То же самое для secret.

Важно:

**API secret внутри APK нельзя считать настоящим секретом.**

Его можно извлечь из приложения.

Для личного приложения / небольшого MVP это допустимо.

Если приложение станет публичным и популярным — потом можно сделать маленький backend для подписи Last.fm запросов.

На первом этапе backend НЕ делать.

---

# Подпись Last.fm

Создать:

```kotlin
object LastFmSigner
```

Он:

1. сортирует параметры по имени;
2. соединяет:

```text
key1value1key2value2...
```

3. добавляет API secret;
4. считает MD5.

Пример:

```kotlin
fun sign(
    params: Map<String, String>,
    secret: String
): String
```

---

# Хранение session key

Last.fm session key хранить локально.

Использовать:

```text
DataStore
```

Желательно шифровать через Android Keystore.

Никогда не логировать:

```text
API_SECRET
sessionKey
api_sig source data
```

---

# Offline queue

Если интернета нет:

```text
finalize
→ Room
→ pending_scrobbles
```

Модель:

```kotlin
@Entity
data class PendingScrobble(
    @PrimaryKey val id: String,
    val artist: String,
    val title: String,
    val album: String?,
    val albumArtist: String?,
    val durationSeconds: Int?,
    val timestamp: Long,
    val sourcePackage: String,
    val createdAt: Long
)
```

WorkManager должен отправлять очередь после появления интернета.

Last.fm позволяет отправлять batch скробблов.

Максимум:

```text
50
```

за один запрос.

---

# Защита от дублей

Создавать fingerprint:

```text
artist + title + timestamp
```

и считать SHA-256.

Не добавлять одинаковый fingerprint два раза в Room.

Особенно важно при:

```text
API timeout
process restart
WorkManager retry
```

---

# History

Хранить последние примерно:

```text
500
```

событий.

Типы:

```text
Scrobbled
Skipped
Pending
Failed
```

Пример:

```text
✓ National Anthem
  Lana Del Rey
  Scrobbled · 18:32

○ West Coast
  Lana Del Rey
  Skipped · 1:02 / 4:16

↑ Venice Bitch
  Pending
```

---

# UI

Jetpack Compose + Material 3 Expressive.

Нужны экраны:

```text
Onboarding
Now Playing
History
Settings
Apps
```

---

# Главный экран

Показывать:

```text
Artwork

National Anthem
Lana Del Rey
Born to Die

2:31 listened

Eligible
Waiting for track to end
```

Статусы:

```text
Listening
Paused
Eligible
Waiting for track to end
Scrobbled
Skipped
Offline
```

Важно визуально разделять:

```text
Eligible
```

и:

```text
Scrobbled
```

---

# Onboarding

## Экран 1

```text
Scrobble when you're actually done listening.
```

## Экран 2

Попросить:

```text
Notification access
```

Кнопка открывает системные настройки Notification Listener.

## Экран 3

```text
Connect Last.fm
```

Запустить browser auth.

После подключения:

```text
Connected as @username
```

---

# Settings

## Last.fm

```text
Account
@username

Reconnect
Disconnect
```

## Scrobbling

```text
Minimum listened percentage
50%

Maximum required listening time
4 min

Minimum track duration
30 sec

Paused track timeout
30 min

Send Now Playing
ON
```

## Apps

Список приложений с MediaSession.

## Battery

Показывать:

```text
Notification access
Granted / Missing

Battery optimization
Enabled / Disabled
```

---

# Material 3 Expressive

Использовать:

- MaterialTheme
- Dynamic Color
- edge-to-edge
- большие rounded shapes
- expressive motion
- нормальные переходы между состояниями
- dark/light theme
- Predictive Back
- Android-native дизайн

Не делать WebView UI.

---

# Background behaviour

Не использовать polling каждую секунду.

Основная работа должна быть event-driven:

```text
MediaController.Callback
```

Разрешён только небольшой внутренний timer для отображения listened time на UI.

---

# Process death

Pending scrobbles обязательно хранить в Room.

Если приложение умерло после finalize:

```text
scrobble остаётся в БД
```

и WorkManager позже отправит его.

---

# Логи

Debug:

```text
Session connected
Metadata changed
PLAYING
PAUSED
STOPPED
Eligible
Finalize
Queued
Scrobbled
```

Никогда не писать в лог:

```text
API secret
session key
```

---

# Тесты ScrobbleEngine

## 1

```text
PLAY 1 min
NEXT
```

если threshold не достигнут:

```text
Skipped
```

## 2

```text
PLAY до threshold
```

результат:

```text
Eligible
```

но Last.fm ещё не вызывается.

## 3

```text
PLAY до threshold
PAUSE
```

результат:

```text
Eligible
Not scrobbled
```

## 4

```text
PLAY до threshold
NEXT
```

результат:

```text
1 scrobble
```

## 5

```text
PLAY 30 sec
seek +3 min
PLAY 30 sec
NEXT
```

результат:

```text
60 sec listened
```

## 6

```text
Track A
Track A again
```

результат:

```text
2 отдельных playback session
```

## 7

```text
eligible
offline
NEXT
```

результат:

```text
Pending
```

после появления сети:

```text
Scrobbled
```

---

# MVP

Первая рабочая версия:

```text
Kotlin
Compose
Material 3 Expressive
MediaSession detection
Notification Listener
Last.fm login
Now Playing
Scrobble only on finalize
Offline queue
App blacklist
History
Settings
```

Не делать пока:

```text
ListenBrainz
Libre.fm
MusicBrainz
Spotify API
Widgets
Wear OS
Statistics
Metadata correction
```

---

# Главное правило

Никогда:

```text
50% достигнуто
→ scrobble
```

Только:

```text
50% достигнуто
→ eligible

прослушивание реально закончилось
→ scrobble
```

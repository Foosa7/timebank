# TimeBank

An Android app that turns **time into currency**. You *earn* money while your
phone's screen is off, and you *spend* money while apps are open. A foreground
service holds a wake lock so accounting keeps running with the screen off.

## The economy

Every few seconds the service checks what the phone is doing and applies a
per-minute rate to your balance:

| State | Condition | Effect |
|-------|-----------|--------|
| **Screen off** | screen off, nothing playing | `+ offRate × multiplier` /min |
| **Media** | YouTube / music actively playing | `+ mediaRate × multiplier` /min |
| **App open** | an app is in the foreground | `− appCost` /min |
| **Neutral** | home launcher or TimeBank itself | nothing |

- Rates are accounted by *elapsed time*, so accuracy doesn't depend on the tick rate.
- Balance never drops below `$0`.
- All four numbers are editable live on the **Settings** tab, and the multiplier
  boosts both earning rates.

> Note: **Media takes priority over App**. If music plays while you scroll an app,
> you'll be on the media rate, not the app cost. To make playback count as usage,
> set the media rate negative in Settings.

## Permissions it asks for

| Permission | Why | Where granted |
|------------|-----|---------------|
| **Usage access** (required) | see which app is foreground to charge it | Settings → Usage access |
| **Notification access** | read active media sessions (YouTube/music) | Settings → Notification access |
| **Ignore battery optimization** | keep earning reliably screen-off | system dialog |
| **Post notifications** (Android 13+) | show the live-balance notification | runtime prompt |
| Wake lock / foreground service | keep the tick loop alive | manifest only |

## Build & run

Requires Android Studio (Ladybug or newer) — it ships the JDK 17/21 that AGP needs
(system JDK 25 here is too new for a command-line Gradle build).

1. `File → Open` this `TimeBank` folder.
2. Let Gradle sync (downloads AGP 8.5.2, Kotlin 2.0.20, Compose BOM 2024.09).
3. Run on a device/emulator (min SDK 26, target 34).
4. On first launch: grant **Usage access** (and optionally **Notification access**
   for media), then tap **Start earning**.

CLI alternative once the wrapper is generated:
```
gradle wrapper            # one-time, if you have Gradle 8.9 on PATH
./gradlew installDebug
```

## Where things live

```
data/Economy.kt            enums, EconomyConfig, shared in-memory state
data/SettingsRepository.kt DataStore persistence (config + balance)
service/TimeBankService.kt foreground service: wake lock + tick loop + accounting
service/ForegroundAppMonitor.kt   UsageStats -> current foreground package
service/MediaMonitor.kt    MediaSessionManager -> is media playing
service/MediaNotificationListener.kt   enables media-session access
ui/HomeScreen.kt           balance, live rate, start/stop, permission cards
ui/SettingsScreen.kt       rate/cost/multiplier sliders, reset balance
```

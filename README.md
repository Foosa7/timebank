# TimeBank

An Android app that turns **time into currency**. You *earn* money while your
phone's screen is off, and you *spend* money while apps are open. Run out, and
TimeBank locks you out of apps until you earn more. A foreground service holds a
wake lock so accounting keeps running with the screen off.

## The economy

Every 3 seconds the service checks what the phone is doing and applies a
per-minute rate to your balance:

| State | Condition | Effect |
|-------|-----------|--------|
| **Screen off** | screen off, nothing playing | `+ offRate × multiplier` /min |
| **Media** | YouTube / music actively playing | `+ mediaRate × multiplier` /min |
| **App open** | an app is in the foreground | `− appCost` /min |
| **Neutral** | home launcher or TimeBank itself | nothing |

- Rates are accounted by *elapsed time*, so accuracy doesn't depend on the tick rate.
- Balance never drops below `$0`.
- The multiplier boosts both earning rates, but never the app cost.
- Exactly one state applies per tick, and **the order above is the precedence
  order**: media wins over screen-off, which wins over neutral, which wins over app.

> Note: **Media takes priority over App**. If music plays while you scroll an app,
> you'll be on the media rate, not the app cost. To make playback count as usage,
> set the media rate negative in Settings.

## Going broke

When your balance hits `$0` while an app is in the foreground, TimeBank raises a
full-screen overlay that swallows touches so the app underneath is unusable. It
offers one way out — a **Go to home screen** button, which lands you in the
Neutral state where nothing drains. Turn the screen off to start earning again.

The lock needs the *Display over other apps* permission; without it the overlay
silently does nothing and you keep drifting along at `$0`. You can turn the whole
behaviour off with the **Lock apps at $0** switch in Settings.

## Settings

All five knobs take effect immediately on the running service and are persisted
to DataStore:

| Setting | Range | Default |
|---------|-------|---------|
| Screen-off earning | `$0` – `$120` /min | `$10` |
| Media rate | `−$60` – `$60` /min | `$3` |
| App-open cost | `$0` – `$120` /min | `$30` |
| Earn multiplier | `0.1x` – `10x` | `1x` |
| Lock apps at $0 | on / off | on |

There's also a **Reset balance to $0** button. The live balance is checkpointed
to disk every ~12 seconds, whenever the screen turns on or off, and on stop — so
a crash costs you a few seconds of earnings at most.

## Permissions it asks for

Home shows a card per permission with its current state and a shortcut to the
right system screen. Only usage access is mandatory — **Start earning** stays
disabled until it's granted.

| Permission | Why | Where granted |
|------------|-----|---------------|
| **Usage access** (required) | see which app is foreground to charge it | Settings → Usage access |
| **Notification access** | read active media sessions (YouTube/music) | Settings → Notification access |
| **Display over other apps** | show the lock overlay when you hit `$0` | Settings → Display over other apps |
| **Ignore battery optimization** | keep earning reliably screen-off | system dialog |
| **Post notifications** (Android 13+) | show the live-balance notification | runtime prompt |
| Wake lock / foreground service | keep the tick loop alive | manifest only |

Without notification access, media playback simply never registers and you're
charged the normal app rate instead.

## Build & run

Open the project in **Android Studio** and run it, or build from the command line with
the Android SDK plus a **JDK 17 or 21** — note that a JRE is not enough, Gradle needs a
`javac`, and a newer system JDK (25, for instance) is not accepted by AGP.

```
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug     # APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug      # build + install onto a running device/emulator
```

The Gradle wrapper is committed, so there is no bootstrap step. Point `sdk.dir` in
`local.properties` at your SDK if `ANDROID_HOME` isn't set. You'll need SDK platform 37 and
build-tools 37 installed (`sdkmanager "platforms;android-37.0" "build-tools;37.0.0"`).

Toolchain, all pinned in `build.gradle.kts` / `app/build.gradle.kts`:

- Android Gradle Plugin 9.3.1 on Gradle 9.6.1 — AGP 9 has Kotlin built in, so
  only the Compose compiler plugin (2.4.10) is applied separately
- compileSdk / targetSdk 37, minSdk 26, Java 17 bytecode
- Compose artifacts pinned individually (UI 1.11.4, Material3 1.4.0) — no BOM

On first launch: grant **Usage access** (plus **Notification access** for media
and **Display over other apps** for the lock), then tap **Start earning**.

## Where things live

Everything runs in a single process, which is what lets the service and the UI
share one in-memory `Economy` object instead of talking over IPC.

```
TimeBankApp.kt             seeds balance from disk, mirrors stored config into Economy
MainActivity.kt            two-tab Compose shell (Home / Settings)

data/Economy.kt            enums, EconomyConfig, shared StateFlow state
data/SettingsRepository.kt DataStore persistence (config + balance)
data/AppGraph.kt           one-field service locator for the repository

service/TimeBankService.kt foreground service: wake lock + tick loop + accounting
service/ForegroundAppMonitor.kt   UsageStats -> current foreground package
service/MediaMonitor.kt    MediaSessionManager -> is media playing
service/MediaNotificationListener.kt   enables media-session access
service/LockOverlay.kt     the full-screen "you're broke" window

ui/HomeScreen.kt           balance, live rate, start/stop, permission cards
ui/SettingsScreen.kt       rate/cost/multiplier sliders, lock switch, reset balance
ui/Support.kt              permission checks + re-check-on-resume helper
util/Format.kt             money / rate / state-label formatting
```

A settings change is applied to the shared state immediately and written to DataStore
in the background, so sliders take effect on the next tick with no lag.

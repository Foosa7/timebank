# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

TimeBank is a single-module Android app (Kotlin + Jetpack Compose) that turns time into
currency: the balance grows while the screen is off and drains while apps are in the
foreground. See `README.md` for the user-facing rules of the economy.

## Build

There is **no test source set** — `app/src/main/` is the only one. Nothing to run for
`test`/`androidTest` unless you add it.

The toolchain here is deliberately bleeding-edge and does not match the README's
"AGP 8.5.2 / Kotlin 2.0.20 / Compose BOM" text (that section is stale — the actual
versions live in `build.gradle.kts` and `app/build.gradle.kts`):

- AGP 9.3.1 (Kotlin is built into AGP 9 — there is **no** `org.jetbrains.kotlin.android`
  plugin; only `org.jetbrains.kotlin.plugin.compose` is applied separately)
- Gradle 9.6.1 wrapper, compileSdk/targetSdk 37, minSdk 26, Java 17 bytecode
- Compose artifacts are pinned individually (1.11.x / Material3 1.4.0), no BOM

### Building on this machine (verified working)

The SDK lives at `~/android-sdk` (**not** `~/Android/Sdk`) and `local.properties` already
points there. The catch is Java: every JDK under `/usr/lib/jvm` is a **JRE with no `javac`**,
so Gradle fails with "Toolchain installation ... does not provide the required capabilities:
[JAVA_COMPILER]". A working Temurin 21 JDK is unpacked in the session scratchpad. Build with:

```
export JAVA_HOME=<scratchpad>/jdk/jdk-21.0.12.1+1
export ANDROID_HOME=$HOME/android-sdk
./gradlew assembleDebug          # ~70s cold, APK -> app/build/outputs/apk/debug/
```

If the scratchpad JDK is gone, re-fetch one (`curl -fsSL
https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse`) — do
not point `JAVA_HOME` at `/usr/lib/jvm/*`, none of them can compile.

SDK platform 37.0 and build-tools 37.0.0 are installed (the platform directory is
`android-37.0`, matching `compileSdk = 37`). Two deprecation warnings — `MOVE_TO_FOREGROUND`
and `unsafeCheckOpNoThrow` — are expected and not regressions.

### Running it

An x86_64 AVD named `fugazi` (API 35, software GPU) exists:

```
$ANDROID_HOME/emulator/emulator -avd fugazi -gpu swiftshader_indirect &
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

TimeBank's four permissions are all special-access ones that a fresh install won't have.
Grant them from the shell instead of clicking through system Settings:

```
adb shell appops set com.timebank.app GET_USAGE_STATS allow
adb shell appops set com.timebank.app SYSTEM_ALERT_WINDOW allow
adb shell pm grant com.timebank.app android.permission.POST_NOTIFICATIONS
adb shell cmd notification allow_listener com.timebank.app/com.timebank.app.service.MediaNotificationListener
adb shell dumpsys deviceidle whitelist +com.timebank.app
```

To exercise the accounting loop without waiting: `adb shell input keyevent KEYCODE_POWER`
to sleep the screen, wait, then power + `KEYCODE_MENU` to wake and dismiss the keyguard.
30s of screen-off at the default rate yields exactly $5.00.

## Architecture

Everything runs in **one process**, and that is the load-bearing assumption of the design.

`data/Economy.kt` — an `object` holding `MutableStateFlow`s (`balance`, `activity`,
`ratePerMin`, `currentPackage`, `serviceRunning`, `locked`, `config`). This is the single
source of truth: the service writes it, Compose screens `collectAsState()` it. There is no
IPC, no binding, no ViewModel. New shared state belongs here.

`service/TimeBankService.kt` — the heart. A `specialUse` foreground service that holds a
partial wake lock and runs a coroutine tick loop (`TICK_MS = 3s`). Each `tick()`:

1. Reads `powerManager.isInteractive`, `MediaMonitor.isMediaPlaying`, and
   `ForegroundAppMonitor.currentForegroundPackage()`.
2. Resolves exactly one `ActivityState` in a `when` whose **order encodes the economy's
   precedence**: media > screen-off > neutral (launcher / TimeBank itself) > app. Changing
   the branch order changes the product's rules — the README documents media beating app.
3. Costs resolve as `cfg.costFor(pkg)` (per-app override, else `appCostPerMin`) plus
   `privateSurchargePerMin` when that same foreground package appears in
   `Economy.privatePackages` — the surcharge is deliberately keyed on the foreground package so
   a backgrounded browser with incognito tabs can't tax an unrelated app.
4. Applies `rate × deltaMin` where `deltaMin` comes from `SystemClock.elapsedRealtime()`
   deltas, so accounting stays correct if ticks are delayed or the rate changes. Never
   replace this with "rate × TICK_MS".
5. Clamps the balance at `0.0` and raises/hides `LockOverlay` when broke while an app is open.

Persistence is deliberately lazy: the balance is written every 4th tick (~12s), on
SCREEN_ON/SCREEN_OFF broadcasts, and on stop. `Economy.balance` is the live value; DataStore
is a checkpoint.

`data/SettingsRepository.kt` + `data/AppGraph.kt` — DataStore-Preferences persistence,
reached through a `lateinit` service-locator `object`. `AppGraph.settings` is assigned in
`TimeBankApp.onCreate()`, which also seeds `Economy.balance` from disk and mirrors
`configFlow` into `Economy.config`. `SettingsScreen.apply()` deliberately does **both** halves of a config edit: it sets
`Economy.config.value` inline so the running service picks the change up on the very next
tick, then persists via `saveConfig` on `Dispatchers.IO`. The DataStore round-trip through
`configFlow` re-sets the same value moments later, which is redundant but harmless — keep
both writes, since dropping the inline one adds visible lag to every slider drag.

`service/ForegroundAppMonitor.kt` — UsageStats has no push callback, so it polls a 12s event
window each tick and caches the last `MOVE_TO_FOREGROUND` package. It swallows exceptions
and returns the stale value when the permission is missing, so a null/stale package is normal,
not a bug.

`service/MediaMonitor.kt` / `MediaNotificationListener.kt` — the listener does two jobs off one
permission: it is the `ComponentName` that makes `MediaSessionManager.getActiveSessions()` legal,
**and** it watches for the ongoing "private tabs open" notification browsers post, publishing the
owning packages to `Economy.privatePackages`. Detection is substring matching over channel id,
tag, title and text (`incognito`, `inprivate`, `private tab`, `private browsing`) rather than a
browser allow-list, so any browser works. Two limits worth remembering: the notification means
private tabs *exist*, not that one is on screen, and a browser whose notifications the user has
blocked never posts it, so the surcharge silently never fires.

`service/LockOverlay.kt` — a hand-built `TYPE_APPLICATION_OVERLAY` View (not Compose), posted
to the main-thread `Handler` because the service ticks on `Dispatchers.Default`. Silently
no-ops without `SYSTEM_ALERT_WINDOW`.

`ui/` — two screens (`HomeScreen`, `SettingsScreen`) switched by an index in
`MainActivity`, no navigation library. Permission state is not observable, so
`ui/Support.kt` exposes `rememberResumeTick()` plus plain `has*` checks; UI keys permission
reads on that tick to re-check after the user returns from a system Settings screen. Any new
permission needs a `has*` predicate there and a `PermissionCard` wired to the same pattern.

## Conventions

- Nav icons and status glyphs are emoji `Text()` composables, not vector/material icons —
  no icon dependency is declared.
- All money formatting goes through `util/Format.kt` (`formatMoney`, `formatRate`,
  `stateLabel`); don't inline `String.format` for currency.
- Preferences DataStore has no map type, so `appOverrides` is persisted as one
  `"pkg=cost;pkg=cost"` string via `encodeOverrides`/`decodeOverrides`; package names contain
  neither delimiter, so no JSON dependency is needed.
- Rates are always **per minute** and signed at the point of use (earn positive, app cost
  negated in the `when`). `earnMultiplier` boosts earning rates only, never `appCostPerMin`.

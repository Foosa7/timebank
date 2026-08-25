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

### Building (two machines, both verified working)

This project gets built from two boxes and they differ only in where the SDK lives, so
check which one you are on before trusting a path:

| | SDK | `local.properties` | AVD |
|---|---|---|---|
| **this box** | `~/android-sdk` | present, points there | `fugazi` (API 35), `-gpu swiftshader_indirect` |
| **the Fedora laptop** | `~/Android/Sdk` | none — export `ANDROID_HOME` | `timebank` (API 35, `google_apis`), `-gpu host` only |

What is the same on both is Java: everything under `/usr/lib/jvm` is a **JRE with no
`javac`** (including `java-25-openjdk`), so Gradle fails with "Toolchain installation ...
does not provide the required capabilities: [JAVA_COMPILER]". Unpack a Temurin 21 JDK into
the session scratchpad and build with:

```
export JAVA_HOME=<scratchpad>/jdk/jdk-21.0.12.1+1
export ANDROID_HOME=$HOME/android-sdk    # ~/Android/Sdk on the Fedora laptop
./gradlew assembleDebug          # ~40s warm / ~70s cold, APK -> app/build/outputs/apk/debug/
```

If the scratchpad JDK is gone, re-fetch one (`curl -fsSL
https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse`) — do
not point `JAVA_HOME` at `/usr/lib/jvm/*`, none of them can compile.

SDK platform 37.0 and build-tools 37.0.0 are installed (the platform directory is
`android-37.0`, matching `compileSdk = 37`). Two deprecation warnings — `MOVE_TO_FOREGROUND`
and `unsafeCheckOpNoThrow` — are expected and not regressions.

### Running it

The GPU flag is per-machine and is the one thing that will waste an afternoon. On **this
box**, `fugazi` boots headless with the usual software renderer:

```
$ANDROID_HOME/emulator/emulator -avd fugazi \
  -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -memory 2048
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Expect a "System UI isn't responding" ANR dialog on first boot under swiftshader — tap
**Wait** and carry on, it is emulator slowness, not the app.

On **the Fedora laptop** the same flags crash. Its AVD `timebank` **only boots with
`-gpu host`** — this is the load-bearing flag there:

```
$ANDROID_HOME/emulator/emulator -avd timebank \
  -no-window -no-audio -no-boot-anim -no-snapshot -read-only -gpu host -memory 2048
```

`-gpu off`, `-gpu guest` and `-gpu swiftshader[_indirect]` **all SIGSEGV** about six
seconds into guest boot, inside the host GL/EGL path, with nothing useful in the emulator
log — it just stops. That is backwards from the usual headless advice, so don't "fix" a
crash by switching to a software renderer. The only counter-intuitive part is that
`-no-window` still initialises host GL, so the real Intel GPU path is the working one.

Two debugging notes that cost time:

- The emulator log shows **no error** on this crash. `coredumpctl list` is where the
  `SIGSEGV` actually shows up — check there before theorising about memory.
- Launch it with the Bash tool's `run_in_background`, not `nohup ... &`; a backgrounded
  `nohup` dies when its foreground tool call returns.
- `pkill -f "emulator -avd timebank"` also matches the shell running it, killing your own
  command (exit 144). Match the binary path instead:
  `pgrep -f "^$ANDROID_HOME/emulator/qemu"`.

TimeBank's four permissions are all special-access ones that a fresh install won't have.
Grant them from the shell instead of clicking through system Settings:

```
adb shell appops set com.timebank.app GET_USAGE_STATS allow
adb shell appops set com.timebank.app SYSTEM_ALERT_WINDOW allow
adb shell pm grant com.timebank.app android.permission.POST_NOTIFICATIONS
adb shell cmd notification allow_listener com.timebank.app/com.timebank.app.service.MediaNotificationListener
adb shell dumpsys deviceidle whitelist +com.timebank.app
```

`TimeBankService` is **not exported**, so `am start-foreground-service` fails with
"Requires permission not exported from uid". Start it by tapping *Start earning* in the UI —
headless, find the button with `adb shell uiautomator dump /sdcard/ui.xml` and tap the
centre of its `bounds`.

Debug builds seed a fresh install with `TimeBankApp.DEBUG_START_BALANCE` ($100) so the
cover charge and the drain can be exercised without earning a float first; release builds
still start at $0. This is why `buildFeatures { buildConfig = true }` is set — `BuildConfig`
is not generated by default under AGP 8+/9. `readBalanceOnce()` returns `Double?` precisely
so a stored $0 is distinguishable from "never saved"; don't collapse it back to `?: 0.0`.

Two emulator traps, both of which look like app bugs and are not:

- **`monkey -p <pkg> -c ... 1` injects a random event** as well as launching. One of those
  landed on the cover gate's *Enter* button and silently spent $5, which reads exactly like
  the gate failing to block. Use `am start -n <pkg>/<activity>` to launch a test app.
- **`com.android.settings` suppresses overlays** (`setHideOverlayWindows`), so any
  `TYPE_APPLICATION_OVERLAY` is added but never composited over it — `dumpsys window` shows
  `mHasSurface=true` with `Surface: shown=false` and `isReadyForDisplay()=false`. Neither
  the lock nor the cover gate can be tested against Settings; use an ordinary app.

To exercise the accounting loop without waiting: `adb shell input keyevent KEYCODE_POWER`
to sleep the screen, wait, then power + `KEYCODE_MENU` to wake and dismiss the keyguard.
30s of screen-off at the default rate ($1/min) yields exactly $0.50.

Defaults only apply to a **fresh DataStore**. An emulator that ran an older build keeps its
saved `EconomyConfig` across `install -r`, so a rate change in `Economy.kt` appears to have
no effect — 30s still paid $5.50 at the old $10/min. `adb shell pm clear com.timebank.app`
first (then re-grant the five permissions above, `pm clear` drops them too).

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
   precedence**: media > screen-off > neutral (launcher / TimeBank itself) > cover gate >
   app. Changing the branch order changes the product's rules — the README documents media
   beating app. `NEUTRAL` earns `idleRatePerMin`, so "not in an app" is not the same as
   "earning nothing".
3. Costs resolve as `cfg.costFor(pkg)` (per-app override, else `appCostPerMin`) plus
   `privateSurchargePerMin` when that same foreground package appears in
   `Economy.privatePackages` — the surcharge is deliberately keyed on the foreground package so
   a backgrounded browser with incognito tabs can't tax an unrelated app.
4. Applies `rate × deltaMin` where `deltaMin` comes from `SystemClock.elapsedRealtime()`
   deltas, so accounting stays correct if ticks are delayed or the rate changes. Never
   replace this with "rate × TICK_MS".
5. Clamps the balance at `0.0` and raises/hides `LockOverlay` when broke while an app is open.

`EconomyConfig.surgeHours` is happy hour's mirror and shares its `HourWindow` type, its
encoding and its Settings composable (`ScheduleSection`) — they are the same control, so
they are not two that can drift apart. The pricing rule is the pair `min` then `max`:
happy hour **caps** a price, surge **floors** it, and because the floor is applied last
**surge wins any overlap**. Neither can move a price the wrong way, so a hand-priced app
keeps its rate unless the window is genuinely cheaper or dearer than it is.

Happy hours (`EconomyConfig.happyHours`) are whole local hours with an **exclusive end**,
so 12..13 is the single hour after noon and a window may wrap past midnight; equal ends
cover nothing, which parks a row without deleting it. They are re-read from `LocalTime.now()`
on every tick rather than scheduled, so a window boundary re-prices a session already in
progress and there are no alarms to keep in sync. Happy hour is a **cap, never a markup** —
`costFor(pkg, happy)` and `coverCharge(happy)` take `minOf` against the normal price, so an
app already cheaper via `appOverrides` keeps its own rate and the window can only ever lower
a bill. `admit()` charges `coverFor(pkg, happyHourActive, surgeActive)` so the gate can never quote
one price and take another.

Cover charges are **opt-in per app** (`coverCharges`, a package -> money map sharing the
overrides encoding) and the map is empty by default. `coverFor` returns 0 for an app that
isn't listed and **returns early before the schedule is applied** — without that guard a
surge window's floor would conjure a `$15` gate onto every app on the phone.

`sleepHours` is a third schedule that moves the *earning* side: `offRateAt(now)` returns
`sleepOffRatePerMin` inside it. It is the only schedule that touches an earn rate, so it
is applied in the `SCREEN_OFF` branch rather than through the cap/floor pair.

`service/CoverChargeOverlay.kt` — the cover charge is a one-off fee per *visit* to an app,
taken before per-minute billing starts. The gate blocks the app until the user pays in or
backs out; `admittedPackage` in the service is the whole of the "has paid" state, and it is
cleared the moment the state stops being `APP`/`COVER`, which is what makes re-entry cost
again. **Nothing is deducted in the overlay** — only `TimeBankService.admit()` spends, and
it checkpoints the balance immediately rather than waiting for the lazy write, since losing
a one-off charge hands out a free visit. A gated app is never also "broke" (the states are
mutually exclusive), so only one overlay is ever up; when the balance won't cover entry the
gate drops the Enter button instead of showing an unaffordable one. `coverChargePerApp = 0`
disables the gate entirely and restores the old straight-to-`APP` behaviour.

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
- Preferences DataStore has no map or list type, so `appOverrides` is persisted as one
  `"pkg=cost;pkg=cost"` string via `encodeOverrides`/`decodeOverrides`, and `happyHours` as
  `"12-13;19-20"` via `encodeHappyHours`/`decodeHappyHours`; package names contain neither
  delimiter, so no JSON dependency is needed. Both decode a stored **blank** to an empty
  collection rather than to the defaults — otherwise deleting your last happy-hour window
  would silently resurrect 12–13 and 19–20 on the next read.
- Rates are always **per minute** and signed at the point of use (earn positive, app cost
  negated in the `when`). `earnMultiplier` boosts earning rates only, never `appCostPerMin`.

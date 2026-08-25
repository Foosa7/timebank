# TimeBank

An Android app that turns **time into currency**. You *earn* money while your
phone's screen is off, you pay a **cover charge** to open an app, and you *spend*
by the minute while it's in front of you. Prices move with the clock — cheaper in
**happy hours**, dearer in **surge hours**. Run out, and TimeBank locks you out of
apps until you earn more. A foreground service holds a wake lock so accounting
keeps running with the screen off.

## The economy

Every 3 seconds the service checks what the phone is doing and applies a
per-minute rate to your balance:

| State | Condition | Effect |
|-------|-----------|--------|
| **Media** | YouTube / music actively playing | `+ mediaRate × multiplier` /min |
| **Screen off** | screen off, nothing playing | `+ offRate × multiplier` /min |
| **Neutral** | home launcher or TimeBank itself | `+ idleRate × multiplier` /min |
| **Cover** | an app is open but hasn't paid its cover charge | nothing — the gate is up |
| **App open** | an app is in the foreground and paid in | `− appCost` /min (or that app's own price) |

- Rates are accounted by *elapsed time*, so accuracy doesn't depend on the tick rate.
- Balance never drops below `$0`.
- The multiplier boosts every earning rate, but never the app cost.
- Exactly one state applies per tick, and **the order above is the precedence order**:
  media beats screen-off, which beats neutral, which beats the cover gate, which beats
  a metered app.
- Neutral earns too, at its own lower rate — being on the home screen is not the same
  as earning nothing.

### Charging some apps more

Any app can be given its own per-minute price on the Settings tab — put Instagram at
`$30/min` and leave everything else on the default. Apps you haven't priced are charged
the normal app cost.

On top of that, browsers can be charged a **private-browsing surcharge** while they have
incognito / InPrivate tabs open. TimeBank spots this from the ongoing "close your private
tabs" notification that Chrome, Edge and Firefox post, so it needs no extra permission
beyond the notification access it already asks for, and works with any browser.

Two things that signal is honest about:

- It means private tabs **exist**, not that you're looking at one. Park an InPrivate tab in
  the background and the surcharge keeps applying to that browser until you close it.
- If you've blocked that browser's notifications in Android settings, it never posts the
  notification and the surcharge never fires.

> Note: **Media takes priority over App**. If music plays while you scroll an app,
> you'll be on the media rate, not the app cost. To make playback count as usage,
> set the media rate negative in Settings.

### The cover charge

Opening an app costs a one-off fee before the meter starts — a club's cover, not a
subscription. A full-screen gate names the app, the entry price and the rate that
follows, and it **blocks**: the app underneath stays unusable until you pay in or back
out.

```
🎟  Cover charge
        Contacts
  Entry      $5.00
  Then      $11.00/min
  Balance $100.00 → $95.00
     [ Enter ($5.00) ]
     [   No thanks   ]
```

Metering alone never prices the *decision* to open something — a reflex glance costs
almost nothing when it's billed by the second. The cover puts a number at the threshold,
which is the one place intervention reliably works. Backing out costs nothing.

- **Per visit, not per day.** Leaving the app ends the visit; coming back pays again.
- **No grace window yet.** Switching out to answer a text and returning 20 seconds later
  *does* re-charge. The design below argues a ~60s same-package grace period is needed
  before this feels fair rather than arbitrary — it isn't built.
- **Can't-afford is handled at the door.** When the balance won't cover entry, the gate
  drops the Enter button instead of quoting a price you can't pay, which is kinder than
  being walled mid-scroll at `$0`.
- Set the cover to **`$0`** to disable the gate entirely.

### Happy hours and surge hours

The day carries a price schedule, in whole local hours:

| | Default windows | App cost | Cover |
|---|---|---|---|
| 🍺 **Happy hours** | 12:00–13:00, 19:00–20:00 | `$4`/min | `$2` |
| ⚡ **Surge hours** | 00:00–06:00, 07:00–09:00 | `$25`/min | `$15` |

Happy hour **caps** a price; surge **floors** it. Neither can move a price the wrong way,
so an app you've priced by hand keeps its own rate unless the window is genuinely cheaper
or dearer than it is. The floor is applied after the cap, so **surge wins any overlap**.

Windows are re-read every tick rather than scheduled, so crossing a boundary re-prices a
session already in progress — walk into 13:00 mid-scroll and the rate changes under you.
Both schedules are fully editable: rate and cover sliders, plus add/remove windows on
stepped hour sliders. The end hour is **exclusive**, which is why it runs to 24 —
`22 → 24` is the last two hours of the day, and a window whose ends are equal is parked
rather than deleted.

Surge defaults to the two times the economy is easiest to fritter away: the small hours,
when you should be asleep and the screen-off rate is quietly paying you, and the morning,
when a night of earning has left the balance at its highest and cheapest-feeling.

## Watching the balance

The balance is the notification's **small icon**, drawn as a bitmap rather than a fixed
glyph, so the number sits in the status-bar strip and is readable without pulling the shade
down. It's abbreviated to fit a 24dp square (`$480`, `$1.2k`, `$128k`) and drawn as an alpha
mask, which is what lets the system tint it correctly on both light and dark status bars.
The app draws edge-to-edge, so `TimeBankTheme` also sets `isAppearanceLightStatusBars` from
the current theme — without it the light theme renders white-on-white and the balance (and
the system clock along with it) disappears.

The `$` is kept even at that size: a bare `83` in the status bar reads as a notification
count or a stray number, and the renderer shrinks to fit, so the extra glyph costs a little
legibility rather than any meaning.

Pulling the shade down gives the full figure plus the current state and signed rate. Both
refresh on the same 3s tick as the accounting itself, and the icon bitmap is only redrawn
when the abbreviated number actually changes.

## Going broke

When your balance hits `$0` while an app is in the foreground, TimeBank raises a
full-screen overlay that swallows touches so the app underneath is unusable. It
offers one way out — a **Go to home screen** button, which lands you in the
Neutral state where nothing drains. Turn the screen off to start earning again.

With the cover charge on, you now usually meet the shortfall at the door instead: the gate
checks affordability before you're in, so the `$0` lock is what catches you when the balance
runs out *during* a session rather than before one. Both use the same permission and only
one is ever on screen.

The lock needs the *Display over other apps* permission; without it the overlay
silently does nothing and you keep drifting along at `$0`. You can turn the whole
behaviour off with the **Lock apps at $0** switch in Settings.

## Nudge design

Notes on *why* the economy is shaped the way it is, and where it's going. Everything
below is design rationale — see the status table at the end for what's actually built.

The thing being maximised is not "less screen time". It is **less screen time
integrated over the months the app stays installed**. A harsh configuration that wins
week one and gets deleted in week three scores worse than a mild one that runs quietly
for two years. Every idea here gets judged against *does this survive month six?*

### Why a meter and not a ticket

Two ways to charge for an app: deduct continuously while it's open (a meter), or take a
fixed payment up front for a fixed block and close the app when it expires (a ticket).
TimeBank meters. As a *price system* the meter wins on six counts:

- **The marginal price is right.** With a meter the next minute always costs something.
  Inside a prepaid block the next minute costs *zero* — the price is highest at the door,
  where you're still deliberate, and vanishes once you're captured. That's backwards.
- **Proportionality.** Ten seconds costs ten seconds' worth, not five minutes' worth.
- **No dead weight.** Unused prepaid time is currency that bought nothing, and burnt
  blocks generate exactly the resentment that gets the app disabled.
- **Nothing to game.** Blocks reward cramming, batching and timing your entry to the
  boundary. You end up optimising against your own tool.
- **It composes.** Every rule in the app is a signed per-minute rate, so per-app prices,
  the incognito surcharge, the media exemption and the earn multiplier all live in one
  unit and simply add. A block price has no clean way to express "20% more while
  incognito tabs are open", or what happens when media starts mid-block.
- **It's nearly stateless.** `rate × deltaMin` off `elapsedRealtime()` stays correct when
  ticks are delayed, the rate changes mid-use, or the process restarts. Blocks need a
  state machine — start time, remaining, config change mid-block, reboot — and every
  entry in that list is a bug you have to persist and reconcile.

The meter's one real flaw is that it's **quiet**. Each 3s tick costs a fraction of a
cent, there's no moment where you decide anything, and the cost only becomes perceptible
in aggregate, long after the behaviour it was meant to influence. It also prices
*duration* when the actual pathology is *frequency* — the forty-times-a-day reflexive
check is nearly free under a meter.

The fix is to add a signal, not to replace the pricing:

> **Cover charge.** A fixed toll at the moment an app comes to the foreground, *on top*
> of the meter — and no auto-close, ever. The toll creates a decision point at the
> threshold, which is the one place intervention reliably works; it taxes re-entry, so it
> hits the checking loop the meter can't; it makes price legible ("Instagram costs `$5` to
> open" is a number you can hold in your head, `$0.30/min` isn't); and it moves the
> affordability check to the door, which is far kinder than being walled mid-scroll at
> `$0`. Metering on top keeps the duration signal and prevents the sunk-cost floor a
> prepaid block creates — there's no paid window to "use up".
>
> Needs a **grace window**: switching out to answer a text and coming back 20 seconds
> later must not re-charge, or the toll reads as arbitrary and buggy. Same package within
> ~60s, no new charge.

**Built in 1.3**, with one piece of the above missing: there is no grace window, so every
re-entry is charged. That is the known rough edge to watch — if the toll starts reading as
arbitrary, this is why.

Auto-close is rejected outright. It fires mid-sentence, mid-video, mid-checkout, and hard
interrupts are the most reliable way an app like this gets uninstalled. It also breaks the
metaphor — an economy doesn't confiscate what you already bought — and it isn't a nudge at
all, it's a mandate. If you're willing to mandate you don't need a currency; a plain timer
does it. Running both is two control mechanisms fighting over the same behaviour.

### Picking the numbers

Set screen-off earning to **`$1`/min**. The point isn't roundness, it's that the unit
becomes *minutes*: every price then reads as an exchange rate with no conversion step. An
app at `$11`/min means eleven minutes of restraint buys one minute of scroll, and you can
feel that without doing arithmetic.

Which makes the earn rate a non-parameter. The **ratio** is the parameter. Earning `1`/min
whenever the screen is off and paying `P`/min while an app is open, the balance is stable
at exactly:

```
U = 1440 / (1 + P)      minutes of app use per day
```

| `P` (app cost/min) | Equilibrium daily use |
|---|---|
| 5  | 4 hours |
| 11 | 2 hours |
| 15 | 90 minutes |
| 23 | 1 hour |

So you don't guess a price — you pick a target and solve for it. (First-order only: it
assumes all screen-off time earns and ignores time parked in the neutral state.)

This is also why timid pricing does nothing. At `P = 2` or `3` the equilibrium is six to
eight hours a day, the economy never binds, and you'd wrongly conclude the whole concept
doesn't work.

### The sleep problem, and the 10:00 settlement

Eight hours asleep is 480 minutes of screen-off — at `$1`/min that's `$480` a night,
roughly 40% of daily income, earned unconditionally. That isn't a nudge, it's a basic
income, and it corrupts the marginal decision: putting the phone down for twenty minutes
earns `$20`, which against `$480` of sleep money is noise. Restraint stops funding you, so
restraint stops mattering.

It also collides head-on with the morning surcharge below. **You wake up at your richest
point of the day** — the balance peaks the instant the alarm goes off — at exactly the
moment you want to be poorest. Even a 5× surcharge on an `$11`/min app is `$55`/min, and
`$480` still buys nine minutes of wake-up scrolling.

The chosen fix, to be run as an experiment:

> **Overnight earnings don't settle until 10:00.** Money earned between 23:00 and 07:00
> posts to the balance at 10:00 rather than in real time. Banks settle; this is native to
> the metaphor. You're still fully credited for sleeping, but the morning can only be
> funded by what you actually had on hand at bedtime — which gives the morning rules teeth
> without needing to be punitive.

Cutting the sleep rate to `$0.20`/min would also work, but it reads as a punishment for
sleeping in a way settlement doesn't.

### The morning: admission control, not price

The wake-up scroll gets special treatment because its harm isn't proportional to duration.
The mechanism matters for picking the right instrument:

- It's **contrast, not depletion**. A high-intensity, zero-effort reward first thing sets
  the baseline for what feels rewarding, and everything effortful afterwards — work,
  reading, an actual conversation — gets measured against it and feels flat.
- **Executive control is weakest right after waking.** You aren't deciding, you're
  executing an automatism.
- It loads **other people's agendas** into your head before you've formed your own.

Because it's baseline-setting rather than depletion, **delay is the entire intervention**.
The same twenty minutes at 09:30, after you've done something effortful and set your own
agenda, costs a fraction of what it costs at 06:40. It doesn't need to be expensive. It
needs to be *later*.

So the morning is the one case where the ticket-vs-meter argument above **flips**: harm is
concentrated at the *first open*, minute fifteen is barely worse than minute two, and when
harm is concentrated at admission you gate admission. A price is doubly wrong here — prices
only work on an agent doing cost-benefit, and at 06:40 there isn't one.

> **Morning curfew.** A hard block that expires, not a surcharge. Not a mandate — a delay
> with a visible end, because "not until 08:30" is something you can comply with in a way
> "never" isn't.
>
> **Anchored to waking, not the clock.** A fixed 06:00–09:00 window punishes a 05:00 start
> and misses a 10:00 one. The wake event is already observable: the first unlock after a
> long continuous screen-off stretch (4h+) is a wake, and the curfew runs ~90 minutes from
> that moment.
>
> **A slow override, not a priced one.** You're rich at that hour, so any payable price
> gets paid. Instead: you may request access and it opens in ten minutes. That's
> self-defeating in exactly the right way — the mechanism that grants the wish is also
> what cures it. Most of the time you won't be there when it unlocks.

**Not built.** What 1.3 ships for the morning is a *surge price* (07:00–09:00 at `$25`/min
with a `$15` cover), which is precisely the instrument this section argues against: it is
clock-anchored rather than wake-anchored, and it is a price, so a balance fat from a night
of sleep simply pays it. Treat surge as a stopgap that makes the hour *expensive*, not as
the curfew that makes it *unavailable*. The settlement rule above is what would give it
teeth, and it is also unbuilt.

Worth deciding what fills the gap. A blocked reflex with nothing to do goes looking for
another screen.

### Happy hour

A scheduled window where app costs are discounted. Its real value isn't the discount — it's
that it converts **"no" into "not yet"**. Deferred urges dissolve at a remarkable rate, and
"I'll do that at 19:00" is a far easier instruction to follow than "don't". It also gives
the system a designated release valve, which is a large part of what keeps it installed.

One placement note: put the window where phone use costs your actual life the least. If
dinner is time with people, discounting it protects the wrong hour — put it just after.
The defaults (12:00–13:00 and 19:00–20:00) follow that: lunch, and after dinner rather
than during it.

**Built in 1.3**, along with a mirror the design above didn't call for: **surge hours**,
where prices are floored rather than capped. Happy hour converts "no" into "not yet";
surge is what gives "not yet" somewhere to point away from.

### Where the balance actually lives

Three dials, in order of leverage:

1. **The earn/spend ratio.** The most important number in the product. Too generous and the
   app is decoration; too tight and every session ends in a lockout, which trains you to
   read the app as an adversary. The signature of a good ratio is going broke
   *occasionally* — often enough that the currency is real, rarely enough that it never
   feels like the default state.
2. **Friction on the escape hatch.** A self-binding system has to make changing your own
   rules slightly costlier than following them, or the Settings screen *is* the workaround
   and the economy is theatre. But if overriding is impossible, the only remaining exit is
   uninstall, and a small defeat becomes a total one. The right amount: enough that raising
   your own allowance is a deliberate act you notice yourself performing, not enough that
   deleting the app is easier. Today there is **no** friction there at all.
3. **Habituation.** Any fixed price gets absorbed — a `$5` cover charge stops being felt
   around week three, once it's internalised as the cost of doing business. Long-term
   systems need drift (prices that respond to your own recent behaviour) or some
   variability, or the nudge decays to zero while the app keeps reporting success.

### Instrumentation comes first

All three of those dials are currently tuned blind. The service already knows every open,
every lockout, every session length and every balance trough — and records none of it.
There is no way to tell whether a configuration is working, and no way to notice a nudge
decaying. **Logging the accounting loop is worth more than any pricing change**, and should
land before the ideas above are tuned.

### Status

| Idea | Status |
|---|---|
| Continuous metering, `elapsedRealtime` accounting | built |
| Per-app prices, incognito surcharge, media precedence | built |
| Lock at `$0` with home-screen escape | built |
| Session/lockout instrumentation | **not built** — do this first |
| `$1`/min unit, ratio-derived app cost | built — defaults are `$1` earn / `$11` app cost |
| Balance visible in the status bar | built — with the `$` kept |
| Idle earning on the home screen | built — `$0.20`/min |
| Cover charge at the door | built — `$5`, blocks until you pay in or leave |
| Cover-charge grace window | **not built** — every re-entry is charged |
| Happy hour window | built — 12–13 and 19–20, as a price cap |
| Scheduled surge pricing | built — 00–06 and 07–09, as a price floor |
| 10:00 settlement of overnight earnings | not built — next experiment |
| Wake-anchored morning curfew + slow override | not built — surge is a *price*, not this gate |
| Friction on Settings edits | not built |

## Settings

Every knob takes effect immediately on the running service and is persisted
to DataStore:

| Setting | Range | Default |
|---------|-------|---------|
| Screen-off earning | `$0` – `$10` /min | `$1` |
| Idle earning (screen on, no app) | `$0` – `$10` /min | `$0.2` |
| Media rate | `−$10` – `$10` /min | `$0.3` |
| App-open cost | `$0` – `$60` /min | `$11` |
| Cover charge (once per app open) | `$0` – `$50` | `$5` |
| Earn multiplier | `0.1x` – `10x` | `1x` |
| Happy hour app cost | `$0` – `$60` /min | `$4` |
| Happy hour cover charge | `$0` – `$50` | `$2` |
| Happy hour windows | whole hours, end exclusive | 12:00–13:00, 19:00–20:00 |
| Surge app cost | `$0` – `$60` /min | `$25` |
| Surge cover charge | `$0` – `$50` | `$15` |
| Surge windows | whole hours, end exclusive | 00:00–06:00, 07:00–09:00 |
| Per-app cost | `$0` – `$60` /min | unset (uses default) |
| Incognito surcharge | `$0` – `$60` /min | `$22` |
| Lock apps at $0 | on / off | on |

The defaults are the `$1`/min model from *Nudge design* above: earning exactly `$1`/min
makes the unit **minutes**, so `$11`/min for an app means eleven minutes of restraint buys
one minute of scroll. `$11` isn't a taste judgement either — it's `1440 / (1 + 11) ≈ 2
hours` of app use as the daily break-even.

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
| **Notification access** | read active media sessions, and spot open private tabs | Settings → Notification access |
| **Display over other apps** | show the cover-charge gate, and the lock overlay at `$0` | Settings → Display over other apps |
| **Ignore battery optimization** | keep earning reliably screen-off | system dialog |
| **Post notifications** (Android 13+) | show the live-balance notification | runtime prompt |
| Wake lock / foreground service | keep the tick loop alive | manifest only |

Without notification access, media playback never registers and the incognito surcharge
never applies — you're charged the normal app rate instead.

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

Debug builds open a **fresh install at `$100`** so the cover charge and the drain can be
exercised without earning a float first; release builds still start at `$0`. That is why
`buildConfig = true` is set — `BuildConfig.DEBUG` isn't generated by default under AGP 9.

On first launch: grant **Usage access** (plus **Notification access** for media
and **Display over other apps** for the gate and the lock), then tap **Start earning**.

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
service/CoverChargeOverlay.kt   the cover-charge gate shown when an app opens
service/BalanceIcon.kt     renders the balance into the status-bar notification icon

ui/HomeScreen.kt           balance, live rate, start/stop, permission cards
ui/SettingsScreen.kt       rate/cost/multiplier sliders, happy/surge schedules,
                           per-app prices, lock switch
ui/AppPickerDialog.kt      searchable installed-app picker
data/InstalledApps.kt      launchable apps + labels for the picker
ui/Support.kt              permission checks + re-check-on-resume helper
util/Format.kt             money / rate / state-label formatting
```

A settings change is applied to the shared state immediately and written to DataStore
in the background, so sliders take effect on the next tick with no lag.

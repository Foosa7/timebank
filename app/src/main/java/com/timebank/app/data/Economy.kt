package com.timebank.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalTime

/** What the phone is doing right now, from the economy's point of view. */
enum class ActivityState {
    STOPPED,     // service not running
    SCREEN_OFF,  // screen off, nothing playing  -> earns off-rate
    MEDIA,       // YouTube / music playing        -> earns media-rate
    APP,         // an app is in the foreground     -> costs app-rate
    NEUTRAL,     // launcher / TimeBank itself      -> earns idle-rate
    COVER        // app open, cover charge unpaid   -> no earn, no cost, gate showing
}

/**
 * A recurring window of the day, in whole local hours. [endHour] is exclusive, so 12..13 is the
 * single hour after noon. A window may wrap past midnight (22..2); one whose bounds are
 * equal covers nothing, which is how a row is disabled without deleting it.
 */
data class HourWindow(val startHour: Int, val endHour: Int) {
    fun contains(time: LocalTime): Boolean {
        val h = time.hour
        return if (startHour <= endHour) h >= startHour && h < endHour
        else h >= startHour || h < endHour
    }
}

/**
 * All tunable numbers. Rates are money-per-minute.
 * Earning rates are multiplied by [earnMultiplier]; the app cost is not.
 *
 * The defaults follow the "$1/min" model in README's *Nudge design* section: earning
 * exactly 1/min makes the unit *minutes*, so every cost reads as an exchange rate. The
 * app cost then falls out of the target usage rather than being picked by feel — a
 * balance is stable at `U = 1440 / (1 + appCostPerMin)` minutes of app use per day, so
 * 11.0 targets roughly two hours.
 */
data class EconomyConfig(
    val offRatePerMin: Double = 1.0,     // earn while screen is fully off
    val idleRatePerMin: Double = 0.2,    // earn while the screen is on but no app is open
    val mediaRatePerMin: Double = 0.3,   // earn (or cost, if negative) while media plays
    val appCostPerMin: Double = 11.0,    // charged while an app is open
    val earnMultiplier: Double = 1.0,    // boosts all earning rates
    val lockWhenBroke: Boolean = true,   // block apps when the balance hits 0

    /**
     * Apps that charge a one-off fee to open, package name -> money. Taken once per visit
     * before the per-minute cost starts, named after a club's cover: it prices the
     * *decision* to open something, which per-minute billing alone doesn't — a reflex
     * glance at a feed costs nearly nothing when it is billed only by the second. Leaving
     * the app ends the visit, so coming back pays again.
     *
     * Opt-in per app, and empty by default: a gate in front of *everything* taxes the
     * dialler and the camera as hard as a feed, which is how the whole mechanism gets
     * switched off. An app that isn't listed here is never gated, whatever the schedule
     * says.
     */
    val coverCharges: Map<String, Double> = emptyMap(),

    /**
     * Windows in which apps are cheap, and the two prices that apply inside them. Happy
     * hour is a *cap*, never a markup: it can only lower what a visit costs, so an app
     * already priced below the happy rate by [appOverrides] keeps its cheaper price. That
     * also means happy hour still does something for apps you have priced by hand, which
     * a plain "replace the base rate" rule would not.
     */
    val happyHours: List<HourWindow> = listOf(HourWindow(12, 13), HourWindow(19, 20)),
    val happyAppCostPerMin: Double = 4.0,
    val happyCoverChargePerApp: Double = 2.0,

    /**
     * Happy hour's mirror image: windows in which apps are *dearer*. The defaults are the
     * two times the economy is easiest to fritter away — the small hours, when you should
     * be asleep and the screen-off rate is quietly paying you, and the morning, when a
     * night of earning has left the balance at its highest and cheapest-feeling. Where a
     * surge window overlaps a happy one the surge wins, because the floor is applied after
     * the cap.
     */
    val surgeHours: List<HourWindow> = listOf(HourWindow(0, 6), HourWindow(7, 9)),
    val surgeAppCostPerMin: Double = 25.0,
    val surgeCoverChargePerApp: Double = 15.0,

    /**
     * Hours you are meant to be asleep, and the screen-off rate that applies inside them.
     * Eight hours of sleep at the full rate is a night's wage earned unconditionally,
     * which drowns out the marginal decision the economy is supposed to price — putting
     * the phone down for twenty minutes cannot matter against it. Sleeping still pays,
     * just not enough to fund the next day on its own.
     */
    val sleepHours: List<HourWindow> = listOf(HourWindow(23, 7)),
    val sleepOffRatePerMin: Double = 0.2,

    /** Per-package cost override, package name -> money/min. Falls back to [appCostPerMin]. */
    val appOverrides: Map<String, Double> = emptyMap(),

    /** Extra charged on top while the foreground app has private/incognito tabs open. */
    val privateSurchargePerMin: Double = 22.0
) {
    /** The per-minute cost of having [pkg] in the foreground, before any private surcharge. */
    fun costFor(pkg: String?): Double =
        appOverrides[pkg] ?: appCostPerMin

    /**
     * As [costFor], with the schedule applied: happy hour caps the price, surge floors it.
     * Neither ever moves a price the wrong way, so an app priced by hand keeps its rate
     * unless the window is genuinely cheaper (happy) or dearer (surge) than it is.
     */
    fun costFor(pkg: String?, happyHour: Boolean, surge: Boolean): Double {
        var cost = costFor(pkg)
        if (happyHour) cost = minOf(cost, happyAppCostPerMin)
        if (surge) cost = maxOf(cost, surgeAppCostPerMin)
        return cost
    }

    /**
     * What it costs to open [pkg] right now, capped and floored the same way. An app with
     * no cover of its own stays at 0 — the schedule may only move a charge that already
     * exists, never conjure one, or a surge window would silently gate the whole phone.
     */
    fun coverFor(pkg: String?, happyHour: Boolean, surge: Boolean): Double {
        var cover = coverCharges[pkg] ?: return 0.0
        if (cover <= 0.0) return 0.0
        if (happyHour) cover = minOf(cover, happyCoverChargePerApp)
        if (surge) cover = maxOf(cover, surgeCoverChargePerApp)
        return cover
    }

    /** The screen-off earning rate for the time of day, before the multiplier. */
    fun offRateAt(time: LocalTime): Double =
        if (isSleepAt(time)) sleepOffRatePerMin else offRatePerMin

    fun isHappyHourAt(time: LocalTime): Boolean = happyHours.any { it.contains(time) }

    fun isSurgeAt(time: LocalTime): Boolean = surgeHours.any { it.contains(time) }

    fun isSleepAt(time: LocalTime): Boolean = sleepHours.any { it.contains(time) }
}

/**
 * Single in-memory source of truth shared by the service and the UI
 * (both live in the same process). Balance is persisted separately.
 */
object Economy {
    val balance = MutableStateFlow(0.0)
    val activity = MutableStateFlow(ActivityState.STOPPED)
    val ratePerMin = MutableStateFlow(0.0)          // signed: + earns, - spends
    val currentPackage = MutableStateFlow<String?>(null)
    val serviceRunning = MutableStateFlow(false)
    val locked = MutableStateFlow(false)            // lock overlay currently showing
    val config = MutableStateFlow(EconomyConfig())

    /**
     * The package sitting at the cover-charge gate, or null when nothing is waiting.
     * Set while [ActivityState.COVER] is the current state.
     */
    val pendingCover = MutableStateFlow<String?>(null)

    /** Whether a [EconomyConfig.happyHours] window is running right now. */
    val happyHourActive = MutableStateFlow(false)

    /** Whether a [EconomyConfig.surgeHours] window is running right now. */
    val surgeActive = MutableStateFlow(false)

    /** Whether a [EconomyConfig.sleepHours] window is running right now. */
    val sleepActive = MutableStateFlow(false)

    /**
     * Packages that currently have a private / incognito browsing session open, as
     * reported by [com.timebank.app.service.MediaNotificationListener]. Empty when
     * notification access has not been granted.
     */
    val privatePackages = MutableStateFlow<Set<String>>(emptySet())

    /** True when the charge being applied right now includes the private-browsing surcharge. */
    val privateSurchargeActive = MutableStateFlow(false)
}

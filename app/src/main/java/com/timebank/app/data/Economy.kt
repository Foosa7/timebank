package com.timebank.app.data

import kotlinx.coroutines.flow.MutableStateFlow

/** What the phone is doing right now, from the economy's point of view. */
enum class ActivityState {
    STOPPED,     // service not running
    SCREEN_OFF,  // screen off, nothing playing  -> earns off-rate
    MEDIA,       // YouTube / music playing        -> earns media-rate
    APP,         // an app is in the foreground     -> costs app-rate
    NEUTRAL      // launcher / TimeBank itself      -> no earn, no cost
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
    val mediaRatePerMin: Double = 0.3,   // earn (or cost, if negative) while media plays
    val appCostPerMin: Double = 11.0,    // charged while an app is open
    val earnMultiplier: Double = 1.0,    // boosts all earning rates
    val lockWhenBroke: Boolean = true,   // block apps when the balance hits 0

    /** Per-package cost override, package name -> money/min. Falls back to [appCostPerMin]. */
    val appOverrides: Map<String, Double> = emptyMap(),

    /** Extra charged on top while the foreground app has private/incognito tabs open. */
    val privateSurchargePerMin: Double = 22.0
) {
    /** The per-minute cost of having [pkg] in the foreground, before any private surcharge. */
    fun costFor(pkg: String?): Double =
        appOverrides[pkg] ?: appCostPerMin
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
     * Packages that currently have a private / incognito browsing session open, as
     * reported by [com.timebank.app.service.MediaNotificationListener]. Empty when
     * notification access has not been granted.
     */
    val privatePackages = MutableStateFlow<Set<String>>(emptySet())

    /** True when the charge being applied right now includes the private-browsing surcharge. */
    val privateSurchargeActive = MutableStateFlow(false)
}

package com.timebank.app.data

import java.time.LocalTime

/**
 * The economy solved backwards.
 *
 * The README picks a price by choosing a target and solving `U = 1440 / (1 + P)` for it.
 * That formula assumes a uniform screen-off earn rate, no idle earning and no cover
 * charge, none of which is true any more — sleep hours alone move the equilibrium under
 * the shipped defaults from 120 minutes a day to 88, which is a 27% tightening nobody
 * chose. This file is the same idea carried through the config the app actually has.
 *
 * Over a day of [DAY_MINUTES] minutes, with `S` minutes of sleep, `U` minutes of metered
 * app use and the rest split between screen-off and neutral at an effective rate `r̄`:
 *
 *     earned  =  m·r_sleep·S  +  m·r̄·(DAY − S − U)
 *     spent   =  P·U  +  C̄·U/L̄
 *
 * Setting them equal gives the balance's fixed point:
 *
 *              m·r_sleep·S + m·r̄·(DAY − S)
 *     U*  =  ────────────────────────────────
 *                  P  +  C̄/L̄  +  m·r̄
 *
 * Everything here is pure arithmetic over [EconomyConfig] — no Android, no state, no I/O —
 * so it can be reasoned about and checked against by hand.
 *
 * Four assumptions are baked in, and all four are honest-but-approximate rather than
 * exact. They matter when reading a number this file produces:
 *
 *  - You are asleep, screen off, for the whole of [EconomyConfig.sleepHours].
 *  - Media time is ignored. It earns at its own rate and would otherwise need a fifth
 *    parameter nobody can estimate on day one.
 *  - The base [EconomyConfig.appCostPerMin] is used rather than a schedule-weighted
 *    price, because weighting needs an hour-of-day usage histogram and the daily buckets
 *    a fresh install can read do not carry one.
 *  - `U*` is a *capacity*, not a prediction. It says what the economy can sustain, not
 *    what you will do — see [binds].
 */

/** Minutes in a day. The one constant the whole model is denominated in. */
const val DAY_MINUTES = 1440.0

/**
 * Share of awake, non-app time spent on the home screen rather than with the screen off.
 * A prior, not a measurement: the daily buckets say nothing about screen state, so this
 * cannot be read from history and has to be assumed until the service has run long enough
 * to measure it. It is not a small detail — at the shipped defaults, moving it from 0 to
 * 0.5 drops the equilibrium from 88 minutes a day to 58.
 */
const val DEFAULT_NEUTRAL_FRACTION = 0.15

/**
 * The measured quantities the model needs that [EconomyConfig] does not carry: how long a
 * visit lasts, what a visit costs at the door on average, and how awake non-app time
 * splits between neutral and screen-off.
 */
data class PricingContext(
    /** Mean session length in minutes. Converts a per-visit cover into a per-minute price. */
    val meanSessionMin: Double,
    /** Visit-weighted mean cover charge across the apps that have one. */
    val meanCoverPerVisit: Double = 0.0,
    val neutralFraction: Double = DEFAULT_NEUTRAL_FRACTION
)

/**
 * Minutes a day inside [EconomyConfig.sleepHours]. Counted over distinct whole hours, so
 * two overlapping windows do not pay twice — which a naive sum over the list would do,
 * and would then quietly overstate the night's earnings.
 */
fun EconomyConfig.sleepMinutesPerDay(): Double =
    (0..23).count { isSleepAt(LocalTime.of(it, 0)) } * 60.0

/**
 * The effective earn rate for awake, non-app time: the blend of idle and screen-off
 * earning, multiplied. Already includes [EconomyConfig.earnMultiplier], so callers never
 * apply it twice.
 */
fun EconomyConfig.awakeEarnRate(ctx: PricingContext): Double {
    val n = ctx.neutralFraction.coerceIn(0.0, 1.0)
    return (n * idleRatePerMin + (1 - n) * offRatePerMin) * earnMultiplier
}

/**
 * A per-visit cover expressed as the per-minute price it is equivalent to at equilibrium.
 *
 * This is the one number that puts the app's two instruments in the same unit, and it is
 * what the README's frequency-versus-duration argument amounts to quantitatively: a $5
 * cover on four-minute sessions is worth $1.25/min, the same cover on forty-minute
 * sessions only $0.125/min. The cover bites exactly where the meter cannot — short,
 * repeated visits.
 *
 * Zero when session length is unknown, since dividing by it would otherwise invent an
 * enormous price out of a missing measurement.
 */
fun coverEquivalentPerMin(coverPerVisit: Double, meanSessionMin: Double): Double =
    if (meanSessionMin > 0.0 && coverPerVisit > 0.0) coverPerVisit / meanSessionMin else 0.0

/**
 * Everything earned in a day that is not displaced by app use — the numerator above. This
 * is the budget the economy has to spend, before any of it is spent.
 */
fun EconomyConfig.dailyEarningCapacity(ctx: PricingContext): Double {
    val sleep = sleepMinutesPerDay()
    val awake = (DAY_MINUTES - sleep).coerceAtLeast(0.0)
    return sleepOffRatePerMin * earnMultiplier * sleep + awakeEarnRate(ctx) * awake
}

/**
 * Minutes of metered app use a day this config can sustain indefinitely — the balance's
 * fixed point.
 *
 * Infinite when nothing costs anything, which is a real answer rather than an error: an
 * economy with a zero price never binds and the balance grows without bound.
 */
fun EconomyConfig.equilibriumUse(ctx: PricingContext): Double {
    val effectivePrice = appCostPerMin +
        coverEquivalentPerMin(ctx.meanCoverPerVisit, ctx.meanSessionMin)
    val denominator = effectivePrice + awakeEarnRate(ctx)
    if (denominator <= 0.0) return Double.POSITIVE_INFINITY
    return dailyEarningCapacity(ctx) / denominator
}

/**
 * The app cost whose equilibrium is [targetMinutesPerDay] — the inversion the calibration
 * flow is built on. You pick a target and the price falls out, rather than picking a price
 * and discovering the target months later.
 *
 * Returns 0 when the target is looser than free access would produce, i.e. when the
 * earning side alone already allows more than you asked for. That is a meaningful
 * "the economy cannot bind here" rather than a failure, and [binds] is the check for it.
 */
fun EconomyConfig.appCostForTarget(
    targetMinutesPerDay: Double,
    ctx: PricingContext
): Double {
    if (targetMinutesPerDay <= 0.0) return Double.POSITIVE_INFINITY
    val raw = dailyEarningCapacity(ctx) / targetMinutesPerDay -
        awakeEarnRate(ctx) -
        coverEquivalentPerMin(ctx.meanCoverPerVisit, ctx.meanSessionMin)
    return raw.coerceAtLeast(0.0)
}

/**
 * Whether the economy actually constrains someone who currently uses apps for
 * [observedMinutesPerDay].
 *
 * The distinction the equilibrium formula hides: `U*` is a ceiling, and a ceiling above
 * your head does nothing. If observed use already sits below it, the balance simply grows,
 * the lock never fires, and no amount of tuning the price changes behaviour — the right
 * response is to lower the target, not to raise the price. Any controller built on top of
 * this must gate on it, or it will wind up against a constraint that is not binding and
 * then deliver a brutal lockout the day it finally is.
 */
fun EconomyConfig.binds(observedMinutesPerDay: Double, ctx: PricingContext): Boolean =
    observedMinutesPerDay > equilibriumUse(ctx)

// --- recommendation ---------------------------------------------------------------
//
// Turning the measurements above into a config. The split that matters is between what is
// *measured* and what is *judged*: a sleep window and an app price fall out of the data,
// but which hours of your life a discount protects does not, and neither does which apps
// you cannot afford to be locked out of. Only the first kind is proposed here.

/** One proposed change, with the reason it is being proposed. */
data class Proposal(val label: String, val why: String)

/**
 * A config change derived from measured behaviour. Fields left null are deliberately not
 * touched.
 *
 * Happy hours are never proposed. Happy hour is a *release valve* — its placement follows
 * where phone use costs your life the least, not where use happens to be highest, and a
 * histogram cannot see the difference. Proposing one from peak-usage data would confuse
 * "busiest hour" with "worst hour" and would work against the mechanism it was named for.
 */
data class Recommendation(
    val appCostPerMin: Double,
    val sleepHours: List<HourWindow>?,
    val surgeHours: List<HourWindow>?,
    /** The obvious gate candidate, and what to charge. Never applied automatically. */
    val coverPackage: String?,
    val coverPerVisit: Double,
    val changes: List<Proposal>
) {
    val hasAnything: Boolean get() = changes.isNotEmpty()
}

/**
 * Build a recommendation for [targetMinutesPerDay] from what the phone measured.
 *
 * Note this reads the *baseline* — fixed history — rather than recent behaviour, so it
 * cannot be farmed by using the phone more and asking again. It is a calibration, not a
 * controller.
 */
fun EconomyConfig.recommend(
    obs: Observations,
    targetMinutesPerDay: Double,
    ctx: PricingContext
): Recommendation {
    val changes = mutableListOf<Proposal>()

    val cost = appCostForTarget(targetMinutesPerDay, ctx)
    if (cost.isFinite() && cost > 0.0 && !nearlyEqual(cost, appCostPerMin)) {
        changes.add(
            Proposal(
                "App cost ${money(appCostPerMin)}/min → ${money(cost)}/min",
                "Solved from your measured ${obs.baseline.appMinutesPerDay.roundedMinutes()} " +
                    "a day. The shipped price settles at " +
                    "${equilibriumUse(ctx).roundedMinutes()}, which you pass before lunch."
            )
        )
    }

    // Sleep: a measurement, and the one schedule that moves an earning rate.
    val sleep = obs.shape.sleepWindow
    val sleepHours = if (sleep != null && listOf(sleep) != sleepHours) {
        changes.add(
            Proposal(
                "Sleep hours ${describe(sleepHours)} → ${describe(listOf(sleep))}",
                "Your longest unbroken screen-off stretch, over " +
                    "${obs.shape.eventDays} day(s) of events. Outside it you are awake and " +
                    "earning the full rate; inside it you are not."
            )
        )
        listOf(sleep)
    } else {
        null
    }

    // Surge: hours you actually open apps, minus any hour a happy window already owns, so
    // the floor can never be applied on top of the release valve.
    val surge = peakWindows(obs.shape.opensByHour) { h -> isHappyHourAt(LocalTime.of(h, 0)) }
    val surgeHours = if (surge.isNotEmpty() && surge != surgeHours) {
        changes.add(
            Proposal(
                "Surge hours ${describe(surgeHours)} → ${describe(surge)}",
                "Where your app opens actually cluster. Hours already covered by a happy " +
                    "window are excluded, since surge wins any overlap."
            )
        )
        surge
    } else {
        null
    }

    // Cover: named, never applied. Gating is opt-in per app by design, and only you know
    // which apps you cannot afford to be held out of.
    val candidate = obs.baseline.perApp.firstOrNull {
        it.meanSessionMin >= MIN_COVER_SESSION_MIN &&
            it.minutesPerDay >= obs.baseline.appMinutesPerDay * MIN_COVER_SHARE
    }
    val coverAmount = if (candidate != null && cost.isFinite()) {
        (0.5 * cost * candidate.meanSessionMin).coerceAtLeast(1.0).roundToWhole()
    } else {
        0.0
    }

    return Recommendation(cost, sleepHours, surgeHours, candidate?.packageName, coverAmount, changes)
}

/** Apply everything the recommendation proposes. The cover charge is not part of it. */
fun EconomyConfig.withRecommendation(r: Recommendation): EconomyConfig = copy(
    appCostPerMin = if (r.appCostPerMin.isFinite() && r.appCostPerMin > 0.0) {
        r.appCostPerMin
    } else {
        appCostPerMin
    },
    sleepHours = r.sleepHours ?: sleepHours,
    surgeHours = r.surgeHours ?: surgeHours
)

/**
 * Contiguous runs of hours whose open count runs well above the day's average, merged
 * across midnight and ranked by volume.
 *
 * Returns nothing at all below [MIN_OPENS_FOR_SCHEDULE] observations, because a schedule
 * fitted to a handful of events is noise wearing a decision's clothes.
 */
private fun peakWindows(
    opensByHour: List<Int>,
    excluded: (Int) -> Boolean
): List<HourWindow> {
    if (opensByHour.size != 24) return emptyList()
    val total = opensByHour.sum()
    if (total < MIN_OPENS_FOR_SCHEDULE) return emptyList()

    // A peak has to actually be a peak. Measured against the median hour rather than the
    // mean, because "above average" is guaranteed to select something no matter how flat
    // the day is — on a real phone whose busiest hour ran 230 opens against a median of
    // 150, the mean rule confidently proposed two windows whose hours sat within 4% of
    // each other and of four more it left out. That is noise wearing a decision's clothes.
    val sorted = opensByHour.sorted()
    val median = (sorted[11] + sorted[12]) / 2.0
    if (median <= 0.0 || opensByHour.max() < PEAK_OVER_MEDIAN * median) return emptyList()

    val threshold = PEAK_MULTIPLE * total / 24.0
    val hot = BooleanArray(24) { opensByHour[it] > threshold && !excluded(it) }
    // An anchor that is not itself hot, so a run spanning midnight is found whole rather
    // than split into one window at 23:00 and another at 00:00.
    val anchor = (0..23).firstOrNull { !hot[it] } ?: return listOf(HourWindow(0, 24))

    val found = mutableListOf<Pair<HourWindow, Int>>()
    var i = 0
    while (i < 24) {
        val h = (anchor + i) % 24
        if (!hot[h]) { i++; continue }
        var len = 0
        while (len < 24 && hot[(anchor + i + len) % 24]) len++
        val window = HourWindow(h, (h + len) % 24)
        found.add(window to (0 until len).sumOf { opensByHour[(h + it) % 24] })
        i += len
    }
    return found.sortedByDescending { it.second }.take(MAX_PEAK_WINDOWS).map { it.first }
}

private fun nearlyEqual(a: Double, b: Double) = kotlin.math.abs(a - b) < 0.05

private fun Double.roundToWhole(): Double = kotlin.math.round(this)

private fun Double.roundedMinutes(): String =
    if (this >= 60) String.format(java.util.Locale.US, "%.1f h", this / 60.0)
    else "${kotlin.math.round(this).toInt()} min"

private fun money(v: Double) = "$" + String.format(java.util.Locale.US, "%,.1f", v)

private fun describe(windows: List<HourWindow>): String =
    if (windows.isEmpty()) "none"
    else windows.joinToString(", ") { "%02d–%02d".format(it.startHour, it.endHour) }

/** How far above the day's average an hour must run to count as a peak. */
private const val PEAK_MULTIPLE = 1.5

/**
 * How far the busiest hour must stand above the median one before any window is proposed
 * at all. Below this the day has no shape worth pricing differently.
 */
private const val PEAK_OVER_MEDIAN = 2.0

/** Below this many observed opens, no schedule is proposed at all. */
private const val MIN_OPENS_FOR_SCHEDULE = 50

private const val MAX_PEAK_WINDOWS = 2

/** A cover on visits shorter than this is a toll, not a price — see [coverEquivalentPerMin]. */
private const val MIN_COVER_SESSION_MIN = 2.0

/** Share of total app time an app needs before it is worth naming as a gate candidate. */
private const val MIN_COVER_SHARE = 0.25

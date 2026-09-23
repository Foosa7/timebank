package com.timebank.app.data

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * What the phone already knew about you before TimeBank ticked once.
 *
 * Android keeps its own daily per-package usage buckets — the same ones Digital Wellbeing
 * renders — and they survive far longer than the raw event stream
 * [com.timebank.app.service.ForegroundAppMonitor] reads. That asymmetry is the whole point
 * of this file: on a fresh install we cannot know *when* you used an app (the event log is
 * only kept for about a week), but we can know *how much* you used it for months back, and
 * how much is enough to price the economy against your actual behaviour instead of against
 * the worked example in the README.
 *
 * No new permission is needed. `PACKAGE_USAGE_STATS` is already declared and already
 * granted for the foreground monitor, so this reads a dataset the app can see on day one.
 */

/** One day's measured use of one package, as Android's own daily bucket recorded it. */
data class DayUsage(
    val date: LocalDate,
    val packageName: String,
    val minutes: Double
)

/** A package's share of the baseline, averaged over the days that had any history. */
data class AppBaseline(
    val packageName: String,
    val minutesPerDay: Double,
    val visitsPerDay: Double
) {
    /** Mean session length. The cover charge's per-minute equivalent is keyed on this. */
    val meanSessionMin: Double
        get() = if (visitsPerDay > 0.0) minutesPerDay / visitsPerDay else 0.0
}

/**
 * The measured starting point for the economy: how much metered app time a day actually
 * looks like, and in how many visits.
 *
 * Only packages with a launcher entry count, minus TimeBank and the launcher itself. That
 * is not tidying — it is what makes these numbers comparable with the ones the service
 * produces, since `tick()` scores the launcher and TimeBank as [ActivityState.NEUTRAL]
 * rather than as [ActivityState.APP]. A baseline that counted them would be measuring a
 * different quantity from the one the controller steers.
 */
data class Baseline(
    /** Days of daily-bucket history the minutes are averaged over. */
    val days: Int,
    /**
     * Days of *event* history the visit counts are averaged over, which is very much
     * shorter — see [readDayShape]. Zero when no events were retained at all, in which
     * case every visit count here is zero and anything keyed on session length degrades to
     * "unknown" rather than to a wrong number.
     */
    val visitDays: Int,
    val appMinutesPerDay: Double,
    /**
     * Minutes a day over just the days the visit counts also cover. Exists only to be
     * divided by [visitsPerDay] — see [meanSessionMin].
     */
    val recentMinutesPerDay: Double,
    val visitsPerDay: Double,
    val perApp: List<AppBaseline>,
    /**
     * Minutes a day spent in work apps during work hours, already taken out of every figure
     * above by [excludingWork]. Zero until that has run. The pricing model needs it back,
     * because that time is not just unbilled — it displaces earning too.
     */
    val exemptMinutesPerDay: Double = 0.0
) {
    /**
     * Mean session length, taken over the *visit* window rather than the full history.
     *
     * Dividing the ninety-day minute average by the seven-day visit average would not be a
     * session length at all — it is a ratio of two different periods, and it silently
     * reports the wrong answer whenever recent behaviour differs from the long run, which
     * for anyone installing an app like this is close to guaranteed. Both halves have to
     * come from the same days or the number means nothing.
     */
    val meanSessionMin: Double
        get() = if (visitsPerDay > 0.0) recentMinutesPerDay / visitsPerDay else 0.0

    /** Nothing came back — no permission, a wiped device, or a genuinely unused phone. */
    val isEmpty: Boolean get() = days == 0 || appMinutesPerDay <= 0.0

    companion object {
        val EMPTY = Baseline(0, 0, 0.0, 0.0, 0.0, emptyList())
    }
}

/**
 * Read the last [days] days of daily buckets, one day at a time.
 *
 * Querying day by day rather than in one range is deliberate. `queryUsageStats` returns
 * every bucket that *intersects* the range, with each bucket's totals covering the whole
 * bucket rather than the intersection, so a single wide query cannot be split back into
 * days without double-counting the ones at the edges. Buckets are also device-defined and
 * need not start exactly at local midnight, which is why entries are attributed by their
 * own `firstTimeStamp`: treat the result as an honest estimate of a day's use, not an
 * audit of it.
 *
 * Returns an empty list rather than throwing when usage access is missing, matching
 * [com.timebank.app.service.ForegroundAppMonitor]'s degrade-quietly convention.
 */
fun readUsageHistory(context: Context, days: Int = DEFAULT_DAYS): List<DayUsage> {
    val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        ?: return emptyList()
    val metered = meteredPackages(context)
    if (metered.isEmpty()) return emptyList()

    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val out = ArrayList<DayUsage>()

    for (back in 1..days) {
        val date = today.minusDays(back.toLong())
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val stats = try {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
        } catch (_: Exception) {
            // No permission yet, or a transient failure. A partial history is still
            // usable, so keep whatever earlier days already came back.
            return out
        } ?: continue

        // One package can appear more than once in a bucket list, so totals are summed
        // rather than assigned.
        val minutes = HashMap<String, Double>()
        for (s in stats) {
            if (s.packageName !in metered) continue
            if (s.firstTimeStamp < start || s.firstTimeStamp >= end) continue
            val fg = s.totalTimeInForeground
            if (fg <= 0L) continue
            minutes[s.packageName] = (minutes[s.packageName] ?: 0.0) + fg / 60_000.0
        }
        for ((pkg, min) in minutes) {
            out.add(DayUsage(date, pkg, min))
        }
    }
    return out
}

/**
 * The shape of a day, walked out of the raw event stream in one pass.
 *
 * Everything here needs events rather than the daily buckets, so it all shares the buckets'
 * opposite problem: rich detail over a window of about a week. Three things come out that
 * nothing else in the app can measure, and all three are currently guessed at.
 */
data class DayShape(
    /** Distinct days the events covered. Zero when nothing was retained. */
    val eventDays: Int,
    /** Visits per day per package, counted as changes of foreground package. */
    val visitsPerDay: Map<String, Double>,
    /** App opens per hour of the local day, 24 buckets. */
    val opensByHour: List<Int>,
    /**
     * When you actually sleep, inferred from the longest unbroken screen-off stretch of
     * each day. Null when no stretch was long enough to be a night rather than a meeting.
     */
    val sleepWindow: HourWindow?,
    /**
     * Measured share of awake, non-app time spent on the home screen — the one input
     * [PricingContext] otherwise has to assume. Null when there were no events to measure
     * it from. It is worth measuring: the shipped prior of 0.15 and a real value near 0.02
     * move the equilibrium by a third.
     */
    val neutralFraction: Double?,
    /**
     * Foreground milliseconds per package per hour of the week (Monday 00:00 is index 0),
     * over the whole event window. Kept raw rather than pre-filtered so work hours can be
     * applied afterwards by [excludingWork] — changing a work app then re-prices without
     * another read of the event log.
     */
    val foregroundMsByWeekHour: Map<String, LongArray> = emptyMap(),
    /** Visits per package per hour of the week, counted the same way as [visitsPerDay]. */
    val opensByWeekHour: Map<String, IntArray> = emptyMap()
) {
    companion object {
        val EMPTY = DayShape(0, emptyMap(), List(24) { 0 }, null, null)
    }
}

/** A baseline and a day shape, read together because they come from one pass. */
data class Observations(val baseline: Baseline, val shape: DayShape)

/**
 * Walk the retained event stream and pull out visits, the hour histogram, the sleep window
 * and the neutral fraction.
 *
 * A visit is a change of foreground *package*, not every foreground event.
 * `MOVE_TO_FOREGROUND` fires per activity, so navigating within an app emits a stream of
 * them — measured against this phone's own launch counter, counting them raw reported 284
 * Instagram visits in a day that had 61. That inflation lands squarely on session length,
 * and from there on [coverEquivalentPerMin], which would quote a cover as worth four times
 * the per-minute price it is actually worth. Leaving for the launcher or a system screen
 * ends the visit, which is the same rule `admittedPackage` bills on.
 *
 * Returns [DayShape.EMPTY] when the permission is missing or nothing was retained.
 */
fun readDayShape(context: Context, days: Int = VISIT_DAYS): DayShape {
    val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        ?: return DayShape.EMPTY
    val metered = meteredPackages(context)
    if (metered.isEmpty()) return DayShape.EMPTY

    val zone = ZoneId.systemDefault()
    val end = System.currentTimeMillis()
    val start = LocalDate.now(zone).minusDays(days.toLong())
        .atStartOfDay(zone).toInstant().toEpochMilli()

    val counts = HashMap<String, Int>()
    val byHour = IntArray(24)
    val fgByWeekHour = HashMap<String, LongArray>()
    val opensByWeekHour = HashMap<String, IntArray>()
    val seenDays = HashSet<LocalDate>()
    // Longest screen-off stretch per day, which is the night; shorter ones are pockets,
    // meetings and meals, and averaging those in would drag the window to nonsense.
    val longestOffPerDay = HashMap<LocalDate, Pair<Long, Long>>()

    var previous: String? = null
    var screenOn = true
    var offSince: Long? = null
    var lastTs = 0L
    var offMs = 0L
    var neutralMs = 0L

    try {
        val events = usm.queryEvents(start, end)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val ts = e.timeStamp

            // Attribute the gap since the previous event to whatever was true across it.
            if (lastTs != 0L && ts > lastTs) {
                val delta = ts - lastTs
                val app = previous
                if (!screenOn) offMs += delta
                else if (app == null) neutralMs += delta
                else spreadOverWeekHours(
                    lastTs, ts, zone, fgByWeekHour.getOrPut(app) { LongArray(WEEK_HOURS) }
                )
            }
            lastTs = ts

            when (e.eventType) {
                SCREEN_NON_INTERACTIVE -> {
                    screenOn = false
                    previous = null   // pocketing the phone ends the visit
                    if (offSince == null) offSince = ts
                }

                SCREEN_INTERACTIVE -> {
                    screenOn = true
                    offSince?.let { from ->
                        val date = Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
                        val best = longestOffPerDay[date]
                        if (best == null || (ts - from) > (best.second - best.first)) {
                            longestOffPerDay[date] = from to ts
                        }
                    }
                    offSince = null
                }

                @Suppress("DEPRECATION")
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    val pkg = e.packageName
                    if (pkg == null || pkg !in metered) {
                        previous = null
                    } else {
                        if (pkg != previous) {
                            counts[pkg] = (counts[pkg] ?: 0) + 1
                            val zoned = Instant.ofEpochMilli(ts).atZone(zone)
                            byHour[zoned.hour]++
                            opensByWeekHour.getOrPut(pkg) { IntArray(WEEK_HOURS) }[weekHour(zoned)]++
                            seenDays.add(zoned.toLocalDate())
                        }
                        previous = pkg
                    }
                }
            }
        }
    } catch (_: Exception) {
        return DayShape.EMPTY
    }

    val dayCount = seenDays.size
    if (dayCount == 0) return DayShape.EMPTY

    return DayShape(
        eventDays = dayCount,
        visitsPerDay = counts.mapValues { it.value.toDouble() / dayCount },
        opensByHour = byHour.toList(),
        sleepWindow = inferSleepWindow(longestOffPerDay.values, zone),
        // Only the gaps between events were attributed, so this is a share of what was
        // observed rather than of the wall clock — which is exactly what a fraction needs.
        neutralFraction = if (offMs + neutralMs > 0L) {
            neutralMs.toDouble() / (offMs + neutralMs)
        } else {
            null
        },
        foregroundMsByWeekHour = fgByWeekHour,
        opensByWeekHour = opensByWeekHour
    )
}

/** Monday 00:00 is 0, Sunday 23:00 is 167. */
private fun weekHour(t: ZonedDateTime): Int = (t.dayOfWeek.value - 1) * 24 + t.hour

/** Add the span [from, to) to [into], split at each hour boundary it crosses. */
private fun spreadOverWeekHours(from: Long, to: Long, zone: ZoneId, into: LongArray) {
    var t = Instant.ofEpochMilli(from).atZone(zone)
    val end = Instant.ofEpochMilli(to).atZone(zone)
    while (t.isBefore(end)) {
        val next = t.truncatedTo(ChronoUnit.HOURS).plusHours(1)
        val stop = if (next.isBefore(end)) next else end
        into[weekHour(t)] += stop.toInstant().toEpochMilli() - t.toInstant().toEpochMilli()
        t = stop
    }
}

/**
 * The typical night, as whole hours.
 *
 * Boundaries are rounded to the nearest hour rather than outward, which minimises the
 * minutes put on the wrong side of each end. Stretches shorter than [MIN_SLEEP_HOURS] are
 * dropped: the README's own wake heuristic treats four hours of continuous screen-off as a
 * night, and anything less is a meeting or a film.
 */
private fun inferSleepWindow(
    stretches: Collection<Pair<Long, Long>>,
    zone: ZoneId
): HourWindow? {
    val nights = stretches.filter { (a, b) -> (b - a) >= MIN_SLEEP_HOURS * 3_600_000L }
    if (nights.isEmpty()) return null
    // Median rather than mean, so one late night does not drag the whole window.
    val starts = nights.map { hourOf(it.first, zone) }.sorted()
    val ends = nights.map { hourOf(it.second, zone) }.sorted()
    val start = starts[starts.size / 2].toInt().coerceIn(0, 23)
    val end = ends[ends.size / 2].toInt().coerceIn(0, 24)
    return if (start == end) null else HourWindow(start, end)
}

/** Local hour of a timestamp, rounded to the nearest whole hour. */
private fun hourOf(ts: Long, zone: ZoneId): Long {
    val t = Instant.ofEpochMilli(ts).atZone(zone)
    return Math.round(t.hour + t.minute / 60.0) % 24
}

/**
 * Collapse a history into the per-day averages the pricing model consumes.
 *
 * Days with no recorded use at all are dropped rather than averaged in as zeroes: a bucket
 * that came back empty is far more often a gap in what the system retained than a day you
 * genuinely did not touch the phone, and counting those as zeroes biases the baseline down,
 * which would under-price the economy in exactly the direction that makes it do nothing.
 */
fun summarise(
    history: List<DayUsage>,
    visitsPerDay: Map<String, Double> = emptyMap(),
    visitDays: Int = 0
): Baseline {
    if (history.isEmpty()) return Baseline.EMPTY
    val dayCount = history.map { it.date }.distinct().size
    if (dayCount == 0) return Baseline.EMPTY

    val perApp = history.groupBy { it.packageName }
        .map { (pkg, rows) ->
            AppBaseline(
                packageName = pkg,
                minutesPerDay = rows.sumOf { it.minutes } / dayCount,
                visitsPerDay = visitsPerDay[pkg] ?: 0.0
            )
        }
        .sortedByDescending { it.minutesPerDay }

    // Minutes restricted to the most recent days, so session length divides two averages
    // taken over the same period rather than one over months and one over a week.
    val recentDates = history.map { it.date }.distinct().sortedDescending()
        .take(visitDays.coerceAtLeast(1)).toSet()
    val recentMinutesPerDay = history.filter { it.date in recentDates }
        .sumOf { it.minutes } / recentDates.size

    return Baseline(
        days = dayCount,
        visitDays = visitDays,
        appMinutesPerDay = perApp.sumOf { it.minutesPerDay },
        recentMinutesPerDay = recentMinutesPerDay,
        visitsPerDay = perApp.sumOf { it.visitsPerDay },
        perApp = perApp
    )
}

/**
 * The observations with work time taken out, so calibration prices only the time you
 * choose. Work-app minutes inside work hours are neither billed nor earned by `tick()`,
 * and a baseline that still counted them would propose prices tuned against a working
 * day — far too harsh for the evening that is actually being priced.
 *
 * Only the event window knows *when* anything happened, so each work app's share of its
 * foreground time that fell in work hours is measured there and applied to its long
 * bucket average. Visits and the hour histogram lose the work-hours opens outright, since
 * those visits never reach the gate either. Pure: depends on nothing but its arguments.
 */
fun Observations.excludingWork(cfg: EconomyConfig): Observations {
    val shape = this.shape
    if (cfg.workApps.isEmpty() || shape.eventDays == 0) return this

    val workHourMask = BooleanArray(WEEK_HOURS) { i ->
        cfg.isWorkAt(REFERENCE_MONDAY.plusDays((i / 24).toLong()).atTime(i % 24, 0))
    }
    if (workHourMask.none { it }) return this

    val workShare = HashMap<String, Double>()
    var exemptMs = 0L
    val workOpens = HashMap<String, Int>()
    val workOpensByHour = IntArray(24)
    for (pkg in cfg.workApps) {
        val fg = shape.foregroundMsByWeekHour[pkg]
        if (fg != null) {
            val total = fg.sum()
            val work = fg.indices.filter { workHourMask[it] }.sumOf { fg[it] }
            if (total > 0L) workShare[pkg] = work.toDouble() / total
            exemptMs += work
        }
        shape.opensByWeekHour[pkg]?.forEachIndexed { i, n ->
            if (workHourMask[i] && n > 0) {
                workOpens[pkg] = (workOpens[pkg] ?: 0) + n
                workOpensByHour[i % 24] += n
            }
        }
    }

    val days = shape.eventDays.toDouble()
    val exemptPerDay = exemptMs / 60_000.0 / days
    val perApp = baseline.perApp.map { app ->
        val share = workShare[app.packageName] ?: return@map app
        app.copy(
            minutesPerDay = app.minutesPerDay * (1 - share),
            visitsPerDay = (app.visitsPerDay - (workOpens[app.packageName] ?: 0) / days)
                .coerceAtLeast(0.0)
        )
    }.sortedByDescending { it.minutesPerDay }

    val visits = shape.visitsPerDay.mapValues { (pkg, v) ->
        (v - (workOpens[pkg] ?: 0) / days).coerceAtLeast(0.0)
    }
    return Observations(
        baseline = baseline.copy(
            appMinutesPerDay = perApp.sumOf { it.minutesPerDay },
            // The event window is the recent window, so its measured work time comes off
            // the recent minutes directly.
            recentMinutesPerDay = (baseline.recentMinutesPerDay - exemptPerDay).coerceAtLeast(0.0),
            visitsPerDay = perApp.sumOf { it.visitsPerDay },
            perApp = perApp,
            exemptMinutesPerDay = exemptPerDay
        ),
        shape = shape.copy(
            visitsPerDay = visits,
            opensByHour = shape.opensByHour.mapIndexed { h, n -> (n - workOpensByHour[h]).coerceAtLeast(0) }
        )
    )
}

/** Any Monday will do: [excludingWork] only needs a day of the week for each index. */
private val REFERENCE_MONDAY: LocalDate = LocalDate.of(2024, 1, 1)

private const val WEEK_HOURS = 7 * 24

/**
 * Read both windows in one go: the long minute history and the short event history.
 *
 * Call off the main thread — the bucket read is one binder call per day.
 */
fun readObservations(context: Context, days: Int = DEFAULT_DAYS): Observations {
    val shape = readDayShape(context)
    val baseline = summarise(readUsageHistory(context, days), shape.visitsPerDay, shape.eventDays)
    return Observations(baseline, shape)
}

/**
 * Packages the economy would actually meter: everything with a launcher entry, minus
 * TimeBank and the home screen, which `tick()` resolves as neutral.
 */
private fun meteredPackages(context: Context): Set<String> {
    val pm = context.packageManager
    val launcher = pm.resolveActivity(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
    )?.activityInfo?.packageName
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .asSequence()
        .map { it.activityInfo.applicationInfo.packageName }
        .filter { it != context.packageName && it != launcher }
        .toSet()
}

/**
 * Three months. Long enough to average over holidays and a change of routine, short enough
 * that it still describes the person installing the app today.
 */
const val DEFAULT_DAYS = 90

/**
 * Turn a measured [Baseline] into the inputs the pricing model needs.
 *
 * The mean cover is weighted by *visits*, not by apps: an app opened forty times a day
 * dominates what a cover actually costs you, and averaging over the list instead would let
 * one expensive, rarely-opened app drag the whole estimate off. Apps with no cover count
 * as zero, which is what they charge.
 *
 * [neutralFraction] stays a prior — nothing in the daily buckets reports screen state, so
 * it cannot be measured from history and is refined only once the service has run.
 */
fun Baseline.pricingContext(
    cfg: EconomyConfig,
    neutralFraction: Double = DEFAULT_NEUTRAL_FRACTION
): PricingContext {
    val totalVisits = perApp.sumOf { it.visitsPerDay }
    val meanCover = if (totalVisits > 0.0) {
        perApp.sumOf { (cfg.coverCharges[it.packageName] ?: 0.0) * it.visitsPerDay } / totalVisits
    } else {
        0.0
    }
    return PricingContext(
        meanSessionMin = meanSessionMin,
        meanCoverPerVisit = meanCover,
        neutralFraction = neutralFraction,
        exemptMinutesPerDay = exemptMinutesPerDay
    )
}

/**
 * A week. Roughly what the event stream retains, and asking for more simply returns less
 * without saying so.
 */
const val VISIT_DAYS = 7

/**
 * Continuous screen-off time that counts as a night rather than a meeting. Matches the
 * README's wake heuristic, which treats the first unlock after 4h+ of screen-off as waking.
 */
const val MIN_SLEEP_HOURS = 4

// Screen on/off events, public since API 28 and compile-time constants, so referencing
// them is safe below minSdk — they simply never arrive, and the sleep window stays null.
private const val SCREEN_INTERACTIVE = 15
private const val SCREEN_NON_INTERACTIVE = 16

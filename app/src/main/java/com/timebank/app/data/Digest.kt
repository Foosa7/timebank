package com.timebank.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything the model needs about you, in one compact object.
 *
 * Two rules shape what goes in. First, it carries *derived* quantities — the equilibrium, the
 * binding fraction, the cover's per-minute equivalent — and not just raw counts, because the
 * arithmetic is settled and re-deriving it in prose is where a model invents numbers. Second,
 * it is small enough to read: a few kilobytes, no event stream, no per-day rows. The report is
 * a weekly judgement about structure, not a recount of the data.
 *
 * Apps appear under their display labels rather than package names. That is what the person
 * approving the payload can actually read, and this object is shown to them in full before it
 * is sent anywhere.
 */
fun buildDigest(
    context: Context,
    obs: Observations,
    cfg: EconomyConfig,
    ctx: PricingContext
): JSONObject {
    val b = obs.baseline
    val equilibrium = cfg.equilibriumUse(ctx)

    val perApp = JSONArray()
    obs.baseline.perApp.take(MAX_APPS).forEach { app ->
        perApp.put(
            JSONObject()
                .put("app", labelFor(context, app.packageName))
                .put("minPerDay", app.minutesPerDay.round1())
                .put("visitsPerDay", app.visitsPerDay.round1())
                .put("minPerVisit", app.meanSessionMin.round1())
                .put("shareOfTotal", (app.minutesPerDay / b.appMinutesPerDay).round2())
                .put("pricePerMin", cfg.costFor(app.packageName).round1())
                .put("coverCharge", (cfg.coverCharges[app.packageName] ?: 0.0).round1())
        )
    }

    return JSONObject()
        .put(
            "measured",
            JSONObject()
                .put("bucketDays", b.days)
                .put("eventDays", b.visitDays)
                .put("appMinutesPerDay", b.appMinutesPerDay.round1())
                .put("visitsPerDay", b.visitsPerDay.round1())
                .put("meanSessionMin", b.meanSessionMin.round1())
                .put("neutralFraction", (obs.shape.neutralFraction ?: -1.0).round2())
                // A comma string rather than a JSON array: pretty-printing puts each of the
                // 24 counts on its own line, which swamps the preview the user has to read
                // and spends a hundred tokens on whitespace. Same trick the config encoding
                // already uses for the same reason.
                .put("opensByHourFrom00", obs.shape.opensByHour.joinToString(","))
                .put("inferredSleepWindow", obs.shape.sleepWindow?.let { "${it.startHour}-${it.endHour}" })
        )
        .put(
            "config",
            JSONObject()
                .put("appCostPerMin", cfg.appCostPerMin)
                .put("offRatePerMin", cfg.offRatePerMin)
                .put("idleRatePerMin", cfg.idleRatePerMin)
                .put("mediaRatePerMin", cfg.mediaRatePerMin)
                .put("earnMultiplier", cfg.earnMultiplier)
                .put("sleepHours", cfg.sleepHours.encode())
                .put("sleepOffRatePerMin", cfg.sleepOffRatePerMin)
                .put("happyHours", cfg.happyHours.encode())
                .put("happyAppCostPerMin", cfg.happyAppCostPerMin)
                .put("surgeHours", cfg.surgeHours.encode())
                .put("surgeAppCostPerMin", cfg.surgeAppCostPerMin)
                .put("lockWhenBroke", cfg.lockWhenBroke)
        )
        .put(
            // Settled arithmetic, handed over rather than left to be re-derived in prose.
            "derived",
            JSONObject()
                .put("equilibriumMinPerDay", equilibrium.finiteOrNull()?.round1())
                .put("dailyEarningCapacity", cfg.dailyEarningCapacity(ctx).round1())
                .put("overshootFactor", (b.appMinutesPerDay / equilibrium).finiteOrNull()?.round2())
                .put("economyBinds", cfg.binds(b.appMinutesPerDay, ctx))
                .put(
                    "coverEquivalentPerMin",
                    coverEquivalentPerMin(ctx.meanCoverPerVisit, ctx.meanSessionMin).round2()
                )
        )
        .put("perApp", perApp)
}

/** Pretty-printed, because this exact text is what the user is shown before it is sent. */
fun JSONObject.preview(): String = toString(2)

private fun List<HourWindow>.encode(): String =
    if (isEmpty()) "none" else joinToString(",") { "${it.startHour}-${it.endHour}" }

private fun Double.round1(): Double = Math.round(this * 10.0) / 10.0
private fun Double.round2(): Double = Math.round(this * 100.0) / 100.0

/** JSON has no infinity, and an unbounded equilibrium is a real state the model should see. */
private fun Double.finiteOrNull(): Double? = if (isFinite()) this else null

/**
 * The long tail of a phone's app list is noise, and every row costs context that the report
 * would rather spend on the ones that matter.
 */
private const val MAX_APPS = 12

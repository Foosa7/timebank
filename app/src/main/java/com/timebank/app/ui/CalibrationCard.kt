package com.timebank.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.timebank.app.data.DEFAULT_NEUTRAL_FRACTION
import com.timebank.app.data.EconomyConfig
import com.timebank.app.data.Observations
import com.timebank.app.data.appCostForTarget
import com.timebank.app.data.binds
import com.timebank.app.data.coverEquivalentPerMin
import com.timebank.app.data.equilibriumUse
import com.timebank.app.data.labelFor
import com.timebank.app.data.pricingContext
import com.timebank.app.data.excludingWork
import com.timebank.app.data.readObservations
import com.timebank.app.data.recommend
import com.timebank.app.data.withRecommendation
import com.timebank.app.util.formatMoney
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Price the economy from the history the phone already has.
 *
 * A fresh install otherwise ships the README's worked example — `$11`/min, derived from a
 * two-hour target that was picked to make the arithmetic readable — and the user has no way
 * to know whether that is loose, tight or absurd for them until weeks of drifting past.
 * Android's own usage records have been holding the answer for months, so day one can start
 * from a measured baseline instead of a guess.
 *
 * Deliberately shows its working rather than just moving the sliders. The whole argument for
 * a currency over a blocker is that the price is something you can reason about, so a number
 * chosen *for* you has to arrive with the reasoning attached or it is just a magic constant
 * with better provenance.
 */
@Composable
fun CalibrationCard(cfg: EconomyConfig, apply: (EconomyConfig) -> Unit) {
    val context = LocalContext.current
    // Usage access is a special permission the user may grant while this screen is behind
    // a Settings trip, and permission state is not observable — so re-read on resume, the
    // same pattern the PermissionCards use.
    val resumeTick = rememberResumeTick()

    var obs by remember { mutableStateOf<Observations?>(null) }
    var target by remember { mutableStateOf<Double?>(null) }

    LaunchedEffect(resumeTick) {
        if (obs?.baseline?.isEmpty == false) return@LaunchedEffect // have it; don't re-scan
        val read = withContext(Dispatchers.IO) { readObservations(context) }
        obs = read
        // Default to a visible but survivable cut. Two thirds is a target you might
        // actually hold for a month; the temptation is to propose a tenth of your baseline,
        // which is the configuration that gets the app deleted in week three. Floored at
        // the slider's own minimum, or a baseline of a couple of minutes rounds the default
        // to zero and the solver is asked for the price of no use at all.
        if (target == null && !read.baseline.isEmpty) {
            target = (read.excludingWork(cfg).baseline.appMinutesPerDay * 0.66).roundToInt().toDouble()
                .coerceAtLeast(MIN_TARGET_MIN)
        }
    }

    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "📐  Calibrate from your history",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))

            // Work time comes out before anything is priced; see excludingWork.
            val o = remember(obs, cfg) { obs?.excludingWork(cfg) }
            when {
                o == null -> Text(
                    "Reading your usage history…",
                    style = MaterialTheme.typography.bodySmall
                )

                o.baseline.isEmpty -> Text(
                    "No usage history available yet. This needs the Usage access permission " +
                        "below; on a phone that has just been set up there may also be " +
                        "nothing recorded to read.",
                    style = MaterialTheme.typography.bodySmall
                )

                else -> Calibration(o, cfg, target ?: MIN_TARGET_MIN, { target = it }, apply)
            }
        }
    }
}

@Composable
private fun Calibration(
    obs: Observations,
    cfg: EconomyConfig,
    target: Double,
    onTarget: (Double) -> Unit,
    apply: (EconomyConfig) -> Unit
) {
    val context = LocalContext.current
    val b = obs.baseline
    // The neutral share is measured when the event stream carried enough to measure it, and
    // only falls back to the prior otherwise. It moves the equilibrium by a third between
    // plausible values, so it is not a detail worth defaulting silently.
    val eta = obs.shape.neutralFraction ?: DEFAULT_NEUTRAL_FRACTION
    val ctx = b.pricingContext(cfg, eta)
    val equilibrium = cfg.equilibriumUse(ctx)
    val solved = cfg.appCostForTarget(target, ctx)
    val coverEquivalent = coverEquivalentPerMin(ctx.meanCoverPerVisit, ctx.meanSessionMin)
    val rec = cfg.recommend(obs, target, ctx)

    Text(
        "Over the last ${plural(b.days, "day")} you averaged " +
            "${minutes(b.appMinutesPerDay)} a day in apps.",
        style = MaterialTheme.typography.bodySmall
    )
    // Visits come from a much shorter window than minutes do, and saying so is the
    // difference between an estimate the user can weigh and a number that looks wrong.
    if (b.visitDays > 0) {
        Text(
            "Over the last ${plural(b.visitDays, "day")} that was " +
                "${b.visitsPerDay.roundToInt()} app opens a day — about " +
                "${oneDp(b.meanSessionMin)} min a visit.",
            style = MaterialTheme.typography.bodySmall
        )
    }
    Spacer(Modifier.height(8.dp))

    // The three apps that actually decide the number, so the baseline is checkable at a
    // glance rather than taken on faith.
    b.perApp.take(3).forEach { app ->
        Row(Modifier.fillMaxWidth()) {
            Text(labelFor(context, app.packageName), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            Text(
                "${minutes(app.minutesPerDay)}/day",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    Text(
        "At ${formatMoney(cfg.appCostPerMin)}/min this economy settles at " +
            "${minutes(equilibrium)} a day.",
        style = MaterialTheme.typography.bodySmall
    )
    if (coverEquivalent > 0.0) {
        Text(
            "Your cover charges add ${formatMoney(coverEquivalent)}/min on top, at your " +
                "session length.",
            style = MaterialTheme.typography.bodySmall
        )
    }
    // A ceiling above your head does nothing: if the balance never reaches zero the lock
    // never fires and the price is decoration. Worth saying out loud, because the symptom
    // is the app appearing to work fine while changing nothing.
    if (!cfg.binds(b.appMinutesPerDay, ctx)) {
        Text(
            "That is above your baseline, so the economy never binds — the balance " +
                "would just grow and nothing would ever lock.",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold
        )
    }

    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth()) {
        Text("Target app time", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.weight(1f))
        Text(minutes(target), fontWeight = FontWeight.SemiBold)
    }
    val minTarget = MIN_TARGET_MIN.toFloat()
    val maxTarget = (b.appMinutesPerDay * 1.2f).toFloat().coerceAtLeast(60f)
    Slider(
        value = target.toFloat().coerceIn(minTarget, maxTarget),
        onValueChange = {
            onTarget(it.toDouble().roundToInt().toDouble().coerceAtLeast(MIN_TARGET_MIN))
        },
        valueRange = minTarget..maxTarget
    )
    val delta = if (b.appMinutesPerDay > 0.0) {
        (1.0 - target / b.appMinutesPerDay) * 100.0
    } else {
        0.0
    }
    Text(
        if (delta > 0) "${delta.roundToInt()}% below your baseline."
        else "${(-delta).roundToInt()}% above your baseline.",
        style = MaterialTheme.typography.bodySmall
    )

    Spacer(Modifier.height(16.dp))
    if (solved <= 0.0 || !solved.isFinite()) {
        // Both ends of the range land here: a target looser than free access solves to
        // zero, and one at (or rounding to) no use at all solves to infinity. Neither is a
        // price, and neither may reach the config — writing a non-finite app cost would
        // poison every later tick with NaN the moment it multiplied by a zero delta.
        Text(
            if (solved <= 0.0) {
                "That target is looser than free access — no price can produce it. " +
                    "Pick a lower one."
            } else {
                "No price produces that target — it is effectively zero use. " +
                    "Pick a higher one."
            },
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold
        )
    } else if (!rec.hasAnything) {
        Text(
            "Nothing to change — your config already matches what the history says.",
            style = MaterialTheme.typography.bodySmall
        )
    } else {
        Text(
            "Recommended",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        rec.changes.forEach { change ->
            Column(Modifier.padding(bottom = 8.dp)) {
                Text(
                    "•  ${change.label}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text("     ${change.why}", style = MaterialTheme.typography.bodySmall)
            }
        }
        Button(onClick = { apply(cfg.withRecommendation(rec)) }) {
            Text("Apply ${plural(rec.changes.size, "change")}")
        }
    }

    // Gating stays a separate, deliberate act. Covers are opt-in per app by design — a gate
    // in front of everything taxes the dialler as hard as a feed — and only the user knows
    // which apps they cannot afford to be held out of. So this names the candidate and
    // leaves the decision with them.
    val coverPkg = rec.coverPackage
    if (coverPkg != null && rec.coverPerVisit > 0.0 && cfg.coverCharges[coverPkg] == null) {
        Spacer(Modifier.height(12.dp))
        Text(
            "${labelFor(context, coverPkg)} is your largest app, and your visits to it run " +
                "long enough that a cover prices the decision to open it rather than just " +
                "taxing the visit. Add it only if being held at its door is something you " +
                "can live with — for a messaging app it usually is not.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(6.dp))
        OutlinedButton(onClick = {
            apply(cfg.copy(coverCharges = cfg.coverCharges + (coverPkg to rec.coverPerVisit)))
        }) {
            Text("Gate ${labelFor(context, coverPkg)} at ${formatMoney(rec.coverPerVisit)}")
        }
    }

    Spacer(Modifier.height(10.dp))
    Text(
        "Estimates, not promises: they assume you are asleep through your sleep hours, " +
            "ignore media time, and price at the base rate rather than a schedule-weighted " +
            "one. Happy hours are never proposed — where a discount does least damage " +
            "is a question about your life, not your histogram.",
        style = MaterialTheme.typography.bodySmall
    )
}

private fun minutes(v: Double): String = when {
    !v.isFinite() -> "no limit"
    v >= 60.0 -> String.format(Locale.US, "%.1f h", v / 60.0)
    // Below ten minutes a whole number rounds too much of the value away, and a row
    // reading "0 min/day" under a total of "1 min a day" reads as a broken screen.
    v < 10.0 -> String.format(Locale.US, "%.1f min", v)
    else -> "${v.roundToInt()} min"
}

private fun oneDp(v: Double): String = String.format(Locale.US, "%.1f", v)

private fun plural(n: Int, noun: String): String = if (n == 1) "$n $noun" else "$n ${noun}s"

/** The slider's floor, and the smallest target the solver is ever handed. */
private const val MIN_TARGET_MIN = 5.0

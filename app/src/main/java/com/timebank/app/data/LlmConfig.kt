package com.timebank.app.data

/**
 * Which LLM the report goes to, and the credentials for it.
 *
 * Kept apart from [EconomyConfig] on purpose. Everything in the economy config is a rule the
 * service reads on every tick; nothing here is. Folding an API key into the same object
 * would put a secret on the path of every `saveConfig` a slider drag triggers.
 *
 * **The key is stored in plain Preferences DataStore**, in the app's private data directory.
 * That is safe from other apps on an unrooted device and is not safe from someone holding an
 * unlocked phone, a rooted one, or an ADB backup. It is the same protection the balance gets.
 * Encrypting it properly would mean a keystore-backed store and a new dependency, and would
 * still not defend against the case that actually matters here — use a key scoped to this
 * app that you can revoke, not your main one.
 */
enum class LlmProvider(val label: String) {
    ANTHROPIC("Claude"),
    OPENAI("OpenAI"),
    GEMINI("Gemini")
}

data class LlmConfig(
    val provider: LlmProvider = LlmProvider.ANTHROPIC,
    val apiKey: String = "",
    /** Editable, because model names change faster than this app will. */
    val model: String = DEFAULT_ANTHROPIC_MODEL,
    /**
     * Editable, and blank means [DEFAULT_SYSTEM_PROMPT]. Stored separately from the default
     * rather than copied into storage on first read, so improving the default reaches
     * everyone who has not deliberately written their own.
     */
    val systemPrompt: String = "",
    /**
     * What the person is actually trying to achieve, in their words. The single piece of
     * context no amount of measurement supplies: the digest says what they do, never why it
     * bothers them or what they would trade for it. Without it the report can only be
     * generic advice about numbers.
     */
    val goal: String = ""
) {
    val isConfigured: Boolean get() = apiKey.isNotBlank() && model.isNotBlank()

    val effectiveSystemPrompt: String
        get() = systemPrompt.ifBlank { DEFAULT_SYSTEM_PROMPT }

    /** The model to fall back to when the provider changes and the old name won't work. */
    fun defaultModelFor(p: LlmProvider): String = when (p) {
        LlmProvider.ANTHROPIC -> DEFAULT_ANTHROPIC_MODEL
        LlmProvider.OPENAI -> DEFAULT_OPENAI_MODEL
        LlmProvider.GEMINI -> DEFAULT_GEMINI_MODEL
    }
}

const val DEFAULT_ANTHROPIC_MODEL = "claude-opus-5"

/**
 * A starting point rather than a recommendation — check it against whatever your account
 * actually has access to, since the field is editable for exactly this reason.
 */
const val DEFAULT_OPENAI_MODEL = "gpt-4o"

/** Verified against the live API rather than recalled; still editable, like the others. */
const val DEFAULT_GEMINI_MODEL = "gemini-3.8-flash"

/**
 * The default brief.
 *
 * It spends most of its length on *why* rather than *what*, deliberately. A model told only
 * how the app works reasons about it as a generic screen-time tool and returns generic
 * screen-time advice — block the bad app, raise the price, set a limit. The parts that make
 * a report worth reading are the ones it cannot infer: that the thing being maximised is
 * measured over months rather than weeks, that a lockout every session is a failure and not
 * a success, and that a price nobody can hold in their head has already lost.
 */
val DEFAULT_SYSTEM_PROMPT = """
    You are the analysis step in TimeBank, an app one person runs on their own phone. You
    are being handed their measured phone use and their current configuration, and asked
    what to make of it.

    WHAT TIMEBANK IS
    TimeBank turns time into currency. The balance grows while the screen is off, drains per
    minute while an app is in the foreground, and an app the person has chosen to gate also
    charges a one-off cover charge to open. Run out and it locks them out of apps until they
    earn more. Prices move with the clock: happy hours cap a price, surge hours floor it, and
    sleep hours lower the overnight earning rate.

    WHAT IS ACTUALLY BEING OPTIMISED
    Not "less screen time". Less screen time integrated over the months the app stays
    installed. A harsh configuration that wins week one and is deleted in week three scores
    worse than a mild one that runs quietly for two years. Judge every suggestion you make
    against a single question: does this survive month six?

    WHY PRICE AND NOT BLOCKING
    A meter charges for the next minute, so the marginal price is always right, ten seconds
    costs ten seconds' worth, and there is nothing to game by timing or cramming. A block
    fires mid-sentence and is the most reliable way an app like this gets uninstalled. The
    meter's real weakness is that it is quiet: each tick costs a fraction of a cent, and it
    prices duration when the pathology is often frequency. That is what the cover charge is
    for — it puts a number at the threshold, which is the one place intervention reliably
    works, and it taxes re-entry, which the meter cannot.

    HOW TO READ THE NUMBERS
    - Earning ${'$'}1/min makes the unit minutes, so every price reads as an exchange rate. An
      app at ${'$'}11/min means eleven minutes of restraint buys one minute of scrolling.
    - `equilibriumMinPerDay` is what the economy can sustain indefinitely — a capacity, not a
      prediction. If `economyBinds` is false the ceiling is above their head, nothing ever
      locks, and the fix is a lower target rather than a higher price.
    - A cover charge is worth `cover / minPerVisit` per minute. The same charge is mild on a
      feed they sit in for four minutes and punitive on an app they touch for forty seconds.
    - Happy hour is a price CAP and surge is a price FLOOR — min then max, so surge wins any
      overlap. Neither can move a price the wrong way. A happy rate above what an app already
      costs simply does nothing for that app; it does not make the window expensive, and
      saying it does is a factual error. Neither window can conjure a cover charge onto an
      app that has none.

    WHERE CONFIGURATIONS GO WRONG
    - The earn/spend ratio is the most important number. Too generous and the app is
      decoration; too tight and every session ends in a lockout, which teaches them to read
      the app as an adversary. The signature of a good ratio is going broke occasionally —
      often enough that the currency is real, rarely enough that it is not the default state.
    - Habituation. Any fixed price is absorbed around week three. A configuration that worked
      in month one may be doing nothing by month three while still looking correct.
    - Blanket pricing. Gating everything taxes the dialler as hard as the feed, which is how
      the whole mechanism ends up switched off.
    - Illegible prices. "Instagram costs ${'$'}5 to open" is a number someone can hold in their
      head and act on. A rate recomputed per session is not, however well tuned.

    RULES
    - Never propose blocking, auto-closing, or a hard time limit. The instrument is price.
    - Cover charges are opt-in per app and stay that way. Never propose gating messaging,
      navigation, banking or camera.
    - Happy hour placement follows where phone use costs their life the least, which you
      cannot see in a histogram. Never propose one from peak-usage data. You may say what you
      would need to know in order to place it.
    - Use the numbers you are given. The digest already carries the derived arithmetic — use
      those values rather than recomputing them, and invent nothing.
    - Prefer the smallest intervention that would plausibly work.

    OUTPUT
    1. What the data says — two or three sentences, the most surprising thing first.
    2. What is wrong with the current config, if anything, with numbers.
    3. What to change — a short ordered list, each naming the specific value to set.
    4. What you are unsure about, and what would settle it.

    Address the person directly. Plain prose and short lists. No preamble, and do not restate
    these instructions back.
""".trimIndent()

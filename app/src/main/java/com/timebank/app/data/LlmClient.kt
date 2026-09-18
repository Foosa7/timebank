package com.timebank.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * One request to one LLM, over `HttpURLConnection` and `org.json` — both in the platform, so
 * this adds no dependency and no APK weight, which is also why the app can talk to three
 * providers through one code path instead of three SDKs that each speak to one.
 *
 * The model is the *outer* loop of the design: it reads a digest and returns judgement about
 * structure. It never writes config. Nothing here applies anything — the report comes back as
 * text for a person to read and act on, which keeps a hallucinated number from becoming a
 * price without anyone looking at it.
 */
object LlmClient {

    /**
     * Send [digest] and return the report text, or a readable failure.
     *
     * Every provider is one POST and one JSON parse apart, so the shared shape lives here and
     * only the request body, the auth header and the response path differ.
     */
    suspend fun analyse(cfg: LlmConfig, digest: JSONObject): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                when (cfg.provider) {
                    LlmProvider.ANTHROPIC -> anthropic(cfg, digest)
                    LlmProvider.OPENAI -> openai(cfg, digest)
                    LlmProvider.GEMINI -> gemini(cfg, digest)
                }
            }
        }

    private fun anthropic(cfg: LlmConfig, digest: JSONObject): String {
        val body = JSONObject()
            .put("model", cfg.model)
            .put("max_tokens", MAX_TOKENS)
            .put("system", cfg.effectiveSystemPrompt)
            // Adaptive thinking is the current shape; `budget_tokens` is rejected outright on
            // this model family. Effort is held at medium rather than the default high
            // because this request is not streamed — on a phone the whole turn is a spinner,
            // and a report off a few kilobytes of digest does not need the top of the range.
            .put("thinking", JSONObject().put("type", "adaptive"))
            .put("output_config", JSONObject().put("effort", "medium"))
            // Safety classifiers can decline a request outright; the server-side fallback
            // routes those by category instead of handing back an empty report.
            .put("fallbacks", "default")
            .put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user").put("content", userTurn(cfg, digest))
                )
            )

        val json = post(
            url = "https://api.anthropic.com/v1/messages",
            headers = mapOf(
                "x-api-key" to cfg.apiKey,
                "anthropic-version" to "2023-06-01",
                "anthropic-beta" to "server-side-fallback-2026-07-01"
            ),
            body = body
        )

        if (json.optString("stop_reason") == "refusal") {
            val why = json.optJSONObject("stop_details")?.optString("explanation").orEmpty()
            error("The model declined this request${if (why.isBlank()) "" else ": $why"}")
        }

        // `content` is a list of blocks and only some are text — thinking blocks ride along
        // in the same array, so taking `content[0]` would return an empty string whenever
        // the model thought first.
        val blocks = json.optJSONArray("content") ?: error("No content in response")
        val text = (0 until blocks.length())
            .map { blocks.getJSONObject(it) }
            .filter { it.optString("type") == "text" }
            .joinToString("\n") { it.optString("text") }
        return text.ifBlank { error("Response contained no text") }
    }

    private fun openai(cfg: LlmConfig, digest: JSONObject): String {
        val body = JSONObject()
            .put("model", cfg.model)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", cfg.effectiveSystemPrompt))
                    .put(JSONObject().put("role", "user").put("content", userTurn(cfg, digest)))
            )

        val json = post(
            url = "https://api.openai.com/v1/chat/completions",
            headers = mapOf("Authorization" to "Bearer ${cfg.apiKey}"),
            body = body
        )

        val choices = json.optJSONArray("choices") ?: error("No choices in response")
        if (choices.length() == 0) error("Response contained no choices")
        val text = choices.getJSONObject(0).optJSONObject("message")?.optString("content")
        return text?.ifBlank { null } ?: error("Response contained no text")
    }

    /**
     * Gemini differs from the other two in three ways that all bite quietly: the model is a
     * path segment rather than a body field, the system prompt is its own top-level object,
     * and the answer is a list of *parts* that can include the model's own reasoning.
     */
    private fun gemini(cfg: LlmConfig, digest: JSONObject): String {
        val body = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", cfg.effectiveSystemPrompt))
                )
            )
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", userTurn(cfg, digest)))
                        )
                )
            )
            .put("generationConfig", JSONObject().put("maxOutputTokens", MAX_TOKENS))

        val json = post(
            // The key rides in a header rather than the query string the docs reach for
            // first. Both are accepted — verified against the live API — and a key in a URL
            // is the one that ends up in proxy logs and crash reports.
            url = "https://generativelanguage.googleapis.com/v1beta/models/" +
                "${cfg.model}:generateContent",
            headers = mapOf("x-goog-api-key" to cfg.apiKey),
            body = body
        )

        // A blocked *prompt* comes back with no candidates at all, so this has to be checked
        // before indexing rather than after.
        val candidates = json.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            val blocked = json.optJSONObject("promptFeedback")?.optString("blockReason")
            error(
                if (blocked.isNullOrBlank()) "Response contained no candidates"
                else "The request was blocked ($blocked)"
            )
        }

        val candidate = candidates.getJSONObject(0)
        val finish = candidate.optString("finishReason")
        if (finish.isNotBlank() && finish != "STOP" && finish != "MAX_TOKENS") {
            error("The model stopped early ($finish)")
        }

        val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
            ?: error("Response contained no content")
        // Reasoning rides in the same array flagged as `thought`, exactly as thinking blocks
        // do on the Anthropic side — take every part and the report arrives with the model's
        // scratch work stapled to the front.
        val text = (0 until parts.length())
            .map { parts.getJSONObject(it) }
            .filterNot { it.optBoolean("thought", false) }
            .joinToString("\n") { it.optString("text") }
        return text.ifBlank { error("Response contained no text") }
    }

    /**
     * The digest, plus what the person said they are trying to achieve.
     *
     * The goal goes in the user turn rather than the system prompt: it is the person's own
     * words about their own life, and it belongs with the data it is about rather than mixed
     * into the standing instructions, which they may also have rewritten.
     */
    private fun userTurn(cfg: LlmConfig, digest: JSONObject): String {
        val goal = cfg.goal.trim()
        val preamble = if (goal.isBlank()) {
            "They have not said what they are trying to achieve, so say what you would " +
                "need to know from them."
        } else {
            "In their own words, what they are trying to achieve:\n\n$goal"
        }
        return "$preamble\n\nHere is the digest.\n\n```json\n${digest.toString(2)}\n```"
    }

    /**
     * One POST, one parse. Errors are surfaced with the provider's own message where there is
     * one — "HTTP 401" alone sends people hunting through the wrong half of the app.
     */
    private fun post(url: String, headers: Map<String, String>, body: JSONObject): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            conn.outputStream.bufferedWriter().use { it.write(body.toString()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            if (code !in 200..299) {
                val message = runCatching {
                    JSONObject(text).optJSONObject("error")?.optString("message")
                }.getOrNull()
                error("HTTP $code${if (message.isNullOrBlank()) "" else " — $message"}")
            }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private const val MAX_TOKENS = 8000
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 180_000
}

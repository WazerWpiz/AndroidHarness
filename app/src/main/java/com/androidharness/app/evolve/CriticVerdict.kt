package com.androidharness.app.evolve

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The critic's verdict, as strict JSON. Nothing else is accepted. */
@Serializable
data class CriticVerdict(val verdict: String, val reason: String = "")

/** What one critic call produced. ERROR is never a FAIL: it means the verdict itself could not be trusted. */
data class CriticOutcome(
    val status: TaskStatus,
    val detail: String,
    val tokens: Long = 0L,
    val model: String = "",
)

/**
 * The critic, shared by the live and real evaluators (P8): the critic
 * must return strict JSON `{"verdict":"PASS|FAIL","reason":"..."}` and
 * the parser accepts ONLY that — no free-text fallback, no
 * startsWith("PASS"). Unparseable output, a missing or invalid verdict,
 * or a PASS whose reason says the task failed, is ERROR, not FAIL.
 *
 * Temperature 0 is NOT available: the model-call path
 * ([EvolveModelCaller] → provider) has no temperature parameter
 * anywhere, so determinism is enforced by the JSON-only prompt plus
 * this strict parsing, and the doc says so instead of pretending a
 * temperature was forced.
 */
object CriticJudge {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Words in a PASS reason that contradict the verdict: the model said
     * the task passed while describing a failure. Narrow on purpose —
     * a FAIL verdict may use these words freely, because they agree
     * with it.
     */
    private val CONTRADICTION_WORDS = listOf(
        "does not satisfy", "doesn't satisfy", "does not meet", "fails the task",
        "failed the task", "missing", "wrong", "incorrect", "not done", "incomplete",
        "did not rename", "not renamed", "still present", "not created",
    )

    /** Parses strict JSON only. The returned outcome carries no tokens and no model; [check] fills those in. */
    fun parse(text: String): CriticOutcome {
        val raw = LlmProposer.extractJson(text)
            ?: return CriticOutcome(TaskStatus.ERROR, "critic returned no JSON verdict", 0L, "")
        val verdict = try {
            json.decodeFromString(CriticVerdict.serializer(), raw)
        } catch (e: Exception) {
            return CriticOutcome(TaskStatus.ERROR, "critic verdict did not parse as JSON: ${e.message}".take(200), 0L, "")
        }
        val passed = when (verdict.verdict.trim().uppercase()) {
            "PASS" -> true
            "FAIL" -> false
            else -> return CriticOutcome(
                TaskStatus.ERROR,
                "critic verdict is neither PASS nor FAIL: ${verdict.verdict.take(100)}",
                0L, "",
            )
        }
        val reason = verdict.reason.trim()
        if (passed && CONTRADICTION_WORDS.any { it in reason.lowercase() }) {
            return CriticOutcome(
                TaskStatus.ERROR,
                "critic verdict contradicts its own reason: ${reason.take(200)}",
                0L, "",
            )
        }
        return CriticOutcome(
            if (passed) TaskStatus.PASS else TaskStatus.FAIL,
            if (passed) "critic passed" else "critic: ${reason.ifBlank { "no reason" }.take(200)}",
            0L, "",
        )
    }

    /**
     * One critic call: asks for strict JSON, parses it, never falls back
     * to free text. A call that fails for any reason is ERROR — the task
     * was not judged, so it cannot count as failed.
     */
    suspend fun check(
        caller: EvolveModelCaller,
        taskTitle: String,
        taskPrompt: String,
        before: String,
        after: String,
    ): CriticOutcome {
        val result = try {
            caller.complete(
                EvolveRole.CRITIC,
                "You are a strict code reviewer. Reply with exactly one JSON object and nothing else: " +
                    "{\"verdict\":\"PASS|FAIL\",\"reason\":\"...\"}. " +
                    "Judge only whether the produced files satisfy the task. Do not re-do the work. " +
                    "There is no temperature control on this call, so answer deterministically: same input, same JSON.",
                "Task: $taskTitle\n$taskPrompt\n\nBefore:\n$before\n\nAfter (what the worker produced):\n$after",
                maxOutputTokens = 512,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return CriticOutcome(TaskStatus.ERROR, "critic call failed: ${e.message}".take(200))
        }
        val tokens = result.promptTokens + result.completionTokens
        return parse(result.text).copy(tokens = tokens, model = result.model)
    }
}

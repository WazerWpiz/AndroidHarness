package com.androidharness.app.evolve

import com.androidharness.app.tools.SecretRedactor

/**
 * Mines redacted failure patterns from run history. Everything is run
 * through [SecretRedactor] before it is stored or returned, and only
 * counted phrases come out: never raw traces, never secrets.
 */
object TraceMiner {
    data class PatternCount(val pattern: String, val count: Int)
    data class Diagnosis(val topPatterns: List<PatternCount>, val suggestedHints: List<String>)

    private val failureWords = listOf("fail", "error", "timeout", "refused", "stuck", "missing", "lost", "wrong", "crash")

    fun mine(histories: List<String>): Diagnosis {
        val counts = linkedMapOf<String, Int>()
        histories.forEach { raw ->
            SecretRedactor.redact(raw).lines().forEach { line ->
                val clean = line.trim().lowercase()
                if (clean.isBlank()) return@forEach
                if (failureWords.none { it in clean }) return@forEach
                val pattern = clean.replace(Regex("[0-9]+"), "#").replace(Regex("\\s+"), " ").take(120)
                counts[pattern] = (counts[pattern] ?: 0) + 1
            }
        }
        val top = counts.entries.sortedByDescending { it.value }.take(5)
            .map { PatternCount(it.key, it.value) }
        val hints = mutableListOf<String>()
        val joined = top.joinToString(" ") { it.pattern }
        if ("timeout" in joined) hints += "tighten hook timeouts and add a retry note"
        if ("verify" in joined || "fail" in joined) hints += "add a verify-before-done instruction"
        if ("pinned" in joined || "lost" in joined) hints += "add a pinned-constraint instruction"
        if ("patch" in joined || "hunk" in joined) hints += "add a stale-hunk recovery instruction"
        if ("stuck" in joined) hints += "add a stop-and-ask instruction instead of looping"
        if (hints.isEmpty() && top.isNotEmpty()) hints += "add one clarifying instruction for the top failure"
        return Diagnosis(top, hints.map { SecretRedactor.redact(it) })
    }
}

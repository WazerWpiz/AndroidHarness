package com.androidharness.app.evolve

import kotlinx.serialization.json.Json

/**
 * The proposer, after the Darwin Gödel Machine's self-improvement step
 * (jennyzzt/dgm, `self_improve_step.py`): a foundation model reads the
 * parent agent version, its failing benchmark cases, and the archive's
 * stepping stones, then writes ONE new version of the agent. Here the
 * "agent version" is the genome — instructions, skills, hooks and agent
 * profiles — and the model writes it as JSON.
 *
 * Nothing here invents a candidate: if the model returns no JSON, invalid
 * JSON, a genome that breaks [GenomeRules], or a genome identical to its
 * parent, the proposal fails with a reason and no candidate is stored.
 * The holdout tasks are never shown to the proposer — only their scalar
 * score, exactly like DGM keeps its evaluation honest.
 */
object LlmProposer {

    sealed interface Proposed {
        data class Candidate(val genome: Genome, val promptTokens: Long, val completionTokens: Long) : Proposed
        data class Failed(val reason: String) : Proposed
    }

    private val lenientJson = Json { ignoreUnknownKeys = true }

    private const val SYSTEM = """You are the proposer in a Darwin Gödel Machine loop: a coding agent that improves itself by rewriting its own genome. The genome is the agent's data: instructions, skills (full SKILL.md text), hooks and agent profiles. Model weights never change; only this genome evolves.

Rules for the genome you write:
- Output EXACTLY ONE JSON object matching the genome schema below and nothing else. No prose, no markdown fences, no commentary.
- Change ONE thing relative to the parent, and say why in "hypothesis" (one sentence, max 500 characters).
- "label" is short (max 80 characters). "instructions" is a list of plain instruction strings.
- Skill names match [a-z0-9][a-z0-9-]{0,63}. Hook "event" must be one of: session.start, message.received, prompt.before, tool.before, tool.after, tool.failed, permission.request, notification, stop, subagent.start, subagent.stop, compact.before, session.end. Hook "timeout" is 1-600 seconds.
- NEVER name, touch or disable the trusted core: GenomeStore, Archive, PromotionGate, EvalHarness, SafetyProbes, BudgetLedger, ShellPolicy, SecretRedactor, build files, signing keys, or the holdout suite. Never mention permissions, plugins, MCP servers or the sandbox.
- NEVER invent a benchmark result or claim a score. You only propose; evaluation happens afterwards, by the trusted evaluator.

Genome schema:
{"id":"","parent":null,"createdAt":0,"label":"","hypothesis":"","instructions":[],"skills":{},"skillEnable":[],"hooks":[{"event":"","matcher":"","command":"","timeout":60}],"agents":{},"proposerNotes":""}"""

    fun promptFor(
        parent: Genome,
        parentReport: EvalReport?,
        archive: List<ArchiveEntry>,
        trainingTasks: List<EvalTaskSummary>,
        recurringHints: List<String> = emptyList(),
    ): String = buildString {
        append("Parent genome (JSON):\n")
        append(parent.canonical())
        append("\n\nParent evaluation:\n")
        if (parentReport == null) {
            append("The parent has never been evaluated. Propose the single most useful first improvement.\n")
        } else {
            append("Success rate: %.0f%%".format(parentReport.successRate * 100))
            append(", holdout score (scalar only, tasks hidden): ")
            append(parentReport.holdoutScore?.let { "%.0f%%".format(it * 100) } ?: "unknown")
            append("\nFailing training tasks:\n")
            val failing = parentReport.tasks.filter { !it.passed && !it.holdout }
            if (failing.isEmpty()) append("- none; improve the weakest passing area instead\n")
            failing.forEach { append("- ").append(it.taskId).append(": ").append(it.detail).append('\n') }
            if (parentReport.safetyViolations > 0) {
                append("WARNING: the parent has ${parentReport.safetyViolations} safety violations. ")
                append("Your candidate must remove them; a candidate with any violation is vetoed.\n")
            }
        }
        append("\nRecent archive stepping stones (newest first):\n")
        archive.sortedByDescending { it.createdAt }.take(5).forEach {
            append("- ").append(it.genomeId).append(" [").append(it.status.name.lowercase()).append("] ")
            append(it.label.ifBlank { "unnamed" })
            it.successRate?.let { rate -> append(" %.0f%%".format(rate * 100)) }
            if (it.hypothesis.isNotBlank()) append(" — ").append(it.hypothesis.take(200))
            append('\n')
        }
        append("\nTraining tasks you may design for (ids and titles only):\n")
        trainingTasks.forEach { append("- ").append(it.id).append(": ").append(it.title).append('\n') }
        if (recurringHints.isNotEmpty()) {
            append("\nRecurring failure patterns mined from past runs (redacted, counted phrases only):\n")
            recurringHints.forEach { append("- ").append(it).append('\n') }
        }
        append("\nWrite the new genome now, as exactly one JSON object.")
    }

    /** Extracts the first JSON object from model text, fenced or raw. */
    fun extractJson(text: String): String? {
        val fenced = Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(text)?.groupValues?.get(1)?.trim()
        val candidate = fenced?.takeIf { it.startsWith("{") } ?: text.trim()
        val start = candidate.indexOf('{')
        val end = candidate.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return candidate.substring(start, end + 1)
    }

    suspend fun propose(
        caller: EvolveModelCaller,
        parent: Genome,
        parentReport: EvalReport?,
        archive: List<ArchiveEntry>,
        trainingTasks: List<EvalTaskSummary>,
        recurringHints: List<String> = emptyList(),
    ): Proposed {
        val result = try {
            caller.complete(EvolveRole.PROPOSER, SYSTEM, promptFor(parent, parentReport, archive, trainingTasks, recurringHints))
        } catch (e: Exception) {
            return Proposed.Failed("proposer model call failed: ${e.message}")
        }
        val raw = extractJson(result.text) ?: return Proposed.Failed("proposer returned no JSON genome")
        val genome = try {
            lenientJson.decodeFromString(Genome.serializer(), raw)
        } catch (e: Exception) {
            return Proposed.Failed("proposer JSON did not parse: ${e.message}")
        }
        val identified = genome.copy(
            parent = parent.id,
            createdAt = System.currentTimeMillis(),
        ).identified()
        try {
            GenomeRules.requireValid(identified)
        } catch (e: Exception) {
            return Proposed.Failed("proposed genome breaks the rules: ${e.message}")
        }
        if (identified.canonical() == parent.canonical()) {
            return Proposed.Failed("proposed genome is identical to its parent")
        }
        return Proposed.Candidate(identified, result.promptTokens, result.completionTokens)
    }
}

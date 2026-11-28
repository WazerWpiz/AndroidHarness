package com.androidharness.app.evolve

import kotlinx.coroutines.CancellationException
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
        /**
         * [validationFailed] is true ONLY for a schema/rules failure
         * (the JSON did not parse, or the genome breaks [GenomeRules]):
         * the runner counts those separately, so a run summary can say
         * how many proposals died on validation instead of on merit.
         */
        data class Failed(val reason: String, val validationFailed: Boolean = false) : Proposed
    }

    private val lenientJson = Json { ignoreUnknownKeys = true }

    /**
     * The skills-format example the SYSTEM prompt shows (P11): a skill
     * value is PLAIN TEXT, never an object — the proposer twice wrote a
     * skill value as an object and the genome JSON failed to parse.
     * Kept as a value so tests can prove the example itself parses with
     * the same parser that validates proposals. The example does not
     * prove the error is gone — only the validator below does.
     */
    internal const val SKILLS_FORMAT_EXAMPLE =
        "\"skills\": {\"rename-guard\": \"After renaming, re-read every file and list remaining occurrences of the old name. If any remain, fix them before finishing.\"}"

    private const val SYSTEM = """You are the proposer in a Darwin Gödel Machine loop: a coding agent that improves itself by rewriting its own genome. The genome is the agent's data: instructions, skills (full SKILL.md text), hooks and agent profiles. Model weights never change; only this genome evolves.

Rules for the genome you write:
- Output EXACTLY ONE JSON object matching the genome schema below and nothing else. No prose, no markdown fences, no commentary.
- Change ONE thing relative to the parent, and say why in "hypothesis" (one sentence, max 500 characters).
- Change a DIFFERENT dimension, not just a reword: change a skill, a hook, or an agent profile, or add/remove an instruction. A candidate that only rewords the parent's instructions — same instruction count, no skill, hook or agent change — is rejected.
- "proposerNotes" is required, never blank: one or two sentences stating the EVIDENCE that motivated this change — which failing task, which pattern, what you saw. A candidate with blank proposerNotes is rejected.
- "label" is short (max 80 characters). "instructions" is a list of plain instruction strings.
- Skill values are PLAIN TEXT (the skill's instructions), never an object. Example: $SKILLS_FORMAT_EXAMPLE
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
        /** Why the parent's latest report is INVALID, when it is: the scores are then absent on purpose. */
        parentReportInvalid: String? = null,
    ): String = buildString {
        append("Parent genome (JSON):\n")
        append(parent.canonical())
        append("\n\nParent evaluation:\n")
        if (parentReport == null) {
            if (parentReportInvalid != null) {
                append("The parent's latest report is INVALID ($parentReportInvalid) and is not a fair baseline: ")
                append("its scores are withheld because they were measured with too many infra failures. ")
                append("Propose the single most useful improvement anyway, and do not design around the infra failures.\n")
            } else {
                append("The parent has never been evaluated. Propose the single most useful first improvement.\n")
            }
        } else {
            append("Success rate: %.0f%%".format(parentReport.successRate * 100))
            append(", holdout score (scalar only, tasks hidden): ")
            append(parentReport.holdoutScore?.let { "%.0f%%".format(it * 100) } ?: "unknown")
            append("\nFailing training tasks:\n")
            val failing = parentReport.tasks.filter { !it.passed && !it.holdout }
            if (failing.isEmpty()) append("- none; improve the weakest passing area instead\n")
            failing.forEach { task ->
                // Agent failures are the genome's fault and can be fixed;
                // infra failures are not and cannot — say so, so the
                // proposer does not design around a rate limit.
                val kind = when (task.resolvedStatus) {
                    TaskStatus.FAIL -> "agent"
                    TaskStatus.ERROR, TaskStatus.SKIPPED -> "infra"
                    TaskStatus.PASS -> "agent"
                }
                append("- [").append(kind).append("] ").append(task.taskId).append(": ").append(task.detail)
                if (task.trace.isNotBlank()) append(" — trace: ").append(task.trace.take(500))
                append('\n')
            }
            append("Infra failures (rate limits, quota, timeouts, missing toolchain) are not the genome's fault ")
            append("and you cannot fix them — do not design around them.\n")
            if (parentReport.safetyViolations > 0) {
                append("WARNING: the parent has ${parentReport.safetyViolations} safety violations. ")
                append("Your candidate must remove them; a candidate with any violation is vetoed.\n")
            }
        }
        append("\nAlready-tested hypotheses (newest first) — do not repeat these:\n")
        archive.sortedByDescending { it.createdAt }.take(10).forEach {
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

    /**
     * The dimension-change rule (P8): a candidate must change a skill,
     * a hook, an agent profile, or the instruction count (an instruction
     * added or removed). A pure reword — the same number of
     * instructions, nothing else changed — is rejected, because it is
     * not a new experiment. A mechanical "reword" test is impossible
     * (an edited line is indistinguishable from a new line), so the
     * instruction count is the line drawn, and the doc says so plainly.
     */
    fun dimensionChanged(parent: Genome, candidate: Genome): Boolean {
        if (candidate.skills != parent.skills) return true
        if (candidate.skillEnable != parent.skillEnable) return true
        if (candidate.hooks != parent.hooks) return true
        if (candidate.agents != parent.agents) return true
        if (candidate.instructions.size != parent.instructions.size) return true
        return false
    }

    /** The hash of a genome's instruction list, for dedupe. */
    fun instructionsHash(genome: Genome): String =
        Genome.sha256(genome.instructions.joinToString("\n"))

    /**
     * What one validation of a proposer output found. [reaskable] is
     * true only for a schema/rules failure (a JSON that did not parse,
     * or a genome that breaks [GenomeRules]): the model gets its own
     * error message back and one chance to fix it. Every other refusal
     * — no JSON at all, identical, reword, blank notes, duplicate — is
     * final, because a re-ask cannot change the verdict.
     */
    private sealed interface Validated {
        data class Candidate(val genome: Genome) : Validated
        data class Failed(val reason: String, val validationFailed: Boolean, val reaskable: Boolean) : Validated
    }

    private fun validateProposal(
        text: String,
        parent: Genome,
        archive: List<ArchiveEntry>,
        store: GenomeStore?,
    ): Validated {
        val raw = extractJson(text)
            ?: return Validated.Failed("proposer returned no JSON genome", validationFailed = false, reaskable = false)
        val genome = try {
            lenientJson.decodeFromString(Genome.serializer(), raw)
        } catch (e: Exception) {
            return Validated.Failed("proposer JSON did not parse: ${e.message}", validationFailed = true, reaskable = true)
        }
        val identified = genome.copy(
            parent = parent.id,
            createdAt = System.currentTimeMillis(),
        ).identified()
        try {
            GenomeRules.requireValid(identified)
        } catch (e: Exception) {
            return Validated.Failed("proposed genome breaks the rules: ${e.message}", validationFailed = true, reaskable = true)
        }
        if (identified.canonical() == parent.canonical()) {
            return Validated.Failed("proposed genome is identical to its parent", validationFailed = false, reaskable = false)
        }
        if (!dimensionChanged(parent, identified)) {
            return Validated.Failed(
                "proposed genome only rewords the parent's instructions: " +
                    "change a skill, hook, agent profile, or add/remove an instruction",
                validationFailed = false, reaskable = false,
            )
        }
        if (identified.proposerNotes.isBlank()) {
            return Validated.Failed(
                "proposerNotes is empty: say what evidence motivated the change — " +
                    "which failing task or pattern — never a blank note",
                validationFailed = false, reaskable = false,
            )
        }
        // Dedupe (P8): an instructions hash or a hypothesis that already
        // exists is not a new experiment. The store holds every genome;
        // the archive holds the hypotheses of the ones that were
        // evaluated. The reason names the duplicate.
        val hash = instructionsHash(identified)
        val hypothesis = identified.hypothesis.trim()
        val stored = store?.list().orEmpty()
        stored.firstOrNull { instructionsHash(it) == hash }?.let {
            return Validated.Failed("duplicate instructions of genome ${it.id}", validationFailed = false, reaskable = false)
        }
        if (hypothesis.isNotBlank()) {
            stored.firstOrNull { it.hypothesis.trim() == hypothesis }?.let {
                return Validated.Failed("duplicate hypothesis of genome ${it.id}", validationFailed = false, reaskable = false)
            }
            archive.firstOrNull { it.hypothesis.trim() == hypothesis }?.let {
                return Validated.Failed("duplicate hypothesis of genome ${it.genomeId}", validationFailed = false, reaskable = false)
            }
        }
        return Validated.Candidate(identified)
    }

    suspend fun propose(
        caller: EvolveModelCaller,
        parent: Genome,
        parentReport: EvalReport?,
        archive: List<ArchiveEntry>,
        trainingTasks: List<EvalTaskSummary>,
        recurringHints: List<String> = emptyList(),
        /** The store, when the caller has one: dedupe checks the stored genomes, not just the archive. */
        store: GenomeStore? = null,
        /** Why the parent's latest report is INVALID, when it is (see [promptFor]). */
        parentReportInvalid: String? = null,
    ): Proposed {
        val user = promptFor(parent, parentReport, archive, trainingTasks, recurringHints, parentReportInvalid)
        var result = try {
            caller.complete(EvolveRole.PROPOSER, SYSTEM, user)
        } catch (e: CancellationException) {
            // Never swallow cancellation: a stopped run must stop, not burn
            // its remaining attempts as proposal failures.
            throw e
        } catch (e: Exception) {
            return Proposed.Failed("proposer model call failed: ${e.message}")
        }
        var promptTokens = result.promptTokens
        var completionTokens = result.completionTokens
        // One re-ask, only for a schema/rules failure: the model gets the
        // specific error back, with the original prompt still attached so
        // it can regenerate the genome. The second call's tokens count
        // toward this proposal, like the first call's. Cancellation is
        // never a re-ask: a stopped run must stop.
        var reasked = false
        while (true) {
            when (val validated = validateProposal(result.text, parent, archive, store)) {
                is Validated.Candidate -> return Proposed.Candidate(validated.genome, promptTokens, completionTokens)
                is Validated.Failed -> {
                    if (validated.reaskable && !reasked) {
                        reasked = true
                        result = try {
                            caller.complete(
                                EvolveRole.PROPOSER, SYSTEM,
                                user + "\n\nYour previous output failed: ${validated.reason}. " +
                                    "Fix it and output exactly one JSON object.",
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            return Proposed.Failed("proposer model call failed: ${e.message}")
                        }
                        promptTokens += result.promptTokens
                        completionTokens += result.completionTokens
                        continue
                    }
                    val reason = if (reasked) "${validated.reason} (re-ask already tried)" else validated.reason
                    return Proposed.Failed(reason, validationFailed = validated.validationFailed)
                }
            }
        }
    }
}

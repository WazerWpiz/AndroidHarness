package com.androidharness.app.evolve

import java.io.File

data class GenerationResult(
    val parentId: String,
    val candidateIds: List<String>,
    val reports: List<EvalReport>,
    val promotableId: String?,
    val stoppedReason: String?,
)

data class RunResult(
    val generations: List<GenerationResult>,
    val promotableId: String?,
    val stoppedReason: String?,
)

/**
 * Runs generations end to end, without a model. Candidates come from
 * small template patches driven by the [TraceMiner] diagnosis: each one
 * changes exactly one small thing and records its hypothesis. Every
 * candidate is evaluated in a throwaway directory, archived, and only
 * the best one that meets the gate rule (minus approval) is returned as
 * promotable. Nothing here applies a genome: promotion is a UI action
 * behind an approval card.
 *
 * The proposer is template-based for now: no model is called, so a run
 * is deterministic and costs no tokens. An LLM proposer that picks its
 * provider and model from the existing picker is the next step after
 * this runner proves itself.
 */
class EvolutionRunner(
    private val store: GenomeStore,
    private val archive: Archive,
    private val ledger: BudgetLedger,
    private val settings: EvolveSettings,
) {
    companion object {
        private const val VERIFY_LINE = "Verify before claiming done: run the checks and quote their evidence."
        private const val PINNED_LINE = "Keep pinned constraints in every summary: copy each pinned line verbatim."
        private const val DEBUG_SKILL = "systematic-debugging"
        private const val DEBUG_SKILL_TEXT = "---\nname: systematic-debugging\ndescription: Reproduce first, then fix.\n---\n\n# Systematic debugging\n\n1. Reproduce the failure.\n2. Narrow it to one change.\n3. Fix that change and re-run the check.\n"

        /**
         * At most [maxCandidates] template candidates, each one small change
         * away from [parent]. Templates already satisfied by the parent are
         * skipped, so a candidate always differs from its parent.
         */
        fun templateCandidates(parent: Genome, diagnosis: TraceMiner.Diagnosis, maxCandidates: Int = 4): List<Genome> {
            val hints = diagnosis.suggestedHints.joinToString(" ").lowercase()
            val candidates = mutableListOf<Genome>()
            fun add(label: String, hypothesis: String, change: (Genome) -> Genome) {
                if (candidates.size >= maxCandidates) return
                candidates += change(parent).copy(
                    parent = parent.id,
                    createdAt = System.currentTimeMillis(),
                    label = label,
                    hypothesis = hypothesis,
                    proposerNotes = diagnosis.topPatterns.joinToString("; ") { "${it.pattern} x${it.count}" }.take(500),
                ).identified()
            }
            if (parent.instructions.none { "verify" in it.lowercase() }) {
                add("gen-verify", "A verify-before-done line should stop claims without evidence.",
                    { it.copy(instructions = it.instructions + VERIFY_LINE) })
            }
            if (parent.instructions.none { "pinned" in it.lowercase() }) {
                add("gen-pinned", "A pinned-constraint line should keep pinned lines through compaction.",
                    { it.copy(instructions = it.instructions + PINNED_LINE) })
            }
            if (DEBUG_SKILL !in parent.skills) {
                add("gen-skill", "Restoring the systematic-debugging skill should fix the patch family.",
                    { it.copy(skills = it.skills + (DEBUG_SKILL to DEBUG_SKILL_TEXT), skillEnable = it.skillEnable + DEBUG_SKILL) })
            }
            if (parent.hooks.isEmpty()) {
                add("gen-hook", "A tool-failed hook should catch patch failures early.",
                    { it.copy(hooks = it.hooks + GenomeHook("tool.failed", "apply_patch", "re-read the file and rebuild the hunk", 30)) })
            } else {
                val first = parent.hooks.first()
                if (first.timeout > 30) {
                    add("gen-hook", "A tighter hook timeout should stop slow hooks sooner.",
                        { it.copy(hooks = listOf(first.copy(timeout = 30)) + it.hooks.drop(1)) })
                }
            }
            if ("stale" in hints || "hunk" in hints) {
                if (parent.instructions.none { "stale" in it.lowercase() }) {
                    add("gen-hunk", "A stale-hunk note should help the agent recover instead of guessing.",
                        { it.copy(instructions = it.instructions + "When a patch hunk is stale, re-read the file and rebuild the hunk instead of guessing.") })
                }
            }
            return candidates
        }
    }

    /** The archive refuses a child whose parent it has never seen, so record the parent first. */
    private fun ensureParentArchived(parent: Genome) {
        if (archive.entries().any { it.genomeId == parent.id }) return
        val report = store.report(parent.id)
        val active = store.active()
        archive.add(
            ArchiveEntry(
                genomeId = parent.id,
                parent = parent.parent,
                label = parent.label,
                hypothesis = parent.hypothesis,
                createdAt = parent.createdAt,
                status = if (active?.genomeId == parent.id) ArchiveStatus.ACTIVE else ArchiveStatus.EVALUATED,
                successRate = report?.successRate,
                holdoutScore = report?.holdoutScore,
                tokensPerSuccess = report?.tokensPerSuccess,
                safetyViolations = report?.safetyViolations ?: 0,
            ),
        )
    }

    fun runGeneration(parent: Genome, diagnosis: TraceMiner.Diagnosis, workDir: File): GenerationResult {
        ledger.stopReason()?.let { reason ->
            return GenerationResult(parent.id, emptyList(), emptyList(), null, reason)
        }
        ensureParentArchived(parent)
        val parentReport = store.report(parent.id)
        val candidates = templateCandidates(parent, diagnosis, settings.maxCandidates)
        val reports = mutableListOf<EvalReport>()
        val candidateIds = mutableListOf<String>()
        var best: Pair<Genome, EvalReport>? = null
        for (candidate in candidates) {
            if (ledger.shouldStop()) break
            val stored = store.save(candidate)
            candidateIds += stored.id
            val evalDir = File(workDir, "eval-${stored.id}").apply { mkdirs() }
            val report = EvalHarness.evaluate(stored, workDir = evalDir)
            val violations = SafetyProbes.safetyViolations(stored, evalDir)
            val finalReport = report.copy(safetyViolations = violations)
            store.saveReport(finalReport)
            reports += finalReport
            if (archive.entries().none { it.genomeId == stored.id }) {
                archive.add(
                    ArchiveEntry(
                        genomeId = stored.id,
                        parent = parent.id,
                        status = if (violations > 0) ArchiveStatus.VETOED else ArchiveStatus.EVALUATED,
                        successRate = finalReport.successRate,
                        holdoutScore = finalReport.holdoutScore,
                        tokensPerSuccess = finalReport.tokensPerSuccess,
                        safetyViolations = violations,
                        label = stored.label,
                        hypothesis = stored.hypothesis,
                        createdAt = stored.createdAt,
                    ),
                )
            }
            ledger.consume(BudgetRole.WORKER, 0, finalReport.wallMs)
            val decision = PromotionGate.wouldPromote(parentReport, finalReport, violations == 0, settings)
            if (decision is GateDecision.Approved) {
                if (best == null || finalReport.successRate > best.second.successRate) best = stored to finalReport
            }
        }
        val improved = best != null && parentReport != null &&
            (best.second.successRate - parentReport.successRate) >= settings.minSuccessDelta
        ledger.recordGeneration(improved || (parentReport == null && best != null))
        return GenerationResult(
            parentId = parent.id,
            candidateIds = candidateIds,
            reports = reports,
            promotableId = best?.first?.id,
            stoppedReason = ledger.stopReason(),
        )
    }

    /**
     * Runs up to [EvolveSettings.maxGenerations] generations. Each generation
     * starts from the previous generation's best candidate when one exists.
     * Returns the last promotable candidate found, if any; promotion itself
     * stays a UI action behind an approval card.
     */
    fun run(parent: Genome, workDir: File): RunResult {
        val generations = mutableListOf<GenerationResult>()
        var current = parent
        var promotableId: String? = null
        while (!ledger.shouldStop()) {
            val histories = archive.entries().mapNotNull { it.hypothesis.ifBlank { null } } +
                archive.entries().map { it.label }
            val diagnosis = TraceMiner.mine(histories)
            val result = runGeneration(current, diagnosis, workDir)
            generations += result
            promotableId = result.promotableId ?: promotableId
            val next = result.promotableId?.let { store.load(it) } ?: break
            if (next.id == current.id) break
            current = next
        }
        return RunResult(generations, promotableId, ledger.stopReason())
    }
}

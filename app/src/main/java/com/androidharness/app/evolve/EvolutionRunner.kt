package com.androidharness.app.evolve

import java.io.File

data class GenerationResult(
    val parentId: String,
    val candidateIds: List<String>,
    val reports: List<EvalReport>,
    val promotableId: String?,
    val stoppedReason: String?,
    val proposalFailures: List<String> = emptyList(),
)

data class RunResult(
    val generations: List<GenerationResult>,
    val promotableId: String?,
    val stoppedReason: String?,
)

/**
 * The real evolution loop, after the Darwin Gödel Machine
 * (jennyzzt/dgm, `DGM_outer.py` + `self_improve_step.py`):
 *
 * 1. Sample a parent from the archive, weighted by score and novelty
 *    (`Archive.selectParent` — DGM's `score_child_prop`: accuracy
 *    sigmoid times 1/(1+children), so the search keeps exploring new
 *    branches instead of re-polishing the same winner).
 * 2. Self-modify: the proposer model reads the parent, its failing
 *    benchmark cases and the archive's stepping stones, and writes ONE
 *    new genome. Invalid output produces no candidate — never a template.
 * 3. Evaluate: the worker model, running under the candidate's
 *    instructions and skills, does the live benchmark tasks in a scratch
 *    workspace, with repair rounds and a critic check (Voyager's
 *    iterative prompting + self-verification).
 * 4. Archive the candidate with its report. Only the best candidate that
 *    meets the gate rule (minus approval) is returned as promotable.
 *    Nothing here applies a genome: promotion is a UI action behind an
 *    approval card, and the model never touches the trusted core.
 *
 * If no model is configured for the proposer or the worker, the run
 * refuses with [NoModelConfigured] instead of falling back to any fake
 * score. The static suite is gone from this path: the score now measures
 * what the candidate's instructions do to a real model's work.
 */
class EvolutionRunner(
    private val store: GenomeStore,
    private val archive: Archive,
    private val ledger: BudgetLedger,
    private val settings: EvolveSettings,
    private val caller: EvolveModelCaller,
) {
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

    suspend fun runGeneration(parent: Genome, workDir: File): GenerationResult {
        ledger.stopReason()?.let { reason ->
            return GenerationResult(parent.id, emptyList(), emptyList(), null, reason)
        }
        if (!caller.isConfigured(EvolveRole.PROPOSER)) throw NoModelConfigured(EvolveRole.PROPOSER)
        if (!caller.isConfigured(EvolveRole.WORKER)) throw NoModelConfigured(EvolveRole.WORKER)
        ensureParentArchived(parent)
        val parentReport = store.report(parent.id)
        val failures = mutableListOf<String>()
        val reports = mutableListOf<EvalReport>()
        val candidateIds = mutableListOf<String>()
        var best: Pair<Genome, EvalReport>? = null
        var attempts = 0
        // The proposer sees the training tasks of both suites, ids and
        // titles only; the holdouts of both suites stay hidden.
        val trainingTasks = LiveEvaluator.proposerView().tasks + RealProjectEvaluator.proposerView().tasks
        while (attempts < settings.maxCandidates && !ledger.shouldStop()) {
            attempts++
            val histories = archive.entries().mapNotNull { it.hypothesis.ifBlank { null } }
            val hints = TraceMiner.mine(histories).suggestedHints
            when (val proposed = LlmProposer.propose(
                caller, parent, parentReport, archive.entries(), trainingTasks, hints,
            )) {
                is LlmProposer.Proposed.Failed -> {
                    failures += proposed.reason
                    continue
                }
                is LlmProposer.Proposed.Candidate -> {
                    if (!ledger.consume(BudgetRole.PROPOSER, proposed.promptTokens + proposed.completionTokens, 0)) {
                        failures += "proposal tokens exceeded the budget"
                        break
                    }
                    val stored = store.save(proposed.genome)
                    candidateIds += stored.id
                    val evalDir = File(workDir, "eval-${stored.id}").apply { mkdirs() }
                    val (liveReport, liveTokens) = LiveEvaluator.evaluate(stored, caller, evalDir)
                    val (realReport, realTokens) = RealProjectEvaluator.evaluate(
                        stored, caller, File(workDir, "real-eval-${stored.id}"),
                    )
                    ledger.consume(BudgetRole.WORKER, liveTokens.first + realTokens.first, liveReport.wallMs + realReport.wallMs)
                    ledger.consume(BudgetRole.CRITIC, liveTokens.second + realTokens.second, 0)
                    val report = mergeReports(liveReport, realReport, liveTokens, realTokens)
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
                    val decision = PromotionGate.wouldPromote(parentReport, finalReport, violations == 0, settings)
                    if (decision is GateDecision.Approved) {
                        if (best == null || finalReport.successRate > best.second.successRate) best = stored to finalReport
                    }
                }
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
            proposalFailures = failures,
        )
    }

    /**
     * One report over both suites: the live toy suite and the real-project
     * suite. The suite identity stays the live suite's, so the archive,
     * the gate and the reports API keep reading one report per candidate,
     * with the holdout score now spanning both suites' holdouts.
     */
    private fun mergeReports(
        live: EvalReport,
        real: EvalReport,
        liveTokens: Pair<Long, Long>,
        realTokens: Pair<Long, Long>,
    ): EvalReport {
        val tasks = live.tasks + real.tasks
        val holdout = tasks.filter { it.holdout }
        val successes = tasks.count { it.passed }
        val totalTokens = liveTokens.first + liveTokens.second + realTokens.first + realTokens.second
        return EvalReport(
            genomeId = live.genomeId,
            suiteId = LiveEvaluator.SUITE_ID,
            suiteVersion = LiveEvaluator.SUITE_VERSION,
            seeds = listOf(1),
            tasks = tasks,
            successRate = successes.toDouble() / tasks.size,
            tokensPerSuccess = if (successes == 0) 0.0 else totalTokens.toDouble() / successes,
            toolValidity = 1.0,
            meanTurns = 1.0,
            safetyViolations = 0,
            wallMs = live.wallMs + real.wallMs,
            modelPin = live.modelPin.ifBlank { real.modelPin },
            envPin = live.envPin,
            holdoutScore = if (holdout.isEmpty()) null else holdout.count { it.passed }.toDouble() / holdout.size,
        )
    }

    /**
     * Runs up to [EvolveSettings.maxGenerations] generations. Each
     * generation after the first samples its parent from the archive by
     * score and novelty, like DGM. Returns the last promotable candidate
     * found, if any; promotion itself stays a UI action behind an
     * approval card.
     */
    suspend fun run(parent: Genome, workDir: File): RunResult {
        if (!caller.isConfigured(EvolveRole.PROPOSER)) throw NoModelConfigured(EvolveRole.PROPOSER)
        if (!caller.isConfigured(EvolveRole.WORKER)) throw NoModelConfigured(EvolveRole.WORKER)
        val generations = mutableListOf<GenerationResult>()
        var current = parent
        var promotableId: String? = null
        while (!ledger.shouldStop()) {
            val result = runGeneration(current, workDir)
            generations += result
            promotableId = result.promotableId ?: promotableId
            val nextId = archive.selectParent(fitness = { it.successRate ?: 0.0 })?.genomeId ?: break
            val next = store.load(nextId) ?: break
            if (next.id == current.id) break
            current = next
        }
        return RunResult(generations, promotableId, ledger.stopReason())
    }
}

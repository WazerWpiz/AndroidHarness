package com.androidharness.app.evolve

import java.io.File
import kotlinx.coroutines.delay

data class GenerationResult(
    val parentId: String,
    val candidateIds: List<String>,
    val reports: List<EvalReport>,
    val promotableId: String?,
    val stoppedReason: String?,
    val proposalFailures: List<String> = emptyList(),
    /**
     * Proposal failures that died on validation (the JSON did not parse,
     * or the genome broke the rules) — counted separately from failures
     * on merit, so a run summary can say how much of the run was lost
     * to malformed proposer output (P11).
     */
    val validationFailures: Int = 0,
)

data class RunResult(
    val generations: List<GenerationResult>,
    val promotableId: String?,
    val stoppedReason: String?,
) {
    /** Validation failures across all generations. */
    val validationFailures: Int
        get() = generations.sumOf { it.validationFailures }
}

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
 *    instructions and skills, does the benchmark tasks in a scratch
 *    workspace, with repair rounds and a critic check where the task
 *    needs one (Voyager's iterative prompting + self-verification).
 * 4. Archive the candidate with its report. Only the best candidate that
 *    meets the gate rule (minus approval) is returned as promotable.
 *    Nothing here applies a genome: promotion is a UI action behind an
 *    approval card, and the model never touches the trusted core.
 *
 * If no model is configured for the proposer or the worker, the run
 * refuses with [NoModelConfigured] instead of falling back to any fake
 * score. The static suite is gone from this path: the score now measures
 * what the candidate's instructions do to a real model's work.
 *
 * P8 — fair comparison: every candidate is screened at 1 seed first,
 * and only a candidate that EXCEEDS its parent at 1 seed (strictly
 * greater success rate on the same seed, same suite, same model pins)
 * goes to a 3-seed confirmation, where candidate and parent are both
 * evaluated on the same 3 seeds and the gate compares the MEAN task
 * counts (>= +2 tasks) and the MEAN holdout (not lower). The parent's
 * 1-seed report is cached by (suite, version, model pins, seeds) and
 * reused while the key matches, so Baseline is evaluated once per
 * model-and-suite, not once per run. An INVALID report (more than 20%
 * ERROR/SKIPPED) is never a promotion candidate and never proposer
 * feedback: the proposer gets the last VALID parent report, or nothing
 * with an explicit note when the parent's latest report is invalid.
 *
 * P8 — suite selection: the Doctor (run by the host before the run)
 * picks the full suite when the toolchain is ready and the lite suite
 * (live tasks only, no real tasks) when it is not. A run constructed
 * without a Doctor result decides the same way from the test runner.
 * Promotion only ever compares reports from the same suite.
 */
class EvolutionRunner(
    private val store: GenomeStore,
    private val archive: Archive,
    private val ledger: BudgetLedger,
    private val settings: EvolveSettings,
    private val caller: EvolveModelCaller,
    /** Live progress (P6): called at each real transition. No-op by default; emits only, never scores. */
    private val onProgress: (EvolveRunProgress) -> Unit = {},
    /** The Doctor preflight for this run, when the host ran one; its suite decision wins. */
    private val doctor: DoctorResult? = null,
    /** The suite to evaluate. Null means: full when the toolchain is ready, lite when it is not. */
    private val suite: EvalSuite? = null,
    /**
     * The delay the evaluators use between tasks and before retries.
     * Real in production, a no-op in tests, so a retried task never
     * slows a test.
     */
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
) {
    /** The suite this run evaluates: the Doctor's decision, or the test runner's report. */
    private val resolvedSuite: EvalSuite =
        suite ?: if (doctor != null) {
            if (doctor.toolchainReady) EvalSuite.FULL else EvalSuite.LITE
        } else if (RealProjectEvaluator.commandRunner.toolchainReady) EvalSuite.FULL else EvalSuite.LITE

    /** Candidates finished so far in this run, in completion order. */
    private val finishedCandidates = mutableListOf<FinishedCandidate>()

    /**
     * True when an evaluation's tokens did not fit the ledger (P8):
     * the tokens were already spent by the model call, so the ledger
     * refuses the charge and the run must stop starting new work
     * instead of overspending. Checked between attempts and before a
     * confirmation; the generation's stop reason names it.
     */
    private var budgetExceeded = false

    /** One cheap progress snapshot from the facts the runner knows right now. */
    private fun emitProgress(
        phase: EvolveRunPhase,
        generation: Int,
        candidateIndex: Int,
        candidateId: String?,
        candidateLabel: String?,
        failures: List<String>,
    ) {
        onProgress(
            EvolveRunProgress(
                phase = phase,
                generation = generation,
                maxGenerations = settings.maxGenerations,
                candidateIndex = candidateIndex,
                maxCandidates = settings.maxCandidates,
                candidateId = candidateId,
                candidateLabel = candidateLabel,
                // Small fixed-size snapshot: the most recent candidates only.
                finishedCandidates = finishedCandidates.takeLast(settings.maxCandidates),
                tokensUsed = ledger.tokensUsed,
                tokenBudget = ledger.maxTokens,
                proposalFailures = failures.size,
                lastFailureReason = failures.lastOrNull()?.take(200),
            ),
        )
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

    /** The worker pin a report must carry for the cache to reuse it: derived from the CONFIGURED worker choice. */
    private fun expectedWorkerPin(): String? =
        settings.workerModel?.let { "live/${it.providerId}/${it.model}" }

    /** The critic pin a report must carry for the cache to reuse it: derived from the CONFIGURED critic choice. */
    private fun expectedCriticPin(): String? =
        settings.criticModel?.let { "${it.providerId}/${it.model}" }

    /**
     * The baseline cache: the stored report for [genomeId] is reused
     * only when it matches the current run exactly — same suite and
     * version, same seeds, same worker pin, and (when both reports name
     * a critic) the same critic pin. A report from another model or
     * another suite is never reused as if it were this run's. Returns
     * null when no stable configured-model identifier exists (the roles
     * fall back to the app's active model), because then there is no
     * key to match against.
     */
    private fun cachedReport(genomeId: String, seeds: List<Int>): EvalReport? {
        val report = store.report(genomeId) ?: return null
        if (!report.valid) return null
        if (report.suiteId != resolvedSuite.suiteId) return null
        if (report.suiteVersion != LiveEvaluator.SUITE_VERSION) return null
        if (report.seeds != seeds) return null
        val workerPin = expectedWorkerPin() ?: return null
        if (report.modelPin != workerPin) return null
        val criticPin = expectedCriticPin() ?: return null
        if (report.criticPin.isNotBlank() && criticPin.isNotBlank() && report.criticPin != criticPin) return null
        return report
    }

    /** The parent's 1-seed report: from the cache when the key matches, else evaluated now and stored. */
    private suspend fun ensureParentSeed1Report(parent: Genome, workDir: File): EvalReport {
        cachedReport(parent.id, listOf(1))?.let { return it }
        // A 3-seed aggregate carries the seed-1 task list, so it is the
        // parent's seed-1 report too — reuse it instead of re-evaluating.
        cachedReport(parent.id, listOf(1, 2, 3))?.let { return it }
        // When the roles fall back to the app's active model there is no
        // stable pin, so the cache above can never hit — but a stored
        // 3-seed aggregate is still this genome's best report (it was
        // produced by the same caller in an earlier generation), and
        // re-evaluating would overwrite it with a 1-seed report. Reuse
        // it. When pins ARE configured, a mismatch means the model
        // changed, and the parent is re-evaluated fresh instead.
        if (expectedWorkerPin() == null || expectedCriticPin() == null) {
            store.report(parent.id)?.takeIf {
                it.valid && it.suiteId == resolvedSuite.suiteId &&
                    it.suiteVersion == LiveEvaluator.SUITE_VERSION && it.seeds == listOf(1, 2, 3)
            }?.let { return it }
        }
        val dir = File(workDir, "parent-seed1-${parent.id}").apply { mkdirs() }
        val report = evaluateGenome(parent, dir, seed = 1)
        val violations = SafetyProbes.safetyViolations(parent, dir)
        val final = report.copy(safetyViolations = violations)
        // Never clobber a stored 3-seed aggregate with a 1-seed report:
        // the aggregate is the genome's best-known report.
        val stored = store.report(parent.id)
        if (stored == null || stored.seeds.size <= 1) store.saveReport(final)
        return final
    }

    /** One genome, one seed, in the run's suite: live tasks, plus the real tasks when the suite is full. */
    private suspend fun evaluateGenome(genome: Genome, workDir: File, seed: Int): EvalReport {
        val (liveReport, liveTokens) = LiveEvaluator.evaluate(genome, caller, File(workDir, "live"), seed, delayFn)
        if (!ledger.consume(BudgetRole.WORKER, liveTokens.first, liveReport.wallMs)) budgetExceeded = true
        if (!ledger.consume(BudgetRole.CRITIC, liveTokens.second, 0)) budgetExceeded = true
        if (resolvedSuite == EvalSuite.LITE) {
            return mergeReports(liveReport, null, liveTokens, 0L to 0L, seed)
        }
        val (realReport, realTokens) = RealProjectEvaluator.evaluate(genome, caller, File(workDir, "real"), seed, delayFn)
        if (!ledger.consume(BudgetRole.WORKER, realTokens.first, realReport.wallMs)) budgetExceeded = true
        if (!ledger.consume(BudgetRole.CRITIC, realTokens.second, 0)) budgetExceeded = true
        return mergeReports(liveReport, realReport, liveTokens, realTokens, seed)
    }

    /**
     * One report over the run's suite. The seed-1 task list is the
     * representative; the scores are decided-only (only PASS/FAIL count).
     */
    private fun mergeReports(
        live: EvalReport,
        real: EvalReport?,
        liveTokens: Pair<Long, Long>,
        realTokens: Pair<Long, Long>,
        seed: Int,
    ): EvalReport {
        val tasks = live.tasks + (real?.tasks.orEmpty())
        val totalTokens = liveTokens.first + liveTokens.second + realTokens.first + realTokens.second
        val passed = tasks.count { it.resolvedStatus == TaskStatus.PASS }
        val (valid, invalidReason) = EvalReport.validityOf(tasks)
        return EvalReport(
            genomeId = live.genomeId,
            suiteId = resolvedSuite.suiteId,
            suiteVersion = LiveEvaluator.SUITE_VERSION,
            seeds = listOf(seed),
            tasks = tasks,
            successRate = EvalReport.successRateOf(tasks),
            tokensPerSuccess = if (passed == 0) 0.0 else totalTokens.toDouble() / passed,
            toolValidity = 1.0,
            meanTurns = tasks.map { it.turns }.average().takeIf { it.isFinite() } ?: 0.0,
            safetyViolations = 0,
            wallMs = live.wallMs + (real?.wallMs ?: 0L),
            modelPin = live.modelPin.ifBlank { real?.modelPin ?: "" },
            criticPin = live.criticPin.ifBlank { real?.criticPin ?: "" },
            envPin = live.envPin,
            holdoutScore = EvalReport.holdoutScoreOf(tasks),
            meanPassedTasks = passed.toDouble(),
            valid = valid,
            invalidReason = invalidReason,
            doctor = doctor,
        )
    }

    /**
     * The 3-seed aggregate (P8): the mean of the per-seed rates and
     * task counts, the seed-1 task list as the representative, and
     * validity computed over ALL seed outcomes — a report is invalid
     * when more than 20% of the task runs across its seeds were
     * ERROR/SKIPPED. Tokens per success is total tokens over total
     * passes across the seeds.
     */
    private fun aggregateSeeds(genomeId: String, seedReports: List<EvalReport>): EvalReport {
        require(seedReports.isNotEmpty()) { "At least one seed report is needed." }
        val first = seedReports.first()
        val allTasks = seedReports.flatMap { it.tasks }
        val meanPassed = seedReports.map { r -> r.tasks.count { it.resolvedStatus == TaskStatus.PASS } }.average()
        val meanRate = seedReports.map { it.successRate }.average()
        val holdouts = seedReports.mapNotNull { it.holdoutScore }
        val meanHoldout = if (holdouts.isEmpty()) null else holdouts.average()
        val totalTokens = seedReports.sumOf { it.tokensPerSuccess * it.meanPassedTasks }
        val totalPassed = seedReports.sumOf { it.meanPassedTasks }
        val (valid, invalidReason) = EvalReport.validityOf(allTasks)
        return first.copy(
            seeds = seedReports.flatMap { it.seeds },
            tasks = first.tasks,
            successRate = meanRate,
            tokensPerSuccess = if (totalPassed == 0.0) 0.0 else totalTokens / totalPassed,
            meanTurns = seedReports.map { it.meanTurns }.average().takeIf { it.isFinite() } ?: 0.0,
            wallMs = seedReports.sumOf { it.wallMs },
            holdoutScore = meanHoldout,
            meanPassedTasks = meanPassed,
            valid = valid,
            invalidReason = invalidReason,
        )
    }

    /**
     * The 1-seed screening (P8): null when the candidate EXCEEDS its
     * parent at 1 seed — strictly greater success rate on the same
     * seed — else the reason it does not. A comparison is only fair
     * when both reports are valid, from the same suite, and from the
     * same model pins; anything else is a refusal, not a loss.
     */
    private fun screeningReason(candidate: EvalReport, parent: EvalReport): String? {
        if (!candidate.valid) return "invalid report: ${candidate.invalidReason ?: "too many tasks were ERROR/SKIPPED"}"
        if (!parent.valid) return "parent report is invalid: ${parent.invalidReason ?: "too many tasks were ERROR/SKIPPED"}"
        if (candidate.suiteId != parent.suiteId) {
            return "cannot compare ${candidate.suiteId} with ${parent.suiteId}: screening only within the same suite"
        }
        if (candidate.modelPin != parent.modelPin) {
            return "model pin changed during the run (${parent.modelPin} vs ${candidate.modelPin}): not compared"
        }
        if (candidate.criticPin.isNotBlank() && parent.criticPin.isNotBlank() &&
            candidate.criticPin != parent.criticPin
        ) {
            return "critic pin changed during the run (${parent.criticPin} vs ${candidate.criticPin}): not compared"
        }
        val candidateRate = EvalReport.successRateOf(candidate.tasks)
        val parentRate = EvalReport.successRateOf(parent.tasks)
        return if (candidateRate > parentRate) null
        else "screening: candidate %.0f%% does not exceed parent %.0f%% at 1 seed".format(candidateRate * 100, parentRate * 100)
    }

    /**
     * The 3-seed confirmation (P8): candidate and parent on the SAME 3
     * seeds. Seed 1 is reused from the screening report and the
     * parent's 1-seed report; seeds 2 and 3 are evaluated fresh for
     * both — unless the parent already has a cached 3-seed aggregate,
     * which is reused whole. Returns the candidate's aggregate and the
     * parent's aggregate. The parent's aggregate is stored as its
     * best-known report, and its archive entry is updated to match.
     * Returns null when the budget ran out mid-confirmation: the
     * candidate keeps its screening report and is not confirmed.
     */
    private suspend fun confirmCandidate(
        candidate: Genome,
        parent: Genome,
        parentSeed1: EvalReport,
        workDir: File,
    ): Pair<EvalReport, EvalReport>? {
        val candidateSeed1 = store.report(candidate.id)
            ?: throw IllegalStateException("Candidate ${candidate.id} has no screening report.")
        val parentAggregate = cachedReport(parent.id, listOf(1, 2, 3)) ?: run {
            val parentSeed2 = evaluateGenome(parent, File(workDir, "confirm-${parent.id}-s2"), seed = 2)
            if (budgetExceeded) return null
            val parentSeed3 = evaluateGenome(parent, File(workDir, "confirm-${parent.id}-s3"), seed = 3)
            if (budgetExceeded) return null
            val aggregate = aggregateSeeds(parent.id, listOf(parentSeed1, parentSeed2, parentSeed3))
            store.saveReport(aggregate)
            archive.entries().firstOrNull { it.genomeId == parent.id }?.let { entry ->
                archive.update(
                    entry.copy(
                        successRate = aggregate.successRate,
                        holdoutScore = aggregate.holdoutScore,
                        tokensPerSuccess = aggregate.tokensPerSuccess,
                    ),
                )
            }
            aggregate
        }
        val candidateSeed2 = evaluateGenome(candidate, File(workDir, "confirm-${candidate.id}-s2"), seed = 2)
        if (budgetExceeded) return null
        val candidateSeed3 = evaluateGenome(candidate, File(workDir, "confirm-${candidate.id}-s3"), seed = 3)
        if (budgetExceeded) return null
        val candidateAggregate = aggregateSeeds(candidate.id, listOf(candidateSeed1, candidateSeed2, candidateSeed3))
        return candidateAggregate to parentAggregate
    }

    suspend fun runGeneration(parent: Genome, workDir: File, generation: Int = 1): GenerationResult {
        ledger.stopReason()?.let { reason ->
            return GenerationResult(parent.id, emptyList(), emptyList(), null, reason)
        }
        if (!caller.isConfigured(EvolveRole.PROPOSER)) throw NoModelConfigured(EvolveRole.PROPOSER)
        if (!caller.isConfigured(EvolveRole.WORKER)) throw NoModelConfigured(EvolveRole.WORKER)
        ensureParentArchived(parent)
        // Baseline first (P8): the parent's 1-seed report, from the
        // cache when the key matches, else evaluated now. An INVALID
        // parent report is never proposer feedback: the proposer gets
        // nothing and an explicit note instead.
        val parentReport = ensureParentSeed1Report(parent, workDir)
        val parentForProposer = parentReport.takeIf { it.valid }
        val parentInvalidReason = parentReport.takeIf { !it.valid }?.invalidReason
        val failures = mutableListOf<String>()
        val reports = mutableListOf<EvalReport>()
        val candidateIds = mutableListOf<String>()
        var best: Pair<Genome, EvalReport>? = null
        var attempts = 0
        var validationFailures = 0
        // The proposer sees the training tasks of the run's suite, ids
        // and titles only; the holdouts stay hidden. In the lite suite
        // the real tasks are not run, so they are not designed for.
        val trainingTasks = LiveEvaluator.proposerView().tasks +
            if (resolvedSuite == EvalSuite.LITE) emptyList() else RealProjectEvaluator.proposerView().tasks
        while (attempts < settings.maxCandidates && !ledger.shouldStop() && !budgetExceeded) {
            attempts++
            emitProgress(EvolveRunPhase.PROPOSING, generation, attempts, null, null, failures)
            val histories = archive.entries().mapNotNull { it.hypothesis.ifBlank { null } }
            val hints = TraceMiner.mine(histories).suggestedHints
            when (val proposed = LlmProposer.propose(
                caller, parent, parentForProposer, archive.entries(), trainingTasks, hints, store, parentInvalidReason,
            )) {
                is LlmProposer.Proposed.Failed -> {
                    failures += proposed.reason
                    if (proposed.validationFailed) validationFailures++
                    emitProgress(EvolveRunPhase.PROPOSING, generation, attempts, null, null, failures)
                    continue
                }
                is LlmProposer.Proposed.Candidate -> {
                    if (!ledger.consume(BudgetRole.PROPOSER, proposed.promptTokens + proposed.completionTokens, 0)) {
                        failures += "proposal tokens exceeded the budget"
                        emitProgress(EvolveRunPhase.PROPOSING, generation, attempts, null, null, failures)
                        break
                    }
                    val stored = store.save(proposed.genome)
                    candidateIds += stored.id
                    emitProgress(EvolveRunPhase.EVALUATING, generation, attempts, stored.id, stored.label, failures)
                    val screenDir = File(workDir, "screen-${stored.id}").apply { mkdirs() }
                    val screenReport = evaluateGenome(stored, screenDir, seed = 1)
                    val violations = SafetyProbes.safetyViolations(stored, screenDir)
                    var finalReport = screenReport.copy(safetyViolations = violations)
                    store.saveReport(finalReport)
                    reports += finalReport
                    val gateReason: String?
                    if (violations > 0) {
                        gateReason = "candidate has safety violations"
                    } else if (budgetExceeded) {
                        gateReason = "stopped: the token budget was exhausted during evaluation"
                    } else {
                        val screen = screeningReason(finalReport, parentReport)
                        if (screen != null) {
                            gateReason = screen
                        } else {
                            // 3-seed confirmation: candidate and parent on
                            // the same 3 seeds; the gate compares the
                            // means. A run that would exceed the budget
                            // stops honestly mid-confirmation (null) and
                            // never overspends.
                            val confirmed = confirmCandidate(stored, parent, parentReport, workDir)
                            if (confirmed == null) {
                                gateReason = "stopped: the token budget was exhausted during confirmation"
                            } else {
                                val (confirmedReport, parentConfirmed) = confirmed
                                finalReport = confirmedReport.copy(safetyViolations = violations)
                                store.saveReport(finalReport)
                                reports[reports.lastIndex] = finalReport
                                val decision = PromotionGate.wouldPromote(parentConfirmed, finalReport, violations == 0, settings)
                                gateReason = (decision as? GateDecision.Rejected)?.reason?.take(200)
                                if (decision is GateDecision.Approved) {
                                    if (best == null || finalReport.meanPassedTasks > best.second.meanPassedTasks) {
                                        best = stored to finalReport
                                    }
                                }
                            }
                        }
                    }
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
                                gateReason = gateReason,
                            ),
                        )
                    } else {
                        val existing = archive.entries().first { it.genomeId == stored.id }
                        archive.update(
                            existing.copy(
                                successRate = finalReport.successRate,
                                holdoutScore = finalReport.holdoutScore,
                                tokensPerSuccess = finalReport.tokensPerSuccess,
                                gateReason = gateReason,
                            ),
                        )
                    }
                    finishedCandidates += FinishedCandidate(stored.id, stored.label, finalReport.successRate)
                    emitProgress(EvolveRunPhase.SCORING, generation, attempts, stored.id, stored.label, failures)
                }
            }
        }
        // Improvement means a confirmed, promotable candidate was found.
        ledger.recordGeneration(best != null)
        return GenerationResult(
            parentId = parent.id,
            candidateIds = candidateIds,
            reports = reports,
            promotableId = best?.first?.id,
            stoppedReason = ledger.stopReason() ?: if (budgetExceeded) "token budget exhausted" else null,
            proposalFailures = failures,
            validationFailures = validationFailures,
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
        finishedCandidates.clear()
        val generations = mutableListOf<GenerationResult>()
        var current = parent
        var promotableId: String? = null
        while (!ledger.shouldStop()) {
            val result = runGeneration(current, workDir, generations.size + 1)
            generations += result
            promotableId = result.promotableId ?: promotableId
            // P7: the current parent is excluded from the draw, so the
            // sampler cannot re-pick it and end the run after one
            // generation. The equality check stays as a safety net.
            val nextId = archive.selectParent(fitness = { it.successRate ?: 0.0 }, excludeId = current.id)?.genomeId ?: break
            val next = store.load(nextId) ?: break
            if (next.id == current.id) break
            current = next
        }
        return RunResult(generations, promotableId, ledger.stopReason())
    }
}

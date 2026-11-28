package com.androidharness.app.evolve

import com.androidharness.app.tools.EvolveDiffTool
import com.androidharness.app.tools.EvolveEvaluateTool
import com.androidharness.app.tools.EvolveHistoryTool
import com.androidharness.app.tools.EvolveProposeTool
import com.androidharness.app.tools.Tool
import com.androidharness.app.tools.EvolveStatusTool
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the Settings screen shows for the latest evolution run. */
enum class EvolveRunPhase { PROPOSING, EVALUATING, SCORING, DONE }

/** One candidate that finished evaluation, with the score it really got. */
data class FinishedCandidate(
    val genomeId: String,
    val label: String,
    val successRate: Double,
)

/**
 * One live progress event from the runner. Only facts the runner knows at
 * that moment: the current phase and generation, which candidate is being
 * worked on, the candidates that already finished with their real scores,
 * tokens the ledger has recorded, and proposal failures so far. There is
 * deliberately no "percent complete": the total work is not knowable in
 * advance (budget, stagnation and quota can stop a run early), so the UI
 * shows counts and the current phase only.
 */
data class EvolveRunProgress(
    val phase: EvolveRunPhase,
    val generation: Int,
    val maxGenerations: Int,
    val candidateIndex: Int,
    val maxCandidates: Int,
    val candidateId: String? = null,
    val candidateLabel: String? = null,
    /** Most recently finished candidates, oldest first; capped at maxCandidates. */
    val finishedCandidates: List<FinishedCandidate> = emptyList(),
    val tokensUsed: Long = 0,
    val tokenBudget: Long = 0,
    val proposalFailures: Int = 0,
    /** Last proposal failure reason, capped at 200 characters like the summary. */
    val lastFailureReason: String? = null,
)

/** What the Settings screen shows for the latest evolution run. */
data class EvolveRunState(
    val running: Boolean,
    val summary: String? = null,
    val promotableId: String? = null,
    val error: String? = null,
    // Live progress (P6): honest counts and the current phase only — never
    // a percent-complete, because the total work is not knowable in advance.
    val phase: EvolveRunPhase? = null,
    val generation: Int = 0,
    val maxGenerations: Int = 0,
    /** Attempt within the generation, 1-based; 0 when no candidate is in progress yet. */
    val candidateIndex: Int = 0,
    val maxCandidates: Int = 0,
    val candidateId: String? = null,
    val candidateLabel: String? = null,
    val finishedCandidates: List<FinishedCandidate> = emptyList(),
    val tokensUsed: Long = 0,
    val tokenBudget: Long = 0,
    /** Run start, epoch millis. The UI derives the elapsed time from this. */
    val startedAtMs: Long? = null,
    val proposalFailures: Int = 0,
    val lastFailureReason: String? = null,
    /** Why the run stopped, from the ledger; null while it is still going. */
    val stopReason: String? = null,
)

/**
 * The evolve package as the app uses it: one content-addressed store, one archive and the
 * gate in front of both. Everything here is inert until a genome is promoted through
 * [PromotionGate], which needs a person's approval.
 */
class EvolveHost(val store: GenomeStore, val archive: Archive) {

    val gate = PromotionGate(store, archive)
    private val settingsStore = EvolveSettingsStore.at(store.root)

    /** Caps and role models, persisted under the evolve directory. */
    var settings: EvolveSettings = settingsStore.load()
        private set

    /**
     * The real model caller, wired by the app (HarnessApp) once. Null in
     * unit tests and before the app wires it; the runner refuses to run
     * without one instead of scoring anything fake.
     */
    var modelCaller: EvolveModelCaller? = null

    fun updateSettings(settings: EvolveSettings) {
        this.settings = settings.normalized()
        settingsStore.save(this.settings)
    }

    /**
     * The latest run, held here — not in the Settings screen — so leaving
     * the screen does not lose it: the run keeps going and the screen shows
     * its live state when the user comes back.
     */
    private val runScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _runState = MutableStateFlow<EvolveRunState?>(null)
    val runState: StateFlow<EvolveRunState?> = _runState.asStateFlow()
    private var runJob: Job? = null

    /**
     * Starts a full run in the app-scoped [runScope]: navigating away from
     * Settings no longer cancels it. Returns false when a run is already
     * going. The run survives leaving the screen; it stops only if the app
     * process itself is killed.
     */
    fun startRun(caller: EvolveModelCaller, workDir: File = File(store.root, "work")): Boolean {
        if (_runState.value?.running == true) return false
        _runState.value = EvolveRunState(
            running = true,
            phase = EvolveRunPhase.PROPOSING,
            generation = 1,
            maxGenerations = settings.maxGenerations,
            maxCandidates = settings.maxCandidates,
            tokenBudget = settings.tokenBudget,
            startedAtMs = System.currentTimeMillis(),
        )
        runJob = runScope.launch {
            try {
                val parent = store.active()?.let { store.load(it.genomeId) }
                    ?: store.load(Genome.baseline().id)
                    ?: store.save(Genome.baseline())
                // Live progress (P6): the runner reports each real
                // transition; the host merges it into the one run state,
                // so the screen shows what the run is doing while it runs.
                val onProgress: (EvolveRunProgress) -> Unit = { progress ->
                    _runState.update { current ->
                        current?.copy(
                            phase = progress.phase,
                            generation = progress.generation,
                            maxGenerations = progress.maxGenerations,
                            candidateIndex = progress.candidateIndex,
                            maxCandidates = progress.maxCandidates,
                            candidateId = progress.candidateId,
                            candidateLabel = progress.candidateLabel,
                            finishedCandidates = progress.finishedCandidates,
                            tokensUsed = progress.tokensUsed,
                            tokenBudget = progress.tokenBudget,
                            proposalFailures = progress.proposalFailures,
                            lastFailureReason = progress.lastFailureReason,
                        )
                    }
                }
                val result = newRun(caller, workDir, onProgress).run(parent, workDir)
                val best = result.generations.flatMap { it.reports }.maxByOrNull { it.successRate }
                val summary = buildString {
                    append(result.generations.size).append(" generation(s), ")
                    append(result.generations.sumOf { it.candidateIds.size }).append(" candidate(s)")
                    best?.let { append(", best %.0f%%".format(it.successRate * 100)) }
                    result.stoppedReason?.let { append(" — stopped: ").append(it) }
                    val failures = result.generations.flatMap { it.proposalFailures }
                    if (failures.isNotEmpty()) append(" — proposals failed: ").append(failures.joinToString("; ").take(200))
                    if (result.promotableId == null) append(" — No candidate met the promotion bar.")
                }
                _runState.update {
                    it?.copy(
                        running = false, summary = summary, promotableId = result.promotableId,
                        phase = EvolveRunPhase.DONE, stopReason = result.stoppedReason,
                    ) ?: EvolveRunState(
                        running = false, summary = summary, promotableId = result.promotableId,
                        phase = EvolveRunPhase.DONE, stopReason = result.stoppedReason,
                    )
                }
            } catch (e: CancellationException) {
                _runState.update {
                    it?.copy(running = false, summary = "Run stopped.", phase = EvolveRunPhase.DONE)
                        ?: EvolveRunState(running = false, summary = "Run stopped.", phase = EvolveRunPhase.DONE)
                }
                throw e
            } catch (e: Exception) {
                _runState.update {
                    it?.copy(running = false, error = e.message, phase = EvolveRunPhase.DONE)
                        ?: EvolveRunState(running = false, error = e.message, phase = EvolveRunPhase.DONE)
                }
            }
        }
        return true
    }

    /** Stops the current run, if any. A stopped run leaves no partial promotion. */
    fun cancelRun() {
        runJob?.cancel()
    }

    /** Clears the promotion offer after the user promotes or dismisses it. */
    fun dismissPromotable() {
        _runState.update { it?.copy(promotableId = null) }
    }

    /** A fresh runner with a budget built from the caps: one run, one ledger. */
    fun newRun(
        caller: EvolveModelCaller,
        workDir: File = File(store.root, "work"),
        onProgress: (EvolveRunProgress) -> Unit = {},
    ): EvolutionRunner {
        val ledger = BudgetLedger(
            maxTokens = settings.tokenBudget,
            maxWallMs = settings.maxWallMinutes * 60_000L,
            maxGenerations = settings.maxGenerations,
            maxCandidates = settings.maxCandidates,
            stagnationLimit = settings.stagnationsBeforeStop,
        )
        return EvolutionRunner(store, archive, ledger, settings, caller, onProgress)
    }

    fun tools(): List<Tool> = listOf(
        EvolveStatusTool(this),
        EvolveHistoryTool(this),
        EvolveDiffTool(this),
        EvolveProposeTool(this),
        EvolveEvaluateTool(this),
    )

    companion object {
        fun at(root: File): EvolveHost = EvolveHost(GenomeStore(root), Archive(File(root, "archive.json")))
    }
}

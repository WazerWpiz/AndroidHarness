package com.androidharness.app.evolve

import com.androidharness.app.tools.EvolveDiffTool
import com.androidharness.app.tools.EvolveEvaluateTool
import com.androidharness.app.tools.EvolveHistoryTool
import com.androidharness.app.tools.EvolveProposeTool
import com.androidharness.app.tools.Tool
import com.androidharness.app.tools.EvolveStatusTool
import java.io.File

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

    /** A fresh runner with a budget built from the caps: one run, one ledger. */
    fun newRun(caller: EvolveModelCaller, workDir: File = File(store.root, "work")): EvolutionRunner {
        val ledger = BudgetLedger(
            maxTokens = settings.tokenBudget,
            maxWallMs = settings.maxWallMinutes * 60_000L,
            maxGenerations = settings.maxGenerations,
            maxCandidates = settings.maxCandidates,
            stagnationLimit = settings.stagnationsBeforeStop,
        )
        return EvolutionRunner(store, archive, ledger, settings, caller)
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

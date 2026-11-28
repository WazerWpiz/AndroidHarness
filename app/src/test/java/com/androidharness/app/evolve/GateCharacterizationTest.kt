package com.androidharness.app.evolve

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden tests for the promotion gate and the screening rule, frozen as
 * they behave today (P11). If a later round changes the gate, these
 * tests must be updated deliberately — a failure here means the gate
 * moved, not that the test is wrong. Nothing in this file changes
 * production code.
 */
class GateCharacterizationTest {

    private val root: File = Files.createTempDirectory("gate-characterization").toFile()
    private val settings = EvolveSettings()

    @After
    fun cleanUp() {
        RealProjectEvaluator.commandRunner = UnavailableTestCommandRunner
        root.deleteRecursively()
    }

    private fun report(
        passed: Double,
        holdout: Double?,
        suiteId: String = "evolve-live-v1",
        modelPin: String = "live/fake/worker",
        criticPin: String = "fake/critic",
        valid: Boolean = true,
        tokensPerSuccess: Double = 100.0,
    ): EvalReport = EvalReport(
        genomeId = "genome",
        suiteId = suiteId,
        meanPassedTasks = passed,
        holdoutScore = holdout,
        valid = valid,
        modelPin = modelPin,
        criticPin = criticPin,
        tokensPerSuccess = tokensPerSuccess,
        successRate = passed / 17.0,
    )

    private fun baseline(passed: Double, holdout: Double?): EvalReport =
        report(passed, holdout).copy(genomeId = "baseline")

    @Test
    fun `a candidate beating its parent by exactly 2 tasks with no holdout drop is approved`() {
        val decision = PromotionGate.wouldPromote(
            baseline(passed = 10.0, holdout = 0.67),
            report(passed = 12.0, holdout = 0.67),
            safetyClean = true, settings,
        )
        assertTrue(decision is GateDecision.Approved)
    }

    @Test
    fun `a candidate beating its parent by 1 task is rejected`() {
        val decision = PromotionGate.wouldPromote(
            baseline(passed = 10.0, holdout = 0.67),
            report(passed = 11.0, holdout = 0.67),
            safetyClean = true, settings,
        )
        assertTrue(decision is GateDecision.Rejected)
        assertTrue((decision as GateDecision.Rejected).reason.contains("2-task"))
    }

    @Test
    fun `a tie with the parent is rejected`() {
        val decision = PromotionGate.wouldPromote(
            baseline(passed = 10.0, holdout = 0.67),
            report(passed = 10.0, holdout = 0.67),
            safetyClean = true, settings,
        )
        assertTrue(decision is GateDecision.Rejected)
        assertTrue((decision as GateDecision.Rejected).reason.contains("2-task"))
    }

    @Test
    fun `a candidate whose holdout dropped is rejected even with a 2-task win`() {
        val decision = PromotionGate.wouldPromote(
            baseline(passed = 10.0, holdout = 0.67),
            report(passed = 12.0, holdout = 0.33),
            safetyClean = true, settings,
        )
        assertTrue(decision is GateDecision.Rejected)
        assertTrue((decision as GateDecision.Rejected).reason.contains("holdout"))
    }

    @Test
    fun `an invalid report on either side is rejected`() {
        val invalidCandidate = PromotionGate.wouldPromote(
            baseline(passed = 10.0, holdout = 0.67),
            report(passed = 12.0, holdout = 0.67, valid = false),
            safetyClean = true, settings,
        )
        assertTrue(invalidCandidate is GateDecision.Rejected)
        assertTrue((invalidCandidate as GateDecision.Rejected).reason.contains("INVALID"))

        val invalidBaseline = PromotionGate.wouldPromote(
            baseline(passed = 10.0, holdout = 0.67).copy(valid = false),
            report(passed = 12.0, holdout = 0.67),
            safetyClean = true, settings,
        )
        assertTrue(invalidBaseline is GateDecision.Rejected)
        assertTrue((invalidBaseline as GateDecision.Rejected).reason.contains("INVALID"))
    }

    @Test
    fun `reports from different suites worker pins or critic pins are never compared`() {
        val base = baseline(passed = 10.0, holdout = 0.67)
        val good = report(passed = 12.0, holdout = 0.67)

        val otherSuite = PromotionGate.wouldPromote(
            base, good.copy(suiteId = "evolve-live-v1-lite"), safetyClean = true, settings,
        )
        assertTrue(otherSuite is GateDecision.Rejected)
        assertTrue((otherSuite as GateDecision.Rejected).reason.contains("same suite"))

        val otherWorker = PromotionGate.wouldPromote(
            base, good.copy(modelPin = "live/other/worker"), safetyClean = true, settings,
        )
        assertTrue(otherWorker is GateDecision.Rejected)
        assertTrue((otherWorker as GateDecision.Rejected).reason.contains("model pins"))

        val otherCritic = PromotionGate.wouldPromote(
            base, good.copy(criticPin = "other/critic"), safetyClean = true, settings,
        )
        assertTrue(otherCritic is GateDecision.Rejected)
        assertTrue((otherCritic as GateDecision.Rejected).reason.contains("critic pins"))
    }

    @Test
    fun `a candidate with safety violations is rejected even when it wins`() {
        val decision = PromotionGate.wouldPromote(
            baseline(passed = 10.0, holdout = 0.67),
            report(passed = 12.0, holdout = 0.67).copy(safetyViolations = 1),
            safetyClean = false, settings,
        )
        assertTrue(decision is GateDecision.Rejected)
        assertTrue((decision as GateDecision.Rejected).reason.contains("safety violations"))
    }

    @Test
    fun `a candidate with no holdout score cannot be offered at all`() {
        val decision = PromotionGate.wouldPromote(
            baseline(passed = 10.0, holdout = 0.67),
            report(passed = 12.0, holdout = null),
            safetyClean = true, settings,
        )
        assertTrue(decision is GateDecision.Rejected)
        assertTrue((decision as GateDecision.Rejected).reason.contains("no holdout score"))
    }

    private fun runner(caller: EvolveModelCaller): EvolutionRunner {
        val host = EvolveHost.at(root)
        val ledger = BudgetLedger(
            maxTokens = host.settings.tokenBudget,
            maxWallMs = host.settings.maxWallMinutes * 60_000L,
            maxGenerations = host.settings.maxGenerations,
            maxCandidates = host.settings.maxCandidates,
            stagnationLimit = host.settings.stagnationsBeforeStop,
        )
        // No-op delays: retries and the 1-second inter-task pause are
        // real in production but must never slow a test.
        return EvolutionRunner(host.store, host.archive, ledger, host.settings, caller, delayFn = {})
    }

    /**
     * A caller whose worker is imperfect under the parent (it
     * over-renames and mis-sorts) and correct under a candidate that
     * carries the verify instruction: the only way a candidate can
     * exceed a parent at 1 seed in a scripted test.
     */
    private fun improvingCaller(parent: Genome): FakeModelCaller = FakeModelCaller(
        proposerGenome = { _ ->
            parent.copy(
                label = "gen-verify",
                hypothesis = "A verify-before-done line should stop claims without evidence.",
                instructions = parent.instructions + "Verify before claiming done: run the checks and quote their evidence.",
                proposerNotes = "Evidence: the parent over-renames fetchDataInternal and mis-sorts; a verify step should stop both.",
            )
        },
        workerSystem = { system, user ->
            val w = if ("Verify before claiming done" in system) FakeModelCaller.correctWorker()
            else FakeModelCaller.imperfectWorker()
            w(user)
        },
    )

    @Test
    fun `screening passes a candidate that exceeds its parent at 1 seed`() = runBlocking {
        val host = EvolveHost.at(root)
        val parent = host.store.save(Genome.baseline())
        val result = runner(improvingCaller(parent)).runGeneration(parent, File(root, "work"))
        assertTrue(result.candidateIds.isNotEmpty())
        assertNotNull(result.promotableId)
        // It went to confirmation: the stored report spans all 3 seeds.
        val confirmed = host.store.report(result.promotableId!!)!!
        assertEquals(listOf(1, 2, 3), confirmed.seeds)
    }

    @Test
    fun `screening rejects a candidate that merely ties its parent at 1 seed`() = runBlocking {
        val host = EvolveHost.at(root)
        val parent = host.store.save(Genome.baseline())
        val caller = FakeModelCaller(
            proposerGenome = { _ ->
                parent.copy(
                    label = "gen-same",
                    hypothesis = "Same score.",
                    instructions = parent.instructions + "Verify before claiming done.",
                    proposerNotes = "Evidence: the candidate scores the same as the parent.",
                )
            },
            worker = FakeModelCaller.correctWorker(),
        )
        val result = runner(caller).runGeneration(parent, File(root, "work"))
        assertTrue(result.candidateIds.isNotEmpty())
        assertNull(result.promotableId)
        // Never confirmed: the stored report is the 1-seed screening report.
        val report = host.store.report(result.candidateIds.first())!!
        assertEquals(listOf(1), report.seeds)
        val entry = host.archive.entries().first { it.genomeId == result.candidateIds.first() }
        assertNotNull(entry.gateReason)
        assertTrue(entry.gateReason!!.contains("screening"))
    }
}

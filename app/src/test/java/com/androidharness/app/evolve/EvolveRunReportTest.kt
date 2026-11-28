package com.androidharness.app.evolve

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P7 — full run report, sampler fix, toolchain indicator.
 * P8 — the report block loads from the stored last-run pointer, the
 * gate reasons are the P8 screening/confirmation reasons, and the lite
 * suite (13 live tasks) is selected when the toolchain is missing.
 *
 * Nothing here calls a real model: the runner tests use the scripted
 * [FakeModelCaller], and the toolchain test only swaps the [TestCommandRunner]
 * the evaluator reads. The real-project tasks are not run at all when
 * the toolchain is missing — the Doctor selects the lite suite instead
 * of failing them.
 */
class EvolveRunReportTest {

    private val root: File = Files.createTempDirectory("run-report").toFile()
    private val host = EvolveHost.at(root)

    @Before
    fun setUp() {
        // Real tasks must fail the same way in every test here, whatever
        // another test class left installed on the global runner.
        RealProjectEvaluator.commandRunner = UnavailableTestCommandRunner
    }

    @After
    fun cleanUp() {
        RealProjectEvaluator.commandRunner = UnavailableTestCommandRunner
        root.deleteRecursively()
    }

    private fun runner(caller: EvolveModelCaller): EvolutionRunner {
        val ledger = BudgetLedger(
            maxTokens = host.settings.tokenBudget,
            maxWallMs = host.settings.maxWallMinutes * 60_000L,
            maxGenerations = host.settings.maxGenerations,
            maxCandidates = host.settings.maxCandidates,
            stagnationLimit = host.settings.stagnationsBeforeStop,
        )
        return EvolutionRunner(host.store, host.archive, ledger, host.settings, caller, delayFn = {})
    }

    private fun callerWithCounter(): FakeModelCaller {
        val parent = host.store.save(Genome.baseline())
        var n = 0
        return FakeModelCaller(
            proposerGenome = { _ ->
                n++
                parent.copy(
                    label = "gen-$n",
                    hypothesis = "Candidate $n.",
                    // Accumulate: each candidate has more instructions than
                    // any earlier one, so the dimension rule (an added
                    // instruction) passes against whatever parent the
                    // sampler picked.
                    instructions = (1..n).map { "Step $it: verify before claiming done." },
                    proposerNotes = "Evidence: candidate $n.",
                )
            },
            worker = FakeModelCaller.correctWorker(),
        )
    }

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
            // workerSystem returns the worker's OUTPUT for this call,
            // not the worker function itself.
            val w = if ("Verify before claiming done" in system) FakeModelCaller.correctWorker()
            else FakeModelCaller.imperfectWorker()
            w(user)
        },
    )

    @Test
    fun `an approved candidate is archived with no gate reason`() = runBlocking {
        val parent = host.store.save(Genome.baseline())
        val result = runner(improvingCaller(parent)).runGeneration(parent, File(root, "work"))
        assertTrue(result.candidateIds.isNotEmpty())
        assertNotNull(result.promotableId)
        val entry = host.archive.entries().first { it.genomeId == result.promotableId }
        assertNull(entry.gateReason)
        // The stored report is the one the screen shows: 13 live tasks
        // in the lite suite, confirmed on 3 seeds.
        val report = host.store.report(result.promotableId!!)
        assertNotNull(report)
        assertEquals(13, report!!.tasks.size)
        assertEquals(listOf(1, 2, 3), report.seeds)
    }

    @Test
    fun `a rejected candidate is archived with the gate reason`() = runBlocking {
        val parent = host.store.save(Genome.baseline())
        val gen1 = runner(improvingCaller(parent)).runGeneration(parent, File(root, "work"))
        assertNotNull(gen1.promotableId)
        // The second generation's parent has a report to beat; its worker
        // produces nothing, so the candidate scores 0 and cannot beat it.
        val parent2 = host.store.load(gen1.promotableId!!)!!
        val failingCaller = FakeModelCaller(
            proposerGenome = { _ ->
                parent2.copy(
                    label = "gen-worse",
                    hypothesis = "A candidate that scores nothing.",
                    instructions = parent2.instructions + "Step 2: verify before claiming done.",
                    proposerNotes = "Evidence: a candidate that scores nothing.",
                )
            },
            worker = { "" },
        )
        val gen2 = runner(failingCaller).runGeneration(parent2, File(root, "work"), generation = 2)
        assertTrue(gen2.candidateIds.isNotEmpty())
        assertNull(gen2.promotableId)
        val entry = host.archive.entries().first { it.genomeId == gen2.candidateIds.first() }
        assertNotNull(entry.gateReason)
        assertTrue(entry.gateReason!!.contains("screening"))
        assertTrue(entry.gateReason!!.length <= 200)
    }

    @Test
    fun `a run reaches the second generation through run`() = runBlocking {
        host.updateSettings(host.settings.copy(maxGenerations = 2, maxCandidates = 2))
        host.store.save(Genome.baseline())
        val parent = host.store.load(Genome.baseline().id)!!
        // Deterministic by construction now: run() excludes the current
        // parent from the draw, so the sampler cannot re-pick Baseline
        // and stop the run after one generation.
        val result = runner(callerWithCounter()).run(parent, File(root, "work"))
        assertEquals(2, result.generations.size)
        assertEquals(4, result.generations.sumOf { it.candidateIds.size })
    }

    @Test
    fun `the toolchain indicator reads what the test runner reports`() {
        RealProjectEvaluator.commandRunner = UnavailableTestCommandRunner
        assertFalse(RealProjectEvaluator.commandRunner.toolchainReady)
        val ready = object : TestCommandRunner {
            override val toolchainReady: Boolean = true
            override suspend fun run(command: String, cwd: File, timeoutMs: Int): TestCommandResult =
                TestCommandResult(0, false, "", "")
        }
        RealProjectEvaluator.commandRunner = ready
        assertTrue(RealProjectEvaluator.commandRunner.toolchainReady)
    }
}

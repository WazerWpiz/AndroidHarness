package com.androidharness.app.evolve

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A scripted stand-in for a real model: the proposer returns a genome the
 * test builds, the worker returns the correct file blocks for each task
 * (computed from the task prompt, like a competent model would), and the
 * critic passes. This verifies the harness mechanics — propose, evaluate,
 * archive, gate — not any real model's skill.
 */
class FakeModelCaller(
    private val proposerGenome: (Genome) -> Genome? = { null },
    private val worker: (String) -> String = { "" },
    private val criticVerdict: String = "PASS",
    private val configuredRoles: Set<EvolveRole> = EvolveRole.entries.toSet(),
) : EvolveModelCaller {
    var proposerCalls = 0
        private set
    var workerCalls = 0
        private set

    override suspend fun isConfigured(role: EvolveRole): Boolean = role in configuredRoles

    override suspend fun complete(
        role: EvolveRole,
        system: String,
        user: String,
        maxOutputTokens: Int,
    ): ModelCallResult = when (role) {
        EvolveRole.PROPOSER -> {
            proposerCalls++
            // The test's proposer genome is supplied via the parent in the prompt;
            // the fake extracts nothing — it returns the canned genome JSON.
            val genome = proposerGenome(Genome.baseline())
            if (genome == null) ModelCallResult("not json at all", 10, 5)
            else ModelCallResult(
                Json { encodeDefaults = true }.encodeToString(Genome.serializer(), genome),
                100, 50, "fake/proposer",
            )
        }
        EvolveRole.WORKER -> {
            workerCalls++
            ModelCallResult(worker(user), 200, 100, "fake/worker")
        }
        EvolveRole.CRITIC -> ModelCallResult(criticVerdict, 50, 10, "fake/critic")
    }

    companion object {
        /** A worker that does each live task correctly, by reading the prompt. */
        fun correctWorker(): (String) -> String = { user ->
            when {
                "lines 101-150" in user && "claim.txt" in user -> {
                    val page = (101..150).joinToString("\n") { "line $it" }
                    "```page.txt\n$page\n```\n```claim.txt\nstatus: done\nevidence: page.txt has 50 lines\n```"
                }
                "lines 101-150" in user -> {
                    val page = (101..150).joinToString("\n") { "line $it" }
                    "```page.txt\n$page\n```"
                }
                "needle" in user -> "```hits.txt\na.txt: needle here\nc.md: another needle\n```"
                "ALPHA" in user -> "```edit.txt\nALPHA\nbeta\nGAMMA\n```"
                "loadData" in user -> "```main.kt\nfun loadData(): String {\n    return loadDataInternal()\n}\n\nfun loadDataInternal(): String = \"data\"\n```"
                "second line to TWO" in user -> "```patch.txt\none\nTWO\nthree\n```"
                "modified:" in user -> "```summary.txt\nmodified: 1\nuntracked: 1\n```"
                "pinned constraint" in user -> {
                    "```summary.txt\nfiller line 1\nfiller line 2\nfiller line 3\nfiller line 4\nfiller line 5\nfiller line 46\nfiller line 47\nfiller line 48\nfiller line 49\nfiller line 50\n[PINNED] keep the 30 minute cap\n```"
                }
                else -> ""
            }
        }
    }
}

class EvolutionRunnerTest {

    private val root: File = Files.createTempDirectory("runner").toFile()

    @After
    fun cleanUp() {
        root.deleteRecursively()
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
        return EvolutionRunner(host.store, host.archive, ledger, host.settings, caller)
    }

    @Test
    fun `one generation with a real proposer and worker produces a promotable candidate`() = runBlocking {
        val host = EvolveHost.at(root)
        val parent = host.store.save(Genome.baseline())
        val caller = FakeModelCaller(
            proposerGenome = { _ ->
                parent.copy(
                    label = "gen-verify",
                    hypothesis = "A verify-before-done line should stop claims without evidence.",
                    instructions = parent.instructions + "Verify before claiming done: run the checks and quote their evidence.",
                )
            },
            worker = FakeModelCaller.correctWorker(),
        )
        val result = runner(caller).runGeneration(parent, File(root, "work"))
        assertTrue(result.candidateIds.isNotEmpty())
        // P4: each merged report covers the live suite (8) plus the
        // real-project suite (4).
        assertTrue(result.reports.all { it.tasks.size == 12 })
        assertNotNull(result.promotableId)
        assertTrue(result.proposalFailures.isEmpty())
        result.candidateIds.forEach { assertNotNull(host.store.load(it)) }
        val archived = host.archive.entries().map { it.genomeId }.toSet()
        assertTrue(archived.containsAll(result.candidateIds))
        assertTrue(archived.contains(parent.id))
    }

    @Test
    fun `a full run stops within the generation cap`() = runBlocking {
        val host = EvolveHost.at(root)
        val parent = host.store.save(Genome.baseline())
        val caller = FakeModelCaller(
            proposerGenome = { _ ->
                parent.copy(
                    label = "gen-verify",
                    hypothesis = "A verify-before-done line should stop claims without evidence.",
                    instructions = parent.instructions + "Verify before claiming done: run the checks and quote their evidence.",
                )
            },
            worker = FakeModelCaller.correctWorker(),
        )
        val result = runner(caller).run(parent, File(root, "work"))
        assertTrue(result.generations.size in 1..host.settings.maxGenerations)
        assertNotNull(result.promotableId)
        assertNotNull(host.store.load(result.promotableId!!))
    }

    @Test
    fun `without a configured model the run refuses instead of faking a score`() = runBlocking {
        val host = EvolveHost.at(root)
        val parent = host.store.save(Genome.baseline())
        val caller = FakeModelCaller(configuredRoles = emptySet())
        try {
            runner(caller).runGeneration(parent, File(root, "work"))
            throw AssertionError("expected NoModelConfigured")
        } catch (e: NoModelConfigured) {
            assertTrue(e.message!!.contains("PROPOSER"))
        }
    }

    @Test
    fun `a proposer that returns no JSON produces no candidate, only a recorded failure`() = runBlocking {
        val host = EvolveHost.at(root)
        val parent = host.store.save(Genome.baseline())
        val caller = FakeModelCaller(proposerGenome = { null }, worker = FakeModelCaller.correctWorker())
        val result = runner(caller).runGeneration(parent, File(root, "work"))
        assertTrue(result.candidateIds.isEmpty())
        assertEquals(host.settings.maxCandidates, result.proposalFailures.size)
        assertEquals(null, result.promotableId)
    }

    @Test
    fun `the gate rule without approval - delta, safety and token regression`() {
        val settings = EvolveSettings.DEFAULT
        val baseline = EvalReport(genomeId = "base", successRate = 0.5, holdoutScore = 0.5, tokensPerSuccess = 100.0)
        val same = EvalReport(genomeId = "same", successRate = 0.5, holdoutScore = 0.5, tokensPerSuccess = 100.0)
        assertTrue(PromotionGate.wouldPromote(baseline, same, true, settings) is GateDecision.Rejected)
        val better = EvalReport(genomeId = "better", successRate = 0.6, holdoutScore = 1.0, tokensPerSuccess = 100.0)
        assertTrue(PromotionGate.wouldPromote(baseline, better, true, settings) is GateDecision.Approved)
        val unsafe = EvalReport(genomeId = "unsafe", successRate = 0.9, holdoutScore = 1.0, tokensPerSuccess = 100.0, safetyViolations = 1)
        assertTrue(PromotionGate.wouldPromote(baseline, unsafe, false, settings) is GateDecision.Rejected)
        val wasteful = EvalReport(genomeId = "wasteful", successRate = 0.6, holdoutScore = 1.0, tokensPerSuccess = 200.0)
        assertTrue(PromotionGate.wouldPromote(baseline, wasteful, true, settings) is GateDecision.Rejected)
        val first = EvalReport(genomeId = "first", successRate = 0.5, holdoutScore = 0.5)
        assertTrue(PromotionGate.wouldPromote(null, first, true, settings) is GateDecision.Approved)
        val zero = EvalReport(genomeId = "zero", successRate = 0.0)
        assertTrue(PromotionGate.wouldPromote(null, zero, true, settings) is GateDecision.Rejected)
    }

    @Test
    fun `the gate also needs a holdout win`() {
        val settings = EvolveSettings.DEFAULT
        val baseline = EvalReport(genomeId = "base", successRate = 0.5, holdoutScore = 0.5, tokensPerSuccess = 100.0)
        // Wins training, ties holdout: overfitting the training split is not enough.
        val trainingOnly = EvalReport(genomeId = "training-only", successRate = 0.8, holdoutScore = 0.5, tokensPerSuccess = 100.0)
        assertTrue(PromotionGate.wouldPromote(baseline, trainingOnly, true, settings) is GateDecision.Rejected)
        // Wins training, loses holdout.
        val holdoutLoss = EvalReport(genomeId = "holdout-loss", successRate = 0.9, holdoutScore = 0.0, tokensPerSuccess = 100.0)
        assertTrue(PromotionGate.wouldPromote(baseline, holdoutLoss, true, settings) is GateDecision.Rejected)
        // No holdout evidence at all cannot be offered.
        val noHoldout = EvalReport(genomeId = "no-holdout", successRate = 0.8, tokensPerSuccess = 100.0)
        assertTrue(PromotionGate.wouldPromote(baseline, noHoldout, true, settings) is GateDecision.Rejected)
        val bothWin = EvalReport(genomeId = "both-win", successRate = 0.8, holdoutScore = 1.0, tokensPerSuccess = 100.0)
        assertTrue(PromotionGate.wouldPromote(baseline, bothWin, true, settings) is GateDecision.Approved)
        // The first candidate must score on holdout too.
        val firstFlat = EvalReport(genomeId = "first-flat", successRate = 0.8, holdoutScore = 0.0)
        assertTrue(PromotionGate.wouldPromote(null, firstFlat, true, settings) is GateDecision.Rejected)
        // A parent that already aces holdout cannot be beaten there, so tying it counts.
        val aceBaseline = EvalReport(genomeId = "ace", successRate = 0.5, holdoutScore = 1.0, tokensPerSuccess = 100.0)
        val aceTie = EvalReport(genomeId = "ace-tie", successRate = 0.8, holdoutScore = 1.0, tokensPerSuccess = 100.0)
        assertTrue(PromotionGate.wouldPromote(aceBaseline, aceTie, true, settings) is GateDecision.Approved)
    }

    @Test
    fun `a vetoed candidate is archived as vetoed and never promotable`() = runBlocking {
        val host = EvolveHost.at(root)
        val bad = Genome.baseline().copy(
            instructions = listOf("Open PromotionGate.kt first."),
        ).identified()
        assertTrue(SafetyProbes.vetoed(bad, root))
        val parent = host.store.save(Genome.baseline())
        val caller = FakeModelCaller(
            proposerGenome = { _ ->
                parent.copy(
                    label = "gen-verify",
                    hypothesis = "A verify-before-done line should stop claims without evidence.",
                    instructions = parent.instructions + "Verify before claiming done: run the checks and quote their evidence.",
                )
            },
            worker = FakeModelCaller.correctWorker(),
        )
        val result = runner(caller).runGeneration(parent, File(root, "work"))
        val vetoed = host.archive.entries().filter { it.status == ArchiveStatus.VETOED }
        assertTrue(vetoed.none { it.genomeId == result.promotableId })
        assertEquals(null, result.reports.firstOrNull { it.safetyViolations > 0 })
    }
}

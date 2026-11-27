package com.androidharness.app.evolve

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EvolutionRunnerTest {

    private val root: File = Files.createTempDirectory("runner").toFile()

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun runner(): EvolutionRunner {
        val host = EvolveHost.at(root)
        val ledger = BudgetLedger(
            maxTokens = host.settings.tokenBudget,
            maxWallMs = host.settings.maxWallMinutes * 60_000L,
            maxGenerations = host.settings.maxGenerations,
            maxCandidates = host.settings.maxCandidates,
            stagnationLimit = host.settings.stagnationsBeforeStop,
        )
        return EvolutionRunner(host.store, host.archive, ledger, host.settings)
    }

    @Test
    fun `one generation from the baseline produces evaluated candidates and a promotable one`() {
        val host = EvolveHost.at(root)
        val parent = host.store.save(Genome.baseline())
        val result = runner().runGeneration(parent, TraceMiner.mine(emptyList()), File(root, "work"))
        assertTrue(result.candidateIds.isNotEmpty())
        assertTrue(result.reports.all { it.tasks.size == 8 })
        assertNotNull(result.promotableId)
        // Every candidate is stored and archived.
        result.candidateIds.forEach { assertNotNull(host.store.load(it)) }
        val archived = host.archive.entries().map { it.genomeId }.toSet()
        assertTrue(archived.containsAll(result.candidateIds))
        assertTrue(archived.contains(parent.id))
    }

    @Test
    fun `a full run stops within the generation cap and keeps the last promotable candidate`() {
        val host = EvolveHost.at(root)
        val parent = host.store.save(Genome.baseline())
        val result = runner().run(parent, File(root, "work"))
        assertTrue(result.generations.size in 1..host.settings.maxGenerations)
        assertNotNull(result.promotableId)
        assertNotNull(host.store.load(result.promotableId!!))
    }

    @Test
    fun `the gate rule without approval - delta, safety and token regression`() {
        val settings = EvolveSettings.DEFAULT
        val baseline = EvalReport(genomeId = "base", successRate = 0.5, tokensPerSuccess = 100.0)
        val same = EvalReport(genomeId = "same", successRate = 0.5, tokensPerSuccess = 100.0)
        assertTrue(PromotionGate.wouldPromote(baseline, same, true, settings) is GateDecision.Rejected)
        val better = EvalReport(genomeId = "better", successRate = 0.6, tokensPerSuccess = 100.0)
        assertTrue(PromotionGate.wouldPromote(baseline, better, true, settings) is GateDecision.Approved)
        val unsafe = EvalReport(genomeId = "unsafe", successRate = 0.9, tokensPerSuccess = 100.0, safetyViolations = 1)
        assertTrue(PromotionGate.wouldPromote(baseline, unsafe, false, settings) is GateDecision.Rejected)
        val wasteful = EvalReport(genomeId = "wasteful", successRate = 0.6, tokensPerSuccess = 200.0)
        assertTrue(PromotionGate.wouldPromote(baseline, wasteful, true, settings) is GateDecision.Rejected)
        val first = EvalReport(genomeId = "first", successRate = 0.5)
        assertTrue(PromotionGate.wouldPromote(null, first, true, settings) is GateDecision.Approved)
        val zero = EvalReport(genomeId = "zero", successRate = 0.0)
        assertTrue(PromotionGate.wouldPromote(null, zero, true, settings) is GateDecision.Rejected)
    }

    @Test
    fun `template candidates always differ from their parent`() {
        val parent = Genome.baseline()
        val candidates = EvolutionRunner.templateCandidates(parent, TraceMiner.mine(emptyList()))
        assertTrue(candidates.isNotEmpty())
        assertTrue(candidates.all { it.id != parent.id })
        assertTrue(candidates.all { it.parent == parent.id })
        assertTrue(candidates.map { it.id }.toSet().size == candidates.size)
    }

    @Test
    fun `a vetoed candidate is archived as vetoed and never promotable`() {
        val host = EvolveHost.at(root)
        // A parent that already carries the guidance still gets a clean run;
        // vetoing is proven by the probes: a genome naming a protected file.
        val bad = Genome.baseline().copy(
            instructions = listOf("Open PromotionGate.kt first."),
        ).identified()
        assertTrue(SafetyProbes.vetoed(bad, root))
        val parent = host.store.save(Genome.baseline())
        val result = runner().runGeneration(parent, TraceMiner.mine(emptyList()), File(root, "work"))
        val vetoed = host.archive.entries().filter { it.status == ArchiveStatus.VETOED }
        assertTrue(vetoed.none { it.genomeId == result.promotableId })
        assertNull(result.reports.firstOrNull { it.safetyViolations > 0 })
    }
}

package com.androidharness.app.evolve

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P6 — live run progress: the runner reports each real transition, and the
 * host turns those reports into the one run state the screen shows.
 *
 * Nothing here calls a real model: the runner-level tests record the
 * progress callback directly, and the host-level tests drive
 * [EvolveHost.startRun] with the scripted [FakeModelCaller].
 */
class EvolveRunProgressTest {

    private val root: File = Files.createTempDirectory("run-progress").toFile()
    private val host = EvolveHost.at(root)

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private suspend fun awaitSettled() {
        withTimeout(60_000) {
            host.runState.first { it != null && !it.running }
        }
    }

    /** A caller that stalls inside the worker call so the test can cancel mid-evaluation. */
    private class SlowWorkerCaller(
        private val inner: FakeModelCaller,
        private val delayMs: Long,
    ) : EvolveModelCaller {
        override suspend fun isConfigured(role: EvolveRole): Boolean = inner.isConfigured(role)
        override suspend fun complete(
            role: EvolveRole,
            system: String,
            user: String,
            maxOutputTokens: Int,
        ): ModelCallResult {
            if (role == EvolveRole.WORKER) delay(delayMs)
            return inner.complete(role, system, user, maxOutputTokens)
        }
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
                    instructions = parent.instructions + "Step $n: verify before claiming done.",
                )
            },
            worker = FakeModelCaller.correctWorker(),
        )
    }

    private fun runner(caller: EvolveModelCaller, events: MutableList<EvolveRunProgress>): EvolutionRunner {
        val ledger = BudgetLedger(
            maxTokens = host.settings.tokenBudget,
            maxWallMs = host.settings.maxWallMinutes * 60_000L,
            maxGenerations = host.settings.maxGenerations,
            maxCandidates = host.settings.maxCandidates,
            stagnationLimit = host.settings.stagnationsBeforeStop,
        )
        return EvolutionRunner(host.store, host.archive, ledger, host.settings, caller) { events += it }
    }

    @Test
    fun `progress advances across generations and candidates with real scores`() = runBlocking {
        host.updateSettings(host.settings.copy(maxGenerations = 2, maxCandidates = 2))
        host.store.save(Genome.baseline())
        val events = mutableListOf<EvolveRunProgress>()
        val parent = host.store.load(Genome.baseline().id)!!
        val run = runner(callerWithCounter(), events)
        // Drive the generations directly: run() samples the next parent at
        // random, and it may re-pick the current parent and stop after one
        // generation — pre-existing sampler behaviour, not a progress bug.
        // Driving runGeneration keeps this test about progress, not luck.
        val gen1 = run.runGeneration(parent, File(root, "work"), generation = 1)
        assertEquals(2, gen1.candidateIds.size)
        val parent2 = host.store.load(gen1.candidateIds.first())!!
        val gen2 = run.runGeneration(parent2, File(root, "work"), generation = 2)
        assertEquals(2, gen2.candidateIds.size)

        assertTrue(events.isNotEmpty())
        // The first thing the run reports is generation 1, proposing.
        assertEquals(1, events.first().generation)
        assertEquals(EvolveRunPhase.PROPOSING, events.first().phase)
        // The run reaches the second generation.
        assertTrue(events.any { it.generation == 2 })
        // Candidate attempts advance within a generation.
        assertEquals(2, events.maxOf { it.candidateIndex })
        // Every evaluation names the candidate being worked on.
        assertTrue(events.filter { it.phase == EvolveRunPhase.EVALUATING }.all { it.candidateId != null })
        // Tokens only ever grow.
        assertTrue(events.zipWithNext().all { (a, b) -> b.tokensUsed >= a.tokensUsed })
        // Finished candidates carry their real scores: each entry matches
        // the report the store holds for that genome.
        val scoring = events.filter { it.phase == EvolveRunPhase.SCORING }
        assertTrue(scoring.isNotEmpty())
        val finished = scoring.last().finishedCandidates
        // The live list is capped at maxCandidates: the most recent two.
        assertEquals(2, finished.size)
        finished.forEach { candidate ->
            assertNotNull(host.store.load(candidate.genomeId))
            assertEquals(
                host.store.report(candidate.genomeId)?.successRate,
                candidate.successRate,
            )
            assertTrue(candidate.successRate > 0.0)
        }
        // Across the whole run, four distinct candidates finished.
        val distinctFinished = scoring.flatMap { it.finishedCandidates }.map { it.genomeId }.toSet()
        assertEquals(4, distinctFinished.size)
    }

    @Test
    fun `a proposal failure is reported with its reason, then the run goes on`() = runBlocking {
        host.updateSettings(host.settings.copy(maxGenerations = 1, maxCandidates = 2))
        host.store.save(Genome.baseline())
        var calls = 0
        val caller = FakeModelCaller(
            proposerGenome = { _ ->
                calls++
                // First proposal is garbage; the second one is a real genome.
                if (calls == 1) null else Genome.baseline().copy(
                    label = "gen-after-failure",
                    hypothesis = "A second attempt after a bad proposal.",
                    instructions = listOf("Verify before claiming done."),
                )
            },
            worker = FakeModelCaller.correctWorker(),
        )
        val events = mutableListOf<EvolveRunProgress>()
        val parent = host.store.load(Genome.baseline().id)!!
        val result = runner(caller, events).runGeneration(parent, File(root, "work"), generation = 1)

        assertEquals(1, result.proposalFailures.size)
        assertEquals(1, result.candidateIds.size)
        val withFailure = events.filter { it.proposalFailures > 0 }
        assertTrue(withFailure.isNotEmpty())
        assertNotNull(withFailure.first().lastFailureReason)
        // The run continued after the failure and finished a candidate.
        assertTrue(events.any { it.phase == EvolveRunPhase.SCORING })
    }

    @Test
    fun `starting a run sets generation 1 with no finished candidates`() = runBlocking {
        host.store.save(Genome.baseline())
        assertTrue(host.startRun(callerWithCounter(), File(root, "work")))
        val state = host.runState.value
        assertNotNull(state)
        assertTrue(state!!.running)
        assertEquals(1, state.generation)
        assertTrue(state.finishedCandidates.isEmpty())
        assertNotNull(state.startedAtMs)
        assertEquals(host.settings.maxGenerations, state.maxGenerations)
        assertEquals(host.settings.maxCandidates, state.maxCandidates)
        assertEquals(host.settings.tokenBudget, state.tokenBudget)
        awaitSettled()
    }

    @Test
    fun `cancelling a run stops it with no promotable candidate`() = runBlocking {
        host.store.save(Genome.baseline())
        val caller = SlowWorkerCaller(callerWithCounter(), delayMs = 5_000)
        assertTrue(host.startRun(caller, File(root, "work")))
        withTimeout(30_000) {
            host.runState.first { it != null && it.running && it.phase == EvolveRunPhase.EVALUATING }
        }
        host.cancelRun()
        awaitSettled()
        val state = host.runState.value
        assertNotNull(state)
        assertFalse(state!!.running)
        assertNull(state.promotableId)
        assertTrue(state.summary!!.contains("stopped"))
    }

    @Test
    fun `a run stopped by the generation cap reports the stop reason, never a fake percent`() = runBlocking {
        host.updateSettings(host.settings.copy(maxGenerations = 1))
        host.store.save(Genome.baseline())
        assertTrue(host.startRun(callerWithCounter(), File(root, "work")))
        awaitSettled()
        val state = host.runState.value
        assertNotNull(state)
        assertFalse(state!!.running)
        assertEquals("generation cap reached (1)", state.stopReason)
        assertEquals(EvolveRunPhase.DONE, state.phase)
        assertTrue(state.summary!!.contains("stopped: generation cap reached (1)"))
        // Counts, not a percent: the state reports which generation it
        // reached, out of how many — there is no percent-complete anywhere.
        assertEquals(1, state.generation)
        assertEquals(1, state.maxGenerations)
    }
}

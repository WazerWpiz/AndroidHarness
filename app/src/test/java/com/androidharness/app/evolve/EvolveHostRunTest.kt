package com.androidharness.app.evolve

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
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

/** A caller that blocks inside the proposer call until the test releases it. */
private class LatchModelCaller(private val latch: CompletableDeferred<Unit>) : EvolveModelCaller {
    override suspend fun isConfigured(role: EvolveRole): Boolean = true
    override suspend fun complete(
        role: EvolveRole,
        system: String,
        user: String,
        maxOutputTokens: Int,
    ): ModelCallResult {
        if (role == EvolveRole.PROPOSER) latch.await()
        return ModelCallResult("not json at all", 10, 5)
    }
}

class EvolveHostRunTest {

    private val root: File = Files.createTempDirectory("host-run").toFile()
    private val host = EvolveHost.at(root)

    @After
    fun cleanUp() {
        RealProjectEvaluator.commandRunner = UnavailableTestCommandRunner
        root.deleteRecursively()
    }

    private suspend fun awaitSettled() {
        withTimeout(120_000) {
            host.runState.first { it != null && !it.running }
        }
    }

    @Test
    fun `a run through the host produces a promotable candidate`() = runBlocking {
        host.store.save(Genome.baseline())
        // P8: the worker is imperfect under the parent and correct
        // under the candidate, so the candidate exceeds the parent;
        // and the candidate carries proposerNotes, or the proposer
        // refuses it for missing evidence.
        val caller = FakeModelCaller(
            proposerGenome = { parent ->
                parent.copy(
                    label = "Re-read",
                    hypothesis = "Re-reading cuts false completion.",
                    instructions = listOf("Re-read every changed site.", "Verify before claiming done."),
                    proposerNotes = "Evidence: the parent over-renames fetchDataInternal and mis-sorts; a verify step should stop both.",
                )
            },
            workerSystem = { system, user ->
                val w = if ("Verify before claiming done" in system) FakeModelCaller.correctWorker()
                else FakeModelCaller.imperfectWorker()
                w(user)
            },
        )
        assertTrue(host.startRun(caller, File(root, "work"), delayFn = {}))
        awaitSettled()
        val state = host.runState.value
        assertNotNull(state)
        assertFalse(state!!.running)
        assertNull(state.error)
        assertTrue(state.summary!!.contains("candidate(s)"))
        assertNotNull(state.promotableId)
        host.dismissPromotable()
        assertNull(host.runState.value?.promotableId)
    }

    @Test
    fun `a run auto-prunes the archive when it ends`() = runBlocking {
        RealProjectEvaluator.commandRunner = UnavailableTestCommandRunner
        val baseline = host.store.save(Genome.baseline())
        host.store.activate(baseline.id, null, System.currentTimeMillis(), "test")
        // Pre-seed 12 losers under the baseline: 10 middling, 2 worst.
        // The baseline row may already exist when another test in this
        // class ran first, so only add it when it is missing.
        if (host.archive.entries().none { it.genomeId == baseline.id }) {
            host.archive.add(ArchiveEntry(genomeId = baseline.id, label = "Baseline", createdAt = 1L))
        }
        // Each seeded row needs a STORED genome behind it: the runner
        // samples parents from the archive and loads them from the
        // store, so a row without a genome would break the run.
        fun seedLoser(label: String, rate: Double, at: Long): String {
            val genome = host.store.save(Genome(label = label, instructions = listOf("$label instruction.")))
            host.archive.add(
                ArchiveEntry(
                    genomeId = genome.id, parent = baseline.id, label = label,
                    successRate = rate, createdAt = at,
                ),
            )
            return genome.id
        }
        (1..10).forEach { i -> seedLoser("seeded-$i", 0.5, 10L + i) }
        val worst1 = seedLoser("worst-1", 0.0, 21L)
        val worst2 = seedLoser("worst-2", 0.0, 22L)
        val caller = FakeModelCaller(
            proposerGenome = { parent ->
                parent.copy(
                    label = "Re-read",
                    hypothesis = "Re-reading cuts false completion.",
                    instructions = listOf("Re-read every changed site.", "Verify before claiming done."),
                    proposerNotes = "Evidence: the parent over-renames fetchDataInternal and mis-sorts; a verify step should stop both.",
                )
            },
            workerSystem = { system, user ->
                val w = if ("Verify before claiming done" in system) FakeModelCaller.correctWorker()
                else FakeModelCaller.imperfectWorker()
                w(user)
            },
        )
        assertTrue(host.startRun(caller, File(root, "work"), delayFn = {}))
        awaitSettled()
        val state = host.runState.value
        assertNotNull(state)
        assertFalse(state!!.running)
        val ids = host.archive.entries().map { it.genomeId }.toSet()
        // The archive was pruned after the run: the two worst seeded
        // losers are gone, while the active genome survives.
        assertTrue(worst1 !in ids)
        assertTrue(worst2 !in ids)
        assertTrue(baseline.id in ids)
        // The last run's candidate survives, so the P8 report block's
        // data source still resolves after the prune.
        val pointerId = host.store.lastRun()?.candidateId
        if (pointerId != null) {
            assertTrue(pointerId in ids)
            assertNotNull(host.archive.entries().firstOrNull { it.genomeId == pointerId })
        }
        // Prune only removed rows: the run's candidates keep their
        // stored genomes and reports.
        val promotableId = state.promotableId
        if (promotableId != null) {
            assertNotNull(host.store.load(promotableId))
            assertNotNull(host.store.report(promotableId))
        }
    }

    @Test
    fun `a second start while a run is going is refused, and cancel stops the run`() {
        runBlocking {
            host.store.save(Genome.baseline())
            val latch = CompletableDeferred<Unit>()
            val caller = LatchModelCaller(latch)
            assertTrue(host.startRun(caller, File(root, "work")))
            withTimeout(30_000) {
                host.runState.first { it != null && it.running }
            }
            assertFalse(host.startRun(caller, File(root, "work")))
            host.cancelRun()
            awaitSettled()
            val state = host.runState.value
            assertNotNull(state)
            assertFalse(state!!.running)
            assertTrue(state.summary!!.contains("stopped"))
            latch.complete(Unit)
        }
    }
}

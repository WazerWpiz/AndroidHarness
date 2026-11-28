package com.androidharness.app.evolve

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P8 addition — the run report survives an app restart: the last-run
 * pointer is written to storage when a run ends, and a fresh host over
 * the same directory reads the same candidate, report and gate reason
 * with no run state involved. With no run ever, the block falls back
 * to the newest archived candidate, or shows nothing when the archive
 * is empty.
 */
class PersistedReportTest {

    private val root: File = Files.createTempDirectory("persisted-report").toFile()

    @After
    fun cleanUp() {
        RealProjectEvaluator.commandRunner = UnavailableTestCommandRunner
        root.deleteRecursively()
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

    private suspend fun awaitSettled(host: EvolveHost) {
        withTimeout(120_000) {
            host.runState.first { it != null && !it.running }
        }
    }

    @Test
    fun `the last-run pointer survives a fresh host over the same directory`() = runBlocking {
        RealProjectEvaluator.commandRunner = UnavailableTestCommandRunner
        val host = EvolveHost.at(root)
        val parent = host.store.save(Genome.baseline())
        assertTrue(host.startRun(improvingCaller(parent), File(root, "work"), delayFn = {}))
        awaitSettled(host)
        val state = host.runState.value
        assertNotNull(state)
        assertNotNull(state!!.promotableId)

        // A FRESH host over the SAME directory: an app restart. No run
        // state is involved — the pointer and the report come from disk.
        val fresh = EvolveHost.at(root)
        assertNull(fresh.runState.value)
        val pointer = fresh.store.lastRun()
        assertNotNull(pointer)
        assertEquals(state.promotableId, pointer!!.candidateId)
        assertTrue(pointer.summary.isNotBlank())
        val report = fresh.store.report(pointer.candidateId!!)
        assertNotNull(report)
        assertEquals(13, report!!.tasks.size)
        assertEquals(listOf(1, 2, 3), report.seeds)
        val entry = fresh.archive.entries().first { it.genomeId == pointer.candidateId }
        assertNull(entry.gateReason)
        assertEquals(report.successRate, entry.successRate)
    }

    @Test
    fun `no run ever means no pointer, and the fallback is the newest archived candidate`() {
        val host = EvolveHost.at(root)
        assertNull(host.store.lastRun())
        assertTrue(host.archive.entries().isEmpty())
        // Two archived candidates: the fallback is the newest by creation.
        val first = host.store.save(
            Genome(label = "first", createdAt = 1_000L, instructions = listOf("One.")).identified(),
        )
        val second = host.store.save(
            Genome(label = "second", createdAt = 2_000L, instructions = listOf("Two.")).identified(),
        )
        host.archive.add(ArchiveEntry(genomeId = first.id, label = "first", createdAt = 1_000L))
        host.archive.add(ArchiveEntry(genomeId = second.id, parent = first.id, label = "second", createdAt = 2_000L))
        assertNull(host.store.lastRun())
        val newest = host.archive.entries().sortedByDescending { it.createdAt }.firstOrNull()?.genomeId
        assertEquals(second.id, newest)
    }

    @Test
    fun `a blocked run writes a pointer with no candidate`() = runBlocking {
        RealProjectEvaluator.commandRunner = UnavailableTestCommandRunner
        val host = EvolveHost.at(root)
        host.store.save(Genome.baseline())
        // The critic ping throws, so the Doctor blocks the run before
        // any evaluation starts.
        val inner = FakeModelCaller(worker = FakeModelCaller.correctWorker())
        val caller = object : EvolveModelCaller {
            override suspend fun isConfigured(role: EvolveRole): Boolean = true
            override suspend fun complete(
                role: EvolveRole,
                system: String,
                user: String,
                maxOutputTokens: Int,
            ): ModelCallResult {
                if (role == EvolveRole.CRITIC) throw RuntimeException("401 Unauthorized: bad key")
                return inner.complete(role, system, user, maxOutputTokens)
            }
        }
        assertTrue(host.startRun(caller, File(root, "work"), delayFn = {}))
        awaitSettled(host)
        val state = host.runState.value
        assertNotNull(state)
        assertTrue(state!!.error!!.contains("critic"))
        // No evaluation started: the archive is empty (no candidate was
        // ever evaluated or archived). The Doctor's worker ping is not
        // an evaluation — it only proves the model answers.
        assertTrue(host.archive.entries().isEmpty())
        val pointer = host.store.lastRun()
        assertNotNull(pointer)
        assertNull(pointer!!.candidateId)
        assertTrue(pointer.summary.contains("critic"))
    }
}

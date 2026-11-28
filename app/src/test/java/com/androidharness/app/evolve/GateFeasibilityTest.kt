package com.androidharness.app.evolve

import java.io.File
import java.nio.file.Files
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

/** A caller whose pings always answer, so the Doctor never blocks these tests. */
private class PingOkCaller : EvolveModelCaller {
    var proposerCalls = 0
        private set

    override suspend fun isConfigured(role: EvolveRole): Boolean = true

    override suspend fun complete(
        role: EvolveRole,
        system: String,
        user: String,
        maxOutputTokens: Int,
    ): ModelCallResult = when (role) {
        EvolveRole.PROPOSER -> {
            proposerCalls++
            ModelCallResult("not json at all", 10, 5, "fake/proposer")
        }
        EvolveRole.WORKER -> ModelCallResult("ok", 10, 5, "fake/worker")
        EvolveRole.CRITIC -> ModelCallResult("{\"verdict\":\"PASS\",\"reason\":\"ok\"}", 10, 5, "fake/critic")
    }
}

class GateFeasibilityTest {

    private val root: File = Files.createTempDirectory("gate-feasibility").toFile()

    @After
    fun cleanUp() {
        RealProjectEvaluator.commandRunner = UnavailableTestCommandRunner
        root.deleteRecursively()
    }

    private suspend fun awaitSettled(host: EvolveHost) {
        withTimeout(120_000) {
            host.runState.first { it != null && !it.running }
        }
    }

    /**
     * A lite-suite report (the Doctor picks lite in tests: no toolchain):
     * the first [failed] of 13 tasks FAIL, the rest PASS.
     */
    private fun liteReport(
        genomeId: String,
        failed: Int,
        valid: Boolean = true,
        suiteId: String = "evolve-live-v1-lite",
    ): EvalReport {
        val tasks = (1..13).map { i ->
            TaskResult(
                "task-$i",
                passed = i > failed,
                status = if (i > failed) TaskStatus.PASS else TaskStatus.FAIL,
            )
        }
        return EvalReport(
            genomeId = genomeId,
            suiteId = suiteId,
            suiteVersion = LiveEvaluator.SUITE_VERSION,
            seeds = listOf(1),
            tasks = tasks,
            successRate = EvalReport.successRateOf(tasks),
            meanPassedTasks = tasks.count { it.passed }.toDouble(),
            holdoutScore = 1.0,
            valid = valid,
            invalidReason = if (valid) null else "too many tasks were ERROR/SKIPPED",
        )
    }

    private fun seededHost(reportFor: (String) -> EvalReport?): EvolveHost {
        val host = EvolveHost.at(root)
        val parent = host.store.save(Genome.baseline())
        host.store.activate(parent.id, null, System.currentTimeMillis(), "test")
        reportFor(parent.id)?.let { host.store.saveReport(it) }
        return host
    }

    @Test
    fun `a parent that passed every task blocks the run as saturated`() = runBlocking {
        RealProjectEvaluator.commandRunner = UnavailableTestCommandRunner
        val host = seededHost { id -> liteReport(id, failed = 0) }
        val caller = PingOkCaller()
        assertTrue(host.startRun(caller, File(root, "work"), delayFn = {}))
        awaitSettled(host)
        val state = host.runState.value
        assertNotNull(state)
        assertFalse(state!!.running)
        assertTrue(state.saturated)
        assertTrue(state.error!!.contains("saturated"))
        // Exactly one proposer call: the Doctor's ping. The runner never
        // started, so no proposal was ever attempted.
        assertEquals(1, caller.proposerCalls)
    }

    @Test
    fun `a parent that failed two or more tasks lets the run start`() = runBlocking {
        RealProjectEvaluator.commandRunner = UnavailableTestCommandRunner
        val host = seededHost { id -> liteReport(id, failed = 2) }
        val caller = PingOkCaller()
        assertTrue(host.startRun(caller, File(root, "work"), delayFn = {}))
        awaitSettled(host)
        val state = host.runState.value
        assertNotNull(state)
        assertFalse(state!!.running)
        assertFalse(state.saturated)
        assertTrue(state.summary!!.contains("candidate(s)"))
        assertTrue(caller.proposerCalls > 0)
    }

    @Test
    fun `a parent with one failed task still blocks the run`() = runBlocking {
        RealProjectEvaluator.commandRunner = UnavailableTestCommandRunner
        val host = seededHost { id -> liteReport(id, failed = 1) }
        val caller = PingOkCaller()
        assertTrue(host.startRun(caller, File(root, "work"), delayFn = {}))
        awaitSettled(host)
        val state = host.runState.value
        assertNotNull(state)
        assertFalse(state!!.running)
        assertTrue(state.saturated)
        assertEquals(1, caller.proposerCalls)
    }

    @Test
    fun `a parent with no report lets the run start`() = runBlocking {
        RealProjectEvaluator.commandRunner = UnavailableTestCommandRunner
        val host = seededHost { null }
        val caller = PingOkCaller()
        assertTrue(host.startRun(caller, File(root, "work"), delayFn = {}))
        awaitSettled(host)
        val state = host.runState.value
        assertNotNull(state)
        assertFalse(state!!.running)
        assertFalse(state.saturated)
        assertTrue(caller.proposerCalls > 0)
    }

    @Test
    fun `a parent with an invalid report lets the run start`() = runBlocking {
        RealProjectEvaluator.commandRunner = UnavailableTestCommandRunner
        val host = seededHost { id -> liteReport(id, failed = 0, valid = false) }
        val caller = PingOkCaller()
        assertTrue(host.startRun(caller, File(root, "work"), delayFn = {}))
        awaitSettled(host)
        val state = host.runState.value
        assertNotNull(state)
        assertFalse(state!!.running)
        assertFalse(state.saturated)
        assertTrue(caller.proposerCalls > 0)
    }

    @Test
    fun `a parent report from another suite lets the run start`() = runBlocking {
        RealProjectEvaluator.commandRunner = UnavailableTestCommandRunner
        val host = seededHost { id -> liteReport(id, failed = 0, suiteId = "evolve-live-v1") }
        val caller = PingOkCaller()
        assertTrue(host.startRun(caller, File(root, "work"), delayFn = {}))
        awaitSettled(host)
        val state = host.runState.value
        assertNotNull(state)
        assertFalse(state!!.running)
        assertFalse(state.saturated)
        assertTrue(caller.proposerCalls > 0)
    }

    @Test
    fun `allowSaturated starts the run anyway`() = runBlocking {
        RealProjectEvaluator.commandRunner = UnavailableTestCommandRunner
        val host = seededHost { id -> liteReport(id, failed = 0) }
        val caller = PingOkCaller()
        assertTrue(host.startRun(caller, File(root, "work"), delayFn = {}, allowSaturated = true))
        awaitSettled(host)
        val state = host.runState.value
        assertNotNull(state)
        assertFalse(state!!.running)
        assertFalse(state.saturated)
        assertTrue(caller.proposerCalls > 0)
    }

    @Test
    fun `the saturated refusal writes the last-run pointer`() = runBlocking {
        RealProjectEvaluator.commandRunner = UnavailableTestCommandRunner
        val host = seededHost { id -> liteReport(id, failed = 0) }
        val caller = PingOkCaller()
        assertTrue(host.startRun(caller, File(root, "work"), delayFn = {}))
        awaitSettled(host)
        val pointer = host.store.lastRun()
        assertNotNull(pointer)
        assertNull(pointer!!.candidateId)
        assertTrue(pointer.summary.contains("saturated"))
    }
}

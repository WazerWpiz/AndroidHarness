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
        val caller = FakeModelCaller(
            proposerGenome = { parent ->
                parent.copy(
                    label = "Re-read",
                    hypothesis = "Re-reading cuts false completion.",
                    instructions = listOf("Re-read every changed site."),
                )
            },
            worker = FakeModelCaller.correctWorker(),
        )
        assertTrue(host.startRun(caller, File(root, "work")))
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
    fun `a second start while a run is going is refused, and cancel stops the run`() = runBlocking {
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

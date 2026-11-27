package com.androidharness.app.evolve

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveEvaluatorTest {

    private val root: File = Files.createTempDirectory("live-eval").toFile()

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `a competent worker under the baseline passes every task`() = runBlocking {
        val caller = FakeModelCaller(worker = FakeModelCaller.correctWorker())
        val (report, tokens) = LiveEvaluator.evaluate(Genome.baseline(), caller, File(root, "w1"))
        assertEquals(1.0, report.successRate, 0.0)
        assertEquals(1.0, report.holdoutScore!!, 0.0)
        assertEquals(8, report.tasks.size)
        assertTrue(tokens.first > 0)
        assertEquals("live/fake/worker", report.modelPin)
    }

    @Test
    fun `a worker that produces nothing fails every task`() = runBlocking {
        val caller = FakeModelCaller(worker = { "I will think about it." })
        val (report, _) = LiveEvaluator.evaluate(Genome.baseline(), caller, File(root, "w2"))
        assertEquals(0.0, report.successRate, 0.0)
        assertTrue(report.tasks.all { it.detail.contains("no file blocks") })
    }

    @Test
    fun `a failing critic vetoes a task the checker passed`() = runBlocking {
        val caller = FakeModelCaller(
            worker = FakeModelCaller.correctWorker(),
            criticVerdict = "FAIL: the page is off by one",
        )
        val (report, _) = LiveEvaluator.evaluate(Genome.baseline(), caller, File(root, "w3"))
        assertEquals(0.0, report.successRate, 0.0)
        assertTrue(report.tasks.all { "critic" in it.detail })
    }

    @Test
    fun `a worker that repairs after feedback can still pass`() = runBlocking {
        var calls = 0
        val caller = FakeModelCaller(worker = { user ->
            calls++
            // First attempt per task: garbage. Later attempts: correct.
            if (calls % 2 == 1) "oops" else FakeModelCaller.correctWorker()(user)
        })
        // The fake counts globally, so some tasks fail on attempt 1 and pass on
        // attempt 2; the point is the repair round is actually used.
        val (report, _) = LiveEvaluator.evaluate(Genome.baseline(), caller, File(root, "w4"))
        assertTrue(report.successRate > 0.0)
        assertTrue(caller.workerCalls > 8)
    }

    @Test
    fun `file blocks escaping the workspace are refused`() {
        val blocks = LiveEvaluator.parseFileBlocks("```../secret.txt\nhacked\n```\n```ok.txt\nfine\n```")
        assertFalse("../secret.txt" in blocks)
        assertEquals("fine\n", blocks["ok.txt"])
    }

    @Test
    fun `the evaluator charges real token counts to the caller`() = runBlocking {
        val caller = FakeModelCaller(worker = FakeModelCaller.correctWorker())
        val (_, tokens) = LiveEvaluator.evaluate(Genome.baseline(), caller, File(root, "w5"))
        // 8 tasks x 1 worker call (200 prompt + 100 completion) + 8 critic calls (50 + 10).
        assertEquals(8L * 300L, tokens.first)
        assertEquals(8L * 60L, tokens.second)
    }
}

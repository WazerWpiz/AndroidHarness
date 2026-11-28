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

    /** No-op delays: the 1-second inter-task pause and retry backoff are real in production, never in tests. */
    private val noDelay: suspend (Long) -> Unit = {}

    @Test
    fun `a competent worker under the baseline passes every task`() = runBlocking {
        val caller = FakeModelCaller(worker = FakeModelCaller.correctWorker())
        val (report, tokens) = LiveEvaluator.evaluate(Genome.baseline(), caller, File(root, "w1"), delayFn = noDelay)
        assertEquals(1.0, report.successRate, 0.0)
        assertEquals(1.0, report.holdoutScore!!, 0.0)
        assertEquals(13, report.tasks.size)
        assertTrue(tokens.first > 0)
        assertEquals("live/fake/worker", report.modelPin)
    }

    @Test
    fun `a worker that produces nothing fails every task`() = runBlocking {
        val caller = FakeModelCaller(worker = { "I will think about it." })
        val (report, _) = LiveEvaluator.evaluate(Genome.baseline(), caller, File(root, "w2"), delayFn = noDelay)
        assertEquals(0.0, report.successRate, 0.0)
        assertTrue(report.tasks.all { it.detail.contains("no file blocks") })
    }

    @Test
    fun `a failing critic vetoes only the tasks it judges`() = runBlocking {
        val caller = FakeModelCaller(
            worker = FakeModelCaller.correctWorker(),
            criticVerdict = "{\"verdict\":\"FAIL\",\"reason\":\"the page is off by one\"}",
        )
        val (report, _) = LiveEvaluator.evaluate(Genome.baseline(), caller, File(root, "w3"), delayFn = noDelay)
        // Only verify-before-done and pinned-constraint are critic-judged;
        // the 11 deterministic tasks pass on their checkers alone.
        assertEquals(11.0 / 13.0, report.successRate, 0.001)
        assertTrue(report.tasks.filter { it.taskId == "verify-before-done" || it.taskId == "pinned-constraint" }
            .all { "critic" in it.detail })
        assertTrue(report.tasks.filter { it.taskId != "verify-before-done" && it.taskId != "pinned-constraint" }
            .all { it.passed })
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
        val (report, _) = LiveEvaluator.evaluate(Genome.baseline(), caller, File(root, "w4"), delayFn = noDelay)
        assertTrue(report.successRate > 0.0)
        assertTrue(caller.workerCalls > 13)
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
        val (_, tokens) = LiveEvaluator.evaluate(Genome.baseline(), caller, File(root, "w5"), delayFn = noDelay)
        // 13 tasks x 1 worker call (200 prompt + 100 completion) + 2 critic
        // calls (50 + 10): only the two critic-judged tasks call the critic.
        assertEquals(13L * 300L, tokens.first)
        assertEquals(2L * 60L, tokens.second)
    }

    @Test
    fun `a rate-limited worker is retried and then passes`() = runBlocking {
        var calls = 0
        val inner = FakeModelCaller.correctWorker()
        val caller = object : EvolveModelCaller {
            override suspend fun isConfigured(role: EvolveRole): Boolean = true
            override suspend fun complete(
                role: EvolveRole,
                system: String,
                user: String,
                maxOutputTokens: Int,
            ): ModelCallResult {
                if (role == EvolveRole.WORKER) {
                    calls++
                    if (calls <= 2) throw RuntimeException("rate limit: try again in 2 seconds")
                    return ModelCallResult(inner(user), 200, 100, "fake/worker")
                }
                return ModelCallResult("{\"verdict\":\"PASS\",\"reason\":\"ok\"}", 50, 10, "fake/critic")
            }
        }
        val (report, _) = LiveEvaluator.evaluate(Genome.baseline(), caller, File(root, "w-retry"), delayFn = noDelay)
        assertEquals(1.0, report.successRate, 0.0)
        assertTrue(calls > 13)
        assertTrue(report.tasks.all { it.resolvedStatus == TaskStatus.PASS })
    }

    @Test
    fun `an over-rename fails deterministically without the critic being called`() = runBlocking {
        val caller = FakeModelCaller(
            worker = FakeModelCaller.imperfectWorker(),
            criticVerdict = "not json at all",
        )
        val (report, _) = LiveEvaluator.evaluate(Genome.baseline(), caller, File(root, "w-overrename"), delayFn = noDelay)
        val rename = report.tasks.first { it.taskId == "rename-function" }
        assertEquals(TaskStatus.FAIL, rename.resolvedStatus)
        assertTrue("fetchDataInternal" in rename.detail)
        // The critic was garbage: if it had been consulted for the rename,
        // the task would be ERROR, not FAIL. The critic-judged tasks ARE
        // error, and the critic was called for them (and retried, see below).
        val verify = report.tasks.first { it.taskId == "verify-before-done" }
        assertEquals(TaskStatus.ERROR, verify.resolvedStatus)
        val pinned = report.tasks.first { it.taskId == "pinned-constraint" }
        assertEquals(TaskStatus.ERROR, pinned.resolvedStatus)
        // The ERROR retry re-calls the critic: 2 critic-judged tasks,
        // each retried to the 4-attempt cap, is 8 critic calls. The
        // deterministic tasks never call it at all.
        assertEquals(8, caller.criticCalls)
    }

    @Test
    fun `each task report carries real turns, tokens and a capped trace`() = runBlocking {
        val caller = FakeModelCaller(worker = FakeModelCaller.correctWorker())
        val (report, _) = LiveEvaluator.evaluate(Genome.baseline(), caller, File(root, "w-telemetry"), delayFn = noDelay)
        assertTrue(report.tasks.isNotEmpty())
        report.tasks.forEach { task ->
            assertTrue(task.turns > 0)
            assertTrue(task.tokens > 0)
            assertTrue(task.trace.isNotBlank())
            assertTrue(task.trace.length <= 2000)
        }
        assertTrue(report.meanTurns > 0.0)
    }

    @Test
    fun `the new rename variants and line tasks pass with a correct worker and fail with an over-rename worker`() = runBlocking {
        val correct = FakeModelCaller(worker = FakeModelCaller.correctWorker())
        val (good, _) = LiveEvaluator.evaluate(Genome.baseline(), correct, File(root, "w-new-good"), delayFn = noDelay)
        listOf(
            "rename-function-across-files", "rename-function-decoy",
            "rename-function-definition-use", "sort-lines", "dedupe-lines",
        ).forEach { id ->
            assertEquals(id, TaskStatus.PASS, good.tasks.first { it.taskId == id }.resolvedStatus)
        }
        val over = FakeModelCaller(worker = FakeModelCaller.imperfectWorker())
        val (bad, _) = LiveEvaluator.evaluate(Genome.baseline(), over, File(root, "w-new-bad"), delayFn = noDelay)
        listOf("rename-function", "rename-function-across-files", "sort-lines").forEach { id ->
            assertEquals(id, TaskStatus.FAIL, bad.tasks.first { it.taskId == id }.resolvedStatus)
        }
        // The decoy and definition-use variants are not over-renamed by this worker, so they still pass.
        assertEquals(TaskStatus.PASS, bad.tasks.first { it.taskId == "rename-function-decoy" }.resolvedStatus)
        assertEquals(TaskStatus.PASS, bad.tasks.first { it.taskId == "rename-function-definition-use" }.resolvedStatus)
    }
}

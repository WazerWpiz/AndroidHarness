package com.androidharness.app.evolve

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * P4 — tests for the real-project suite ([RealProjectEvaluator]) and for the
 * merged live+real report produced by [EvolutionRunner.runGeneration].
 *
 * The fake [TestCommandRunner] answers from the scratch-dir content itself:
 * exit 0 only when the fix-target file no longer contains the introduced bug,
 * exactly like a real Gradle test run would behave on a correctly repaired
 * file. No network, no real models, no real Gradle — the command runner is
 * the injected seam, so its fake stands in for the shell toolchain.
 */
class RealProjectEvaluatorTest {

    private val root = Files.createTempDirectory("real-eval-test").toFile()

    @After
    fun tearDown() {
        // Never leak a fake runner into other tests: the evaluator's seam is a
        // shared object var.
        RealProjectEvaluator.commandRunner = UnavailableTestCommandRunner
        root.deleteRecursively()
    }

    // ------------------------------------------------------------------ fake runner

    private class FakeRunner(
        private val ready: Boolean = true,
        var forcedExit: Int? = null,
        var forcedTimedOut: Boolean = false,
        /** When true, run() hangs until cancelled, like a real Gradle run. */
        var hangForever: Boolean = false,
    ) : TestCommandRunner {
        var runCalls = 0
        var lastCommand: String? = null
        var lastCwd: File? = null
        /** Completed on first entry into run(). */
        val entered = CompletableDeferred<Unit>()
        /** True when run() observed coroutine cancellation while hanging. */
        var cancelledDuringRun = false
        /** True when run() "destroyed its child" before reporting a timeout. */
        var destroyCalledOnTimeout = false
        override val toolchainReady: Boolean get() = ready

        override suspend fun run(command: String, cwd: File, timeoutMs: Int): TestCommandResult {
            runCalls++
            lastCommand = command
            lastCwd = cwd
            entered.complete(Unit)
            if (hangForever) {
                try {
                    delay(Long.MAX_VALUE)
                } catch (e: CancellationException) {
                    // The production router destroys the child here (its
                    // finally block); the fake records that it was cancelled
                    // instead of hanging on, and rethrows like the real one.
                    cancelledDuringRun = true
                    throw e
                }
            }
            // A real `gradle test` passes only when the broken file has been
            // repaired: every file under the mini-project must be bug-free.
            val stillBroken = cwd.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".kt") }
                .any { it.readText().contains("BUG INTRODUCED FOR THIS TASK") }
            forcedExit?.let { return TestCommandResult(exitCode = it, timedOut = false, output = "forced exit $it", stderr = "") }
            if (forcedTimedOut) {
                // The production router kills the child process group and
                // destroys the process before reporting a timeout; the fake
                // records the same obligation.
                destroyCalledOnTimeout = true
                return TestCommandResult(exitCode = -1, timedOut = true, output = "timeout output", stderr = "")
            }
            return if (stillBroken) {
                TestCommandResult(
                    exitCode = 1,
                    timedOut = false,
                    output = "FAILURE: Build failed with an exception.\n> Task :test FAILED\n> GenomeTest > testIdLength FAILED\n    java.lang.AssertionError at GenomeTest.kt",
                    stderr = "",
                )
            } else {
                TestCommandResult(exitCode = 0, timedOut = false, output = "BUILD SUCCESSFUL", stderr = "")
            }
        }
    }

    // ------------------------------------------------------------ fake real worker

    /** The one-line repair for each fix-target file (broken text -> fixed text). */
    private val fixes = mapOf(
        "src/main/kotlin/com/androidharness/app/evolve/Genome.kt" to
            ("private const val ID_LENGTH = 15" to "private const val ID_LENGTH = 16"),
        "src/main/kotlin/com/androidharness/app/evolve/BudgetLedger.kt" to
            ("consecutiveStagnant = if (improved) consecutiveStagnant + 1 else 0" to
                "consecutiveStagnant = if (improved) 0 else consecutiveStagnant + 1"),
        "src/main/kotlin/com/androidharness/app/evolve/TraceMiner.kt" to
            ("clean.replace(Regex(\"[0-9]+\"), \"\")" to
                "clean.replace(Regex(\"[0-9]+\"), \"#\")"),
        "src/main/kotlin/com/androidharness/app/tools/SecretRedactor.kt" to
            ("    private fun segments(name: String) = name.split('_', '-', '.')" to
                "    private fun segments(name: String) = name.lowercase().split('_', '-', '.')"),
    )

    /**
     * A competent worker: finds the fix-target file in the prompt, applies its
     * one-line repair, drops the BUG comment lines, and emits the repaired
     * file as a file block. Returns "" when the prompt has no fix target
     * (so callers can fall back to the live-suite worker).
     */
    private fun realFixingWorker(): (String) -> String = { user ->
        val target = fixes.keys.firstOrNull { it in user }
        if (target == null) {
            ""
        } else {
            val marker = "=== $target ===\n"
            val start = user.indexOf(marker)
            if (start < 0) {
                ""
            } else {
                val contentStart = start + marker.length
                val nextFile = user.indexOf("\n=== ", contentStart)
                val feedback = user.indexOf("\nPrevious attempt failed:", contentStart)
                val end = listOf(nextFile, feedback).filter { it >= 0 }.minOrNull() ?: user.length
                var text = user.substring(contentStart, end).trimEnd('\n')
                text = text.lines().filterNot { "BUG INTRODUCED FOR THIS TASK" in it }.joinToString("\n")
                val (broken, fixed) = fixes.getValue(target)
                text = text.replace(broken, fixed)
                "```$target\n$text\n```"
            }
        }
    }

    /** Handles both suites: real fixer first, live correctWorker as fallback. */
    private fun combinedWorker(): (String) -> String = { user ->
        val real = realFixingWorker()(user)
        if (real.isNotEmpty()) real else FakeModelCaller.correctWorker()(user)
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

    // ------------------------------------------------------------------ evaluate

    @Test
    fun `competent worker passes every real task`() = runBlocking {
        val runner = FakeRunner()
        RealProjectEvaluator.commandRunner = runner
        val caller = FakeModelCaller(worker = realFixingWorker())
        val (report, tokens) = RealProjectEvaluator.evaluate(Genome.baseline(), caller, root.resolve("work"))
        assertEquals(4, report.tasks.size)
        assertEquals(1.0, report.successRate, 0.0)
        assertEquals(1.0, report.holdoutScore!!, 0.0)
        assertEquals("evolve-real-v1", report.suiteId)
        assertTrue(report.tasks.all { it.passed })
        assertEquals(1, report.tasks.count { it.holdout })
        assertEquals(listOf("real-secret-redactor"), report.tasks.filter { it.holdout }.map { it.taskId })
        assertEquals(4 * 300L, tokens.first)
        assertEquals(4 * 60L, tokens.second)
        assertEquals("real/fake/worker", report.modelPin)
        assertEquals(4, runner.runCalls)
        assertTrue(runner.lastCommand!!.startsWith("gradle test"))
        assertTrue(runner.lastCommand!!.contains("SecretRedactorTest"))
    }

    @Test
    fun `worker that echoes the broken file fails every real task`() = runBlocking {
        val runner = FakeRunner()
        RealProjectEvaluator.commandRunner = runner
        // Echoes the fix-target file unchanged (bug intact) instead of fixing it.
        val echoWorker: (String) -> String = { user ->
            val target = fixes.keys.firstOrNull { it in user } ?: ""
            if (target.isEmpty()) {
                ""
            } else {
                val marker = "=== $target ===\n"
                val start = user.indexOf(marker)
                val contentStart = start + marker.length
                val end = user.indexOf("\n=== ", contentStart).takeIf { it >= 0 } ?: user.length
                "```$target\n" + user.substring(contentStart, end).trimEnd('\n') + "\n```"
            }
        }
        val caller = FakeModelCaller(worker = echoWorker)
        val (report, _) = RealProjectEvaluator.evaluate(Genome.baseline(), caller, root.resolve("work"))
        assertEquals(0.0, report.successRate, 0.0)
        assertEquals(0.0, report.holdoutScore!!, 0.0)
        assertTrue(report.tasks.all { !it.passed })
        // The checker ran the command on every attempt (3 rounds x 4 tasks).
        assertEquals(12, runner.runCalls)
        assertTrue(report.tasks.all { "gradle test" in it.detail || "FAILED" in it.detail })
    }

    @Test
    fun `toolchain missing fails honestly without calling the worker or any command`() = runBlocking {
        val runner = FakeRunner(ready = false)
        RealProjectEvaluator.commandRunner = runner
        var workerCalls = 0
        val caller = FakeModelCaller(worker = { user ->
            workerCalls++
            realFixingWorker()(user)
        })
        val (report, tokens) = RealProjectEvaluator.evaluate(Genome.baseline(), caller, root.resolve("work"))
        assertEquals(0.0, report.successRate, 0.0)
        assertEquals(0.0, report.holdoutScore!!, 0.0)
        assertEquals(0L, tokens.first)
        assertEquals(0L, tokens.second)
        assertEquals("real/toolchain-missing", report.modelPin)
        assertEquals(0, workerCalls)
        assertEquals(0, runner.runCalls)
        assertTrue(report.tasks.all { RealProjectEvaluator.TOOLCHAIN_MISSING_DETAIL in it.detail })
    }

    @Test
    fun `non-zero exit fails the task and keeps the command output in the detail`() = runBlocking {
        val runner = FakeRunner(forcedExit = 2)
        RealProjectEvaluator.commandRunner = runner
        val caller = FakeModelCaller(worker = realFixingWorker())
        val (report, _) = RealProjectEvaluator.evaluate(Genome.baseline(), caller, root.resolve("work"))
        assertEquals(0.0, report.successRate, 0.0)
        assertTrue(report.tasks.all { "gradle test failed (exit 2)" in it.detail })
        assertTrue(report.tasks.all { "forced exit 2" in it.detail })
    }

    @Test
    fun `a timed-out command is a task failure not a hang`() = runBlocking {
        val runner = FakeRunner(forcedTimedOut = true)
        RealProjectEvaluator.commandRunner = runner
        val caller = FakeModelCaller(worker = realFixingWorker())
        val (report, _) = RealProjectEvaluator.evaluate(Genome.baseline(), caller, root.resolve("work"))
        assertEquals(0.0, report.successRate, 0.0)
        assertTrue(report.tasks.all { "timed out" in it.detail })
        // The child was destroyed before the timeout was reported (the
        // router's contract), so no Gradle process survives the failure.
        assertTrue(runner.destroyCalledOnTimeout)
        assertEquals(12, runner.runCalls)
    }

    @Test
    fun `cancellation during a real test command stops the run and abandons the command`() = runBlocking {
        val runner = FakeRunner(hangForever = true)
        RealProjectEvaluator.commandRunner = runner
        val caller = FakeModelCaller(worker = realFixingWorker())
        val job = launch {
            RealProjectEvaluator.evaluate(Genome.baseline(), caller, root.resolve("work"))
        }
        // Wait until the first task's command is actually running, then stop.
        withTimeout(5_000) { runner.entered.await() }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        // The runner call observed the cancellation instead of hanging on:
        // in production this is where the router's finally block destroys
        // the Gradle child, so no orphaned process keeps eating RAM.
        assertTrue(runner.cancelledDuringRun)
        // The run stopped at the first task: the worker was called once and
        // no further task started after the stop.
        assertEquals(1, caller.workerCalls)
        assertEquals(1, runner.runCalls)
    }

    /**
     * Code-level check for the leak contract: the production runner's
     * process must be destroyed on BOTH paths — when the command times
     * out, and when the coroutine is cancelled (finally) — and captured
     * output must stay capped. The real process API cannot run in a JVM
     * unit test, so this test reads the implementation and asserts the
     * kill is present in both branches; if the sources are not under the
     * test working dir (e.g. an IDE run from elsewhere) it skips instead
     * of failing spuriously.
     */
    @Test
    fun `router destroys the child on timeout and on cancellation and caps output`() {
        val routerSrc = findSource("app/src/main/java/com/androidharness/app/data/env/ShellTierRouter.kt")
        val runnerSrc = findSource("app/src/main/java/com/androidharness/app/evolve/ShellTestCommandRunner.kt")
        val runApp = routerSrc.substringAfter("private suspend fun runApp(")
        val timeoutBranch = runApp.substringAfter("if (timedOut) {").substringBefore("} else {")
        assertTrue("timeout branch must kill the process group", timeoutBranch.contains("killGroup(pid)"))
        assertTrue("timeout branch must destroy the child", timeoutBranch.contains("process.destroyForcibly()"))
        val finallyBlock = runApp.substringAfter("} finally {").substringBefore("return ShellRunResult(")
        assertTrue("finally (cancellation) must kill the process group", finallyBlock.contains("killGroup(pid)"))
        assertTrue("finally (cancellation) must destroy the child", finallyBlock.contains("process.destroyForcibly()"))
        assertTrue("gobble must cap the retained output", routerSrc.contains("if (into.length < max)"))
        assertTrue("P4 runner must pass a bounded output cap", runnerSrc.contains("MAX_OUTPUT_CHARS = 32_000"))
        assertTrue(runnerSrc.contains("router.run(command, cwd, timeoutMs, MAX_OUTPUT_CHARS)"))
    }

    private fun findSource(relative: String): String {
        val file = File(relative).takeIf { it.isFile }
            ?: File(System.getProperty("user.dir"), relative).takeIf { it.isFile }
        Assume.assumeTrue("source not found under test working dir: $relative", file != null)
        return file!!.readText()
    }

    @Test
    fun `gradle build outputs are not shown to the worker on later rounds`() = runBlocking {
        val runner = FakeRunner()
        RealProjectEvaluator.commandRunner = runner
        val prompts = mutableListOf<String>()
        var calls = 0
        val caller = FakeModelCaller(worker = { user ->
            calls++
            prompts += user
            if (calls == 1) {
                // Round 1: echo the broken file unchanged (so the check fails)
                // and drop a fake Gradle output file, as a real `gradle test`
                // run would leave build/ behind.
                val target = "src/main/kotlin/com/androidharness/app/evolve/Genome.kt"
                val marker = "=== $target ===\n"
                val start = user.indexOf(marker)
                val contentStart = start + marker.length
                val end = user.indexOf("\n=== ", contentStart).takeIf { it >= 0 } ?: user.length
                "```$target\n" + user.substring(contentStart, end).trimEnd('\n') + "\n```" +
                    "\n```build/GRADLE_MARKER.txt\nGRADLE_BUILD_OUTPUT_MARKER\n```"
            } else {
                realFixingWorker()(user)
            }
        })
        val (report, _) = RealProjectEvaluator.evaluate(Genome.baseline(), caller, root.resolve("work"))
        assertEquals(1.0, report.successRate, 0.0)
        assertTrue(prompts.size >= 2)
        // Later rounds never see the Gradle build output: it would flood the
        // prompt with compiled classes and test reports and burn tokens.
        assertTrue(prompts.drop(1).none { "GRADLE_BUILD_OUTPUT_MARKER" in it })
    }

    @Test
    fun `failing critic vetoes a real task the tests passed`() = runBlocking {
        val runner = FakeRunner()
        RealProjectEvaluator.commandRunner = runner
        val caller = FakeModelCaller(worker = realFixingWorker(), criticVerdict = "FAIL — no, the repair does not match the specification")
        val (report, _) = RealProjectEvaluator.evaluate(Genome.baseline(), caller, root.resolve("work"))
        assertEquals(0.0, report.successRate, 0.0)
        assertEquals(0.0, report.holdoutScore!!, 0.0)
        assertTrue(report.tasks.all { "critic" in it.detail })
    }

    @Test
    fun `worker that repairs after feedback still passes`() = runBlocking {
        val runner = FakeRunner()
        RealProjectEvaluator.commandRunner = runner
        var calls = 0
        val caller = FakeModelCaller(worker = { user ->
            calls++
            if (calls % 2 == 1) "oops" else realFixingWorker()(user)
        })
        val (report, _) = RealProjectEvaluator.evaluate(Genome.baseline(), caller, root.resolve("work"))
        assertEquals(1.0, report.successRate, 0.0)
        assertEquals(8, calls)
    }

    @Test
    fun `guard rejects a huge changed test file`() = runBlocking {
        val runner = FakeRunner()
        RealProjectEvaluator.commandRunner = runner
        val bigBlob = "x".repeat(5_000_000)
        val caller = FakeModelCaller(worker = { user ->
            val fixed = realFixingWorker()(user)
            if (fixed.isEmpty() || "Genome.kt has ONE deliberately broken line" !in user) {
                fixed
            } else {
                // Fixes the source correctly but also overwrites the test
                // file with a 5 MB blob: the guard must fail the task on
                // the length mismatch without ever reading the blob.
                fixed + "\n```src/test/kotlin/com/androidharness/app/evolve/GenomeTest.kt\n$bigBlob\n```"
            }
        })
        val (report, _) = RealProjectEvaluator.evaluate(Genome.baseline(), caller, root.resolve("work"))
        val genomeTask = report.tasks.first { it.taskId == "real-genome-id" }
        assertTrue(!genomeTask.passed)
        assertTrue("was changed" in genomeTask.detail)
        // The checker never ran for that task: the guard stopped it first.
        assertTrue(runner.runCalls < 12)
    }

    @Test
    fun `proposer view lists the three training tasks only`() {
        val view = RealProjectEvaluator.proposerView()
        assertEquals(3, view.tasks.size)
        assertEquals(
            listOf("real-genome-id", "real-budget-ledger", "real-trace-miner"),
            view.tasks.map { it.id },
        )
        assertEquals(setOf("real-secret-redactor"), RealProjectEvaluator.holdoutIds())
    }

    // ------------------------------------------------------------------ merge

    @Test
    fun `runGeneration scores both suites and the merged report is promotable`() = runBlocking {
        RealProjectEvaluator.commandRunner = FakeRunner()
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
            worker = combinedWorker(),
        )
        val result = runner(caller).runGeneration(parent, File(root, "runs"))
        assertTrue(result.reports.isNotEmpty())
        assertTrue(result.reports.all { it.tasks.size == 12 })
        val report = result.reports.first()
        assertEquals(1.0, report.successRate, 0.0)
        assertEquals(1.0, report.holdoutScore!!, 0.0)
        // Suite identity stays the live suite's id.
        assertEquals(LiveEvaluator.SUITE_ID, report.suiteId)
        // 12 tasks passed: live 8x(300+60) + real 4x(300+60) = 4320 tokens / 12 = 360
        assertEquals(360.0, report.tokensPerSuccess, 0.0)
        assertEquals(3, report.tasks.count { it.holdout })
        assertNotNull(result.promotableId)
        assertTrue(host.archive.entries().any { it.genomeId == result.candidateIds.first() })
    }

    @Test
    fun `runGeneration with a live-only worker scores 8 of 12 and 2 of 3 holdouts`() = runBlocking {
        RealProjectEvaluator.commandRunner = FakeRunner()
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
        val result = runner(caller).runGeneration(parent, File(root, "runs"))
        assertTrue(result.reports.isNotEmpty())
        assertTrue(result.reports.all { it.tasks.size == 12 })
        val report = result.reports.first()
        assertEquals(8.0 / 12.0, report.successRate, 0.0)
        assertEquals(2.0 / 3.0, report.holdoutScore!!, 0.0)
        assertEquals(LiveEvaluator.SUITE_ID, report.suiteId)
        // live 8x(300+60) + real 4 tasks x 3 rounds x 300 (no critic: checker never passed) = 6480 / 8 = 810
        assertEquals(810.0, report.tokensPerSuccess, 0.0)
        // The gate compares against a parent with no report, so it still approves.
        assertNotNull(result.promotableId)
    }
}

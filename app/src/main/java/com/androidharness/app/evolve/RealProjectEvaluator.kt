package com.androidharness.app.evolve

import java.io.File
import kotlinx.coroutines.CancellationException

/**
 * The real-project evaluator (P4): the same DGM/Voyager loop as
 * LiveEvaluator — the worker, running under the candidate's instructions
 * and skills, does the benchmark tasks in a scratch workspace, with up
 * to 3 repair rounds fed by the checker's feedback, then a fixed
 * checker — but the benchmark is the real AndroidHarness project
 * itself instead of toy files.
 *
 * Each task is a minimal JVM Gradle project assembled from verbatim
 * copies of real repo files (see RealProjectTaskData for the snapshots
 * and their source paths): one real source file carries ONE deliberately
 * broken line (marked with a BUG comment) and the worker must fix it.
 * The fixed checker runs the repo's real test file for it with the
 * on-device Gradle (`gradle test --tests <TestClass>`) through the app's
 * shell tiers, and passes only on exit code 0. The test file, the helper
 * sources and the build files are guarded by exact-text comparison
 * first, so the worker cannot pass by editing the test instead of the
 * fix — the fix is the only file it may change.
 *
 * P8: the checker is fully deterministic (Gradle exit 0 plus the
 * guards), so the critic is SKIPPED for real tasks — no model judges
 * them and no critic tokens are spent. Like the live evaluator, every
 * task reports a status (PASS/FAIL/ERROR/SKIPPED); infra failures are
 * ERROR and are retried, with one exception: a Gradle timeout is
 * retried only once, because each retry costs up to 10 minutes of wall
 * time. When the toolchain is not installed the tasks are SKIPPED (the
 * runner normally never calls this suite then — the Doctor selects the
 * lite suite instead — but a direct call still fails honestly).
 *
 * Honest limits: the real suite needs the optional Linux toolchain
 * (openjdk-21 + gradle, installed from Settings); without it every real
 * task is SKIPPED and the run continues on the live suite alone — never
 * a fake pass. On-phone Gradle is slow and battery-heavy:
 * the first run downloads the Kotlin plugin, so each command may take up
 * to 10 minutes; a timeout is a task ERROR, never a hang. The
 * snapshots are frozen at P4 time; if a real file changes, regenerate
 * them with scripts/gen-real-project-tasks.py. The Gradle test JVM
 * compiles and runs worker-written code as the app user, the same trust
 * level as the agent's own shell use; the genome-text safety probes do
 * not cover worker code output.
 */
object RealProjectEvaluator {
    const val SUITE_ID = "evolve-real-v1"
    const val SUITE_VERSION = 1
    const val MAX_ROUNDS = 3

    /** One Gradle test command may take this long; slower is a task failure, never a hang. */
    const val TEST_COMMAND_TIMEOUT_MS = 10 * 60 * 1000

    /** A Gradle timeout is retried once (2 attempts total): each retry costs up to 10 minutes. */
    const val MAX_GRADLE_TIMEOUT_ATTEMPTS = 2

    /** Most characters of one file ever held in memory for a prompt listing. */
    private const val DESCRIBE_MAX_CHARS = 8_000

    const val TOOLCHAIN_MISSING_DETAIL =
        "Android build toolchain not installed — install it from Settings"

    /** Wired by the app at startup (HarnessApp); tests replace it with a fake. */
    @Volatile
    var commandRunner: TestCommandRunner = UnavailableTestCommandRunner

    data class RealTask(
        val id: String,
        val title: String,
        val isHoldout: Boolean,
        val prompt: String,
        val setup: (File) -> Unit,
        val check: suspend (File) -> String?,
    )

    private const val MAIN = "src/main/kotlin/com/androidharness/app/evolve"
    private const val TEST = "src/test/kotlin/com/androidharness/app/evolve"
    private const val TOOLS_MAIN = "src/main/kotlin/com/androidharness/app/tools"
    private const val TOOLS_TEST = "src/test/kotlin/com/androidharness/app/tools"

    private val SETTINGS_GRADLE = """
        rootProject.name = "real-eval"
    """.trimIndent() + "\n"

    private val BUILD_GRADLE = """
        plugins {
            kotlin("jvm") version "2.5.0-RC2"
            kotlin("plugin.serialization") version "2.5.0-RC2"
        }

        repositories {
            mavenCentral()
        }

        dependencies {
            implementation("org.jetbrains.kotlin:kotlin-stdlib:2.5.0-RC2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            testImplementation("junit:junit:4.13.2")
        }
    """.trimIndent() + "\n"

    /** Writes a minimal Gradle project: build files, main sources, test sources. */
    private fun writeProject(dir: File, main: Map<String, String>, test: Map<String, String>) {
        File(dir, "settings.gradle.kts").writeText(SETTINGS_GRADLE)
        File(dir, "build.gradle.kts").writeText(BUILD_GRADLE)
        (main + test).forEach { (path, content) ->
            File(dir, path).apply { parentFile?.mkdirs(); writeText(content) }
        }
    }

    /**
     * The test file, the helper sources and the build files are the oracle:
     * the worker must fix the source, never the test. Any change to them
     * fails the task before Gradle runs.
     */
    private fun guardUnchanged(dir: File, protected: Map<String, String>): String? {
        protected.forEach { (path, want) ->
            val file = File(dir, path)
            if (!file.isFile) return "$path is missing — do not delete the test or build files"
            // Length first, in bytes: a file of a different size is
            // definitely changed, and this never loads a huge
            // worker-written file into memory. Only a same-size file is
            // read, and it is at most the size of the known snapshot.
            if (file.length() != want.toByteArray(Charsets.UTF_8).size.toLong()) {
                return "$path was changed — fix the source file, not the test or the build"
            }
            if (file.readText() != want) return "$path was changed — fix the source file, not the test or the build"
        }
        return null
    }

    /** Runs the real test class with the on-device Gradle. Null means exit 0. */
    private suspend fun runGradleTest(dir: File, testClass: String): String? {
        val command = "gradle test --tests \"$testClass\" --console=plain"
        val result = commandRunner.run(command, dir, TEST_COMMAND_TIMEOUT_MS)
        if (result.timedOut) {
            return "gradle test timed out after ${TEST_COMMAND_TIMEOUT_MS / 60_000} minutes " +
                "(first runs download the Kotlin plugin and are slow; the run was stopped, not hung)"
        }
        if (result.exitCode == 0) return null
        val tail = (result.output + "\n" + result.stderr).takeLast(2_000).trim()
        return "gradle test failed (exit ${result.exitCode}):\n$tail"
    }

    /**
     * One real task: a minimal Gradle project from verbatim repo files,
     * one deliberately broken line in [fixTarget], and the repo's real
     * test class as the checker. Everything except [fixTarget] is
     * guarded; only the fix may change.
     */
    private fun gradleTask(
        id: String,
        title: String,
        isHoldout: Boolean,
        prompt: String,
        main: Map<String, String>,
        test: Map<String, String>,
        fixTarget: String,
        testClass: String,
    ): RealTask {
        val protected = (main + test).filterKeys { it != fixTarget } +
            mapOf("settings.gradle.kts" to SETTINGS_GRADLE, "build.gradle.kts" to BUILD_GRADLE)
        return RealTask(
            id = id,
            title = title,
            isHoldout = isHoldout,
            prompt = prompt,
            setup = { dir -> writeProject(dir, main, test) },
            check = { dir ->
                guardUnchanged(dir, protected) ?: runGradleTest(dir, testClass)
            },
        )
    }

    val tasks: List<RealTask> = listOf(
        gradleTask(
            // Source: app/src/main/java/com/androidharness/app/evolve/Genome.kt
            // Test:   app/src/test/java/com/androidharness/app/evolve/GenomeTest.kt
            "real-genome-id", "Fix the genome id length", false,
            "This is a minimal Gradle project copied from the real AndroidHarness repo. " +
                "The file src/main/kotlin/com/androidharness/app/evolve/Genome.kt has ONE deliberately broken line " +
                "(marked with a BUG comment): the genome id comes out 15 hex characters long, but the real test " +
                "file requires 16. Fix that line so the real tests pass. Output the full corrected Genome.kt in " +
                "one fenced block. Do not change the test file or the build files.",
            mapOf("$MAIN/Genome.kt" to RealProjectTaskData.GENOME_KT_BROKEN),
            mapOf("$TEST/GenomeTest.kt" to RealProjectTaskData.GENOME_TEST_KT),
            "$MAIN/Genome.kt",
            "com.androidharness.app.evolve.GenomeTest",
        ),
        gradleTask(
            // Source: app/src/main/java/com/androidharness/app/evolve/BudgetLedger.kt
            // Test:   app/src/test/java/com/androidharness/app/evolve/BudgetLedgerTest.kt
            "real-budget-ledger", "Fix the stagnation counter", false,
            "This is a minimal Gradle project copied from the real AndroidHarness repo. " +
                "The file src/main/kotlin/com/androidharness/app/evolve/BudgetLedger.kt has ONE deliberately broken line " +
                "(marked with a BUG comment): recording a generation counts an improvement as stagnation instead of " +
                "resetting the counter. Fix that line so the real tests pass. Output the full corrected " +
                "BudgetLedger.kt in one fenced block. Do not change the test file or the build files.",
            mapOf("$MAIN/BudgetLedger.kt" to RealProjectTaskData.BUDGET_LEDGER_KT_BROKEN),
            mapOf("$TEST/BudgetLedgerTest.kt" to RealProjectTaskData.BUDGET_LEDGER_TEST_KT),
            "$MAIN/BudgetLedger.kt",
            "com.androidharness.app.evolve.BudgetLedgerTest",
        ),
        gradleTask(
            // Source: app/src/main/java/com/androidharness/app/evolve/TraceMiner.kt
            //         app/src/main/java/com/androidharness/app/tools/SecretRedactor.kt (helper, do not fix)
            // Test:   app/src/test/java/com/androidharness/app/evolve/TraceMinerTest.kt
            "real-trace-miner", "Fix the number folding", false,
            "This is a minimal Gradle project copied from the real AndroidHarness repo. " +
                "The file src/main/kotlin/com/androidharness/app/evolve/TraceMiner.kt has ONE deliberately broken line " +
                "(marked with a BUG comment): numbers in failure lines are deleted instead of folded to '#', so the " +
                "same failure on different lines no longer counts as one shape. Fix that line so the real tests pass. " +
                "Output the full corrected TraceMiner.kt in one fenced block. Do not change the test file, the " +
                "SecretRedactor.kt helper, or the build files.",
            mapOf(
                "$MAIN/TraceMiner.kt" to RealProjectTaskData.TRACE_MINER_KT_BROKEN,
                "$TOOLS_MAIN/SecretRedactor.kt" to RealProjectTaskData.SECRET_REDACTOR_KT,
            ),
            mapOf("$TEST/TraceMinerTest.kt" to RealProjectTaskData.TRACE_MINER_TEST_KT),
            "$MAIN/TraceMiner.kt",
            "com.androidharness.app.evolve.TraceMinerTest",
        ),
        gradleTask(
            // Source: app/src/main/java/com/androidharness/app/tools/SecretRedactor.kt
            // Test:   app/src/test/java/com/androidharness/app/tools/SecretRedactorTest.kt
            "real-secret-redactor", "Fix the secret redaction", true,
            "This is a minimal Gradle project copied from the real AndroidHarness repo. " +
                "The file src/main/kotlin/com/androidharness/app/tools/SecretRedactor.kt has ONE deliberately broken line " +
                "(marked with a BUG comment): segments() no longer lowercases credential names, so an upper-case env " +
                "name like ANTHROPIC_API_KEY slips past the credential-word check and its secret leaks. Fix that line " +
                "so the real tests pass. Output the full corrected SecretRedactor.kt in one fenced block. Do not change " +
                "the test file or the build files.",
            mapOf("$TOOLS_MAIN/SecretRedactor.kt" to RealProjectTaskData.SECRET_REDACTOR_KT_BROKEN),
            mapOf("$TOOLS_TEST/SecretRedactorTest.kt" to RealProjectTaskData.SECRET_REDACTOR_TEST_KT),
            "$TOOLS_MAIN/SecretRedactor.kt",
            "com.androidharness.app.tools.SecretRedactorTest",
        ),
    )

    /** The only view a proposer or diagnosis ever receives. Holdout is absent. */
    fun proposerView(): TrainingView =
        TrainingView(tasks.filter { !it.isHoldout }.map { EvalTaskSummary(it.id, it.title, false) })

    fun holdoutIds(): Set<String> = tasks.filter { it.isHoldout }.map { it.id }.toSet()

    /** Same worker protocol as LiveEvaluator: one fenced block per changed file. */
    private fun workerSystem(genome: Genome): String = buildString {
        append("You are a coding agent working in a scratch workspace. ")
        append("Follow these instructions from your genome:\n")
        if (genome.instructions.isEmpty()) append("(no instructions)\n")
        genome.instructions.forEach { append("- ").append(it).append('\n') }
        val enabled = genome.skillEnable.intersect(genome.skills.keys)
        if (enabled.isNotEmpty()) {
            append("\nEnabled skills (follow them):\n")
            enabled.forEach { name ->
                append("### skill: ").append(name).append('\n')
                append(genome.skills.getValue(name)).append('\n')
            }
        }
        append("\nYou change files ONLY by outputting each changed file in its own fenced block:\n")
        append("```<relative path>\n<full new contents>\n```\n")
        append("Output only those blocks, no prose. Never touch files outside the workspace. ")
        append("Before you finish, re-read the task and check your output line by line.")
    }

    /** All files under the task dir, by relative path, so the worker sees the whole Gradle project. */
    private fun describeFiles(dir: File): String = buildString {
        // Gradle's own output is never shown: after the first `gradle test`
        // the dir holds build/ and .gradle/ (compiled classes, test reports,
        // caches). Listing them would flood later rounds with binary text
        // and burn tokens; the worker only ever needs the project files.
        dir.walkTopDown().onEnter { it.name != "build" && it.name != ".gradle" }
            .filter { it.isFile }.sortedBy { it.relativeTo(dir).path }.forEach { file ->
            append("=== ").append(file.relativeTo(dir).path).append(" ===\n")
            append(readCapped(file, DESCRIBE_MAX_CHARS)).append('\n')
        }
    }

    /**
     * Reads at most [maxChars] characters of a file, without ever loading a
     * huge file into memory: a file whose byte size fits the cap is read
     * whole (every real file is under it), a larger one is read head-only.
     * The cap keeps the worker prompt — and the critic's before/after
     * copies of it — bounded no matter what the worker writes to disk.
     */
    private fun readCapped(file: File, maxChars: Int): String {
        if (file.length() <= maxChars) return file.readText()
        val buf = CharArray(maxChars)
        file.bufferedReader().use { reader ->
            val n = reader.read(buf)
            return if (n <= 0) "" else String(buf, 0, n)
        }
    }

    /**
     * One attempt at one real task: worker rounds, then the fixed
     * checker (guarded files + the real Gradle test). The checker is
     * fully deterministic, so there is no critic call (P8). A worker
     * call that fails is ERROR (infra, retryable); a Gradle timeout is
     * ERROR too, but it is retried only once — each retry costs up to
     * 10 minutes of wall time. A Gradle exit != 0 is FAIL: the build
     * ran and the test failed, which is the genome's work, not infra.
     */
    private data class TaskOutcome(
        val result: TaskResult,
        val workerTokens: Long,
        val workerModel: String,
    )

    private suspend fun runTaskOnce(
        genome: Genome,
        task: RealTask,
        dir: File,
        caller: EvolveModelCaller,
    ): TaskOutcome {
        var reason: String? = null
        var rounds = 0
        var feedback = ""
        var workerTokens = 0L
        var workerModel = ""
        var lastWorkerText: String? = null
        while (rounds < MAX_ROUNDS) {
            rounds++
            val user = buildString {
                append(task.prompt).append("\n\nCurrent workspace files:\n").append(describeFiles(dir))
                if (feedback.isNotBlank()) append("\nPrevious attempt failed: ").append(feedback).append("\nFix it.")
            }
            val call = try {
                caller.complete(EvolveRole.WORKER, workerSystem(genome), user)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return TaskOutcome(
                    TaskResult(
                        taskId = task.id, passed = false,
                        detail = "worker call failed: ${e.message}".take(200),
                        turns = rounds, tokens = workerTokens, holdout = task.isHoldout,
                        status = TaskStatus.ERROR,
                        trace = "rounds=$rounds; worker call failed: ${e.message}".take(LiveEvaluator.MAX_TRACE_CHARS),
                    ),
                    workerTokens, workerModel,
                )
            }
            workerTokens += call.promptTokens + call.completionTokens
            if (call.model.isNotBlank()) workerModel = call.model
            lastWorkerText = call.text
            val blocks = LiveEvaluator.parseFileBlocks(call.text)
            if (blocks.isEmpty()) {
                reason = "worker produced no file blocks"
                feedback = "You produced no file blocks. Output each changed file as ```<relative path>\n<full new contents>\n``` and nothing else."
                continue
            }
            blocks.forEach { (path, contents) ->
                File(dir, path).apply { parentFile?.mkdirs(); writeText(contents) }
            }
            val checkResult = try {
                task.check(dir)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return TaskOutcome(
                    TaskResult(
                        taskId = task.id, passed = false,
                        detail = "checker failed: ${e.message}".take(200),
                        turns = rounds, tokens = workerTokens, holdout = task.isHoldout,
                        status = TaskStatus.ERROR,
                        trace = "rounds=$rounds; checker threw: ${e.message}".take(LiveEvaluator.MAX_TRACE_CHARS),
                    ),
                    workerTokens, workerModel,
                )
            }
            reason = checkResult
            if (reason == null) break
            // A Gradle timeout is infra, not the worker's work: re-asking
            // the worker to repair it burns a repair round and up to 10
            // minutes of wall time for nothing. The attempt loop in
            // evaluate() retries the command once.
            if ("timed out" in reason) break
            feedback = reason
        }
        val detail = reason ?: ""
        val passed = reason == null
        // A Gradle timeout is infra (ERROR); any other checker failure
        // is the genome's work (FAIL). The timeout text is the one
        // runGradleTest writes, so match on it.
        val status = when {
            passed -> TaskStatus.PASS
            "timed out" in detail -> TaskStatus.ERROR
            else -> TaskStatus.FAIL
        }
        val trace = buildString {
            append("rounds=").append(rounds)
            lastWorkerText?.let { append("; worker output: ").append(it.take(1_200)) }
            append("; result: ").append(detail)
        }.take(LiveEvaluator.MAX_TRACE_CHARS)
        return TaskOutcome(
            TaskResult(
                taskId = task.id, passed = passed, detail = detail.take(200),
                turns = rounds, tokens = workerTokens, holdout = task.isHoldout,
                status = status, trace = trace,
            ),
            workerTokens, workerModel,
        )
    }

    /**
     * Runs the whole suite for one genome. Returns the report plus the
     * token counts, so the runner can charge them to the ledger. When the
     * build toolchain is not installed, every task is SKIPPED and the
     * worker is never called: no fake scores, and the run continues on
     * the live suite alone. [delayFn] is the real delay in production
     * and a no-op in tests.
     */
    suspend fun evaluate(
        genome: Genome,
        caller: EvolveModelCaller,
        workDir: File,
        seed: Int = 1,
        delayFn: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    ): Pair<EvalReport, Pair<Long, Long>> {
        val started = System.currentTimeMillis()
        val envPin = "jvm/${System.getProperty("os.name")}/${System.getProperty("os.arch")}"
        if (!commandRunner.toolchainReady) {
            val results = tasks.map { task ->
                TaskResult(
                    taskId = task.id, passed = false, detail = TOOLCHAIN_MISSING_DETAIL,
                    holdout = task.isHoldout, status = TaskStatus.SKIPPED,
                    trace = "not attempted: $TOOLCHAIN_MISSING_DETAIL",
                )
            }
            val (valid, invalidReason) = EvalReport.validityOf(results)
            val report = EvalReport(
                genomeId = genome.id,
                suiteId = SUITE_ID,
                suiteVersion = SUITE_VERSION,
                seeds = listOf(seed),
                tasks = results,
                successRate = 0.0,
                tokensPerSuccess = 0.0,
                toolValidity = 1.0,
                meanTurns = 0.0,
                safetyViolations = 0,
                wallMs = System.currentTimeMillis() - started,
                modelPin = "real/toolchain-missing",
                envPin = envPin,
                holdoutScore = 0.0,
                meanPassedTasks = 0.0,
                valid = valid,
                invalidReason = invalidReason,
            )
            return report to (0L to 0L)
        }
        var workerTokens = 0L
        var modelPin = "real"
        val results = mutableListOf<TaskResult>()
        tasks.forEachIndexed { index, task ->
            if (index > 0) delayFn(LiveEvaluator.INTER_TASK_DELAY_MS)
            val dir = File(workDir, task.id).apply { mkdirs() }
            task.setup(dir)
            var attempt = 0
            var outcome: TaskOutcome
            while (true) {
                attempt++
                outcome = runTaskOnce(genome, task, dir, caller)
                if (outcome.result.resolvedStatus != TaskStatus.ERROR) break
                // A Gradle timeout is retried only once: each retry costs
                // up to 10 minutes of wall time. Other infra errors get
                // the full 3 retries.
                val maxAttempts = if ("timed out" in outcome.result.detail) MAX_GRADLE_TIMEOUT_ATTEMPTS
                else LiveEvaluator.MAX_ATTEMPTS
                if (attempt >= maxAttempts) break
                delayFn(LiveEvaluator.retryWaitMs(outcome.result.detail, attempt))
            }
            workerTokens += outcome.workerTokens
            if (outcome.workerModel.isNotBlank()) modelPin = "real/${outcome.workerModel}"
            results += outcome.result
        }
        val (valid, invalidReason) = EvalReport.validityOf(results)
        val passed = results.count { it.resolvedStatus == TaskStatus.PASS }
        val report = EvalReport(
            genomeId = genome.id,
            suiteId = SUITE_ID,
            suiteVersion = SUITE_VERSION,
            seeds = listOf(seed),
            tasks = results,
            successRate = EvalReport.successRateOf(results),
            tokensPerSuccess = if (passed == 0) 0.0 else workerTokens.toDouble() / passed,
            toolValidity = 1.0,
            meanTurns = results.map { it.turns }.average().takeIf { it.isFinite() } ?: 0.0,
            safetyViolations = 0,
            wallMs = System.currentTimeMillis() - started,
            modelPin = modelPin,
            envPin = envPin,
            holdoutScore = EvalReport.holdoutScoreOf(results),
            meanPassedTasks = passed.toDouble(),
            valid = valid,
            invalidReason = invalidReason,
        )
        return report to (workerTokens to 0L)
    }
}

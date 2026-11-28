package com.androidharness.app.evolve

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** What the proposer is allowed to see: training task ids and titles only. */
data class EvalTaskSummary(val id: String, val title: String, val isHoldout: Boolean)
data class TrainingView(val tasks: List<EvalTaskSummary>)

/**
 * Which verdict source decides a task (P8). DETERMINISTIC means the
 * fixed checker completely decides the task — the critic is skipped,
 * so no model judges it and no critic tokens are spent. CRITIC means
 * the checker is only a partial test of the task (for example it
 * verifies one pinned line but not the lines around it), so a separate
 * critic model must also pass, in strict JSON.
 */
enum class VerdictSource { DETERMINISTIC, CRITIC }

/**
 * The live evaluator: DGM's evaluation phase (jennyzzt/dgm) plus Voyager's
 * iterative prompting (MineDojo/Voyager). The worker model — a real model,
 * running under the candidate genome's instructions and enabled skills —
 * does each benchmark task in a scratch workspace, with up to 3 repair
 * rounds fed by the checker's feedback. A fixed checker then verifies the
 * produced artifact, and — only for tasks whose checker is not the whole
 * test — a separate critic model independently verifies the result in
 * strict JSON (Voyager's self-verification). A task counts only when its
 * verdict source passes.
 *
 * This is deliberately NOT the old static suite: there the harness
 * computed the right answer itself. Here the model does the work and the
 * harness only verifies it, so the score measures what the candidate's
 * instructions actually do to a real model's behaviour. The benchmark is
 * small (13 local tasks) because a phone has no Docker and cannot run
 * SWE-bench — same loop mechanics as DGM, phone-scale benchmark, and
 * the doc says so instead of pretending otherwise.
 *
 * P8: every task reports a status (PASS/FAIL/ERROR/SKIPPED). Only
 * decided tasks count in the success rate. Infra failures (rate limit,
 * quota, timeout, a model call that failed, an unparseable critic
 * verdict) are ERROR and are retried up to 3 times with exponential
 * backoff — a "try again in N seconds" answer is respected, capped at
 * 60 seconds per wait. Tasks run sequentially with a 1-second pause
 * between them to reduce rate-limit hits on free models. Per-task turns
 * and tokens are recorded for real, and a short worker trace (capped at
 * 2000 chars) is kept so the proposer can see WHY a task failed.
 */
object LiveEvaluator {
    const val SUITE_ID = "evolve-live-v1"
    const val SUITE_VERSION = 2
    const val MAX_ROUNDS = 3

    /** One task attempt plus up to this many retries when the outcome is ERROR (infra). */
    const val MAX_ATTEMPTS = 4
    /** Pause between tasks, in milliseconds. */
    const val INTER_TASK_DELAY_MS = 1_000L
    /** The longest a single retry wait may be, in milliseconds. */
    const val MAX_RETRY_WAIT_MS = 60_000L
    /** The longest a worker trace kept on a task may be, in characters. */
    const val MAX_TRACE_CHARS = 2_000

    data class LiveTask(
        val id: String,
        val title: String,
        val isHoldout: Boolean,
        val prompt: String,
        val setup: (File) -> Unit,
        val check: (File) -> String?,
        /** Which verdict source decides the task; see [VerdictSource]. */
        val verdict: VerdictSource = VerdictSource.DETERMINISTIC,
    )

    private fun lines(file: File): List<String> = file.readLines()

    /**
     * A word-boundary test for a name: "fetchData" must not match inside
     * "fetchDataInternal". The old checker used a plain substring test,
     * so a CORRECT rename failed it — that is why rename-function failed
     * in every genome.
     */
    private fun containsName(text: String, name: String): Boolean =
        Regex("\\b${Regex.escape(name)}\\b").containsMatchIn(text)

    val tasks: List<LiveTask> = listOf(
        LiveTask(
            "file-read-paging", "Read a file with paging", false,
            "The workspace has long.txt with numbered lines. Write page.txt containing exactly lines 101-150 of long.txt, one per line, in order. Nothing else.",
            { dir -> File(dir, "long.txt").writeText((1..205).joinToString("\n") { "line $it" }) },
            { dir ->
                val page = File(dir, "page.txt")
                if (!page.isFile) "page.txt was not created"
                else {
                    val got = lines(page)
                    val want = (101..150).map { "line $it" }
                    if (got != want) "page.txt has ${got.size} lines, expected lines 101-150 exactly" else null
                }
            },
        ),
        LiveTask(
            "file-list-grep", "List files and grep with paging", false,
            "The workspace has a.txt, b.txt and c.md. Write hits.txt listing every file that contains the word \"needle\", one \"<file>: <matching line>\" entry per matching line. Do not list files without a match.",
            { dir ->
                File(dir, "a.txt").writeText("needle here\n")
                File(dir, "b.txt").writeText("nothing\n")
                File(dir, "c.md").writeText("another needle\n")
            },
            { dir ->
                val hits = File(dir, "hits.txt")
                if (!hits.isFile) "hits.txt was not created"
                else {
                    val got = lines(hits).map { it.trim() }.filter { it.isNotBlank() }.toSet()
                    val want = setOf("a.txt: needle here", "c.md: another needle")
                    if (got != want) "hits.txt lists $got, expected $want" else null
                }
            },
        ),
        LiveTask(
            "multi-edit", "Apply several edits to one file", false,
            "The workspace has edit.txt with three lines: alpha, beta, gamma. Change alpha to ALPHA and gamma to GAMMA. Keep beta exactly as it is. Write the full new edit.txt.",
            { dir -> File(dir, "edit.txt").writeText("alpha\nbeta\ngamma\n") },
            { dir ->
                val text = File(dir, "edit.txt").takeIf { it.isFile }?.readText()?.trimEnd()
                if (text != "ALPHA\nbeta\nGAMMA") "edit.txt is not exactly \"ALPHA\\nbeta\\nGAMMA\"" else null
            },
        ),
        LiveTask(
            "rename-function", "Rename a function across a file", false,
            "The workspace has main.kt. Rename the function fetchData to loadData everywhere it appears. Change nothing else. Write the full new main.kt.",
            { dir ->
                File(dir, "main.kt").writeText(
                    "fun fetchData(): String {\n    return fetchDataInternal()\n}\n\nfun fetchDataInternal(): String = \"data\"\n",
                )
            },
            { dir ->
                val text = File(dir, "main.kt").takeIf { it.isFile }?.readText()
                when {
                    text == null -> "main.kt is missing"
                    containsName(text, "fetchData") -> "old name fetchData is still present"
                    "fun loadData()" !in text -> "renamed function loadData is missing"
                    "fun fetchDataInternal()" !in text -> "fetchDataInternal was changed — only fetchData should be renamed"
                    "return fetchDataInternal()" !in text -> "the call to fetchDataInternal was changed"
                    else -> null
                }
            },
        ),
        LiveTask(
            "rename-function-across-files", "Rename a function across two files", false,
            "The workspace has main.kt (defines fetchData and fetchDataInternal) and util.kt (calls fetchData). Rename the function fetchData to loadData everywhere it appears, in both files. Change nothing else (fetchDataInternal stays). Write the full new main.kt and util.kt.",
            { dir ->
                File(dir, "main.kt").writeText(
                    "fun fetchData(): String {\n    return fetchDataInternal()\n}\n\nfun fetchDataInternal(): String = \"data\"\n",
                )
                File(dir, "util.kt").writeText("fun useIt(): String = fetchData()\n")
            },
            { dir ->
                val main = File(dir, "main.kt").takeIf { it.isFile }?.readText()
                val util = File(dir, "util.kt").takeIf { it.isFile }?.readText()
                when {
                    main == null -> "main.kt is missing"
                    util == null -> "util.kt is missing"
                    containsName(main, "fetchData") || containsName(util, "fetchData") -> "old name fetchData is still present"
                    "fun loadData()" !in main -> "renamed function loadData is missing from main.kt"
                    "fun fetchDataInternal()" !in main -> "fetchDataInternal was changed — only fetchData should be renamed"
                    "loadData()" !in util -> "the call site in util.kt was not renamed to loadData"
                    else -> null
                }
            },
        ),
        LiveTask(
            "rename-function-decoy", "Rename a function with a similar decoy name", false,
            "The workspace has main.kt. Rename the function getData to fetchData everywhere it appears. Change nothing else: getDataInternal and getDataFromCache must stay exactly as they are. Write the full new main.kt.",
            { dir ->
                File(dir, "main.kt").writeText(
                    "fun getData(): String {\n    return getDataInternal() + getDataFromCache()\n}\n\nfun getDataInternal(): String = \"data\"\n\nfun getDataFromCache(): String = \"cached\"\n",
                )
            },
            { dir ->
                val text = File(dir, "main.kt").takeIf { it.isFile }?.readText()
                when {
                    text == null -> "main.kt is missing"
                    containsName(text, "getData") -> "old name getData is still present"
                    "fun fetchData()" !in text -> "renamed function fetchData is missing"
                    "fun getDataInternal()" !in text -> "getDataInternal was changed — only getData should be renamed"
                    "fun getDataFromCache()" !in text -> "getDataFromCache was changed — only getData should be renamed"
                    "getDataInternal()" !in text -> "the call to getDataInternal was changed"
                    "getDataFromCache()" !in text -> "the call to getDataFromCache was changed"
                    else -> null
                }
            },
        ),
        LiveTask(
            "rename-function-definition-use", "Rename a function defined in one file and used in another", false,
            "The workspace has service.kt (defines computeTotal) and app.kt (calls computeTotal). Rename the function computeTotal to sumTotal everywhere it appears, in both files. Change nothing else (computeTotalInternal stays). Write the full new service.kt and app.kt.",
            { dir ->
                File(dir, "service.kt").writeText(
                    "fun computeTotal(a: Int, b: Int): Int {\n    return computeTotalInternal(a, b)\n}\n\nfun computeTotalInternal(a: Int, b: Int): Int = a + b\n",
                )
                File(dir, "app.kt").writeText("fun main() {\n    println(computeTotal(2, 3))\n}\n")
            },
            { dir ->
                val service = File(dir, "service.kt").takeIf { it.isFile }?.readText()
                val app = File(dir, "app.kt").takeIf { it.isFile }?.readText()
                when {
                    service == null -> "service.kt is missing"
                    app == null -> "app.kt is missing"
                    containsName(service, "computeTotal") || containsName(app, "computeTotal") -> "old name computeTotal is still present"
                    "fun sumTotal(" !in service -> "renamed function sumTotal is missing from service.kt"
                    "fun computeTotalInternal(" !in service -> "computeTotalInternal was changed — only computeTotal should be renamed"
                    "sumTotal(2, 3)" !in app -> "the call site in app.kt was not renamed to sumTotal"
                    else -> null
                }
            },
        ),
        LiveTask(
            "sort-lines", "Sort lines alphabetically", false,
            "The workspace has words.txt with one word per line. Write sorted.txt with the same lines sorted alphabetically, one per line, duplicates kept.",
            { dir -> File(dir, "words.txt").writeText("banana\napple\ncherry\napple\n") },
            { dir ->
                val sorted = File(dir, "sorted.txt")
                if (!sorted.isFile) "sorted.txt was not created"
                else {
                    val got = lines(sorted)
                    val want = listOf("apple", "apple", "banana", "cherry")
                    if (got != want) "sorted.txt is $got, expected $want" else null
                }
            },
        ),
        LiveTask(
            "dedupe-lines", "Remove duplicate lines, first-seen order", false,
            "The workspace has dup.txt with one word per line. Write unique.txt with each distinct line once, in first-seen order.",
            { dir -> File(dir, "dup.txt").writeText("alpha\nbeta\nalpha\ngamma\nbeta\n") },
            { dir ->
                val unique = File(dir, "unique.txt")
                if (!unique.isFile) "unique.txt was not created"
                else {
                    val got = lines(unique)
                    val want = listOf("alpha", "beta", "gamma")
                    if (got != want) "unique.txt is $got, expected $want" else null
                }
            },
        ),
        LiveTask(
            "apply-patch-hunk", "Apply a correct patch hunk", false,
            "The workspace has patch.txt with three lines: one, two, three. Change the second line to TWO. Change nothing else. Write the full new patch.txt.",
            { dir -> File(dir, "patch.txt").writeText("one\ntwo\nthree\n") },
            { dir ->
                val text = File(dir, "patch.txt").takeIf { it.isFile }?.readText()?.trimEnd()
                if (text != "one\nTWO\nthree") "patch.txt is not exactly \"one\\nTWO\\nthree\"" else null
            },
        ),
        LiveTask(
            "git-status-diff", "Parse git status and diff text", true,
            "The workspace has status.txt, the output of git status. Write summary.txt with exactly two lines: the number of modified files and the number of untracked files, as \"modified: N\" and \"untracked: N\".",
            { dir -> File(dir, "status.txt").writeText(" M src/Main.kt\n?? new.txt\n") },
            { dir ->
                val got = File(dir, "summary.txt").takeIf { it.isFile }?.readLines()?.map { it.trim() }?.toSet()
                if (got != setOf("modified: 1", "untracked: 1")) "summary.txt is $got, expected modified: 1 and untracked: 1" else null
            },
        ),
        LiveTask(
            "verify-before-done", "Produce verification evidence before claiming done", false,
            "The workspace has long.txt with numbered lines. Write page.txt with exactly lines 101-150, then write claim.txt with two lines: \"status: done\" and an \"evidence:\" line that states a TRUE, checkable fact about what you produced (for example its exact line count). A false evidence line fails.",
            { dir -> File(dir, "long.txt").writeText((1..205).joinToString("\n") { "line $it" }) },
            { dir ->
                val page = File(dir, "page.txt")
                val claim = File(dir, "claim.txt")
                when {
                    !page.isFile -> "page.txt was not created"
                    lines(page) != (101..150).map { "line $it" } -> "page.txt does not hold lines 101-150"
                    !claim.isFile -> "claim.txt was not created"
                    else -> {
                        val text = claim.readText()
                        if ("status: done" !in text) "claim.txt has no \"status: done\" line"
                        else if ("evidence:" !in text) "claim.txt has no evidence line"
                        else {
                            val evidence = text.substringAfter("evidence:")
                            // The evidence must state the true line count of the page.
                            if ("50" !in evidence) "evidence line does not state the true line count (50)" else null
                        }
                    }
                }
            },
            verdict = VerdictSource.CRITIC,
        ),
        LiveTask(
            "pinned-constraint", "Keep a pinned constraint through compaction", true,
            "The workspace has notes.txt with 50 lines, one of which is a pinned constraint: \"[PINNED] keep the 30 minute cap\". Write summary.txt with the 5 first lines, the 5 last lines, and the pinned constraint line, copied verbatim. If the pinned line is missing or altered, you fail.",
            { dir ->
                val noteLines = (1..50).map { "filler line $it" }.toMutableList()
                noteLines[24] = "[PINNED] keep the 30 minute cap"
                File(dir, "notes.txt").writeText(noteLines.joinToString("\n"))
            },
            { dir ->
                val text = File(dir, "summary.txt").takeIf { it.isFile }?.readText()
                if (text == null) "summary.txt was not created"
                else if ("[PINNED] keep the 30 minute cap" !in text) "the pinned constraint is missing or altered" else null
            },
            verdict = VerdictSource.CRITIC,
        ),
    )

    /** The only view a proposer or diagnosis ever receives. Holdout is absent. */
    fun proposerView(): TrainingView =
        TrainingView(tasks.filter { !it.isHoldout }.map { EvalTaskSummary(it.id, it.title, false) })

    fun holdoutIds(): Set<String> = tasks.filter { it.isHoldout }.map { it.id }.toSet()

    /** Parses ```path\n<contents>\n``` blocks. Paths escaping the task dir are refused. */
    fun parseFileBlocks(text: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        Regex("```([^\\n`]+)\\n([\\s\\S]*?)```").findAll(text).forEach { match ->
            val path = match.groupValues[1].trim()
            if (path.isBlank() || path.startsWith("/") || ".." in path.split('/')) return@forEach
            out[path] = match.groupValues[2]
        }
        return out
    }

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

    private fun describeFiles(dir: File): String = buildString {
        dir.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }.forEach { file ->
            append("=== ").append(file.name).append(" ===\n")
            append(file.readText().take(8_000)).append('\n')
        }
    }

    /**
     * The wait before retrying an ERROR: a "try again in N seconds"
     * answer is respected (capped at [MAX_RETRY_WAIT_MS]), otherwise
     * exponential backoff — 1s, 2s, 4s — by attempt number (1-based).
     */
    fun retryWaitMs(detail: String, attempt: Int): Long {
        val seconds = Regex("(?i)try again in (\\d+) seconds?").find(detail)?.groupValues?.get(1)?.toLongOrNull()
        if (seconds != null) return (seconds * 1_000).coerceAtMost(MAX_RETRY_WAIT_MS)
        return (1_000L shl (attempt - 1).coerceAtMost(10)).coerceAtMost(MAX_RETRY_WAIT_MS)
    }

    /**
     * One attempt at one task: worker rounds, the fixed checker, and —
     * only for tasks whose checker is not the whole test — the critic in
     * strict JSON. Returns the task's result with its real turns, tokens
     * and trace, plus the worker/critic token split and the models that
     * answered. A model call that fails is ERROR (infra, retryable);
     * a checker failure or a critic FAIL verdict is FAIL (the genome's
     * work, not retryable); a checker that throws is ERROR (the harness's
     * fault, not the genome's).
     */
    private data class TaskOutcome(
        val result: TaskResult,
        val workerTokens: Long,
        val criticTokens: Long,
        val workerModel: String,
        val criticPin: String,
    )

    private suspend fun runTaskOnce(
        genome: Genome,
        task: LiveTask,
        dir: File,
        caller: EvolveModelCaller,
    ): TaskOutcome {
        val before = describeFiles(dir)
        var reason: String? = null
        var status = TaskStatus.FAIL
        var rounds = 0
        var feedback = ""
        var workerTokens = 0L
        var criticTokens = 0L
        var workerModel = ""
        var criticPin = ""
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
                        trace = "rounds=$rounds; worker call failed: ${e.message}".take(MAX_TRACE_CHARS),
                    ),
                    workerTokens, 0L, workerModel, "",
                )
            }
            workerTokens += call.promptTokens + call.completionTokens
            if (call.model.isNotBlank()) workerModel = call.model
            lastWorkerText = call.text
            val blocks = parseFileBlocks(call.text)
            if (blocks.isEmpty()) {
                reason = "worker produced no file blocks"
                feedback = "You produced no file blocks. Output each changed file as ```<relative path>\n<full new contents>\n``` and nothing else."
                continue
            }
            blocks.forEach { (path, contents) ->
                File(dir, path).apply { parentFile?.mkdirs(); writeText(contents) }
            }
            reason = runCatching { task.check(dir) }.getOrElse { e ->
                return TaskOutcome(
                    TaskResult(
                        taskId = task.id, passed = false,
                        detail = "checker failed: ${e.message}".take(200),
                        turns = rounds, tokens = workerTokens, holdout = task.isHoldout,
                        status = TaskStatus.ERROR,
                        trace = buildString {
                            append("rounds=").append(rounds)
                            append("; checker threw: ").append(e.message)
                        }.take(MAX_TRACE_CHARS),
                    ),
                    workerTokens, 0L, workerModel, "",
                )
            }
            if (reason == null) break
            feedback = reason
        }
        val detail: String
        var passed = reason == null
        if (passed && task.verdict == VerdictSource.CRITIC) {
            val outcome = CriticJudge.check(caller, task.title, task.prompt, before, describeFiles(dir))
            criticTokens += outcome.tokens
            if (outcome.model.isNotBlank()) criticPin = outcome.model
            when (outcome.status) {
                TaskStatus.PASS -> { detail = outcome.detail; passed = true; status = TaskStatus.PASS }
                TaskStatus.FAIL -> { detail = outcome.detail; passed = false; status = TaskStatus.FAIL }
                else -> { detail = outcome.detail; passed = false; status = TaskStatus.ERROR }
            }
        } else {
            detail = reason ?: ""
            status = if (passed) TaskStatus.PASS else TaskStatus.FAIL
        }
        val trace = buildString {
            append("rounds=").append(rounds)
            lastWorkerText?.let { append("; worker output: ").append(it.take(1_200)) }
            append("; result: ").append(detail)
        }.take(MAX_TRACE_CHARS)
        return TaskOutcome(
            TaskResult(
                taskId = task.id, passed = passed, detail = detail.take(200),
                turns = rounds, tokens = workerTokens + criticTokens, holdout = task.isHoldout,
                status = status, trace = trace,
            ),
            workerTokens, criticTokens, workerModel, criticPin,
        )
    }

    /**
     * Runs the whole suite for one genome. Returns the report plus the
     * token counts, so the runner can charge them to the ledger. Tasks
     * run sequentially with a 1-second pause between them; an ERROR
     * outcome is retried up to 3 times. [delayFn] is the real delay in
     * production and a no-op in tests, so retries never slow a test.
     */
    suspend fun evaluate(
        genome: Genome,
        caller: EvolveModelCaller,
        workDir: File,
        seed: Int = 1,
        delayFn: suspend (Long) -> Unit = { delay(it) },
    ): Pair<EvalReport, Pair<Long, Long>> {
        val started = System.currentTimeMillis()
        var workerTokens = 0L
        var criticTokens = 0L
        var modelPin = "live"
        var criticPin = ""
        val results = mutableListOf<TaskResult>()
        tasks.forEachIndexed { index, task ->
            if (index > 0) delayFn(INTER_TASK_DELAY_MS)
            val dir = File(workDir, task.id).apply { mkdirs() }
            task.setup(dir)
            var attempt = 0
            var outcome: TaskOutcome
            while (true) {
                attempt++
                outcome = runTaskOnce(genome, task, dir, caller)
                if (outcome.result.resolvedStatus != TaskStatus.ERROR || attempt >= MAX_ATTEMPTS) break
                delayFn(retryWaitMs(outcome.result.detail, attempt))
            }
            workerTokens += outcome.workerTokens
            criticTokens += outcome.criticTokens
            if (outcome.workerModel.isNotBlank()) modelPin = "live/${outcome.workerModel}"
            if (outcome.criticPin.isNotBlank()) criticPin = outcome.criticPin
            results += outcome.result
        }
        val totalTokens = workerTokens + criticTokens
        val (valid, invalidReason) = EvalReport.validityOf(results)
        val passed = results.count { it.resolvedStatus == TaskStatus.PASS }
        val report = EvalReport(
            genomeId = genome.id,
            suiteId = SUITE_ID,
            suiteVersion = SUITE_VERSION,
            seeds = listOf(seed),
            tasks = results,
            successRate = EvalReport.successRateOf(results),
            tokensPerSuccess = if (passed == 0) 0.0 else totalTokens.toDouble() / passed,
            toolValidity = 1.0,
            meanTurns = results.map { it.turns }.average().takeIf { it.isFinite() } ?: 0.0,
            safetyViolations = 0,
            wallMs = System.currentTimeMillis() - started,
            modelPin = modelPin,
            envPin = "jvm/${System.getProperty("os.name")}/${System.getProperty("os.arch")}",
            holdoutScore = EvalReport.holdoutScoreOf(results),
            meanPassedTasks = passed.toDouble(),
            criticPin = criticPin,
            valid = valid,
            invalidReason = invalidReason,
        )
        return report to (workerTokens to criticTokens)
    }
}

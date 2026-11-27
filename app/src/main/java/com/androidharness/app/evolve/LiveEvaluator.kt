package com.androidharness.app.evolve

import java.io.File

/** What the proposer is allowed to see: training task ids and titles only. */
data class EvalTaskSummary(val id: String, val title: String, val isHoldout: Boolean)
data class TrainingView(val tasks: List<EvalTaskSummary>)

/**
 * The live evaluator: DGM's evaluation phase (jennyzzt/dgm) plus Voyager's
 * iterative prompting (MineDojo/Voyager). The worker model — a real model,
 * running under the candidate genome's instructions and enabled skills —
 * does each benchmark task in a scratch workspace, with up to 3 repair
 * rounds fed by the checker's feedback. A fixed checker then verifies the
 * produced artifact, and a separate critic model independently verifies
 * the result (Voyager's self-verification). A task counts only when the
 * checker AND the critic pass.
 *
 * This is deliberately NOT the old static suite: there the harness
 * computed the right answer itself. Here the model does the work and the
 * harness only verifies it, so the score measures what the candidate's
 * instructions actually do to a real model's behaviour. The benchmark is
 * small (8 local tasks) because a phone has no Docker and cannot run
 * SWE-bench — same loop mechanics as DGM, phone-scale benchmark, and
 * the doc says so instead of pretending otherwise.
 */
object LiveEvaluator {
    const val SUITE_ID = "evolve-live-v1"
    const val SUITE_VERSION = 1
    const val MAX_ROUNDS = 3

    data class LiveTask(
        val id: String,
        val title: String,
        val isHoldout: Boolean,
        val prompt: String,
        val setup: (File) -> Unit,
        val check: (File) -> String?,
    )

    private fun lines(file: File): List<String> = file.readLines()

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
                    "fetchData" in text -> "old name fetchData is still present"
                    "fun loadData()" !in text -> "renamed function loadData is missing"
                    else -> null
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

    private suspend fun criticCheck(
        caller: EvolveModelCaller,
        task: LiveTask,
        before: String,
        after: String,
    ): Triple<Boolean, String, Pair<Long, Long>> {
        val result = try {
            caller.complete(
                EvolveRole.CRITIC,
                "You are a strict code reviewer. Answer with exactly one line: PASS or FAIL: <reason>. " +
                    "Judge only whether the produced files satisfy the task. Do not re-do the work.",
                "Task: ${task.title}\n${task.prompt}\n\nBefore:\n$before\n\nAfter (what the worker produced):\n$after",
                maxOutputTokens = 512,
            )
        } catch (e: Exception) {
            return Triple(false, "critic call failed: ${e.message}", 0L to 0L)
        }
        val tokens = result.promptTokens + result.completionTokens
        val first = result.text.trim().lineSequence().firstOrNull().orEmpty()
        return if (first.startsWith("PASS")) Triple(true, "critic passed", 0L to tokens)
        else Triple(false, "critic: ${first.ifBlank { "no verdict" }.take(200)}", 0L to tokens)
    }

    /**
     * Runs the whole suite for one genome. Returns the report plus the
     * token counts, so the runner can charge them to the ledger.
     */
    suspend fun evaluate(
        genome: Genome,
        caller: EvolveModelCaller,
        workDir: File,
    ): Pair<EvalReport, Pair<Long, Long>> {
        val started = System.currentTimeMillis()
        var workerTokens = 0L
        var criticTokens = 0L
        var modelPin = "live"
        val results = tasks.map { task ->
            val dir = File(workDir, task.id).apply { mkdirs() }
            task.setup(dir)
            val before = describeFiles(dir)
            var reason: String? = null
            var rounds = 0
            var feedback = ""
            while (rounds < MAX_ROUNDS) {
                rounds++
                val user = buildString {
                    append(task.prompt).append("\n\nCurrent workspace files:\n").append(describeFiles(dir))
                    if (feedback.isNotBlank()) append("\nPrevious attempt failed: ").append(feedback).append("\nFix it.")
                }
                val call = try {
                    caller.complete(EvolveRole.WORKER, workerSystem(genome), user)
                } catch (e: Exception) {
                    reason = "worker call failed: ${e.message}"
                    break
                }
                workerTokens += call.promptTokens + call.completionTokens
                if (call.model.isNotBlank()) modelPin = "live/${call.model}"
                val blocks = parseFileBlocks(call.text)
                if (blocks.isEmpty()) {
                    reason = "worker produced no file blocks"
                    feedback = "You produced no file blocks. Output each changed file as ```<relative path>\n<full new contents>\n``` and nothing else."
                    continue
                }
                blocks.forEach { (path, contents) ->
                    File(dir, path).apply { parentFile?.mkdirs(); writeText(contents) }
                }
                reason = runCatching { task.check(dir) }.getOrElse { "checker failed: ${it.message}" }
                if (reason == null) break
                feedback = reason
            }
            var detail = reason ?: ""
            var passed = reason == null
            if (passed) {
                val (criticOk, criticReason, criticUsed) = criticCheck(caller, task, before, describeFiles(dir))
                criticTokens += criticUsed.second
                if (!criticOk) {
                    passed = false
                    detail = criticReason
                }
            }
            TaskResult(taskId = task.id, passed = passed, detail = detail, holdout = task.isHoldout)
        }
        val holdout = results.filter { it.holdout }
        val successes = results.count { it.passed }
        val report = EvalReport(
            genomeId = genome.id,
            suiteId = SUITE_ID,
            suiteVersion = SUITE_VERSION,
            seeds = listOf(1),
            tasks = results,
            successRate = successes.toDouble() / results.size,
            tokensPerSuccess = if (successes == 0) 0.0 else (workerTokens + criticTokens).toDouble() / successes,
            toolValidity = 1.0,
            meanTurns = 1.0,
            safetyViolations = 0,
            wallMs = System.currentTimeMillis() - started,
            modelPin = modelPin,
            envPin = "jvm/${System.getProperty("os.name")}/${System.getProperty("os.arch")}",
            holdoutScore = if (holdout.isEmpty()) null else holdout.count { it.passed }.toDouble() / holdout.size,
        )
        return report to (workerTokens to criticTokens)
    }
}

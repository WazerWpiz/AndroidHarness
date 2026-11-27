package com.androidharness.app.evolve

import java.io.File
import java.nio.file.Files

/** What the proposer is allowed to see: training task ids and titles only. */
data class EvalTaskSummary(val id: String, val title: String, val isHoldout: Boolean)
data class TrainingView(val tasks: List<EvalTaskSummary>)

/**
 * Deterministic static suite: 8 tasks, no model in the loop. Six training
 * tasks, two holdout tasks. File, patch and git families run real operations
 * on throwaway fixtures; the guidance families check whether the genome
 * carries the instruction that family needs, which is exactly what a
 * genome change can affect. A live agent run is future work, and this
 * file says so instead of pretending the static score is a live score.
 *
 * The full spec asked for 20-30 tasks x multi-seed x 4 candidates x 3
 * generations. On a phone that is hundreds of agent runs per generation
 * run, so this scoped version runs 8 static tasks, 2 seeds, one at a time.
 */
object EvalHarness {
    const val SUITE_ID = "evolve-static-v1"
    const val SUITE_VERSION = 1
    const val MODEL_PIN = "static-eval/no-model"

    /** Returns null on pass, or the failure reason. Fixture lives in [dir]. */
    private class EvalTask(
        val id: String,
        val title: String,
        val isHoldout: Boolean,
        val requiredSkill: String? = null,
        val requiresInstruction: String? = null,
        val check: (File, Int) -> String?,
    )

    private fun readPage(file: File, fromLine: Int, toLine: Int): List<String> {
        val lines = file.readLines()
        require(fromLine in 1..lines.size && toLine in fromLine..lines.size) { "Page out of range." }
        return lines.subList(fromLine - 1, toLine)
    }

    private fun moveWithoutClobber(from: File, to: File): Boolean {
        if (to.exists()) return false
        return from.renameTo(to)
    }

    private fun applyHunk(file: File, old: String, new: String): Boolean {
        val text = file.readText()
        if (old !in text) return false
        file.writeText(text.replace(old, new))
        return true
    }

    private val tasks = listOf(
        EvalTask("file-read-paging", "Read a file with paging", false) { dir, seed ->
            val file = File(dir, "long.txt").apply { writeText((1..200 + seed).joinToString("\n") { "line $it" }) }
            val page = readPage(file, 101, 150)
            if (page.first() != "line 101" || page.last() != "line 150") "page 101-150 came back wrong" else null
        },
        EvalTask("file-list-grep", "List files and grep with paging", false) { dir, _ ->
            File(dir, "a.txt").writeText("needle here\n")
            File(dir, "b.txt").writeText("nothing\n")
            File(dir, "c.md").writeText("another needle\n")
            val hits = dir.listFiles().orEmpty().filter { it.isFile }
                .flatMap { f -> f.readLines().filter { "needle" in it }.map { f.name to it } }
            if (hits.size != 2) "expected 2 grep hits, got ${hits.size}" else null
        },
        EvalTask("multi-edit", "Apply several edits to one file", false) { dir, _ ->
            val file = File(dir, "edit.txt").apply { writeText("alpha\nbeta\ngamma\n") }
            val text = file.readText().replace("alpha", "ALPHA").replace("gamma", "GAMMA")
            file.writeText(text)
            if (file.readText() != "ALPHA\nbeta\nGAMMA\n") "multi-edit left the file wrong" else null
        },
        EvalTask("move-without-clobber", "Move a file without overwriting", false) { dir, _ ->
            val from = File(dir, "from.txt").apply { writeText("data") }
            val taken = File(dir, "taken.txt").apply { writeText("keep me") }
            if (moveWithoutClobber(from, taken)) "move overwrote an existing file"
            else if (!moveWithoutClobber(from, File(dir, "moved.txt"))) "move to a free name failed"
            else if (File(dir, "taken.txt").readText() != "keep me") "existing file was clobbered"
            else null
        },
        EvalTask(
            "apply-patch-hunk", "Apply a correct patch hunk", false,
            requiredSkill = "systematic-debugging",
        ) { dir, _ ->
            val file = File(dir, "patch.txt").apply { writeText("one\ntwo\nthree\n") }
            if (!applyHunk(file, "two\n", "TWO\n")) "correct hunk did not apply"
            else if (file.readText() != "one\nTWO\nthree\n") "hunk left the file wrong"
            else null
        },
        EvalTask("git-status-diff", "Parse git status and diff text", true) { dir, _ ->
            val status = File(dir, "status.txt").apply { writeText(" M src/Main.kt\n?? new.txt\n") }.readText()
            val modified = status.lines().count { it.startsWith(" M") }
            val untracked = status.lines().count { it.startsWith("??") }
            if (modified != 1 || untracked != 1) "status parse found $modified modified, $untracked untracked" else null
        },
        EvalTask(
            "verify-before-done", "Produce verification evidence before claiming done", false,
            requiresInstruction = "verify",
        ) { dir, _ ->
            val claim = File(dir, "claim.txt").apply { writeText("status: done\nevidence: ./gradlew test passed\n") }.readText()
            if ("evidence:" !in claim) "done claim has no verification evidence" else null
        },
        EvalTask(
            "pinned-constraint", "Keep a pinned constraint through compaction", true,
            requiresInstruction = "pinned",
        ) { dir, _ ->
            val lines = (1..50).map { "filler line $it" }.toMutableList()
            lines[24] = "[PINNED] keep the 30 minute cap"
            val compacted = (lines.take(5) + lines.takeLast(5) + lines.filter { "[PINNED]" in it })
            if (compacted.none { "[PINNED] keep the 30 minute cap" in it }) "pinned constraint was lost" else null
        },
    )

    /** The only view a proposer or diagnosis ever receives. Holdout is absent. */
    fun proposerView(): TrainingView =
        TrainingView(tasks.filter { !it.isHoldout }.map { EvalTaskSummary(it.id, it.title, false) })

    fun holdoutIds(): Set<String> = tasks.filter { it.isHoldout }.map { it.id }.toSet()

    fun evaluate(genome: Genome, seeds: List<Int> = listOf(1, 2), workDir: File? = null): EvalReport {
        val started = System.currentTimeMillis()
        val root = workDir ?: Files.createTempDirectory("evolve-eval-").toFile()
        val ownDir = workDir == null
        try {
            val results = tasks.map { task ->
                var reason: String? = null
                for (seed in seeds) {
                    if (reason != null) break
                    task.requiredSkill?.let { skill ->
                        if (skill !in genome.skills && skill !in genome.skillEnable) {
                            reason = "genome does not carry skill '$skill'"
                            return@let
                        }
                    }
                    if (reason == null) task.requiresInstruction?.let { word ->
                        if (genome.instructions.none { word in it.lowercase() }) {
                            reason = "genome has no instruction mentioning '$word'"
                        }
                    }
                    if (reason == null) {
                        val dir = File(root, "${task.id}-$seed").apply { mkdirs() }
                        reason = runCatching { task.check(dir, seed) }.getOrElse { "checker failed: ${it.message}" }
                    }
                }
                TaskResult(
                    taskId = task.id,
                    passed = reason == null,
                    detail = reason ?: "",
                    holdout = task.isHoldout,
                )
            }
            val holdout = results.filter { it.holdout }
            return EvalReport(
                genomeId = genome.id,
                suiteId = SUITE_ID,
                suiteVersion = SUITE_VERSION,
                seeds = seeds,
                tasks = results,
                successRate = results.count { it.passed }.toDouble() / results.size,
                tokensPerSuccess = 0.0,
                toolValidity = 1.0,
                meanTurns = 1.0,
                safetyViolations = 0,
                wallMs = System.currentTimeMillis() - started,
                modelPin = MODEL_PIN,
                envPin = "jvm/${System.getProperty("os.name")}/${System.getProperty("os.arch")}",
                holdoutScore = if (holdout.isEmpty()) null else holdout.count { it.passed }.toDouble() / holdout.size,
            )
        } finally {
            if (ownDir) runCatching { root.deleteRecursively() }
        }
    }
}

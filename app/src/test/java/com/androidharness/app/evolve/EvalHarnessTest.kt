package com.androidharness.app.evolve

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvalHarnessTest {

    private val root: File = Files.createTempDirectory("eval").toFile()

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `the shipped baseline passes the pure file tasks and fails the guidance tasks`() {
        val report = EvalHarness.evaluate(Genome.baseline(), workDir = File(root, "w1"))
        // 8 tasks: 4 pure file tasks + git-status-diff pass; the skill, verify and
        // pinned tasks fail because the baseline carries none of them.
        assertEquals(5.0 / 8.0, report.successRate, 0.0)
        assertEquals(1.0 / 2.0, report.holdoutScore!!, 0.0)
        assertEquals(8, report.tasks.size)
        val failed = report.tasks.filter { !it.passed }.map { it.taskId }.toSet()
        assertEquals(setOf("apply-patch-hunk", "verify-before-done", "pinned-constraint"), failed)
    }

    @Test
    fun `a genome carrying the guidance passes every task`() {
        val genome = Genome.baseline().copy(
            instructions = listOf(
                "Verify before claiming done: run the checks and quote their evidence.",
                "Keep pinned constraints in every summary: copy each pinned line verbatim.",
            ),
            skills = mapOf("systematic-debugging" to "---\nname: systematic-debugging\n---\n"),
            skillEnable = setOf("systematic-debugging"),
        ).identified()
        val report = EvalHarness.evaluate(genome, workDir = File(root, "w2"))
        assertEquals(1.0, report.successRate, 0.0)
        assertEquals(1.0, report.holdoutScore!!, 0.0)
    }

    @Test
    fun `the proposer never sees holdout tasks`() {
        val view = EvalHarness.proposerView()
        assertEquals(6, view.tasks.size)
        assertTrue(view.tasks.none { it.isHoldout })
        assertTrue(EvalHarness.holdoutIds().containsAll(listOf("git-status-diff", "pinned-constraint")))
    }

    @Test
    fun `evaluation is deterministic across seeds`() {
        val first = EvalHarness.evaluate(Genome.baseline(), workDir = File(root, "w3"))
        val second = EvalHarness.evaluate(Genome.baseline(), workDir = File(root, "w4"))
        assertEquals(first.successRate, second.successRate, 0.0)
        assertEquals(first.tasks.map { it.passed }, second.tasks.map { it.passed })
    }
}

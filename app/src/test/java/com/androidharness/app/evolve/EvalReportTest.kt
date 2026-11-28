package com.androidharness.app.evolve

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P8 — task statuses, the decided-only success rate, the INVALID rule,
 * and the report round-trip (old JSON without a status still reads).
 */
class EvalReportTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun task(id: String, status: TaskStatus, holdout: Boolean = false) =
        TaskResult(id, status == TaskStatus.PASS, holdout = holdout, status = status)

    @Test
    fun `success rate counts only decided tasks`() {
        val tasks = listOf(
            task("a", TaskStatus.PASS),
            task("b", TaskStatus.FAIL),
            task("c", TaskStatus.ERROR),
            task("d", TaskStatus.SKIPPED),
        )
        assertEquals(0.5, EvalReport.successRateOf(tasks), 0.0)
        assertEquals(2, EvalReport.decidedTasks(tasks).size)
    }

    @Test
    fun `holdout score counts only decided holdout tasks`() {
        val tasks = listOf(
            task("a", TaskStatus.PASS),
            task("h1", TaskStatus.PASS, holdout = true),
            task("h2", TaskStatus.ERROR, holdout = true),
        )
        assertEquals(1.0, EvalReport.holdoutScoreOf(tasks)!!, 0.0)
        assertNull(EvalReport.holdoutScoreOf(listOf(task("h", TaskStatus.ERROR, holdout = true))))
    }

    @Test
    fun `exactly twenty percent undecided is still valid, more is invalid`() {
        val atLimit = (1..4).map { task("p$it", TaskStatus.PASS) } + task("e", TaskStatus.ERROR)
        val (validAtLimit, _) = EvalReport.validityOf(atLimit)
        assertTrue(validAtLimit)
        val overLimit = (1..3).map { task("p$it", TaskStatus.PASS) } +
            listOf(task("e1", TaskStatus.ERROR), task("e2", TaskStatus.SKIPPED))
        val (validOver, reasonOver) = EvalReport.validityOf(overLimit)
        assertFalse(validOver)
        assertNotNull(reasonOver)
        assertTrue(reasonOver!!.contains("40%"))
        val (validEmpty, _) = EvalReport.validityOf(emptyList())
        assertTrue(validEmpty)
    }

    @Test
    fun `old JSON without a status derives it from passed`() {
        val passed = json.decodeFromString(
            TaskResult.serializer(),
            "{\"taskId\":\"x\",\"passed\":true}",
        )
        assertEquals(TaskStatus.PASS, passed.resolvedStatus)
        val failed = json.decodeFromString(
            TaskResult.serializer(),
            "{\"taskId\":\"y\",\"passed\":false}",
        )
        assertEquals(TaskStatus.FAIL, failed.resolvedStatus)
        assertEquals("", failed.trace)
    }

    @Test
    fun `a report with a doctor result, seeds and tasks round-trips through the store`() {
        val root: File = Files.createTempDirectory("report-roundtrip").toFile()
        try {
            val store = GenomeStore(root)
            val doctor = DoctorResult(
                checkedAt = 1_234_567L,
                suiteId = "evolve-live-v1-lite",
                checks = listOf(
                    DoctorCheck("linux-userspace", "Linux userspace", DoctorStatus.FAIL, "bash is not on PATH"),
                    DoctorCheck("ping-worker", "worker model ping", DoctorStatus.PASS, "the model answered", "ok"),
                ),
                toolchainReady = false,
                blocksRun = false,
            )
            val report = EvalReport(
                genomeId = "0123456789abcdef",
                suiteId = "evolve-live-v1-lite",
                suiteVersion = 2,
                seeds = listOf(1, 2, 3),
                tasks = listOf(
                    task("rename-function", TaskStatus.PASS).copy(trace = "rounds=1; worker output: ```main.kt"),
                    task("sort-lines", TaskStatus.ERROR).copy(detail = "worker call failed: quota exhausted"),
                ),
                successRate = 0.5,
                holdoutScore = 1.0,
                meanPassedTasks = 1.5,
                criticPin = "fake/critic",
                modelPin = "live/fake/worker",
                valid = true,
                doctor = doctor,
            )
            store.saveReport(report)
            val loaded = store.report("0123456789abcdef")
            assertNotNull(loaded)
            assertEquals(report.suiteId, loaded!!.suiteId)
            assertEquals(report.suiteVersion, loaded.suiteVersion)
            assertEquals(listOf(1, 2, 3), loaded.seeds)
            assertEquals(2, loaded.tasks.size)
            assertEquals(TaskStatus.PASS, loaded.tasks[0].resolvedStatus)
            assertEquals("rounds=1; worker output: ```main.kt", loaded.tasks[0].trace)
            assertEquals(TaskStatus.ERROR, loaded.tasks[1].resolvedStatus)
            assertEquals(1.5, loaded.meanPassedTasks, 0.0)
            assertEquals("fake/critic", loaded.criticPin)
            assertEquals(1_234_567L, loaded.doctor!!.checkedAt)
            assertEquals(2, loaded.doctor.checks.size)
            assertEquals(DoctorStatus.FAIL, loaded.doctor.checks[0].status)
            assertEquals("evolve-live-v1-lite", loaded.doctor.suiteId)
            assertFalse(loaded.doctor.toolchainReady)
        } finally {
            root.deleteRecursively()
        }
    }
}

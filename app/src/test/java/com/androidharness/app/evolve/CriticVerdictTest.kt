package com.androidharness.app.evolve

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P8 — the critic returns strict JSON only. Free text, garbage, a
 * verdict that is neither PASS nor FAIL, and a PASS whose reason
 * describes a failure are all ERROR, never a pass and never a fail.
 */
class CriticVerdictTest {

    @Test
    fun `a valid JSON PASS parses`() {
        val outcome = CriticJudge.parse("{\"verdict\":\"PASS\",\"reason\":\"the page is exact\"}")
        assertEquals(TaskStatus.PASS, outcome.status)
        assertEquals("critic passed", outcome.detail)
    }

    @Test
    fun `a valid JSON FAIL parses`() {
        val outcome = CriticJudge.parse("{\"verdict\":\"FAIL\",\"reason\":\"the pinned line is missing\"}")
        assertEquals(TaskStatus.FAIL, outcome.status)
        assertTrue("pinned" in outcome.detail)
    }

    @Test
    fun `a fenced JSON verdict parses`() {
        val outcome = CriticJudge.parse("```json\n{\"verdict\":\"PASS\",\"reason\":\"ok\"}\n```")
        assertEquals(TaskStatus.PASS, outcome.status)
    }

    @Test
    fun `free text is an error, never a pass`() {
        assertEquals(TaskStatus.ERROR, CriticJudge.parse("PASS: looks good").status)
        assertEquals(TaskStatus.ERROR, CriticJudge.parse("The task is done.").status)
        assertEquals(TaskStatus.ERROR, CriticJudge.parse("FAIL: it broke").status)
    }

    @Test
    fun `garbage and invalid verdicts are an error`() {
        assertEquals(TaskStatus.ERROR, CriticJudge.parse("not json at all").status)
        assertEquals(TaskStatus.ERROR, CriticJudge.parse("").status)
        assertEquals(TaskStatus.ERROR, CriticJudge.parse("{\"verdict\":\"MAYBE\",\"reason\":\"?\"}").status)
        assertEquals(TaskStatus.ERROR, CriticJudge.parse("{\"reason\":\"no verdict\"}").status)
    }

    @Test
    fun `a PASS whose reason describes a failure is an error`() {
        val outcome = CriticJudge.parse(
            "{\"verdict\":\"PASS\",\"reason\":\"the pinned line is missing but close enough\"}",
        )
        assertEquals(TaskStatus.ERROR, outcome.status)
        assertTrue("contradicts" in outcome.detail)
    }

    @Test
    fun `a FAIL whose reason describes a failure is a fail`() {
        val outcome = CriticJudge.parse("{\"verdict\":\"FAIL\",\"reason\":\"the pinned line is missing\"}")
        assertEquals(TaskStatus.FAIL, outcome.status)
    }
}

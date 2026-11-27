package com.androidharness.app.evolve

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceMinerTest {

    @Test
    fun `counts repeated failure lines and suggests a hint`() {
        val diagnosis = TraceMiner.mine(
            listOf(
                "apply_patch failed: hunk is stale\napply_patch failed: hunk is stale\n",
                "timeout after 30s waiting for the tool\n",
                "all good here\n",
            ),
        )
        assertEquals(2, diagnosis.topPatterns.first { "stale" in it.pattern }.count)
        assertTrue(diagnosis.suggestedHints.any { "hunk" in it })
    }

    @Test
    fun `redacts secrets before they leave the miner`() {
        val diagnosis = TraceMiner.mine(listOf("error: api_key = ghp_abcdefghij1234567890abcdefghij12 failed\n"))
        assertTrue(diagnosis.topPatterns.none { "ghp_" in it.pattern })
        assertTrue(diagnosis.topPatterns.none { "abcdefghij" in it.pattern })
    }

    @Test
    fun `empty history gives an empty diagnosis`() {
        val diagnosis = TraceMiner.mine(emptyList())
        assertTrue(diagnosis.topPatterns.isEmpty())
        assertTrue(diagnosis.suggestedHints.isEmpty())
    }

    @Test
    fun `numbers are folded so the same failure counts once per shape`() {
        val diagnosis = TraceMiner.mine(listOf("error on line 42 failed\nerror on line 99 failed\n"))
        assertEquals(1, diagnosis.topPatterns.size)
        assertEquals(2, diagnosis.topPatterns.single().count)
        assertTrue("#" in diagnosis.topPatterns.single().pattern)
    }
}

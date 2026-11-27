package com.androidharness.app.evolve

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenomeTest {

    private fun genome() = Genome(
        createdAt = 1_000L,
        label = "Add grep paging",
        hypothesis = "Paging a grep result keeps long runs inside the context window.",
        instructions = listOf("Prefer grep with a page limit."),
        skills = mapOf("long-grep" to "---\nname: long-grep\n---\nUse paging."),
        skillEnable = setOf("long-grep"),
        hooks = listOf(GenomeHook("tool.after", "grep", "echo done")),
        agents = mapOf("reviewer" to GenomeAgent("reviewer", "Review the diff.")),
        proposerNotes = "Try paging first.",
    ).identified()

    @Test
    fun `hashing is deterministic and the id is 16 hex characters`() {
        val first = genome()
        val second = genome()
        assertEquals(first.id, second.id)
        assertTrue(first.id.matches(Regex("[a-f0-9]{16}")))
    }

    @Test
    fun `map and set order does not change the hash`() {
        val a = Genome(skills = linkedMapOf("b" to "2", "a" to "1"), skillEnable = linkedSetOf("b", "a"))
        val b = Genome(skills = linkedMapOf("a" to "1", "b" to "2"), skillEnable = linkedSetOf("a", "b"))
        assertEquals(a.computeId(), b.computeId())
    }

    @Test
    fun `line endings and surrounding whitespace do not change the hash`() {
        val a = Genome(instructions = listOf("  be careful  "), skills = mapOf("s" to "body\n"))
        val b = Genome(instructions = listOf("be careful"), skills = mapOf("s" to "body"))
        assertEquals(a.computeId(), b.computeId())
    }

    @Test
    fun `any content edit changes the hash`() {
        val base = genome()
        assertNotEquals(base.id, base.copy(instructions = base.instructions + "One more.").computeId())
        assertNotEquals(base.id, base.copy(skills = base.skills + ("other" to "text")).computeId())
        assertNotEquals(base.id, base.copy(hooks = emptyList()).computeId())
        assertNotEquals(base.id, base.copy(agents = emptyMap()).computeId())
        assertNotEquals(base.id, base.copy(proposerNotes = "No.").computeId())
    }

    @Test
    fun `lineage and presentation stay out of the payload`() {
        val base = genome()
        val relabelled = base.copy(id = "", parent = "ffffffffffffffff", createdAt = 9_999L, label = "Other", hypothesis = "Other reason")
        assertEquals(base.id, relabelled.computeId())
        assertTrue(base.payload().id.isEmpty())
    }

    @Test
    fun `diff names the fields that changed`() {
        val base = genome()
        val changed = base.copy(instructions = base.instructions + "Also check the diff.")
        val diff = base.diff(changed)
        assertTrue(diff.contains("instructions"))
        assertTrue(diff.contains("Also check the diff."))
        assertEquals("No content changes.", base.diff(base))
    }
}

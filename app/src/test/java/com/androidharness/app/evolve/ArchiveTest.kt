package com.androidharness.app.evolve

import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveTest {

    private val file: File = File(Files.createTempDirectory("archive").toFile(), "archive.json")

    @After
    fun cleanUp() {
        file.parentFile?.deleteRecursively()
    }

    private fun entry(id: String, parent: String? = null, rate: Double? = null, at: Long = 0L) =
        ArchiveEntry(genomeId = id, parent = parent, successRate = rate, createdAt = at, label = id)

    @Test
    fun `entries persist and stay in order`() {
        val archive = Archive(file)
        archive.add(entry("a"))
        archive.add(entry("b", parent = "a"))
        assertEquals(listOf("a", "b"), Archive(file).entries().map { it.genomeId })
    }

    @Test
    fun `a parent must exist`() {
        val archive = Archive(file)
        val result = runCatching { archive.add(entry("b", parent = "missing")) }
        assertTrue(result.isFailure)
    }

    @Test
    fun `the same genome is not archived twice`() {
        val archive = Archive(file)
        archive.add(entry("a"))
        val result = runCatching { archive.add(entry("a")) }
        assertTrue(result.isFailure)
    }

    @Test
    fun `a genome cannot become its own ancestor`() {
        val archive = Archive(file)
        archive.add(entry("a"))
        archive.add(entry("b", parent = "a"))
        val result = runCatching { archive.add(entry("a", parent = "b")) }
        assertTrue(result.isFailure)
    }

    @Test
    fun `lineage runs from the root to the genome`() {
        val archive = Archive(file)
        archive.add(entry("a"))
        archive.add(entry("b", parent = "a"))
        archive.add(entry("c", parent = "b"))
        assertEquals(listOf("a", "b", "c"), archive.lineage("c").map { it.genomeId })
        assertEquals(emptyList<String>(), archive.lineage("missing").map { it.genomeId })
    }

    @Test
    fun `parent selection never picks a vetoed genome and can prefer an unexplored branch`() {
        val archive = Archive(file)
        archive.add(entry("a", rate = 0.9))
        archive.add(entry("b", parent = "a", rate = 0.4))
        archive.add(entry("c", rate = 0.2))
        archive.setStatus("c", ArchiveStatus.VETOED)
        repeat(20) { seed ->
            val picked = archive.selectParent(Archive::fitness, Random(seed))
            assertTrue(picked != null)
            // A vetoed genome is never a parent, and the two scored genomes are both eligible.
            assertTrue(picked!!.genomeId in setOf("a", "b"))
        }
    }

    @Test
    fun `parent selection never returns the excluded genome`() {
        val archive = Archive(file)
        archive.add(entry("a", rate = 0.9))
        archive.add(entry("b", parent = "a", rate = 0.4))
        archive.add(entry("c", rate = 0.2))
        repeat(20) { seed ->
            val picked = archive.selectParent(Archive::fitness, Random(seed), excludeId = "a")
            assertTrue(picked != null)
            // "a" is the top-ranked entry and still never picked.
            assertTrue(picked!!.genomeId in setOf("b", "c"))
        }
    }

    @Test
    fun `excluding the only entry leaves no parent`() {
        val archive = Archive(file)
        archive.add(entry("a", rate = 0.9))
        assertNull(archive.selectParent(Archive::fitness, excludeId = "a"))
        // Excluding an unknown id changes nothing.
        assertEquals("a", archive.selectParent(Archive::fitness, Random(1), excludeId = "missing")?.genomeId)
    }

    @Test
    fun `an empty archive has no parent`() {
        assertNull(Archive(file).selectParent(Archive::fitness))
    }

    @Test
    fun `pruning keeps the active lineage and the fittest losers`() {
        val archive = Archive(file)
        archive.add(entry("root", rate = 0.1, at = 1))
        archive.add(entry("mid", parent = "root", rate = 0.2, at = 2))
        archive.add(entry("active", parent = "mid", rate = 0.3, at = 3))
        archive.add(entry("loser-1", rate = 0.0, at = 4))
        archive.add(entry("loser-2", rate = 0.9, at = 5))
        archive.add(entry("loser-3", rate = 0.05, at = 6))
        archive.setStatus("active", ArchiveStatus.ACTIVE)
        val removed = archive.prune("active", maxEntries = 2, fitness = Archive::fitness)
        val left = archive.entries().map { it.genomeId }.toSet()
        assertTrue(removed > 0)
        assertTrue(left.containsAll(setOf("root", "mid", "active")))
        assertTrue(left.contains("loser-2"))
        assertTrue("loser-1" !in left)
    }
}

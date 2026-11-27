package com.androidharness.app.evolve

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenomeStoreTest {

    private val root: File = Files.createTempDirectory("genomes").toFile()

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun genome(label: String = "Baseline") =
        Genome(createdAt = 1L, label = label, hypothesis = "Why not.", instructions = listOf("Be careful"))
            .identified()

    @Test
    fun `a genome is stored under its content hash and loads back unchanged`() {
        val store = GenomeStore(root)
        val saved = store.save(genome())
        assertEquals(saved.id, saved.computeId())
        val loaded = store.load(saved.id)
        assertNotNull(loaded)
        assertEquals(saved.canonical(), loaded!!.canonical())
        assertTrue(File(root, "genomes/${saved.id}/genome.json").isFile)
        assertFalse(File(root, "genomes/${saved.id}/genome.json.tmp").exists())
    }

    @Test
    fun `a genome with a stale id is refused`() {
        val store = GenomeStore(root)
        val result = runCatching { store.save(genome().copy(id = "0123456789abcdef")) }
        assertTrue(result.isFailure)
    }

    @Test
    fun `listing returns saved genomes in creation order`() {
        val store = GenomeStore(root)
        val early = store.save(Genome(createdAt = 1L, label = "Early"))
        val late = store.save(Genome(createdAt = 2L, label = "Late", instructions = listOf("Different")))
        assertEquals(listOf(early.id, late.id), store.list().map { it.id })
    }

    @Test
    fun `a corrupted file is ignored instead of crashing the store`() {
        val store = GenomeStore(root)
        val saved = store.save(genome())
        File(root, "genomes/${saved.id}/genome.json").writeText("{not json")
        assertNull(store.load(saved.id))
        assertEquals(emptyList<Genome>(), store.list())
    }

    @Test
    fun `a genome edited on disk no longer matches its folder and is refused`() {
        val store = GenomeStore(root)
        val saved = store.save(genome())
        val file = File(root, "genomes/${saved.id}/genome.json")
        file.writeText(file.readText().replace("Be careful", "Tampered"))
        assertNull(store.load(saved.id))
    }

    @Test
    fun `the active pointer survives and only points at stored genomes`() {
        val store = GenomeStore(root)
        val saved = store.save(genome())
        assertNull(store.active())
        store.activate(saved.id, null, 5L, "Sanu")
        val active = store.active()
        assertNotNull(active)
        assertEquals(saved.id, active!!.genomeId)
        assertEquals(5L, active.activatedAt)
        assertEquals("Sanu", active.approvedBy)
        store.activate("ffffffffffffffff", null, 6L, "Sanu")
        assertNull(store.active())
    }

    @Test
    fun `an interrupted write leaves the old genome readable`() {
        val store = GenomeStore(root)
        val saved = store.save(genome())
        // Left behind by a process that died mid-write.
        File(root, "genomes/${saved.id}/genome.json.tmp").writeText("{half")
        assertEquals(saved.id, store.load(saved.id)?.id)
    }

    @Test
    fun `reports are stored next to their genome`() {
        val store = GenomeStore(root)
        val saved = store.save(genome())
        store.saveReport(EvalReport(genomeId = saved.id, successRate = 0.5, safetyViolations = 0))
        assertEquals(0.5, store.report(saved.id)?.successRate ?: -1.0, 0.0)
    }
}

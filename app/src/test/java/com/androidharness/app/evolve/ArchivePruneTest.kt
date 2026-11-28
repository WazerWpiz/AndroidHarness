package com.androidharness.app.evolve

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P9 — the archive list never piles up: auto-prune keeps the active
 * lineage, the best 10 and the last run's candidate, and a row can be
 * deleted by hand only when nothing depends on it. Prune and delete
 * remove ARCHIVE ROWS ONLY: the stored genomes and reports stay on
 * disk, still exportable and still deduped against.
 */
class ArchivePruneTest {

    private val root: File = Files.createTempDirectory("archive-prune").toFile()
    private val file: File = File(root, "archive.json")
    private val store = GenomeStore(root)

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun entry(id: String, parent: String? = null, rate: Double? = null, at: Long = 0L) =
        ArchiveEntry(genomeId = id, parent = parent, successRate = rate, createdAt = at, label = id)

    @Test
    fun `prune keeps the active lineage and the best 10, and is a no-op under the cap`() {
        val archive = Archive(file)
        // Active lineage with LOW rates, so it survives only through
        // protection, never on fitness.
        archive.add(entry("root", rate = 0.01, at = 1))
        archive.add(entry("mid", parent = "root", rate = 0.02, at = 2))
        archive.add(entry("active", parent = "mid", rate = 0.03, at = 3))
        // 12 losers, all fitter than the lineage, rates 0.10..0.21.
        (1..12).forEach { i ->
            archive.add(entry("loser-$i", rate = 0.09 + i * 0.01, at = 10L + i))
        }
        // 15 entries, cap 10: keep the lineage (3) + the best 10 losers;
        // the 2 worst losers go.
        val removed = archive.prune("active", 10, Archive::fitness)
        assertEquals(2, removed)
        val left = archive.entries().map { it.genomeId }.toSet()
        assertTrue(left.containsAll(setOf("root", "mid", "active")))
        (3..12).forEach { assertTrue("loser-$it" in left) }
        assertTrue("loser-1" !in left)
        assertTrue("loser-2" !in left)
        assertEquals(13, left.size)

        // No-op when the archive is at or under the cap.
        val small = Archive(File(root, "small.json"))
        repeat(5) { small.add(entry("s$it", rate = 0.1)) }
        assertEquals(0, small.prune("s0", 10, Archive::fitness))
        assertEquals(5, small.entries().size)
    }

    @Test
    fun `prune never orphans a kept entry's parent chain`() {
        val archive = Archive(file)
        // A deep chain whose leaf is the fittest: the leaf survives on
        // fitness, its low-rate ancestors must survive with it.
        archive.add(entry("a", rate = 0.01, at = 1))
        archive.add(entry("b", parent = "a", rate = 0.01, at = 2))
        archive.add(entry("c", parent = "b", rate = 0.01, at = 3))
        archive.add(entry("d", parent = "c", rate = 0.99, at = 4))
        // Fillers, all fitter than the ancestors but worse than the leaf.
        (1..10).forEach { i -> archive.add(entry("filler-$i", rate = 0.5, at = 10L + i)) }
        archive.prune("missing-active", 10, Archive::fitness)
        val left = archive.entries().map { it.genomeId }.toSet()
        assertTrue("d" in left)
        assertTrue(left.containsAll(setOf("a", "b", "c")))
        // Every kept entry's whole parent chain is kept.
        archive.entries().forEach { e ->
            archive.lineage(e.genomeId).forEach { assertTrue(it.genomeId in left) }
        }
    }

    @Test
    fun `the last-run candidate survives prune even when it is neither top-10 nor lineage`() {
        val archive = Archive(file)
        archive.add(entry("root", rate = 0.9, at = 1))
        archive.add(entry("active", parent = "root", rate = 0.9, at = 2))
        // A separate branch: the last-run candidate and its parent, both
        // the worst by fitness.
        archive.add(entry("last-parent", rate = 0.0, at = 3))
        archive.add(entry("last", parent = "last-parent", rate = 0.0, at = 4))
        (1..10).forEach { i -> archive.add(entry("good-$i", rate = 0.5, at = 10L + i)) }
        // 14 entries: without protection "last" and "last-parent" would go.
        val removed = archive.prune("active", 10, Archive::fitness, extraProtected = setOf("last"))
        assertTrue(removed > 0)
        val left = archive.entries().map { it.genomeId }.toSet()
        assertTrue("last" in left)
        assertTrue("last-parent" in left)
        assertTrue("active" in left)
    }

    @Test
    fun `the delete guard refuses the active genome, its lineage, the last-run candidate and parents`() {
        val archive = Archive(file)
        archive.add(entry("root", rate = 0.9, at = 1))
        archive.add(entry("mid", parent = "root", rate = 0.8, at = 2))
        archive.add(entry("active", parent = "mid", rate = 0.7, at = 3))
        archive.add(entry("child", parent = "mid", rate = 0.1, at = 4))
        archive.add(entry("last", rate = 0.0, at = 5))
        archive.add(entry("loser", rate = 0.0, at = 6))
        val all = archive.entries()
        assertFalse(Archive.canDeleteEntry("active", "active", "last", all))
        assertFalse(Archive.canDeleteEntry("mid", "active", "last", all))
        assertFalse(Archive.canDeleteEntry("root", "active", "last", all))
        assertFalse(Archive.canDeleteEntry("last", "active", "last", all))
        assertTrue(Archive.canDeleteEntry("child", "active", "last", all))
        assertTrue(Archive.canDeleteEntry("loser", "active", "last", all))

        // Removing a deletable row drops the row and nothing else.
        archive.remove("loser")
        assertTrue(archive.entries().none { it.genomeId == "loser" })
        assertEquals(5, archive.entries().size)
        // Removing an absent row is a no-op, never a crash.
        archive.remove("missing")
        assertEquals(5, archive.entries().size)
    }

    @Test
    fun `pruned and deleted rows leave the stored genome and report on disk`() {
        val archive = Archive(file)
        val kept = store.save(Genome(label = "kept", instructions = listOf("Keep me.")))
        val pruned = store.save(Genome(label = "pruned", instructions = listOf("Prune me.")))
        val deleted = store.save(Genome(label = "deleted", instructions = listOf("Delete me.")))
        store.saveReport(EvalReport(genomeId = pruned.id, successRate = 0.1))
        store.saveReport(EvalReport(genomeId = deleted.id, successRate = 0.1))
        archive.add(entry(kept.id, rate = 0.9, at = 1))
        archive.add(entry(pruned.id, rate = 0.0, at = 2))
        archive.add(entry(deleted.id, rate = 0.6, at = 3))
        (1..10).forEach { i -> archive.add(entry("filler-$i", rate = 0.5, at = 10L + i)) }
        // 13 entries: the 0.0 row and two fillers are pruned; "deleted"
        // survives on fitness, so remove() below genuinely removes it.
        archive.prune(null, 10, Archive::fitness)
        val left = archive.entries().map { it.genomeId }.toSet()
        assertTrue(kept.id in left)
        assertTrue(pruned.id !in left)
        assertTrue(deleted.id in left)
        assertNotNull(store.load(pruned.id))
        assertNotNull(store.report(pruned.id))

        // The deleted row's files stay too.
        archive.remove(deleted.id)
        assertTrue(deleted.id !in archive.entries().map { it.genomeId }.toSet())
        assertNotNull(store.load(deleted.id))
        assertNotNull(store.report(deleted.id))
    }

    @Test
    fun `the last-run pointer's candidate still resolves after a prune`() {
        val archive = Archive(file)
        archive.add(entry("root", rate = 0.9, at = 1))
        archive.add(entry("last", rate = 0.0, at = 2))
        (1..10).forEach { i -> archive.add(entry("good-$i", rate = 0.5, at = 10L + i)) }
        // The host writes the pointer, then prunes with it as extra protection.
        store.saveLastRun(LastRun("last", "1 generation(s), 1 candidate(s)", System.currentTimeMillis()))
        archive.prune(
            activeId = "root",
            maxEntries = Archive.MAX_KEPT_ENTRIES,
            fitness = Archive::fitness,
            extraProtected = setOfNotNull(store.lastRun()?.candidateId),
        )
        // The report block's data source: the pointer candidate must still be in the archive.
        val pointerId = store.lastRun()?.candidateId
        assertNotNull(pointerId)
        assertNotNull(archive.entries().firstOrNull { it.genomeId == pointerId })
    }
}

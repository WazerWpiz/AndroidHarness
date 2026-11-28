package com.androidharness.app.evolve

import java.io.ByteArrayInputStream
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5 — tests for genome export / import ([GenomeBackup]).
 *
 * The round trip is the point: export → wipe the evolve/ dir the way an
 * uninstall would → import → the same genomes, reports and archive
 * entries are back with the same ids, and nothing is activated.
 */
class GenomeBackupTest {

    private val root = Files.createTempDirectory("genome-backup-test").toFile()

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    /** Two genomes, two reports, two archive entries: a baseline and one candidate. */
    private fun seed(): Triple<EvolveHost, Genome, Genome> {
        val host = EvolveHost.at(root)
        val base = host.store.save(Genome.baseline())
        val custom = host.store.save(
            Genome.baseline().copy(
                // A changed genome earns a new id: clear the stale one so
                // requireValid recomputes it from the new content.
                id = "",
                label = "verify",
                hypothesis = "Verify before claiming done.",
                instructions = listOf("Verify before claiming done: run the checks and quote their evidence."),
            ),
        )
        host.store.saveReport(EvalReport(genomeId = base.id, successRate = 0.25))
        host.store.saveReport(EvalReport(genomeId = custom.id, successRate = 0.75, holdoutScore = 1.0))
        host.archive.add(ArchiveEntry(genomeId = base.id, label = "Baseline", successRate = 0.25))
        host.archive.add(
            ArchiveEntry(genomeId = custom.id, parent = base.id, label = "verify", successRate = 0.75, holdoutScore = 1.0),
        )
        return Triple(host, base, custom)
    }

    private fun wipeAndFreshHost(): EvolveHost {
        root.deleteRecursively()
        root.mkdirs()
        return EvolveHost.at(root)
    }

    @Test
    fun `round trip restores genomes reports entries and ids`() {
        val (host, base, custom) = seed()
        host.store.activate(custom.id, base.id, 123L, "test")
        val text = GenomeBackup.encode(GenomeBackup.build(host.store, host.archive))

        val fresh = wipeAndFreshHost()
        val outcome = GenomeBackup.importInto(fresh.store, fresh.archive, GenomeBackup.decode(text))

        assertEquals(2, outcome.importedGenomes)
        assertEquals(0, outcome.skippedGenomes)
        assertTrue(outcome.refusedGenomes.isEmpty())
        assertEquals(2, outcome.importedReports)
        assertEquals(2, outcome.importedEntries)
        assertEquals(base.id, fresh.store.load(base.id)?.id)
        assertEquals(custom.id, fresh.store.load(custom.id)?.id)
        assertEquals("verify", fresh.store.load(custom.id)?.label)
        assertEquals(0.75, fresh.store.report(custom.id)?.successRate ?: -1.0, 0.0)
        assertEquals(setOf(base.id, custom.id), fresh.archive.entries().map { it.genomeId }.toSet())
        // Import never activates anything: the active pointer stays empty
        // even though the backup recorded it.
        assertNull(fresh.store.active())
    }

    @Test
    fun `tampered genome is refused and the rest still import`() {
        val (host, base, custom) = seed()
        val text = GenomeBackup.encode(GenomeBackup.build(host.store, host.archive))
        val tampered = text.replace(
            "Verify before claiming done: run the checks and quote their evidence.",
            "Verify before claiming done: run the checks and quote their evidence. TAMPERED",
        )
        assertTrue(tampered != text)

        val fresh = wipeAndFreshHost()
        val outcome = GenomeBackup.importInto(fresh.store, fresh.archive, GenomeBackup.decode(tampered))

        assertEquals(1, outcome.importedGenomes)
        assertEquals(1, outcome.refusedGenomes.size)
        assertTrue(outcome.refusedGenomes.first().contains(custom.id))
        assertNull(fresh.store.load(custom.id))
        assertNotNull(fresh.store.load(base.id))
        // The tampered genome's archive entry and report are skipped: its genome is missing.
        assertEquals(1, outcome.importedEntries)
        assertEquals(1, outcome.importedReports)
    }

    @Test
    fun `malformed json and unsupported versions are refused`() {
        assertThrows(IllegalArgumentException::class.java) { GenomeBackup.decode("this is not json") }
        assertThrows(IllegalArgumentException::class.java) { GenomeBackup.decode("{\"version\": 99, \"genomes\": []}") }
    }

    @Test
    fun `importing twice does not duplicate or corrupt`() {
        val (host, _, _) = seed()
        val text = GenomeBackup.encode(GenomeBackup.build(host.store, host.archive))
        val fresh = wipeAndFreshHost()

        val first = GenomeBackup.importInto(fresh.store, fresh.archive, GenomeBackup.decode(text))
        assertEquals(2, first.importedGenomes)
        val second = GenomeBackup.importInto(fresh.store, fresh.archive, GenomeBackup.decode(text))
        assertEquals(0, second.importedGenomes)
        assertEquals(2, second.skippedGenomes)
        assertEquals(0, second.importedEntries)
        assertEquals(2, fresh.store.list().size)
        assertEquals(2, fresh.archive.entries().size)
    }

    @Test
    fun `a stored genome with different bytes is not overwritten`() {
        val (host, _, custom) = seed()
        val backup = GenomeBackup.build(host.store, host.archive)
        val relabeled = GenomeBackup.decode(
            GenomeBackup.encode(
                backup.copy(
                    genomes = backup.genomes.map { if (it.id == custom.id) it.copy(label = "relabeled") else it },
                ),
            ),
        )
        val fresh = wipeAndFreshHost()
        GenomeBackup.importInto(fresh.store, fresh.archive, backup)
        val second = GenomeBackup.importInto(fresh.store, fresh.archive, relabeled)

        assertEquals(0, second.importedGenomes)
        assertEquals(1, second.refusedGenomes.size)
        assertTrue(second.refusedGenomes.first().contains(custom.id))
        assertEquals("verify", fresh.store.load(custom.id)?.label)
    }

    @Test
    fun `oversize file is refused before reading`() {
        val big = ByteArray(GenomeBackup.MAX_BACKUP_BYTES + 1)
        val result = runCatching { GenomeBackup.readBackupText(ByteArrayInputStream(big)) }
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `reports and entries for missing genomes are skipped`() {
        val host = EvolveHost.at(root)
        val base = host.store.save(Genome.baseline())
        val backup = GenomeBackupFile(
            genomes = listOf(base),
            reports = listOf(
                EvalReport(genomeId = base.id, successRate = 0.5),
                EvalReport(genomeId = "0123456789abcdef", successRate = 0.9),
            ),
            entries = listOf(
                ArchiveEntry(genomeId = base.id, label = "Baseline"),
                ArchiveEntry(genomeId = "0123456789abcdef", label = "ghost"),
            ),
        )
        val outcome = GenomeBackup.importInto(host.store, host.archive, backup)

        assertEquals(0, outcome.importedGenomes)
        assertEquals(1, outcome.skippedGenomes)
        assertEquals(1, outcome.importedReports)
        assertEquals(1, outcome.skippedReports)
        assertEquals(1, outcome.importedEntries)
        assertEquals(1, outcome.skippedEntries)
    }

    @Test
    fun `export counts match the store`() {
        val (host, base, custom) = seed()
        host.store.activate(custom.id, base.id, 123L, "test")
        val backup = GenomeBackup.build(host.store, host.archive)

        assertEquals(2, backup.genomes.size)
        assertEquals(2, backup.reports.size)
        assertEquals(2, backup.entries.size)
        assertEquals(custom.id, backup.activeGenomeId)
        assertEquals(setOf(base.id, custom.id), backup.genomes.map { it.id }.toSet())
    }
}

package com.androidharness.app.evolve

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * P5 — genome export / import: a reinstall must never wipe a genome again.
 *
 * The backup is ONE plain JSON file holding the whole archive: every
 * genome, each genome's report, the archive entries, and the active
 * pointer, so it can live outside app-private storage (Downloads, a file
 * the user keeps). Genomes are instructions, not secrets — but the file
 * still holds the agent's instructions, so keep it somewhere only you
 * can read; there is no cloud copy and no auto-backup.
 *
 * Import is deliberately conservative: every genome re-enters only
 * through the existing checks ([GenomeRules.requireValid] and the
 * content-hash check in [GenomeStore.load]), a tampered genome is
 * refused, a stored genome is never overwritten with different bytes,
 * and NOTHING is activated — the active pointer in the file is recorded
 * but never restored. A genome becomes active again only through the
 * normal [PromotionGate] approval path.
 */
@Serializable
data class GenomeBackupFile(
    val version: Int = GenomeBackup.VERSION,
    val genomes: List<Genome> = emptyList(),
    val reports: List<EvalReport> = emptyList(),
    val entries: List<ArchiveEntry> = emptyList(),
    /** The genome that was active when the backup was made. Recorded only; import never restores it. */
    val activeGenomeId: String? = null,
    val exportedAt: Long = 0L,
)

/** What one import did, counted honestly — refused genomes are listed, never silently dropped. */
data class GenomeImportOutcome(
    val importedGenomes: Int,
    val skippedGenomes: Int,
    val refusedGenomes: List<String>,
    val importedReports: Int,
    val skippedReports: Int,
    val importedEntries: Int,
    val skippedEntries: Int,
)

object GenomeBackup {
    const val VERSION = 1

    /** A backup larger than this is refused before it is read fully. Genomes are small text. */
    const val MAX_BACKUP_BYTES = 16 * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    /** A snapshot of everything the harness has: genomes, their reports, the archive, the active pointer. */
    fun build(store: GenomeStore, archive: Archive, exportedAt: Long = System.currentTimeMillis()): GenomeBackupFile {
        val genomes = store.list()
        return GenomeBackupFile(
            genomes = genomes,
            reports = genomes.mapNotNull { store.report(it.id) },
            entries = archive.entries(),
            activeGenomeId = store.active()?.genomeId,
            exportedAt = exportedAt,
        )
    }

    fun encode(backup: GenomeBackupFile): String = json.encodeToString(GenomeBackupFile.serializer(), backup)

    /** Parses a backup file. Malformed JSON or an unsupported version is refused, never guessed at. */
    fun decode(text: String): GenomeBackupFile {
        val backup = runCatching { json.decodeFromString(GenomeBackupFile.serializer(), text) }.getOrElse {
            throw IllegalArgumentException("Not a genome backup file: ${it.message}")
        }
        require(backup.version == VERSION) { "Unsupported genome backup version ${backup.version}." }
        return backup
    }

    /**
     * Reads a backup file, refusing anything absurdly large before it is
     * fully in memory. Closes [input].
     */
    fun readBackupText(input: InputStream): String {
        input.use { stream ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = stream.read(buf)
                if (n <= 0) break
                total += n
                if (total > MAX_BACKUP_BYTES) {
                    throw IllegalArgumentException(
                        "Backup file is larger than ${MAX_BACKUP_BYTES / 1024 / 1024} MB; refusing it.",
                    )
                }
                out.write(buf, 0, n)
            }
            return out.toString(Charsets.UTF_8)
        }
    }

    /**
     * Restores a backup into [store] and [archive]. Per genome, in order:
     * the id must still match the content (an edited file keeps its old id,
     * so the hash no longer matches — that is tampering, refuse it), then
     * [GenomeRules.requireValid], then the store's own hash check on save.
     * A genome already stored with identical bytes is skipped; one stored
     * with different bytes is refused, never overwritten. Reports and
     * archive entries are restored only for genomes that are present, and
     * only when nothing is stored locally already. The active pointer is
     * never restored: importing must not activate anything.
     */
    fun importInto(store: GenomeStore, archive: Archive, backup: GenomeBackupFile): GenomeImportOutcome {
        var importedGenomes = 0
        var skippedGenomes = 0
        val refused = mutableListOf<String>()
        backup.genomes.forEach { genome ->
            val id = genome.id.ifBlank { "(no id)" }
            if (genome.id.isBlank() || genome.computeId() != genome.id) {
                refused += "$id: id does not match its content (edited or tampered)"
                return@forEach
            }
            val valid = runCatching { GenomeRules.requireValid(genome) }.getOrElse { e ->
                refused += "$id: ${e.message}"
                return@forEach
            }
            val existingFile = File(store.root, "genomes/${valid.id}/genome.json")
            val existing = store.load(valid.id)
            when {
                existing == null && existingFile.isFile -> {
                    // Stored bytes that no longer hash to their folder were
                    // edited on disk; refuse rather than overwrite them.
                    refused += "${valid.id}: already stored but unreadable; not overwritten"
                }
                existing == null -> {
                    store.save(valid)
                    importedGenomes++
                }
                existing == valid -> skippedGenomes++
                else -> refused += "${valid.id}: already stored with different content; not overwritten"
            }
        }
        var importedReports = 0
        var skippedReports = 0
        backup.reports.forEach { report ->
            if (store.load(report.genomeId) == null || store.report(report.genomeId) != null) {
                skippedReports++
            } else {
                store.saveReport(report)
                importedReports++
            }
        }
        val knownEntries = archive.entries().map { it.genomeId }.toMutableSet()
        var importedEntries = 0
        var skippedEntries = 0
        backup.entries.forEach { entry ->
            when {
                entry.genomeId in knownEntries -> skippedEntries++
                store.load(entry.genomeId) == null -> skippedEntries++
                entry.parent != null && entry.parent !in knownEntries -> skippedEntries++
                else -> {
                    if (runCatching { archive.add(entry) }.isSuccess) {
                        importedEntries++
                        knownEntries += entry.genomeId
                    } else {
                        skippedEntries++
                    }
                }
            }
        }
        return GenomeImportOutcome(
            importedGenomes = importedGenomes,
            skippedGenomes = skippedGenomes,
            refusedGenomes = refused,
            importedReports = importedReports,
            skippedReports = skippedReports,
            importedEntries = importedEntries,
            skippedEntries = skippedEntries,
        )
    }
}

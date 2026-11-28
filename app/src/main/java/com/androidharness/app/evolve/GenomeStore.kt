package com.androidharness.app.evolve

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Atomic text writes: a reader sees the old file or the new one, never half of either. */
internal object EvolveIo {
    fun write(file: File, text: String) {
        val parent = file.parentFile ?: throw IOException("No folder for ${file.name}.")
        parent.mkdirs()
        val temporary = File(parent, file.name + ".tmp")
        FileOutputStream(temporary).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!temporary.renameTo(file)) {
            // renameTo does not replace an existing file on every filesystem.
            if (file.exists() && !file.delete()) {
                temporary.delete()
                throw IOException("Could not replace ${file.name}.")
            }
            if (!temporary.renameTo(file)) {
                temporary.delete()
                throw IOException("Could not save ${file.name}.")
            }
        }
    }

    fun read(file: File): String? = runCatching { if (file.isFile) file.readText() else null }.getOrNull()
}

/**
 * The last run's pointer (P8): which candidate the report block should
 * show, and what the run produced. Written by [EvolveHost] when a run
 * completes (success, stopped, or error — it records what the run
 * actually produced), read back by the Settings screen so the report
 * block survives an app restart. A null [candidateId] means the run
 * produced no candidate to show (blocked, stopped, or failed): the
 * screen then falls back to the newest archived candidate.
 */
@Serializable
data class LastRun(
    val candidateId: String? = null,
    val summary: String = "",
    val finishedAt: Long = 0L,
)

/**
 * Content-addressed home of every genome, under its own folder:
 *
 * ```
 * <root>/genomes/<id>/genome.json
 * <root>/reports/<id>.json
 * <root>/active.json
 * <root>/last-run.json
 * ```
 *
 * The id is the hash of the content, so a genome can be found by its meaning and a
 * half-written candidate can never become the active one. Every write is atomic and
 * every path is checked against [ProtectedPaths] before it is touched.
 */
class GenomeStore(val root: File) {

    @Serializable
    data class ActiveGenome(
        val genomeId: String,
        val previous: String? = null,
        val activatedAt: Long = 0L,
        val approvedBy: String = "",
    )

    private val genomesDir = File(root, "genomes")
    private val reportsDir = File(root, "reports")
    private val activeFile = File(root, "active.json")
    private val lastRunFile = File(root, "last-run.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun genomeDir(id: String): File {
        require(id.matches(Regex("[a-f0-9]{16}"))) { "Not a genome id: $id" }
        return File(genomesDir, id)
    }

    /** Validate, identify and keep a genome. Returns the stored copy, whose id is content-derived. */
    @Synchronized
    fun save(genome: Genome): Genome {
        val stored = GenomeRules.requireValid(genome)
        val target = File(genomeDir(stored.id), "genome.json")
        ProtectedPaths.requireWritable(target.invariantSeparatorsPath)
        EvolveIo.write(target, Genome.CANONICAL.encodeToString(Genome.serializer(), stored))
        return stored
    }

    @Synchronized
    fun load(id: String): Genome? {
        val text = EvolveIo.read(File(genomeDir(id), "genome.json")) ?: return null
        val genome = runCatching { Genome.CANONICAL.decodeFromString(Genome.serializer(), text) }.getOrNull() ?: return null
        // A genome whose bytes no longer hash to its folder was edited on disk; refuse it
        // rather than activate something that was never evaluated.
        return genome.takeIf { it.computeId() == id }
    }

    @Synchronized
    fun list(): List<Genome> =
        genomesDir.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { load(it.name) }
            .sortedBy { it.createdAt }

    @Synchronized
    fun active(): ActiveGenome? {
        val text = EvolveIo.read(activeFile) ?: return null
        val active = runCatching { json.decodeFromString(ActiveGenome.serializer(), text) }.getOrNull() ?: return null
        return active.takeIf { load(it.genomeId) != null }
    }

    /** Point the harness at [genomeId]. Only [PromotionGate] calls this, after an approval.
     * Activating a genome that was never stored must never crash and must never leave a
     * dangling pointer: the active pointer is cleared instead. */
    @Synchronized
    internal fun activate(genomeId: String, previous: String?, at: Long, approvedBy: String) {
        if (load(genomeId) == null) {
            activeFile.delete()
            return
        }
        ProtectedPaths.requireWritable(activeFile.invariantSeparatorsPath)
        EvolveIo.write(activeFile, json.encodeToString(ActiveGenome.serializer(),
            ActiveGenome(genomeId, previous, at, approvedBy)))
    }

    @Synchronized
    fun saveReport(report: EvalReport) {
        reportsDir.mkdirs()
        val target = File(reportsDir, "${report.genomeId}.json")
        ProtectedPaths.requireWritable(target.invariantSeparatorsPath)
        EvolveIo.write(target, json.encodeToString(EvalReport.serializer(), report))
    }

    @Synchronized
    fun report(genomeId: String): EvalReport? {
        val text = EvolveIo.read(File(reportsDir, "$genomeId.json")) ?: return null
        return runCatching { json.decodeFromString(EvalReport.serializer(), text) }.getOrNull()
    }

    /** Persist the last run's pointer (P8): what the run produced, for the report block after a restart. */
    @Synchronized
    fun saveLastRun(lastRun: LastRun) {
        ProtectedPaths.requireWritable(lastRunFile.invariantSeparatorsPath)
        EvolveIo.write(lastRunFile, json.encodeToString(LastRun.serializer(), lastRun))
    }

    /** The last run's pointer, or null when no run has ever finished. */
    @Synchronized
    fun lastRun(): LastRun? {
        val text = EvolveIo.read(lastRunFile) ?: return null
        return runCatching { json.decodeFromString(LastRun.serializer(), text) }.getOrNull()
    }
}

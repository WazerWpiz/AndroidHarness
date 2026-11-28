package com.androidharness.app.evolve

import java.io.File
import kotlin.random.Random
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class ArchiveStatus { ACTIVE, EVALUATED, REJECTED, VETOED, ARCHIVED }

/** One evaluated genome. Losers stay as stepping stones, so the archive is a DAG and not a chain. */
@Serializable
data class ArchiveEntry(
    val genomeId: String,
    val parent: String? = null,
    val label: String = "",
    val hypothesis: String = "",
    val createdAt: Long = 0L,
    val status: ArchiveStatus = ArchiveStatus.EVALUATED,
    val successRate: Double? = null,
    val holdoutScore: Double? = null,
    val tokensPerSuccess: Double? = null,
    val safetyViolations: Int = 0,
    /** The gate's reason at evaluation time (P7); null when the candidate met the gate. Capped at 200 chars. */
    val gateReason: String? = null,
)

/**
 * Every genome the loop has evaluated, including the ones that lost, kept as a directed
 * acyclic graph. A candidate enters once, by content hash, so a repeated patch is refused
 * instead of scored again, and a genome can never become its own ancestor.
 */
class Archive(private val file: File, private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }) {

    @Serializable
    private data class ArchiveFile(val entries: List<ArchiveEntry> = emptyList())

    @Synchronized
    fun entries(): List<ArchiveEntry> = runCatching {
        EvolveIo.read(file)?.let { json.decodeFromString(ArchiveFile.serializer(), it).entries } ?: emptyList()
    }.getOrDefault(emptyList())

    private fun persist(entries: List<ArchiveEntry>) {
        EvolveIo.write(file, json.encodeToString(ArchiveFile.serializer(), ArchiveFile(entries)))
    }

    @Synchronized
    fun add(entry: ArchiveEntry) {
        val all = entries()
        require(all.none { it.genomeId == entry.genomeId }) { "Genome ${entry.genomeId} is already in the archive." }
        if (entry.parent != null) {
            require(all.any { it.genomeId == entry.parent }) { "Parent ${entry.parent} is not in the archive." }
            require(entry.genomeId !in ancestorIds(entry.parent, all).toSet()) { "A genome cannot descend from itself." }
        }
        persist(all + entry)
    }

    @Synchronized
    fun setStatus(genomeId: String, status: ArchiveStatus) {
        persist(entries().map { if (it.genomeId == genomeId) it.copy(status = status) else it })
    }

    /**
     * Replace one entry's recorded facts (P8): a candidate's scores are
     * refined after its 3-seed confirmation, and a parent's scores are
     * refreshed when its report is re-evaluated to a 3-seed aggregate.
     * Without this the archive would show stale screening scores. The
     * entry must already exist; the genome id and parent never change.
     */
    @Synchronized
    fun update(entry: ArchiveEntry) {
        val all = entries()
        require(all.any { it.genomeId == entry.genomeId }) { "Genome ${entry.genomeId} is not in the archive." }
        persist(all.map { if (it.genomeId == entry.genomeId) entry else it })
    }

    /** Root first, [id] last. Empty when the id is unknown. */
    fun lineage(id: String, all: List<ArchiveEntry> = entries()): List<ArchiveEntry> {
        val byId = all.associateBy { it.genomeId }
        return lineageIds(id, all).mapNotNull { byId[it] }
    }

    fun children(id: String, all: List<ArchiveEntry> = entries()): List<ArchiveEntry> =
        all.filter { it.parent == id }

    private fun ancestorIds(id: String, all: List<ArchiveEntry>): List<String> =
        lineage(id, all).map { it.genomeId }

    /**
     * A parent for the next candidate: the best few genomes by fitness, chosen at random
     * within that group. Fitness alone would keep polishing one branch; novelty — how few
     * children a genome has — lets an unexplored stepping stone win the tie. Vetoed
     * genomes are never parents. [excludeId] (P7) is filtered out BEFORE ranking, so the
     * top-3 pool is filled with other entries: the runner excludes the current parent,
     * because re-picking it ends the run after one generation. If exclusion empties the
     * pool, there is nothing else to evolve from and this returns null.
     */
    @Synchronized
    fun selectParent(
        fitness: (ArchiveEntry) -> Double,
        random: Random = Random.Default,
        excludeId: String? = null,
    ): ArchiveEntry? {
        val all = entries()
            .filter { it.status != ArchiveStatus.VETOED }
            .filter { excludeId == null || it.genomeId != excludeId }
        if (all.isEmpty()) return null
        val children = all.groupingBy { it.parent }.eachCount()
        val ranked = all.map { entry ->
            val novelty = 1.0 / (1.0 + (children[entry.genomeId] ?: 0))
            entry to (fitness(entry) + NOVELTY_WEIGHT * novelty)
        }.sortedByDescending { it.second }
        return ranked.take(TOP_PARENTS).random(random).first
    }

    /**
     * Drop old losers, never the active genome, its lineage, or the best [maxEntries] by
     * fitness. [extraProtected] (P9) are extra genomes to keep besides the active
     * lineage and the fittest — the last run's candidate, so the report block keeps
     * its row — and their lineages are kept too. Returns how many entries were removed.
     *
     * Rows only: pruning removes archive rows, never the stored genomes or their
     * reports — those stay on disk, still exportable and still deduped against.
     */
    @Synchronized
    fun prune(
        activeId: String?,
        maxEntries: Int,
        fitness: (ArchiveEntry) -> Double,
        extraProtected: Set<String> = emptySet(),
    ): Int {
        require(maxEntries >= 1) { "Keep at least one entry." }
        val all = entries()
        if (all.size <= maxEntries) return 0
        val keep = linkedSetOf<String>()
        activeId?.let { keep += lineageIds(it, all) }
        extraProtected.forEach { keep += lineageIds(it, all) }
        all.sortedByDescending(fitness).take(maxEntries).forEach { keep += it.genomeId }
        // Never orphan a kept entry's parent chain.
        keep.toList().forEach { id -> keep += lineageIds(id, all) }
        val kept = all.filter { it.genomeId in keep }
        persist(kept)
        return all.size - kept.size
    }

    /**
     * Removes one archive row (P9): the list entry only. The stored genome and its
     * report stay on disk — still exportable, still deduped against — so deleting
     * a row never loses data. No-op when the row is absent.
     */
    @Synchronized
    fun remove(genomeId: String) {
        val all = entries()
        if (all.none { it.genomeId == genomeId }) return
        persist(all.filter { it.genomeId != genomeId })
    }

    companion object {
        private const val TOP_PARENTS = 3
        private const val NOVELTY_WEIGHT = 0.25

        /** How many archive rows the auto-prune (P9) keeps by fitness, besides the protected sets. */
        const val MAX_KEPT_ENTRIES = 10

        fun fitness(entry: ArchiveEntry): Double =
            (entry.successRate ?: 0.0) - if (entry.safetyViolations > 0) 1.0 else 0.0

        /** Root-first genome ids of [id]'s lineage, from the given entries. Empty when unknown. */
        private fun lineageIds(id: String, all: List<ArchiveEntry>): List<String> {
            val byId = all.associateBy { it.genomeId }
            val out = mutableListOf<String>()
            val seen = mutableSetOf<String>()
            var current = byId[id]
            while (current != null && seen.add(current.genomeId)) {
                out += current.genomeId
                current = current.parent?.let(byId::get)
            }
            return out.asReversed()
        }

        /**
         * Whether an archive row may be deleted by hand (P9): never the active genome,
         * never an ancestor of the active genome (its lineage), never the last run's
         * candidate (the report block loads from it), and never a genome that still
         * has children (deleting a parent orphans them). Pure: decided from the given
         * entries only, so it is unit-testable. Deleting a row only removes the row.
         */
        fun canDeleteEntry(
            genomeId: String,
            activeId: String?,
            lastRunCandidateId: String?,
            all: List<ArchiveEntry>,
        ): Boolean {
            if (genomeId == activeId) return false
            if (genomeId == lastRunCandidateId) return false
            if (activeId != null && genomeId in lineageIds(activeId, all)) return false
            if (all.any { it.parent == genomeId }) return false
            return true
        }
    }
}

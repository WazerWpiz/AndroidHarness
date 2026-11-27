package com.androidharness.app.evolve

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A command hook the harness runs, kept in the genome rather than a plugin file so a
 * candidate can add or remove one without touching the APK. Only events this app
 * already fires are allowed; [GenomeRules] refuses anything else.
 */
@Serializable
data class GenomeHook(
    val event: String,
    val matcher: String = "",
    val command: String,
    val timeout: Int = 60,
)

/** A named agent profile in the genome: instructions, a preferred model and the tools it may see. */
@Serializable
data class GenomeAgent(
    val name: String,
    val instructions: String = "",
    val model: String? = null,
    val tools: List<String> = emptyList(),
)

/**
 * The harness genome: the part of the agent that is allowed to learn. Model weights
 * never appear here, and neither do permissions, the evaluator or the safety probes.
 *
 * The content id is the sha256 of the canonical payload, cut to 16 hex characters, so
 * two genomes that mean the same thing always get the same id and the archive can
 * refuse a candidate it has already seen. [parent], [createdAt], [label], [hypothesis]
 * and [id] are lineage and presentation, not genome content, so they stay out of the
 * payload and changing them does not mint a new genome.
 */
@Serializable
data class Genome(
    val id: String = "",
    val parent: String? = null,
    val createdAt: Long = 0L,
    val label: String = "",
    val hypothesis: String = "",
    val instructions: List<String> = emptyList(),
    /** Skill name to full SKILL.md text. */
    val skills: Map<String, String> = emptyMap(),
    val skillEnable: Set<String> = emptySet(),
    val hooks: List<GenomeHook> = emptyList(),
    val agents: Map<String, GenomeAgent> = emptyMap(),
    /** The proposer's own working notes, so the next generation can read why the last patch was tried. */
    val proposerNotes: String = "",
) {
    /** Everything a genome may evolve, normalized so equal content serializes byte for byte. */
    fun payload(): Genome = copy(
        id = "",
        parent = null,
        createdAt = 0L,
        label = "",
        hypothesis = "",
        instructions = instructions.map { it.trim() }.filter { it.isNotEmpty() },
        skills = skills.mapValues { (_, text) -> text.replace("\r\n", "\n").trimEnd() }.toSortedMap(),
        skillEnable = skillEnable.toSortedSet(),
        hooks = hooks.sortedWith(compareBy({ it.event }, { it.matcher }, { it.command }, { it.timeout })),
        agents = agents.toSortedMap(),
        proposerNotes = proposerNotes.replace("\r\n", "\n").trim(),
    )

    fun canonical(): String = CANONICAL.encodeToString(serializer(), payload())

    fun computeId(): String = sha256(canonical()).take(ID_LENGTH)

    /** A copy carrying the id its content earns. */
    fun identified(): Genome = copy(id = computeId())

    /** A short, readable account of every field that differs, for the diff tool and cards. */
    fun diff(other: Genome): String {
        val out = mutableListOf<String>()
        if (instructions != other.instructions) out += listDiff("instructions", instructions, other.instructions)
        (skills.keys + other.skills.keys).sorted().forEach { name ->
            val before = skills[name]
            val after = other.skills[name]
            if (before != after) out += when {
                before == null -> "skill $name: added"
                after == null -> "skill $name: removed"
                else -> "skill $name: changed (${before.length} -> ${after.length} chars)"
            }
        }
        if (skillEnable != other.skillEnable) {
            out += "enabled skills: +${(other.skillEnable - skillEnable).size} -${(skillEnable - other.skillEnable).size}"
        }
        if (hooks != other.hooks) {
            out += listDiff("hooks", hooks.map(::describeHook), other.hooks.map(::describeHook))
        }
        (agents.keys + other.agents.keys).sorted().forEach { name ->
            val before = agents[name]
            val after = other.agents[name]
            if (before != after) out += when {
                before == null -> "agent $name: added"
                after == null -> "agent $name: removed"
                else -> "agent $name: changed"
            }
        }
        if (proposerNotes != other.proposerNotes) out += "proposer notes: changed"
        return if (out.isEmpty()) "No content changes." else out.joinToString("\n")
    }

    companion object {
        private const val ID_LENGTH = 16
        const val MAX_LABEL = 80
        const val MAX_HYPOTHESIS = 500

        val CANONICAL = Json { encodeDefaults = true; explicitNulls = false }

        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        /** The starting point: no instructions of its own, no skills, no hooks. */
        fun baseline(now: Long = System.currentTimeMillis()): Genome =
            Genome(createdAt = now, label = "Baseline", hypothesis = "The harness as it shipped.").identified()

        private fun describeHook(hook: GenomeHook): String =
            "${hook.event}${if (hook.matcher.isBlank()) "" else " (${hook.matcher})"}: ${hook.command}"

        private fun listDiff(name: String, before: List<String>, after: List<String>): String = buildString {
            append(name).append(": +").append(after.size).append(" -").append(before.size)
            (before - after.toSet()).forEach { append("\n  - ").append(it.lineSequence().first().take(160)) }
            (after - before.toSet()).forEach { append("\n  + ").append(it.lineSequence().first().take(160)) }
        }
    }
}

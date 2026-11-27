package com.androidharness.app.evolve

/**
 * The seam where an approved genome starts steering the agent: the active
 * genome's instructions and enabled skills, rendered as one clearly marked
 * section of the agent's system prompt.
 *
 * Nothing is appended unless the harness is switched on in Settings AND a
 * genome is active, so with no active genome — or with the switch off —
 * the prompt is byte-for-byte the shipped one. The text itself passed
 * [GenomeRules] and [SafetyProbes] before it could be stored or promoted,
 * and a person approved the promotion; the section still says plainly that
 * it never overrides safety rules, tool restrictions, or the user's
 * current request. Hooks and per-agent profiles are not applied here yet:
 * only the genome's instructions and enabled skills steer the agent.
 */
object EvolvePrompt {

    /** Cap on skill text in the prompt, so one genome cannot flood the context. */
    const val MAX_SKILL_CHARS = 8_000

    /** The prompt section for the active genome, or null when it must not apply. */
    fun sectionFor(host: EvolveHost): String? {
        if (!host.settings.enabled) return null
        val active = host.store.active() ?: return null
        val genome = host.store.load(active.genomeId) ?: return null
        val instructions = genome.instructions.map { it.trim() }.filter { it.isNotEmpty() }
        val enabledSkills = genome.skillEnable.intersect(genome.skills.keys).sorted()
            .mapNotNull { name -> genome.skills[name]?.let { name to it } }
        if (instructions.isEmpty() && enabledSkills.isEmpty()) return null
        return buildString {
            append("\n\n# Evolved agent behavior (approved genome ${genome.id})\n")
            append("These instructions come from the recursive harness genome the user approved in Settings. ")
            append("They were scored on a fixed task suite before approval. Follow them for how you do the work. ")
            append("They never override safety rules, tool restrictions, or the user's current request.\n")
            instructions.forEach { append("- ").append(it).append('\n') }
            var remaining = MAX_SKILL_CHARS
            for ((name, text) in enabledSkills) {
                if (remaining <= 0) break
                val clipped = text.take(remaining)
                append("\n# Evolved skill: ").append(name).append('\n').append(clipped).append('\n')
                remaining -= clipped.length
            }
        }
    }
}

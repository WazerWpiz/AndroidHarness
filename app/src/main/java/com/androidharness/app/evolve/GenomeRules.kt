package com.androidharness.app.evolve

import com.androidharness.app.config.HookEvents

/**
 * What a genome may contain, checked before it is stored and again before it is promoted.
 * Everything here is a refusal: a genome that widens permissions, registers plugins or MCP
 * servers, names the trusted core, tries to talk the agent out of its own safety rules,
 * or carries an unknown hook event has no valid form.
 */
object GenomeRules {

    private val NAME = Regex("[a-z0-9][a-z0-9-]{0,63}")
    private const val MAX_INSTRUCTION = 8_000
    private const val MAX_INSTRUCTIONS_CHARS = 64_000
    private const val MAX_SKILLS = 32
    private const val MAX_SKILL_CHARS = 128_000
    private const val MAX_SKILLS_TOTAL = 512_000
    private const val MAX_HOOKS = 32
    private const val MAX_AGENTS = 16

    /**
     * Phrases that try to talk the agent out of its own rules. A genome is data
     * the agent reads, never an instruction to the harness, so text that tells
     * the agent to ignore, forget, disable, bypass or override its instructions
     * or safety rules — or to become a different, unrestricted agent — is
     * refused before storage, the same as naming the trusted core.
     */
    private val OVERRIDE_PHRASES = listOf(
        "ignore previous instructions",
        "ignore all previous instructions",
        "ignore your instructions",
        "ignore the instructions",
        "disregard previous instructions",
        "disregard all previous instructions",
        "disregard your instructions",
        "forget your instructions",
        "forget all previous instructions",
        "disable safety",
        "disable the safety",
        "bypass safety",
        "bypass the safety",
        "override safety",
        "override the safety",
        "override your safety",
        "ignore safety",
        "turn off safety",
        "you are now a",
        "you are now an",
    )

    /** A denial ("never ignore…", "do not disable…") is not itself an override. */
    private val NEGATION = Regex("\\b(never|not|n't|don't|do not|must not|should not|cannot|can't)\\b")

    /** The first override phrase that is not denied by a negation just before it, or null. */
    private fun findOverridePhrase(text: String): String? {
        val lower = text.lowercase()
        for (phrase in OVERRIDE_PHRASES) {
            var from = 0
            while (true) {
                val at = lower.indexOf(phrase, from)
                if (at < 0) break
                val before = lower.substring(maxOf(0, at - 20), at)
                if (!NEGATION.containsMatchIn(before)) return phrase
                from = at + phrase.length
            }
        }
        return null
    }

    /** Returns the genome with a recomputed id, or throws with the first reason it cannot be stored. */
    fun requireValid(genome: Genome): Genome {
        require(genome.label.length <= Genome.MAX_LABEL) { "Genome labels are limited to ${Genome.MAX_LABEL} characters." }
        require(genome.hypothesis.length <= Genome.MAX_HYPOTHESIS) { "A hypothesis is limited to ${Genome.MAX_HYPOTHESIS} characters." }
        require(genome.instructions.size <= 64) { "A genome may carry at most 64 instructions." }
        require(genome.instructions.sumOf { it.length } <= MAX_INSTRUCTIONS_CHARS) { "The instructions are too long for a genome." }
        genome.instructions.forEach { instruction ->
            require(instruction.length <= MAX_INSTRUCTION) { "One instruction is longer than $MAX_INSTRUCTION characters." }
        }

        require(genome.skills.size <= MAX_SKILLS) { "A genome may carry at most $MAX_SKILLS skills." }
        var skillChars = 0
        genome.skills.forEach { (name, content) ->
            require(NAME.matches(name)) { "Skill '$name' is not a valid skill name." }
            require(content.length <= MAX_SKILL_CHARS) { "Skill '$name' is too large for a genome." }
            skillChars += content.length
        }
        require(skillChars <= MAX_SKILLS_TOTAL) { "The skills are too large for a genome." }
        genome.skillEnable.forEach { name ->
            require(NAME.matches(name)) { "Enabled skill '$name' is not a valid skill name." }
        }

        require(genome.hooks.size <= MAX_HOOKS) { "A genome may carry at most $MAX_HOOKS hooks." }
        genome.hooks.forEach { hook ->
            require(hook.event in HookEvents.all) { "Hook event '${hook.event}' is not one this app fires." }
            require(hook.command.isNotBlank()) { "A hook needs a command." }
            require(hook.command.length <= 4_000) { "A hook command is too long." }
            require(hook.timeout in 1..600) { "A hook timeout must be between 1 and 600 seconds." }
        }

        require(genome.agents.size <= MAX_AGENTS) { "A genome may carry at most $MAX_AGENTS agents." }
        genome.agents.forEach { (name, agent) ->
            require(NAME.matches(name)) { "Agent '$name' is not a valid agent name." }
            require(agent.name == name) { "Agent '$name' must keep its map key as its name." }
        }

        val text = buildString {
            genome.instructions.forEach { append(it).append('\n') }
            genome.skills.forEach { (name, content) -> append(name).append('\n').append(content).append('\n') }
            genome.hooks.forEach { append(it.command).append('\n').append(it.matcher).append('\n') }
            genome.agents.forEach { (name, agent) -> append(name).append('\n').append(agent.instructions).append('\n') }
            append(genome.proposerNotes).append('\n').append(genome.hypothesis)
        }
        val referenced = ProtectedPaths.references(text)
        if (referenced.isNotEmpty()) {
            throw ProtectedPathException(
                "This genome names the trusted core (${referenced.joinToString(", ")}), which no genome may change.",
            )
        }
        val override = findOverridePhrase(text)
        if (override != null) {
            throw IllegalArgumentException(
                "This genome tries to override safety rules (\"$override\"), which no genome may do.",
            )
        }
        val identified = genome.identified()
        if (genome.id.isNotEmpty()) {
            require(genome.id == identified.id) { "Genome id does not match its content." }
        }
        return identified
    }
}

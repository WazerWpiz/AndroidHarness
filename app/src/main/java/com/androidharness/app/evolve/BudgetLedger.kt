package com.androidharness.app.evolve

enum class BudgetRole { PROPOSER, WORKER, CRITIC }

/**
 * Shared token/cost/wall budget for one evolution run, enforced by the
 * trusted core, not by the proposer. [consume] checks before it adds, so
 * a budget can never overshoot: a failed consume changes nothing.
 */
class BudgetLedger(
    val maxTokens: Long = 100_000,
    val maxWallMs: Long = 30L * 60 * 1000,
    val maxGenerations: Int = 3,
    val maxCandidates: Int = 4,
    val stagnationLimit: Int = 2,
) {
    private val tokensByRole = mutableMapOf<BudgetRole, Long>()
    private var wallUsedMs: Long = 0
    var generationsRun: Int = 0
        private set
    var consecutiveStagnant: Int = 0
        private set

    val tokensUsed: Long get() = tokensByRole.values.sum()
    val tokensRemaining: Long get() = (maxTokens - tokensUsed).coerceAtLeast(0)
    val wallRemainingMs: Long get() = (maxWallMs - wallUsedMs).coerceAtLeast(0)

    fun tokensUsedBy(role: BudgetRole): Long = tokensByRole[role] ?: 0L

    @Synchronized
    fun consume(role: BudgetRole, tokens: Long, wallMs: Long): Boolean {
        if (tokens < 0 || wallMs < 0) return false
        if (tokensUsed + tokens > maxTokens) return false
        if (wallUsedMs + wallMs > maxWallMs) return false
        tokensByRole[role] = (tokensByRole[role] ?: 0L) + tokens
        wallUsedMs += wallMs
        return true
    }

    @Synchronized
    fun recordGeneration(improved: Boolean) {
        generationsRun++
        consecutiveStagnant = if (improved) 0 else consecutiveStagnant + 1
    }

    fun shouldStop(): Boolean =
        generationsRun >= maxGenerations || consecutiveStagnant >= stagnationLimit || wallUsedMs >= maxWallMs

    fun stopReason(): String? = when {
        generationsRun >= maxGenerations -> "generation cap reached ($maxGenerations)"
        consecutiveStagnant >= stagnationLimit -> "stagnation: $stagnationLimit generations without improvement"
        wallUsedMs >= maxWallMs -> "wall-clock budget exhausted"
        else -> null
    }
}

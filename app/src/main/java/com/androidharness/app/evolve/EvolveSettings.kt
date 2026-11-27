package com.androidharness.app.evolve

import com.androidharness.app.data.ChatModelChoice
import kotlinx.serialization.Serializable

/**
 * The caps and the budget the trusted core enforces. The feature ships off, and these
 * numbers are clamped here rather than trusted from a genome or a stored value.
 */
@Serializable
data class EvolveSettings(
    val enabled: Boolean = false,
    val maxGenerations: Int = 3,
    val maxCandidates: Int = 4,
    val maxWallMinutes: Int = 30,
    val tokenBudget: Long = 250_000L,
    val stagnationsBeforeStop: Int = 2,
    /** Success must improve by at least this much, across seeds, before a candidate can be promoted. */
    val minSuccessDelta: Double = 0.05,
    /** Tokens per success may not grow by more than this fraction. */
    val maxTokenRegression: Double = 0.15,
    /** Provider + model per role, exactly as the user picked them in Settings. Null means unconfigured. */
    val workerModel: ChatModelChoice? = null,
    val proposerModel: ChatModelChoice? = null,
    val criticModel: ChatModelChoice? = null,
) {
    fun choiceFor(role: EvolveRole): ChatModelChoice? = when (role) {
        EvolveRole.WORKER -> workerModel
        EvolveRole.PROPOSER -> proposerModel
        EvolveRole.CRITIC -> criticModel
    }
    /** Evaluation stays serial: this runs on a phone and the battery is the constraint. */
    val evalConcurrency: Int get() = 1

    fun normalized(): EvolveSettings = copy(
        maxGenerations = maxGenerations.coerceIn(1, 10),
        maxCandidates = maxCandidates.coerceIn(1, 8),
        maxWallMinutes = maxWallMinutes.coerceIn(5, 120),
        tokenBudget = tokenBudget.coerceIn(10_000L, 5_000_000L),
        stagnationsBeforeStop = stagnationsBeforeStop.coerceIn(1, 5),
        minSuccessDelta = minSuccessDelta.coerceIn(0.0, 1.0),
        maxTokenRegression = maxTokenRegression.coerceIn(0.0, 1.0),
    )

    companion object {
        val DEFAULT = EvolveSettings()
    }
}

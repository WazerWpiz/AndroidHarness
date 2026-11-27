package com.androidharness.app.evolve

import kotlinx.serialization.Serializable

/** One task's outcome. Deterministic checkers fill this; a model is never the judge. */
@Serializable
data class TaskResult(
    val taskId: String,
    val passed: Boolean,
    val detail: String = "",
    val turns: Int = 0,
    val tokens: Long = 0L,
    val holdout: Boolean = false,
)

/**
 * What one evaluation of one genome cost and scored. Scores are facts recorded by the
 * trusted evaluator, not something a candidate can report about itself.
 */
@Serializable
data class EvalReport(
    val genomeId: String,
    val suiteId: String = "core",
    val suiteVersion: Int = 1,
    val seeds: List<Int> = emptyList(),
    val tasks: List<TaskResult> = emptyList(),
    val successRate: Double = 0.0,
    val tokensPerSuccess: Double = 0.0,
    val toolValidity: Double = 0.0,
    val meanTurns: Double = 0.0,
    val safetyViolations: Int = 0,
    val wallMs: Long = 0L,
    val modelPin: String = "",
    val envPin: String = "",
    /** A scalar from the holdout split the proposer never sees. */
    val holdoutScore: Double? = null,
)

package com.androidharness.app.evolve

import kotlinx.serialization.Serializable

/**
 * One task's outcome: decided (PASS/FAIL) or undecided (ERROR/SKIPPED).
 * Only decided tasks count in the success rate (P8). ERROR means the
 * task could not be judged for an infrastructure reason (rate limit,
 * quota, timeout, a model call that failed, an unparseable critic
 * verdict); SKIPPED means it could not be attempted at all inside the
 * chosen suite (for example a real task whose command cannot run).
 */
@Serializable
enum class TaskStatus { PASS, FAIL, ERROR, SKIPPED }

/**
 * Which suite a report belongs to (P8). FULL is the live suite plus
 * the real-project suite; LITE is the live suite only. Promotion only
 * ever compares reports from the same suite — the denominators differ
 * (13 vs 17 tasks), so a lite score and a full score are never compared.
 */
@Serializable
enum class EvalSuite(val suiteId: String) {
    FULL("evolve-live-v1"),
    LITE("evolve-live-v1-lite"),
}

/** One task's outcome. Deterministic checkers fill this; a model is never the judge. */
@Serializable
data class TaskResult(
    val taskId: String,
    val passed: Boolean,
    val detail: String = "",
    val turns: Int = 0,
    val tokens: Long = 0L,
    val holdout: Boolean = false,
    /**
     * Decided or not. Null in JSON written before P8: derived from
     * [passed] (PASS when true, FAIL when false), so old reports read.
     */
    val status: TaskStatus? = null,
    /**
     * Short worker trace (P8, capped at 2000 chars): the rounds used and
     * what the worker last produced, so the proposer can see WHY a task
     * failed instead of only that it did.
     */
    val trace: String = "",
) {
    /** The status that counts: explicit when present, else PASS when [passed], FAIL otherwise. */
    val resolvedStatus: TaskStatus
        get() = status ?: if (passed) TaskStatus.PASS else TaskStatus.FAIL
}

/**
 * What one evaluation of one genome cost and scored. Scores are facts recorded by the
 * trusted evaluator, not something a candidate can report about itself.
 *
 * A report is a MEAN over its seeds when it has more than one: [successRate]
 * and [holdoutScore] are the means of the per-seed rates, [meanPassedTasks]
 * is the mean number of tasks passed, and [tasks] is the seed-1 task list
 * (the representative the screen shows). A 1-seed report is the same thing
 * with one seed, so old and new reports compare the same way.
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
    /**
     * Mean number of tasks passed across the report's seeds (P8). The
     * promotion gate compares this, not the rate: a candidate must beat
     * its parent by at least 2 tasks on average. For a 1-seed report it
     * is the passed-task count.
     */
    val meanPassedTasks: Double = 0.0,
    /**
     * "providerId/model" of the critic that judged this report's
     * critic-verified tasks (P8); "" when no critic was called. Part of
     * the baseline-cache key: a report from another critic cannot be
     * reused as if it were judged by this one.
     */
    val criticPin: String = "",
    /**
     * False when more than 20% of the suite's tasks were ERROR/SKIPPED
     * (P8). An invalid report is stored and shown, marked invalid, but
     * is NEVER used for a promotion decision or for proposer feedback.
     */
    val valid: Boolean = true,
    /** Why the report is invalid, or null. */
    val invalidReason: String? = null,
    /** The Doctor preflight that ran before this evaluation, if any (P8). */
    val doctor: DoctorResult? = null,
) {
    companion object {
        /** More than this fraction of ERROR/SKIPPED tasks makes a report invalid. */
        const val MAX_INVALID_FRACTION = 0.20

        /** Tasks that decided something: only these count in the success rate. */
        fun decidedTasks(tasks: List<TaskResult>): List<TaskResult> =
            tasks.filter { it.resolvedStatus == TaskStatus.PASS || it.resolvedStatus == TaskStatus.FAIL }

        /** Decided-only success rate: passed / (passed + failed). 0.0 when nothing decided. */
        fun successRateOf(tasks: List<TaskResult>): Double {
            val decided = decidedTasks(tasks)
            if (decided.isEmpty()) return 0.0
            return decided.count { it.resolvedStatus == TaskStatus.PASS }.toDouble() / decided.size
        }

        /** Decided-only holdout score, or null when no holdout task decided. */
        fun holdoutScoreOf(tasks: List<TaskResult>): Double? {
            val holdout = decidedTasks(tasks).filter { it.holdout }
            if (holdout.isEmpty()) return null
            return holdout.count { it.resolvedStatus == TaskStatus.PASS }.toDouble() / holdout.size
        }

        /** The INVALID rule: more than 20% ERROR/SKIPPED over the given tasks. */
        fun validityOf(tasks: List<TaskResult>): Pair<Boolean, String?> {
            if (tasks.isEmpty()) return true to null
            val undecided = tasks.count {
                it.resolvedStatus == TaskStatus.ERROR || it.resolvedStatus == TaskStatus.SKIPPED
            }
            val fraction = undecided.toDouble() / tasks.size
            return if (fraction > MAX_INVALID_FRACTION) {
                false to "%.0f%% of tasks were ERROR/SKIPPED (more than 20%%)".format(fraction * 100)
            } else {
                true to null
            }
        }
    }
}

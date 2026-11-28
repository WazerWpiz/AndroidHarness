package com.androidharness.app.evolve

/**
 * Promotion and rollback of the active genome, behind an explicit human approval.
 *
 * There is no auto-promote path and no overload that skips [Approval]: the only way to
 * make a genome active is to hand this gate a value a person's card produced. A genome is
 * re-validated here, including its protected-path scan, so one stored before a rule
 * changed cannot be promoted afterwards. Rollback restores the exact previous hash.
 */
/** The gate's verdict without an approval: whether a candidate *could* be promoted. */
sealed interface GateDecision {
    data object Approved : GateDecision
    data class Rejected(val reason: String) : GateDecision
}

class PromotionGate(private val store: GenomeStore, private val archive: Archive? = null) {

    /** Created by the approval card, never by a genome, a hook or the agent. */
    data class Approval(val approvedBy: String, val approvedAt: Long = System.currentTimeMillis()) {
        init {
            require(approvedBy.isNotBlank()) { "An approval needs the person who gave it." }
        }
    }

    companion object {
        /**
         * The promotion rule, minus the approval (P8): a candidate must be clean
         * on safety, have a VALID report (no more than 20% of its tasks
         * ERROR/SKIPPED — an invalid report is never promotable), beat its
         * baseline by at least 2 tasks on average across its seeds
         * ([EvalReport.meanPassedTasks] difference >= 2.0) AND not score
         * lower on holdout on average, and not regress tokens per success
         * by more than [EvolveSettings.maxTokenRegression]. A 1-task win
         * is never enough — it is noise, not improvement. The two reports
         * must come from the same suite and the same model pins (worker
         * and critic): a lite report and a full report have different
         * denominators and are never compared, and neither are reports
         * judged by different models. The holdout split is the part the
         * proposer never sees, so a win there is the only evidence the
         * candidate generalises instead of overfitting the training
         * tasks. A candidate with no holdout score cannot be offered at
         * all. With no baseline yet, any clean, valid candidate that
         * scores above zero on both splits qualifies. The actual
         * promotion still needs an [Approval] through [promote].
         *
         * [EvolveSettings.minSuccessDelta] is no longer used by the gate
         * since P8: the +2-task rule replaced the percentage bar. The
         * setting stays in storage so old settings still read.
         */
        fun wouldPromote(
            baseline: EvalReport?,
            candidate: EvalReport,
            safetyClean: Boolean,
            settings: EvolveSettings,
        ): GateDecision {
            if (!safetyClean || candidate.safetyViolations > 0) {
                return GateDecision.Rejected("candidate has safety violations")
            }
            if (!candidate.valid) {
                return GateDecision.Rejected("candidate report is INVALID: ${candidate.invalidReason ?: "too many tasks were ERROR/SKIPPED"}")
            }
            val candidateHoldout = candidate.holdoutScore
                ?: return GateDecision.Rejected("candidate has no holdout score")
            if (baseline == null) {
                return if (candidate.successRate > 0.0 && candidateHoldout > 0.0) GateDecision.Approved
                else GateDecision.Rejected("candidate scores nothing on the training or holdout split")
            }
            if (!baseline.valid) {
                return GateDecision.Rejected("baseline report is INVALID: ${baseline.invalidReason ?: "too many tasks were ERROR/SKIPPED"}")
            }
            if (baseline.suiteId != candidate.suiteId) {
                return GateDecision.Rejected(
                    "cannot compare ${baseline.suiteId} with ${candidate.suiteId}: promotion only within the same suite",
                )
            }
            if (baseline.modelPin != candidate.modelPin) {
                return GateDecision.Rejected(
                    "cannot compare model pins ${baseline.modelPin} with ${candidate.modelPin}: re-evaluate both with the same models",
                )
            }
            if (baseline.criticPin.isNotBlank() && candidate.criticPin.isNotBlank() &&
                baseline.criticPin != candidate.criticPin
            ) {
                return GateDecision.Rejected(
                    "cannot compare critic pins ${baseline.criticPin} with ${candidate.criticPin}: re-evaluate both with the same models",
                )
            }
            val taskDelta = candidate.meanPassedTasks - baseline.meanPassedTasks
            if (taskDelta < 2.0) {
                return GateDecision.Rejected(
                    "improvement %.1f tasks is below the 2-task bar".format(taskDelta),
                )
            }
            val baselineHoldout = baseline.holdoutScore
                ?: return GateDecision.Rejected("baseline has no holdout score to beat")
            if (candidateHoldout < baselineHoldout) {
                return GateDecision.Rejected(
                    "holdout %.0f%% is below the baseline %.0f%%".format(candidateHoldout * 100, baselineHoldout * 100),
                )
            }
            if (baseline.tokensPerSuccess > 0.0 &&
                candidate.tokensPerSuccess > baseline.tokensPerSuccess * (1.0 + settings.maxTokenRegression)
            ) {
                return GateDecision.Rejected("tokens per success regressed beyond the allowed %.0f%%".format(settings.maxTokenRegression * 100))
            }
            return GateDecision.Approved
        }
    }

    fun promote(genomeId: String, approval: Approval): Result<Genome> = runCatching {
        val genome = store.load(genomeId) ?: throw IllegalArgumentException("Genome $genomeId is not stored.")
        GenomeRules.requireValid(genome)
        val entry = archive?.entries()?.firstOrNull { it.genomeId == genomeId }
        if (entry != null) {
            require(entry.safetyViolations == 0) { "A genome with safety violations cannot be promoted." }
            require(entry.status != ArchiveStatus.VETOED) { "A vetoed genome cannot be promoted." }
        }
        val previous = store.active()?.genomeId
        store.activate(genomeId, previous, approval.approvedAt, approval.approvedBy)
        archive?.let { history ->
            previous?.let { history.setStatus(it, ArchiveStatus.ARCHIVED) }
            if (entry != null) history.setStatus(genomeId, ArchiveStatus.ACTIVE)
        }
        genome
    }

    /** One tap back to the genome that was active before this one, verified by hash. */
    fun rollback(approval: Approval): Result<Genome> = runCatching {
        val active = store.active() ?: throw IllegalStateException("No genome is active.")
        val targetId = active.previous ?: store.load(active.genomeId)?.parent
            ?: throw IllegalStateException("The active genome has no parent to roll back to.")
        val target = store.load(targetId) ?: throw IllegalStateException("Genome $targetId is not stored.")
        GenomeRules.requireValid(target)
        store.activate(target.id, null, approval.approvedAt, approval.approvedBy)
        archive?.let { history ->
            history.setStatus(active.genomeId, ArchiveStatus.ARCHIVED)
            history.setStatus(target.id, ArchiveStatus.ACTIVE)
        }
        target
    }
}

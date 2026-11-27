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
         * The promotion rule, minus the approval: a candidate must be clean
         * on safety, beat its baseline by at least [EvolveSettings.minSuccessDelta],
         * and not regress tokens per success by more than
         * [EvolveSettings.maxTokenRegression]. With no baseline yet, any clean
         * candidate that scores above zero qualifies. The actual promotion
         * still needs an [Approval] through [promote].
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
            if (baseline == null) {
                return if (candidate.successRate > 0.0) GateDecision.Approved
                else GateDecision.Rejected("candidate scores nothing")
            }
            val delta = candidate.successRate - baseline.successRate
            if (delta < settings.minSuccessDelta) {
                return GateDecision.Rejected("improvement %.1f points is below the %.1f-point bar".format(delta * 100, settings.minSuccessDelta * 100))
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

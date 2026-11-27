package com.androidharness.app.tools

import com.androidharness.app.evolve.EvalHarness
import com.androidharness.app.evolve.EvolveHost
import com.androidharness.app.evolve.Genome
import com.androidharness.app.evolve.TraceMiner
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Read-only tools for the recursive harness, plus propose and evaluate
 * from the runner. There is deliberately no `evolve_promote`: promotion
 * is a settings action behind an approval card, never something the agent
 * can call. Propose and evaluate only write candidates, reports and
 * archive entries; nothing they do can make a genome active.
 */
internal abstract class EvolveReadTool(protected val host: EvolveHost) : Tool {
    override val isReadOnly = true
}

internal class EvolveStatusTool(host: EvolveHost) : EvolveReadTool(host) {
    override val name = "evolve_status"
    override val description =
        "Report the recursive harness: whether it is on, the active genome, how many genomes " +
            "the archive holds, and the caps a run would use. Read-only."
    override val parametersSchema = Schema.obj(emptyMap())

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val active = host.store.active()
        val genome = active?.let { host.store.load(it.genomeId) }
        val entries = host.archive.entries()
        val settings = host.settings
        val text = buildString {
            append("Recursive harness: ").append(if (settings.enabled) "on" else "off (this is the default)")
            append('\n').append("Active genome: ")
            append(genome?.let { "${it.id} (${it.label.ifBlank { "unnamed" }})" } ?: "none")
            active?.let { append("\nActivated: ").append(it.activatedAt).append(if (it.approvedBy.isBlank()) "" else " by ${it.approvedBy}") }
            append('\n').append("Archive: ").append(entries.size).append(" genomes")
            val evaluated = entries.count { it.successRate != null }
            if (evaluated > 0) append(" (").append(evaluated).append(" evaluated)")
            append("\nCaps: ").append(settings.maxGenerations).append(" generations, ")
                .append(settings.maxCandidates).append(" candidates per generation, ")
                .append(settings.maxWallMinutes).append(" min wall clock, ")
                .append(settings.tokenBudget).append(" tokens, one evaluation at a time")
            append("\nWeights are never modified. Safety checks are never evolved.")
        }
        return ToolResult(true, text)
    }
}

internal class EvolveHistoryTool(host: EvolveHost) : EvolveReadTool(host) {
    override val name = "evolve_history"
    override val description =
        "List genomes in the recursive harness archive, newest first, with their lineage, " +
            "score and status. Read-only."
    override val parametersSchema = Schema.obj(
        mapOf(
            "offset" to Schema.integer("Entries to skip; default 0."),
            "limit" to Schema.integer("Entries to show; default 20, at most 100."),
        ),
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val offset = args["offset"]?.jsonPrimitive?.content?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val limit = (args["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 20).coerceIn(1, 100)
        val all = host.archive.entries().sortedByDescending { it.createdAt }
        if (all.isEmpty()) return ToolResult(true, "The recursive harness archive is empty.")
        val page = all.drop(offset).take(limit)
        val text = buildString {
            append("Genomes ").append(offset + 1).append("–").append(offset + page.size)
                .append(" of ").append(all.size).append(":\n")
            page.forEach { entry ->
                append("- ").append(entry.genomeId)
                if (entry.label.isNotBlank()) append(" ").append(entry.label)
                append(" [").append(entry.status.name.lowercase()).append(']')
                entry.successRate?.let { append(" success ").append("%.0f%%".format(it * 100)) }
                entry.tokensPerSuccess?.let { append(" · ").append("%.0f".format(it)).append(" tokens/success") }
                entry.parent?.let { append(" · parent ").append(it) }
                if (entry.hypothesis.isNotBlank()) append("\n  ").append(entry.hypothesis.take(200))
                append('\n')
            }
            if (offset + page.size < all.size) append("Use offset=").append(offset + page.size).append(" for older genomes.")
        }
        return ToolResult(true, text.trimEnd())
    }
}

internal class EvolveDiffTool(host: EvolveHost) : EvolveReadTool(host) {
    override val name = "evolve_diff"
    override val description =
        "Show what changed in the genome between two archived genomes, by id. Read-only."
    override val parametersSchema = Schema.obj(
        mapOf(
            "from_id" to Schema.string("The older genome id."),
            "to_id" to Schema.string("The newer genome id."),
        ),
        required = listOf("from_id", "to_id"),
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val fromId = args["from_id"]?.jsonPrimitive?.content?.trim().orEmpty()
        val toId = args["to_id"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (fromId.isBlank() || toId.isBlank()) return ToolResult(false, "Both from_id and to_id are required.")
        val from = host.store.load(fromId) ?: return ToolResult(false, "Genome $fromId is not stored.")
        val to = host.store.load(toId) ?: return ToolResult(false, "Genome $toId is not stored.")
        return ToolResult(true, "Genome $fromId -> $toId\n" + from.diff(to))
    }
}

internal class EvolveProposeTool(host: EvolveHost) : EvolveReadTool(host) {
    override val name = "evolve_propose"
    override val isReadOnly = false
    override val description =
        "Run one evolution generation from the active genome (or the shipped baseline): " +
            "propose template candidates, evaluate them on the static suite, run the safety " +
            "probes and archive the results. Returns the candidate ids and the one that " +
            "could be promoted. Never promotes: promotion is a settings action behind an " +
            "approval card, never something the agent can call."
    override val parametersSchema = Schema.obj(emptyMap())

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val host = this@EvolveProposeTool.host
        val parent = host.store.active()?.let { host.store.load(it.genomeId) }
            ?: host.store.load(Genome.baseline().id)
            ?: host.store.save(Genome.baseline())
        val histories = host.archive.entries().mapNotNull { it.hypothesis.ifBlank { null } }
        val diagnosis = TraceMiner.mine(histories)
        val workDir = File(host.store.root, "work").apply { mkdirs() }
        val result = host.newRun(workDir).runGeneration(parent, diagnosis, workDir)
        val text = buildString {
            append("Generation from parent ${parent.id} (${parent.label.ifBlank { "unnamed" }}): ")
            append(result.candidateIds.size).append(" candidates")
            if (result.reports.isNotEmpty()) {
                append(", best ")
                val best = result.reports.maxByOrNull { it.successRate }
                append("%.0f%%".format((best?.successRate ?: 0.0) * 100))
            }
            result.promotableId?.let { append("\nPromotable (needs your approval in Settings): ").append(it) }
                ?: append("\nNo candidate met the promotion bar.")
            result.stoppedReason?.let { append("\nStopped: ").append(it) }
        }
        ToolResult(true, text)
    }
}

internal class EvolveEvaluateTool(host: EvolveHost) : EvolveReadTool(host) {
    override val name = "evolve_evaluate"
    override val isReadOnly = false
    override val description =
        "Evaluate one stored genome on the static suite (8 tasks, 2 seeds) and run the " +
            "safety probes. Saves the report and returns the score. Never promotes."
    override val parametersSchema = Schema.obj(
        mapOf("genome_id" to Schema.string("The stored genome id to evaluate.")),
        required = listOf("genome_id"),
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val host = this@EvolveEvaluateTool.host
        val genomeId = args["genome_id"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (genomeId.isBlank()) return@withContext ToolResult(false, "genome_id is required.")
        val genome = host.store.load(genomeId) ?: return@withContext ToolResult(false, "Genome $genomeId is not stored.")
        val workDir = File(host.store.root, "work").apply { mkdirs() }
        val evalDir = File(workDir, "eval-$genomeId").apply { mkdirs() }
        val report = EvalHarness.evaluate(genome, workDir = evalDir)
        val violations = com.androidharness.app.evolve.SafetyProbes.safetyViolations(genome, evalDir)
        val final = report.copy(safetyViolations = violations)
        host.store.saveReport(final)
        val failed = final.tasks.filter { !it.passed }
        val text = buildString {
            append("Genome $genomeId: %.0f%%".format(final.successRate * 100))
            append(" success (${final.tasks.count { it.passed }}/${final.tasks.size} tasks)")
            final.holdoutScore?.let { append(", holdout %.0f%%".format(it * 100)) }
            append(", $violations safety violations")
            if (failed.isNotEmpty()) {
                append("\nFailed:")
                failed.forEach { append("\n- ").append(it.taskId).append(": ").append(it.detail) }
            }
            if (violations > 0) append("\nVetoed: a candidate with safety violations cannot be promoted.")
        }
        ToolResult(true, text)
    }
}

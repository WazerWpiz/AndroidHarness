package com.androidharness.app.tools

import com.androidharness.app.evolve.EvolveHost
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Read-only tools for the recursive harness. There is deliberately no `evolve_promote`:
 * promotion is a settings action behind an approval card, never something the agent can
 * call. Propose and evaluate arrive with the runner; until then these three only report.
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

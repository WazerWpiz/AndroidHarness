package com.androidharness.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.evolve.ArchiveEntry
import com.androidharness.app.evolve.EvolveHost
import com.androidharness.app.evolve.Genome
import com.androidharness.app.evolve.PromotionGate
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → Coding → Recursive harness. P0 was a read-only view; the
 * runner adds a Run button and an approval card. A run proposes template
 * candidates (no model is called yet), evaluates them on the static suite,
 * runs the safety probes and archives the results. Only a candidate that
 * meets the gate rule is offered for promotion, and promotion still needs
 * a tap on the approval card below — never automatic, never from a tool.
 */
@Composable
internal fun RecursiveHarnessSection() {
    val context = LocalContext.current
    val host = remember(context) { EvolveHost.at(File(context.filesDir, "evolve")) }
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<ArchiveEntry>>(emptyList()) }
    var activeLabel by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    var runStatus by remember { mutableStateOf<String?>(null) }
    var promotableId by remember { mutableStateOf<String?>(null) }
    var promotableText by remember { mutableStateOf<String?>(null) }
    var runError by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        withContext(Dispatchers.IO) {
            entries = host.archive.entries().sortedByDescending { it.createdAt }
            activeLabel = host.store.active()?.let { active ->
                host.store.load(active.genomeId)?.label?.ifBlank { active.genomeId }
            }
        }
    }

    LaunchedEffect(host) { refresh() }

    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.recursive_harness_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.recursive_harness_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.recursive_harness_active), fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium)
                Text(activeLabel ?: stringResource(R.string.recursive_harness_none), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                stringResource(R.string.recursive_harness_caps),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Run controls: one evolution run, then an approval card for the winner.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = {
                        if (running) return@Button
                        running = true
                        runStatus = null
                        promotableId = null
                        promotableText = null
                        runError = null
                        scope.launch {
                            val outcome = runCatching {
                                withContext(Dispatchers.Default) {
                                    val parent = host.store.active()?.let { host.store.load(it.genomeId) }
                                        ?: host.store.load(Genome.baseline().id)
                                        ?: host.store.save(Genome.baseline())
                                    val result = host.newRun().run(parent, File(context.filesDir, "evolve/work"))
                                    result to parent
                                }
                            }
                            outcome.onSuccess { (result, _) ->
                                val best = result.generations.flatMap { it.reports }.maxByOrNull { it.successRate }
                                runStatus = buildString {
                                    append(result.generations.size).append(" generation(s), ")
                                    append(result.generations.sumOf { it.candidateIds.size }).append(" candidate(s)")
                                    best?.let { append(", best %.0f%%".format(it.successRate * 100)) }
                                    result.stoppedReason?.let { append(" — stopped: ").append(it) }
                                }
                                result.promotableId?.let { id ->
                                    val genome = host.store.load(id)
                                    val report = host.store.report(id)
                                    promotableId = id
                                    promotableText = buildString {
                                        append(genome?.label?.ifBlank { id } ?: id)
                                        report?.let { append(" — %.0f%%".format(it.successRate * 100)) }
                                    }
                                } ?: run {
                                    runStatus = (runStatus ?: "") + " — " + context.getString(R.string.recursive_harness_no_candidate)
                                }
                                refresh()
                            }.onFailure {
                                runError = it.message ?: context.getString(R.string.recursive_harness_run_failed)
                            }
                            running = false
                        }
                    },
                    enabled = !running,
                ) {
                    Text(stringResource(R.string.recursive_harness_run))
                }
                if (running) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.recursive_harness_running), style = MaterialTheme.typography.bodyMedium)
                }
            }
            runStatus?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            runError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            promotableId?.let { id ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.recursive_harness_promotable),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        promotableText ?: id,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            scope.launch {
                                val result = runCatching {
                                    withContext(Dispatchers.Default) {
                                        host.gate.promote(id, PromotionGate.Approval("you"))
                                    }
                                }.getOrElse { Result.failure(it) }
                                if (result.isSuccess) {
                                    promotableId = null
                                    promotableText = null
                                    runStatus = null
                                    refresh()
                                } else {
                                    runError = result.exceptionOrNull()?.message
                                        ?: context.getString(R.string.recursive_harness_run_failed)
                                }
                            }
                        }) {
                            Text(stringResource(R.string.recursive_harness_promote))
                        }
                        TextButton(onClick = { promotableId = null; promotableText = null }) {
                            Text(stringResource(R.string.recursive_harness_dismiss))
                        }
                    }
                }
            }

            if (entries.isEmpty()) {
                Text(stringResource(R.string.recursive_harness_empty), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                entries.take(20).forEach { entry ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            entry.label.ifBlank { entry.genomeId },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            buildString {
                                append(entry.status.name.lowercase().replaceFirstChar { it.uppercase() })
                                append(" · ").append(entry.genomeId)
                                entry.successRate?.let { append(" · ").append("%.0f%%".format(it * 100)).append(" success") }
                                entry.parent?.let { append(" · parent ").append(it) }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (entry.hypothesis.isNotBlank()) {
                            Text(entry.hypothesis, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Text(
                stringResource(R.string.recursive_harness_rule),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.data.ChatModelChoice
import com.androidharness.app.evolve.ArchiveEntry
import com.androidharness.app.evolve.EvolveRole
import com.androidharness.app.evolve.Genome
import com.androidharness.app.evolve.PromotionGate
import com.androidharness.app.llm.effectiveApiKey
import com.androidharness.app.ui.chat.components.ModelPickerSheet
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → Coding → Recursive harness. The runner is real: the proposer
 * model writes candidate genomes, the worker model does the live benchmark
 * tasks under each candidate, and the critic verifies the results. A run
 * proposes, evaluates and archives; only a candidate that meets the gate
 * rule is offered for promotion, and promotion still needs a tap on the
 * approval card below — never automatic, never from a tool. If no model
 * is configured for the proposer or the worker, the run refuses instead
 * of scoring anything fake.
 */
@Composable
internal fun RecursiveHarnessSection(container: AppContainer) {
    val context = LocalContext.current
    val host = container.evolveHost
    val scope = rememberCoroutineScope()
    val providers by container.providers.providers.collectAsStateWithLifecycle(initialValue = emptyList())
    val catalogs by container.providers.catalogs.collectAsStateWithLifecycle(initialValue = emptyMap())
    var evolveSettings by remember { mutableStateOf(host.settings) }
    var pickingRole by remember { mutableStateOf<EvolveRole?>(null) }
    var entries by remember { mutableStateOf<List<ArchiveEntry>>(emptyList()) }
    var activeLabel by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    var runStatus by remember { mutableStateOf<String?>(null) }
    var promotableId by remember { mutableStateOf<String?>(null) }
    var promotableText by remember { mutableStateOf<String?>(null) }
    var runError by remember { mutableStateOf<String?>(null) }

    fun modelLabel(choice: ChatModelChoice?): String {
        if (choice == null) return context.getString(R.string.recursive_harness_model_fallback)
        val name = providers.firstOrNull { it.id == choice.providerId }?.name ?: choice.providerId
        return "$name · ${choice.model}"
    }

    fun roleTitle(role: EvolveRole): String = when (role) {
        EvolveRole.PROPOSER -> context.getString(R.string.recursive_harness_proposer)
        EvolveRole.WORKER -> context.getString(R.string.recursive_harness_worker)
        EvolveRole.CRITIC -> context.getString(R.string.recursive_harness_critic)
    }

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

            // Role models: a real model per role, picked here. Unset means the
            // app's active provider and model, like the planner does.
            listOf(EvolveRole.PROPOSER, EvolveRole.WORKER, EvolveRole.CRITIC).forEach { role ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        roleTitle(role),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        modelLabel(evolveSettings.choiceFor(role)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(2f),
                    )
                    TextButton(onClick = { pickingRole = role }, enabled = !running) {
                        Text(stringResource(R.string.recursive_harness_change))
                    }
                }
            }

            // Run controls: one evolution run, then an approval card for the winner.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = {
                        if (running) return@Button
                        val caller = host.modelCaller
                        if (caller == null) {
                            runError = context.getString(R.string.recursive_harness_run_failed)
                            return@Button
                        }
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
                                    val result = host.newRun(caller, File(context.filesDir, "evolve/work"))
                                        .run(parent, File(context.filesDir, "evolve/work"))
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
                                    val failures = result.generations.flatMap { it.proposalFailures }
                                    if (failures.isNotEmpty()) append(" — proposals failed: ").append(failures.joinToString("; ").take(200))
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

    pickingRole?.let { role ->
        val current = evolveSettings.choiceFor(role)
        ModelPickerSheet(
            providers = providers,
            activeProviderId = current?.providerId,
            activeModel = current?.model,
            catalogs = catalogs,
            onDismiss = { pickingRole = null },
            onSelect = { providerId, model ->
                val chosen = model
                    ?: providers.firstOrNull { it.id == providerId }?.model
                    ?: return@ModelPickerSheet
                val updated = when (role) {
                    EvolveRole.PROPOSER -> evolveSettings.copy(proposerModel = ChatModelChoice(providerId, chosen))
                    EvolveRole.WORKER -> evolveSettings.copy(workerModel = ChatModelChoice(providerId, chosen))
                    EvolveRole.CRITIC -> evolveSettings.copy(criticModel = ChatModelChoice(providerId, chosen))
                }
                host.updateSettings(updated)
                evolveSettings = updated
                pickingRole = null
            },
            onRefreshCatalog = { providerId ->
                val provider = providers.firstOrNull { it.id == providerId }
                val key = container.providers.apiKey(providerId)
                when {
                    provider == null -> context.getString(R.string.ui_unknown_provider)
                    else -> {
                        val usable = provider.effectiveApiKey(key)
                        if (usable.isBlank()) context.getString(R.string.ui_no_api_key_for_this_provider)
                        else when (val result = com.androidharness.app.llm.ModelCatalog.listModels(provider, usable)) {
                            is com.androidharness.app.llm.ModelCatalog.Result.Models -> {
                                container.providers.saveCatalog(providerId, result.models)
                                null
                            }
                            is com.androidharness.app.llm.ModelCatalog.Result.Failed -> result.message
                        }
                    }
                }
            },
            onAddCustomModel = { providerId, model, reasoning ->
                scope.launch { container.providers.addCustomModel(providerId, model, reasoning) }
            },
            onDeleteCustomModel = { providerId, model ->
                scope.launch { container.providers.removeCustomModel(providerId, model) }
            },
            onEnableProvider = { id -> scope.launch { container.providers.setEnabled(id, true) } },
            onManageProviders = { pickingRole = null },
        )
    }
}

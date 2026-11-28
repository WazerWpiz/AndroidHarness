package com.androidharness.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Switch
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
import com.androidharness.app.evolve.Archive
import com.androidharness.app.evolve.ArchiveEntry
import com.androidharness.app.evolve.Doctor
import com.androidharness.app.evolve.DoctorStatus
import com.androidharness.app.evolve.EvalReport
import com.androidharness.app.evolve.EvalSuite
import com.androidharness.app.evolve.EvolveRole
import com.androidharness.app.evolve.EvolveRunPhase
import com.androidharness.app.evolve.GenomeBackup
import com.androidharness.app.evolve.LastRun
import com.androidharness.app.evolve.PromotionGate
import com.androidharness.app.evolve.RealProjectEvaluator
import com.androidharness.app.evolve.TaskStatus
import com.androidharness.app.llm.effectiveApiKey
import com.androidharness.app.ui.chat.components.ModelPickerSheet
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    // The active genome's id, for the per-row delete guard (P9).
    var activeId by remember { mutableStateOf<String?>(null) }
    // The run lives in the host, not in this screen: leaving Settings does
    // not stop it, and coming back shows its live state.
    val runState by host.runState.collectAsStateWithLifecycle(initialValue = null)
    val running = runState?.running == true
    var localError by remember { mutableStateOf<String?>(null) }
    var promotableText by remember { mutableStateOf<String?>(null) }
    var backupMessage by remember { mutableStateOf<String?>(null) }
    var backupBusy by remember { mutableStateOf(false) }
    // Toolchain (P7): what the test runner reports right now. Read once
    // at composition and again on refresh; the runner's getter reads the
    // Linux environment marker live, so no restart is needed to pick up
    // an install.
    var toolchainReady by remember { mutableStateOf(RealProjectEvaluator.commandRunner.toolchainReady) }
    // Full run report (P7, persisted in P8): the latest run's best
    // candidate — the promotable one when there is one, else the newest
    // archived candidate — with its per-task report and its parent's
    // scores. The pointer lives in storage (last-run.json), so the
    // block loads after an app restart with no run this session.
    var reportEntry by remember { mutableStateOf<ArchiveEntry?>(null) }
    var reportCandidate by remember { mutableStateOf<EvalReport?>(null) }
    var reportParent by remember { mutableStateOf<EvalReport?>(null) }
    var lastRun by remember { mutableStateOf<LastRun?>(null) }
    // Doctor preflight (P8): the latest check results, for the checklist.
    val doctor by host.doctorState.collectAsStateWithLifecycle(initialValue = null)
    var doctorBusy by remember { mutableStateOf(false) }

    LaunchedEffect(runState?.promotableId) {
        val id = runState?.promotableId
        promotableText = if (id == null) null else withContext(Dispatchers.IO) {
            val genome = host.store.load(id)
            val report = host.store.report(id)
            buildString {
                append(genome?.label?.ifBlank { id } ?: id)
                report?.let { append(" — %.0f%%".format(it.successRate * 100)) }
            }
        }
    }

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
            activeId = host.store.active()?.genomeId
            activeLabel = host.store.active()?.let { active ->
                host.store.load(active.genomeId)?.label?.ifBlank { active.genomeId }
            }
            toolchainReady = RealProjectEvaluator.commandRunner.toolchainReady
            lastRun = host.store.lastRun()
        }
    }

    // Full run report (P7, persisted in P8): the candidate comes from
    // the stored last-run pointer, falling back to the newest archived
    // candidate when the last run produced none; nothing when the
    // archive is empty. The block's EXISTENCE no longer depends on the
    // session's run state — only the P6 live-progress block does.
    val reportGenomeId = lastRun?.candidateId ?: entries.firstOrNull()?.genomeId
    LaunchedEffect(reportGenomeId) {
        if (reportGenomeId == null) {
            reportEntry = null
            reportCandidate = null
            reportParent = null
        } else {
            withContext(Dispatchers.IO) {
                val entry = host.archive.entries().firstOrNull { it.genomeId == reportGenomeId }
                reportEntry = entry
                reportCandidate = host.store.report(reportGenomeId)
                reportParent = entry?.parent?.let { host.store.report(it) }
            }
        }
    }

    LaunchedEffect(host) { refresh() }

    // The archive gains entries while a run is going; reload when it ends.
    LaunchedEffect(runState?.running) {
        if (runState?.running == false && runState != null) refresh()
    }

    // Live progress (P6): the elapsed clock ticks once a second from the
    // run's start, which the host records; nothing here is estimated.
    var elapsedSeconds by remember { mutableStateOf(0L) }
    LaunchedEffect(running, runState?.startedAtMs) {
        val start = runState?.startedAtMs
        if (!running || start == null) return@LaunchedEffect
        while (true) {
            elapsedSeconds = (System.currentTimeMillis() - start) / 1000
            delay(1000)
        }
    }

    // Backup (P5): one JSON file with every genome, its report, the
    // archive and the active pointer, saved outside the app through the
    // system file picker — so a reinstall can never wipe a genome.
    // Import restores only through the existing checks and never
    // activates anything; a tampered file is refused with a clear message.
    val suggestedBackupName = remember {
        "androidharness-genomes-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".json"
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        backupMessage = null
        localError = null
        backupBusy = true
        scope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) {
                    val backup = GenomeBackup.build(host.store, host.archive)
                    GenomeBackup.encode(backup) to backup
                }
                withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use {
                        it.write(prepared.first)
                    }
                }
                backupMessage = context.getString(
                    R.string.recursive_harness_export_done,
                    prepared.second.genomes.size, prepared.second.reports.size, prepared.second.entries.size,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                localError = context.getString(R.string.recursive_harness_backup_failed, e.message ?: "")
            } finally {
                backupBusy = false
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        backupMessage = null
        localError = null
        backupBusy = true
        scope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    val stream = context.contentResolver.openInputStream(uri)
                        ?: throw IllegalArgumentException(context.getString(R.string.recursive_harness_backup_open_failed))
                    val backup = GenomeBackup.decode(GenomeBackup.readBackupText(stream))
                    GenomeBackup.importInto(host.store, host.archive, backup)
                }
                backupMessage = buildString {
                    append(context.getString(
                        R.string.recursive_harness_import_done,
                        outcome.importedGenomes, outcome.importedReports, outcome.importedEntries, outcome.skippedGenomes,
                    ))
                    if (outcome.refusedGenomes.isNotEmpty()) {
                        append(" ")
                        append(context.getString(
                            R.string.recursive_harness_import_refused,
                            outcome.refusedGenomes.take(3).joinToString("; "),
                        ))
                    }
                }
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                localError = context.getString(R.string.recursive_harness_backup_failed, e.message ?: "")
            } finally {
                backupBusy = false
            }
        }
    }

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

            // The on/off switch: when on, the agent follows the active
            // genome's instructions and enabled skills; when off, the agent
            // behaves as shipped. Runs work either way — evolving only
            // produces candidates, it never changes agent behavior by itself.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(R.string.recursive_harness_enabled),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.recursive_harness_enabled_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = evolveSettings.enabled,
                    onCheckedChange = { checked ->
                        val updated = evolveSettings.copy(enabled = checked)
                        host.updateSettings(updated)
                        evolveSettings = updated
                    },
                )
            }

            // Score text for the report block: "75% (holdout 67%)".
            fun scoreText(successRate: Double, holdoutScore: Double?): String = buildString {
                append("%.0f%%".format(successRate * 100))
                holdoutScore?.let { append(" (holdout ").append("%.0f%%".format(it * 100)).append(")") }
            }

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

            // Toolchain (P7): what the test runner reports — never a claim
            // that the toolchain was verified working. When it is missing,
            // the run uses the lite suite (13 live tasks, no real tasks).
            Text(
                stringResource(
                    if (toolchainReady) R.string.recursive_harness_toolchain_ready
                    else R.string.recursive_harness_toolchain_missing,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Doctor preflight (P8): the checklist from the last run or
            // the last manual check. Each row shows what was checked and
            // how it came out; a Fix button appears where a fix exists
            // and reuses the existing flows — the Linux environment
            // install/update for toolchain items, the model picker for
            // key and ping items. A failed model ping blocks a run; a
            // missing toolchain switches it to the lite suite.
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(R.string.recursive_harness_doctor_title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            if (doctorBusy || running) return@TextButton
                            val caller = host.modelCaller
                            if (caller == null) {
                                localError = context.getString(R.string.recursive_harness_run_failed)
                                return@TextButton
                            }
                            doctorBusy = true
                            localError = null
                            scope.launch {
                                try {
                                    withContext(Dispatchers.Default) {
                                        host.runDoctor(caller, File(context.filesDir, "evolve/work"))
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    localError = e.message
                                } finally {
                                    doctorBusy = false
                                }
                            }
                        },
                        enabled = !doctorBusy && !running,
                    ) {
                        Text(
                            stringResource(
                                if (doctorBusy) R.string.recursive_harness_doctor_checking
                                else R.string.recursive_harness_doctor_run,
                            ),
                        )
                    }
                }
                val doctorResult = doctor
                if (doctorResult == null) {
                    Text(
                        stringResource(R.string.recursive_harness_doctor_never),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        context.getString(
                            R.string.recursive_harness_doctor_suite,
                            if (doctorResult.suiteId == EvalSuite.LITE.suiteId) "lite" else "full",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    doctorResult.blockReason?.let {
                        Text(
                            context.getString(R.string.recursive_harness_doctor_blocked, it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    doctorResult.checks.forEach { check ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                buildString {
                                    append(check.title).append(" — ")
                                    append(
                                        when (check.status) {
                                            DoctorStatus.PASS -> "OK"
                                            DoctorStatus.FAIL -> "failed"
                                            DoctorStatus.WARN -> "warning"
                                            DoctorStatus.UNKNOWN -> "unknown"
                                        },
                                    )
                                    check.value?.let { append(" (").append(it).append(")") }
                                    if (check.detail.isNotBlank()) append(": ").append(check.detail.take(200))
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            if (check.status == DoctorStatus.FAIL || check.status == DoctorStatus.WARN) {
                                val role = Doctor.roleForCheck(check.id)
                                when {
                                    Doctor.isToolchainCheck(check.id) -> TextButton(
                                        onClick = {
                                            scope.launch {
                                                try {
                                                    withContext(Dispatchers.IO) {
                                                        val linuxEnv = container.linuxEnv
                                                        if (!linuxEnv.isReady) {
                                                            linuxEnv.install(linuxEnv.fullPackages).getOrThrow()
                                                        } else {
                                                            linuxEnv.updateEnvironment()
                                                        }
                                                    }
                                                    refresh()
                                                } catch (e: CancellationException) {
                                                    throw e
                                                } catch (e: Exception) {
                                                    localError = e.message
                                                }
                                            }
                                        },
                                        enabled = !running,
                                    ) {
                                        Text(stringResource(R.string.recursive_harness_doctor_fix))
                                    }
                                    role != null -> TextButton(
                                        onClick = { pickingRole = role },
                                        enabled = !running,
                                    ) {
                                        Text(stringResource(R.string.recursive_harness_doctor_fix))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Run controls: one evolution run, then an approval card for the winner.
            // The run lives in the host (app scope): leaving this screen does
            // not stop it, and coming back shows its live state.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = {
                        if (running) return@Button
                        val caller = host.modelCaller
                        if (caller == null) {
                            localError = context.getString(R.string.recursive_harness_run_failed)
                            return@Button
                        }
                        localError = null
                        host.startRun(caller, File(context.filesDir, "evolve/work"))
                    },
                    enabled = !running,
                ) {
                    Text(stringResource(R.string.recursive_harness_run))
                }
                if (running) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.recursive_harness_running), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { host.cancelRun() }) {
                        Text(stringResource(R.string.recursive_harness_stop))
                    }
                }
            }
            runState?.summary?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Live progress (P6): shown only while the run is going, from
            // the host's state — so leaving Settings and coming back shows
            // the same live state. Counts and the current phase only: no
            // percent-complete and no ETA, because the total work is not
            // knowable in advance (budget, stagnation and quota can stop a
            // run early). The final summary above stays once it finishes.
            if (running) {
                runState?.let { progress ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            buildString {
                                append(
                                    context.getString(
                                        R.string.recursive_harness_progress_generation,
                                        progress.generation, progress.maxGenerations,
                                    ),
                                )
                                append(" · ")
                                append(
                                    when (progress.phase) {
                                        EvolveRunPhase.CHECKING -> context.getString(R.string.recursive_harness_progress_checking)
                                        EvolveRunPhase.PROPOSING -> context.getString(R.string.recursive_harness_progress_proposing)
                                        EvolveRunPhase.EVALUATING -> context.getString(R.string.recursive_harness_progress_evaluating)
                                        EvolveRunPhase.SCORING -> context.getString(R.string.recursive_harness_progress_scoring)
                                        EvolveRunPhase.DONE, null -> context.getString(R.string.recursive_harness_progress_proposing)
                                    },
                                )
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            buildString {
                                append(
                                    context.getString(
                                        R.string.recursive_harness_progress_candidate,
                                        progress.candidateIndex.coerceAtLeast(1), progress.maxCandidates,
                                    ),
                                )
                                val name = progress.candidateLabel?.ifBlank { null } ?: progress.candidateId
                                if (name != null) {
                                    append(" · ").append(name)
                                }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            buildString {
                                append(
                                    context.getString(
                                        R.string.recursive_harness_progress_tokens,
                                        progress.tokensUsed, progress.tokenBudget,
                                    ),
                                )
                                append(" · ")
                                append(
                                    context.getString(
                                        R.string.recursive_harness_progress_elapsed,
                                        "%02d:%02d".format(elapsedSeconds / 60, elapsedSeconds % 60),
                                    ),
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (progress.proposalFailures > 0) {
                            Text(
                                buildString {
                                    append(
                                        context.getString(
                                            R.string.recursive_harness_progress_failures,
                                            progress.proposalFailures,
                                        ),
                                    )
                                    progress.lastFailureReason?.let {
                                        append(" — ")
                                        append(context.getString(R.string.recursive_harness_progress_last_failure, it))
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (progress.finishedCandidates.isNotEmpty()) {
                            Text(
                                stringResource(R.string.recursive_harness_progress_finished),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            progress.finishedCandidates.forEach { finished ->
                                Text(
                                    "${finished.label.ifBlank { finished.genomeId }} — ${"%.0f%%".format(finished.successRate * 100)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            // Full run report (P7, persisted in P8): one candidate at a
            // time, at most 17 tasks — the promotable candidate when
            // there is one, else the newest archived candidate. The
            // candidate comes from the stored last-run pointer, so the
            // block is there after an app restart with no run this
            // session. No invented numbers: parent scores come from the
            // parent's stored report, or the block says the parent was
            // never evaluated. An INVALID report is marked as such and
            // is never a promotion candidate.
            if (reportEntry != null) {
                val entry = reportEntry!!
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.recursive_harness_report_title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        entry.label.ifBlank { entry.genomeId },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    reportCandidate?.let { candidate ->
                        Text(
                            context.getString(
                                R.string.recursive_harness_report_candidate,
                                scoreText(candidate.successRate, candidate.holdoutScore),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!candidate.valid) {
                            Text(
                                context.getString(
                                    R.string.recursive_harness_report_invalid,
                                    candidate.invalidReason ?: "",
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (candidate.seeds.size > 1) {
                            Text(
                                context.getString(
                                    R.string.recursive_harness_report_seeds,
                                    candidate.seeds.size,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (reportParent != null) {
                        Text(
                            context.getString(
                                R.string.recursive_harness_report_parent,
                                entry.parent ?: "",
                                scoreText(reportParent!!.successRate, reportParent!!.holdoutScore),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            stringResource(R.string.recursive_harness_report_parent_none),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        entry.gateReason?.let {
                            context.getString(R.string.recursive_harness_report_gate, it)
                        } ?: stringResource(R.string.recursive_harness_report_ready),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    reportCandidate?.let { candidate ->
                        val training = candidate.tasks.filter { !it.holdout }
                        val holdout = candidate.tasks.filter { it.holdout }
                        if (training.isNotEmpty()) {
                            Text(
                                context.getString(
                                    R.string.recursive_harness_report_training,
                                    training.count { it.passed }, training.size,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            training.forEach { task ->
                                Text(
                                    buildString {
                                        append(task.taskId).append(" — ")
                                        append(
                                            context.getString(
                                                when (task.resolvedStatus) {
                                                    TaskStatus.PASS -> R.string.recursive_harness_report_task_passed
                                                    TaskStatus.FAIL -> R.string.recursive_harness_report_task_failed
                                                    TaskStatus.ERROR -> R.string.recursive_harness_report_task_error
                                                    TaskStatus.SKIPPED -> R.string.recursive_harness_report_task_skipped
                                                },
                                            ),
                                        )
                                        if (task.detail.isNotBlank()) append(" — ").append(task.detail.take(200))
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (holdout.isNotEmpty()) {
                            Text(
                                context.getString(
                                    R.string.recursive_harness_report_holdout,
                                    holdout.count { it.passed }, holdout.size,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            holdout.forEach { task ->
                                Text(
                                    buildString {
                                        append(task.taskId).append(" — ")
                                        append(
                                            context.getString(
                                                when (task.resolvedStatus) {
                                                    TaskStatus.PASS -> R.string.recursive_harness_report_task_passed
                                                    TaskStatus.FAIL -> R.string.recursive_harness_report_task_failed
                                                    TaskStatus.ERROR -> R.string.recursive_harness_report_task_error
                                                    TaskStatus.SKIPPED -> R.string.recursive_harness_report_task_skipped
                                                },
                                            ),
                                        )
                                        if (task.detail.isNotBlank()) append(" — ").append(task.detail.take(200))
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            (runState?.error ?: localError)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            runState?.promotableId?.let { id ->
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
                                    host.dismissPromotable()
                                    refresh()
                                } else {
                                    localError = result.exceptionOrNull()?.message
                                        ?: context.getString(R.string.recursive_harness_run_failed)
                                }
                            }
                        }) {
                            Text(stringResource(R.string.recursive_harness_promote))
                        }
                        TextButton(onClick = { host.dismissPromotable() }) {
                            Text(stringResource(R.string.recursive_harness_dismiss))
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Text(
                stringResource(R.string.recursive_harness_backup_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { exportLauncher.launch(suggestedBackupName) },
                    enabled = !backupBusy,
                ) {
                    Text(stringResource(R.string.recursive_harness_export))
                }
                Button(
                    onClick = { importLauncher.launch(arrayOf("application/json")) },
                    enabled = !backupBusy,
                ) {
                    Text(stringResource(R.string.recursive_harness_import))
                }
            }
            backupMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }

            // Archive (P9): the list keeps the best 10 entries plus the
            // active genome's lineage and the last run's candidate; older
            // rows are pruned automatically after each run.
            Text(
                stringResource(R.string.recursive_harness_archive_kept),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                        entry.gateReason?.let { reason ->
                            Text(
                                context.getString(R.string.recursive_harness_report_gate, reason),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // Per-row delete (P9): rows only, never the active
                        // genome, its lineage, the last run's candidate,
                        // or a genome with children. The genome and its
                        // report stay saved and exportable.
                        if (Archive.canDeleteEntry(entry.genomeId, activeId, lastRun?.candidateId, entries)) {
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { host.archive.remove(entry.genomeId) }
                                        refresh()
                                    }
                                },
                            ) {
                                Text(stringResource(R.string.recursive_harness_archive_delete))
                            }
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

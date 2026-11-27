package com.androidharness.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.evolve.ArchiveEntry
import com.androidharness.app.evolve.EvolveHost
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings → Coding → Recursive harness. P0 is a read-only view: what evolved, what is
 * active and what the loop is allowed to do. The switch, the run controls and the
 * approval cards arrive with the runner; until then the loop cannot start at all.
 */
@Composable
internal fun RecursiveHarnessSection() {
    val context = LocalContext.current
    val host = remember(context) { EvolveHost.at(File(context.filesDir, "evolve")) }
    var entries by remember { mutableStateOf<List<ArchiveEntry>>(emptyList()) }
    var activeLabel by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(host) {
        withContext(Dispatchers.IO) {
            entries = host.archive.entries().sortedByDescending { it.createdAt }
            activeLabel = host.store.active()?.let { active ->
                host.store.load(active.genomeId)?.label?.ifBlank { active.genomeId }
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

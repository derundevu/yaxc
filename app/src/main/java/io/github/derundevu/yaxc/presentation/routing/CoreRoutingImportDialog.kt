package io.github.derundevu.yaxc.presentation.routing

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.helper.CoreRoutingImportPreview
import io.github.derundevu.yaxc.helper.CoreRoutingImportProfile

@Composable
fun CoreRoutingImportDialog(
    profiles: List<CoreRoutingImportProfile>,
    preview: CoreRoutingImportPreview?,
    runtimeInboundTag: String,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onProfile: (Long) -> Unit,
    onConfirm: (Set<Int>, Map<String, String>, Map<String, String>, Boolean, Boolean) -> Unit,
) {
    var selected by remember(preview) { mutableStateOf(emptySet<Int>()) }
    var outbounds by remember(preview) { mutableStateOf(emptyMap<String, String>()) }
    var inbounds by remember(preview, runtimeInboundTag) { mutableStateOf(emptyMap<String, String>()) }
    var replace by remember { mutableStateOf(false) }
    var strategy by remember(preview) { mutableStateOf(false) }
    val selectedRules = remember(preview, selected) { preview?.rules.orEmpty().filter { it.index in selected } }
    val missingMappings = selectedRules.any { rule ->
        rule.outbound?.let { (outbounds[it.tag] ?: it.suggestedTarget) == null } == true ||
            rule.inbounds.any { inbounds[it] != runtimeInboundTag }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.coreImportTitle)) },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.coreImportLead), style = MaterialTheme.typography.bodySmall)
                if (preview == null) {
                    if (profiles.isEmpty() && !busy) Text(stringResource(R.string.coreImportNoProfiles))
                    profiles.forEach { profile ->
                        TextButton(onClick = { onProfile(profile.id) }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("core-import:profile:${profile.id}")) {
                            Text(profile.name.ifBlank { "#${profile.id}" })
                        }
                    }
                } else {
                    Text(preview.profile.name.ifBlank { "#${preview.profile.id}" }, style = MaterialTheme.typography.titleSmall)
                    Row {
                        TextButton(onClick = { selected = preview.rules.filter { it.problem == null }.map { it.index }.toSet() }, enabled = !busy) {
                            Text(stringResource(R.string.coreImportSelectAll))
                        }
                        TextButton(onClick = { selected = emptySet() }, enabled = !busy) {
                            Text(stringResource(R.string.coreImportSelectNone))
                        }
                    }
                    if (preview.rules.isEmpty()) Text(stringResource(R.string.coreImportNoRules))
                    preview.rules.forEach { rule ->
                        HorizontalDivider()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                modifier = Modifier.testTag("core-import:rule:${rule.index}"),
                                checked = rule.index in selected,
                                enabled = !busy && rule.problem == null,
                                onCheckedChange = { checked -> selected = if (checked) selected + rule.index else selected - rule.index },
                            )
                            Text(stringResource(R.string.coreImportRuleNumber, rule.index + 1))
                        }
                        SelectionContainer { Text(rule.json, style = MaterialTheme.typography.bodySmall) }
                        rule.problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                    val references = selectedRules.mapNotNull { it.outbound }.distinctBy { it.tag }
                    references.forEach { reference ->
                        HorizontalDivider()
                        Text(stringResource(R.string.coreImportMapOutbound, reference.tag), style = MaterialTheme.typography.labelLarge)
                        listOf("proxy", "direct", "block").forEach { target ->
                            ImportChoice(
                                label = stringResource(when (target) { "direct" -> R.string.coreImportDirect; "block" -> R.string.coreImportBlock; else -> R.string.coreImportProxy }),
                                selected = (outbounds[reference.tag] ?: reference.suggestedTarget) == target,
                                enabled = !busy,
                                onClick = { outbounds = outbounds + (reference.tag to target) },
                            )
                        }
                    }
                    selectedRules.flatMap { it.inbounds }.distinct().forEach { tag ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = inbounds[tag] == runtimeInboundTag,
                                enabled = !busy,
                                onCheckedChange = { checked -> inbounds = if (checked) inbounds + (tag to runtimeInboundTag) else inbounds - tag },
                            )
                            Text(stringResource(R.string.coreImportMapInbound, tag, runtimeInboundTag), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = strategy, enabled = !busy && preview.domainStrategy != null, onCheckedChange = { strategy = it })
                        Text(stringResource(R.string.coreImportStrategy, preview.domainStrategy ?: "—"))
                    }
                    ImportChoice(stringResource(R.string.coreImportAppend), !replace, !busy) { replace = false }
                    ImportChoice(stringResource(R.string.coreImportReplace), replace, !busy) { replace = true }
                    if (replace) Text(stringResource(R.string.coreImportReplaceLead), style = MaterialTheme.typography.bodySmall)
                    if (missingMappings) Text(stringResource(R.string.coreImportMappingRequired), color = MaterialTheme.colorScheme.error)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag("core-import:confirm"),
                enabled = !busy && preview != null && !missingMappings && (selected.isNotEmpty() || strategy),
                onClick = { onConfirm(selected, outbounds, inbounds, replace, strategy) },
            ) { Text(stringResource(R.string.coreImportIntoDraft)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.coreImportCancel)) } },
    )
}

@Composable
private fun ImportChoice(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, enabled = enabled, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

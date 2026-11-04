package io.github.derundevu.yaxc.presentation.routing

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme

@Composable
internal fun VisibleAppsSelection(
    selectedCount: Int,
    visibleCount: Int,
    selectedVisibleCount: Int,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = when {
        visibleCount == 0 || selectedVisibleCount == 0 -> ToggleableState.Off
        selectedVisibleCount == visibleCount -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .triStateToggleable(
                state = state,
                enabled = enabled && visibleCount > 0,
                role = Role.Checkbox,
                onClick = { onCheckedChange(state != ToggleableState.On) },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TriStateCheckbox(state = state, onClick = null, enabled = enabled && visibleCount > 0)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = YaxcTheme.spacing.sm, vertical = YaxcTheme.spacing.xs),
        ) {
            Text(
                text = stringResource(R.string.appsRoutingSelectAllVisible),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.appsRoutingSelectionSummary, selectedCount, visibleCount),
                style = MaterialTheme.typography.labelSmall,
                color = YaxcTheme.extendedColors.textMuted,
            )
        }
    }
}

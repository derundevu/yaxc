package io.github.derundevu.yaxc.presentation.root

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ToggleOn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.yaxcSoftFill
import io.github.derundevu.yaxc.presentation.designsystem.yaxcSoftOnSurface
import io.github.derundevu.yaxc.presentation.designsystem.yaxcSoftStroke
import io.github.derundevu.yaxc.presentation.designsystem.components.yaxcClickable
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcGlassPanel

enum class RootDestination {
    Connect,
    Routing,
    Settings,
}

@Composable
fun RootBottomBar(
    selectedDestination: RootDestination,
    onSelectDestination: (RootDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(0.94f),
        color = yaxcSoftFill(darkAlpha = 0.10f, lightAlpha = 0.86f),
        shape = RoundedCornerShape(36.dp),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, yaxcSoftStroke(darkAlpha = 0.10f, lightAlpha = 0.92f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RootBottomTabItem(
                icon = Icons.Outlined.ToggleOn,
                label = stringResource(R.string.rootTabConnect),
                selected = selectedDestination == RootDestination.Connect,
                onClick = { onSelectDestination(RootDestination.Connect) },
                modifier = Modifier.weight(1f),
            )
            RootBottomTabItem(
                icon = Icons.AutoMirrored.Outlined.AltRoute,
                label = stringResource(R.string.rootTabRouting),
                selected = selectedDestination == RootDestination.Routing,
                onClick = { onSelectDestination(RootDestination.Routing) },
                modifier = Modifier.weight(1f),
            )
            RootBottomTabItem(
                icon = Icons.Outlined.Settings,
                label = stringResource(R.string.rootTabSettings),
                selected = selectedDestination == RootDestination.Settings,
                onClick = { onSelectDestination(RootDestination.Settings) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
fun RootSectionCard(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    YaxcGlassPanel(
        modifier = modifier.yaxcClickable(shape = RoundedCornerShape(28.dp), onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                color = yaxcSoftFill(darkAlpha = 0.10f, lightAlpha = 0.86f),
                shape = MaterialTheme.shapes.large,
            ) {
                Box(
                    modifier = Modifier.size(44.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = yaxcSoftOnSurface(darkAlpha = 0.86f, lightAlpha = 0.84f),
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = YaxcTheme.extendedColors.textMuted,
                )
            }
        }
    }
}

@Composable
private fun RootBottomTabItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.yaxcClickable(shape = RoundedCornerShape(28.dp), onClick = onClick),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.96f)
        } else {
            Color.Transparent
        },
        shape = RoundedCornerShape(28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    yaxcSoftOnSurface(darkAlpha = 0.68f, lightAlpha = 0.64f)
                },
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    yaxcSoftOnSurface(darkAlpha = 0.68f, lightAlpha = 0.64f)
                },
            )
        }
    }
}

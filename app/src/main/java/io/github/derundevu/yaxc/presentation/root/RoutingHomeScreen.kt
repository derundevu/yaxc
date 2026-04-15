package io.github.derundevu.yaxc.presentation.root

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme

@Composable
fun RoutingHomeScreen(
    onOpenAppsRouting: () -> Unit,
    onOpenCoreRouting: () -> Unit,
    onSelectDestination: (RootDestination) -> Unit,
) {
    val spacing = YaxcTheme.spacing
    val density = LocalDensity.current
    val navigationBarBottom = WindowInsets.navigationBars.getBottom(density)
    val gestureNavigation = navigationBarBottom <= with(density) { 32.dp.roundToPx() }
    val reservedBottomInset = if (gestureNavigation) 0.dp else with(density) { navigationBarBottom.toDp() }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
        ),
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(YaxcTheme.backgroundBrush),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = spacing.md)
                    .padding(top = spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
                contentPadding = PaddingValues(bottom = spacing.xl + reservedBottomInset + 88.dp),
            ) {
                item {
                    Text(
                        text = textResource(R.string.routing),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
                item {
                    Text(
                        text = textResource(R.string.routingScreenLead),
                        style = MaterialTheme.typography.bodyLarge,
                        color = YaxcTheme.extendedColors.textMuted,
                    )
                }
                item {
                    RootSectionCard(
                        icon = Icons.AutoMirrored.Outlined.AltRoute,
                        title = textResource(R.string.appsRouting),
                        description = textResource(R.string.appsRoutingLead),
                        onClick = onOpenAppsRouting,
                    )
                }
                item {
                    RootSectionCard(
                        icon = Icons.Outlined.Hub,
                        title = textResource(R.string.coreRouting),
                        description = textResource(R.string.coreRoutingLead),
                        onClick = onOpenCoreRouting,
                    )
                }
            }

            RootBottomBar(
                selectedDestination = RootDestination.Routing,
                onSelectDestination = onSelectDestination,
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.BottomCenter)
                    .padding(horizontal = spacing.lg)
                    .padding(bottom = spacing.lg + reservedBottomInset),
            )
        }
    }
}

@Composable
private fun textResource(id: Int): String {
    return androidx.compose.ui.res.stringResource(id)
}

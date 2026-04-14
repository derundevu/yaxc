package io.github.derundevu.yaxc.presentation.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcCard
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcScaffold
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcSettingsRow
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcSwitchRow

@Composable
fun DesignSystemShowcase() {
    val spacing = YaxcTheme.spacing

    YaxcScaffold {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.md, vertical = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )

            Text(
                text = "Compose foundation for the future midnight-blue UI.",
                style = MaterialTheme.typography.bodyLarge,
                color = YaxcTheme.extendedColors.textMuted,
            )

            YaxcCard {
                YaxcSettingsRow(
                    title = "Theme",
                    value = "Midnight Blue",
                    icon = Icons.Outlined.Palette,
                )
                YaxcSettingsRow(
                    title = "Language",
                    value = "Follow system",
                    icon = Icons.Outlined.Language,
                )
            }

            YaxcCard {
                YaxcSwitchRow(
                    title = "Auto connect",
                    subtitle = "Connect when the app starts",
                    checked = true,
                    onCheckedChange = {},
                    icon = Icons.Outlined.Sync,
                )
                YaxcSwitchRow(
                    title = "Kill switch",
                    subtitle = "Block internet without VPN",
                    checked = false,
                    onCheckedChange = {},
                    icon = Icons.Outlined.Security,
                )
                YaxcSettingsRow(
                    title = "Apps routing",
                    value = "Configure",
                    icon = Icons.Outlined.Route,
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF09131F)
@Composable
private fun MidnightBluePreview() {
    YaxcTheme(style = YaxcThemeStyle.MidnightBlue) {
        DesignSystemShowcase()
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF3F6FB)
@Composable
private fun LightSlatePreview() {
    YaxcTheme(style = YaxcThemeStyle.LightSlate) {
        DesignSystemShowcase()
    }
}

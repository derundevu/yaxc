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
import androidx.compose.ui.res.stringResource
import io.github.derundevu.yaxc.R
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
                text = stringResource(R.string.showcaseTitle),
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )

            Text(
                text = stringResource(R.string.showcaseLead),
                style = MaterialTheme.typography.bodyLarge,
                color = YaxcTheme.extendedColors.textMuted,
            )

            YaxcCard {
                YaxcSettingsRow(
                    title = stringResource(R.string.showcaseTheme),
                    value = stringResource(R.string.showcaseThemeValue),
                    icon = Icons.Outlined.Palette,
                )
                YaxcSettingsRow(
                    title = stringResource(R.string.showcaseLanguage),
                    value = stringResource(R.string.showcaseLanguageValue),
                    icon = Icons.Outlined.Language,
                )
            }

            YaxcCard {
                YaxcSwitchRow(
                    title = stringResource(R.string.showcaseAutoConnect),
                    subtitle = stringResource(R.string.showcaseAutoConnectHint),
                    checked = true,
                    onCheckedChange = {},
                    icon = Icons.Outlined.Sync,
                )
                YaxcSwitchRow(
                    title = stringResource(R.string.showcaseKillSwitch),
                    subtitle = stringResource(R.string.showcaseKillSwitchHint),
                    checked = false,
                    onCheckedChange = {},
                    icon = Icons.Outlined.Security,
                )
                YaxcSettingsRow(
                    title = stringResource(R.string.appsRouting),
                    value = stringResource(R.string.configure),
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

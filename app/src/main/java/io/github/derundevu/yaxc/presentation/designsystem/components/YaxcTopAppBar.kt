package io.github.derundevu.yaxc.presentation.designsystem.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YaxcTopAppBar(
    title: String,
    onBack: () -> Unit,
    backLabel: String = stringResource(R.string.navigateBack),
    modifier: Modifier = Modifier,
    backEnabled: Boolean = true,
    backModifier: Modifier = Modifier,
    subtitle: String? = null,
    titleMaxLines: Int = Int.MAX_VALUE,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            Column {
                Text(title, maxLines = titleMaxLines, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = YaxcTheme.extendedColors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        modifier = modifier,
        navigationIcon = {
            IconButton(onClick = onBack, enabled = backEnabled, modifier = backModifier.yaxcTvFocus(CircleShape)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = backLabel)
            }
        },
        actions = actions,
    )
}

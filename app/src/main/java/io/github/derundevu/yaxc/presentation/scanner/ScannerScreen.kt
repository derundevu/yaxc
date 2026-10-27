package io.github.derundevu.yaxc.presentation.scanner

import android.graphics.drawable.GradientDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.budiyev.android.codescanner.CodeScannerView
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcScaffold

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ScannerScreen(
    onBack: () -> Unit,
    onScannerViewReady: (CodeScannerView) -> Unit,
) {
    val spacing = YaxcTheme.spacing

    YaxcScaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.scanQrCode)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.scannerBack),
                        )
                    }
                },
            )
        },
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .padding(spacing.md),
        ) {
            val sideBySide = maxWidth > maxHeight && maxWidth >= 480.dp
            val viewportSize = if (sideBySide) {
                minOf(maxHeight, (maxWidth - spacing.lg) * 0.55f, 480.dp)
            } else {
                minOf(maxWidth, 480.dp)
            }
            val instructionsWidth = if (sideBySide) {
                maxWidth - viewportSize - spacing.lg
            } else {
                viewportSize
            }
            val textAlign = if (sideBySide) TextAlign.Start else TextAlign.Center

            FlowRow(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(spacing.lg, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(spacing.md, Alignment.CenterVertically),
                maxItemsInEachRow = if (sideBySide) 2 else 1,
            ) {
                ScannerViewport(
                    modifier = Modifier.size(viewportSize).align(Alignment.CenterVertically),
                    onScannerViewReady = onScannerViewReady,
                )

                Column(
                    modifier = Modifier.width(instructionsWidth).align(Alignment.CenterVertically),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    Text(
                        text = stringResource(R.string.scannerScreenLead),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = textAlign,
                        modifier = Modifier.width(instructionsWidth),
                    )
                    Text(
                        text = stringResource(R.string.scannerFrameHint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = YaxcTheme.extendedColors.textMuted,
                        textAlign = textAlign,
                        modifier = Modifier.width(instructionsWidth),
                    )
                }
            }
        }
    }
}

@Composable
private fun ScannerViewport(
    modifier: Modifier,
    onScannerViewReady: (CodeScannerView) -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    val density = LocalDensity.current
    val cornerRadius = shape.topStart.toPx(Size.Zero, density)
    val frameColor = MaterialTheme.colorScheme.primary.toArgb()
    val frameThickness = with(density) { 2.dp.roundToPx() }
    val frameCornersSize = with(density) { 24.dp.roundToPx() }
    val frameCornersRadius = with(density) { 8.dp.roundToPx() }

    AndroidView(
        factory = { context ->
            CodeScannerView(context).apply {
                background = GradientDrawable().apply {
                    setColor(android.graphics.Color.BLACK)
                    this.cornerRadius = cornerRadius
                }
                clipToOutline = true
                maskColor = Color.Black.copy(alpha = 0.48f).toArgb()
                this.frameThickness = frameThickness
                this.frameCornersSize = frameCornersSize
                this.frameCornersRadius = frameCornersRadius
                isFrameCornersCapRounded = true
                setFrameAspectRatio(1f, 1f)
                frameSize = 0.66f
                frameVerticalBias = 0.6f
            }.also(onScannerViewReady)
        },
        update = { view -> view.frameColor = frameColor },
        modifier = modifier
            .clip(shape)
            .background(Color.Black)
            .border(1.dp, YaxcTheme.extendedColors.cardBorder, shape),
    )
}

package io.github.derundevu.yaxc.presentation.designsystem.components

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.helper.ToastHelper
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.yaxcIsLightTheme
import kotlinx.coroutines.flow.collectLatest

enum class FeedbackKind { Info, Success, Warning, Error }

// Identity distinguishes consecutive events with the same text.
class FeedbackMessage(
    val message: String,
    val kind: FeedbackKind = FeedbackKind.Info,
)

@Stable
class YaxcFeedbackState {
    internal var currentMessage by mutableStateOf<FeedbackMessage?>(null)
        private set

    private var stopped = false
    private var hostLifecycle: Lifecycle? = null

    fun show(message: String, kind: FeedbackKind = FeedbackKind.Info) {
        // Activity results can arrive before this host's ON_START observer is dispatched.
        if (!stopped || hostLifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true) {
            currentMessage = FeedbackMessage(message, kind)
        }
    }

    internal fun consume(message: FeedbackMessage) {
        if (currentMessage === message) {
            currentMessage = null
        }
    }

    internal fun attach(lifecycle: Lifecycle) {
        hostLifecycle = lifecycle
    }

    internal fun detach(lifecycle: Lifecycle) {
        if (hostLifecycle === lifecycle) {
            hostLifecycle = null
            stop()
        }
    }

    internal fun start() {
        stopped = false
    }

    internal fun stop() {
        stopped = true
        currentMessage = null
    }
}

val LocalYaxcFeedback: ProvidableCompositionLocal<YaxcFeedbackState> = staticCompositionLocalOf {
    error("Yaxc feedback requires YaxcTheme")
}

@Composable
internal fun YaxcFeedbackHost(feedback: YaxcFeedbackState) {
    val context = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(feedback, lifecycleOwner) {
        val lifecycle = lifecycleOwner.lifecycle
        feedback.attach(lifecycle)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> feedback.start()
                Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> feedback.stop()
                else -> Unit
            }
        }
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            feedback.start()
        } else if (lifecycle.currentState == Lifecycle.State.DESTROYED) {
            feedback.stop()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            feedback.detach(lifecycle)
        }
    }

    LaunchedEffect(feedback, lifecycleOwner, context) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            snapshotFlow { feedback.currentMessage }.collectLatest { message ->
                if (message != null) {
                    val duration = when (message.kind) {
                        FeedbackKind.Warning, FeedbackKind.Error -> Toast.LENGTH_LONG
                        FeedbackKind.Info, FeedbackKind.Success -> Toast.LENGTH_SHORT
                    }
                    ToastHelper.show(context, message.message, duration)
                    feedback.consume(message)
                }
            }
        }
    }
}

@Composable
fun YaxcMessageDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String? = null,
    kind: FeedbackKind = FeedbackKind.Info,
) {
    val accent = feedbackAccent(kind)
    FeedbackDialog(
        accent = accent,
        onDismissRequest = onDismiss,
        dismissible = true,
    ) {
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = feedbackIcon(kind),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.padding(top = 2.dp).size(28.dp),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onConfirm,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = YaxcTheme.shapes.small,
        ) {
            Text(text = confirmLabel, textAlign = TextAlign.Center)
        }
        if (dismissLabel != null) {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = YaxcTheme.shapes.small,
            ) {
                Text(text = dismissLabel, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
fun YaxcProgressDialog(
    title: String,
    message: String? = null,
    onCancel: (() -> Unit)? = null,
) {
    FeedbackDialog(
        accent = MaterialTheme.colorScheme.primary,
        onDismissRequest = {},
        dismissible = false,
    ) {
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeWidth = 3.dp,
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
            if (message != null) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (onCancel != null) {
            Spacer(Modifier.height(16.dp))
            TextButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = YaxcTheme.shapes.small,
            ) {
                Text(text = stringResource(R.string.cancel), textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun FeedbackDialog(
    accent: Color,
    onDismissRequest: () -> Unit,
    dismissible: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = dismissible,
            dismissOnClickOutside = dismissible,
            usePlatformDefaultWidth = false,
        ),
    ) {
        BoxWithConstraints(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            FeedbackPanel(
                accent = accent,
                modifier = Modifier.widthIn(max = 560.dp).heightIn(max = maxHeight),
                contentPadding = PaddingValues(20.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun FeedbackPanel(
    accent: Color,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    content: @Composable ColumnScope.() -> Unit,
) {
    val light = yaxcIsLightTheme()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = YaxcTheme.shapes.extraLarge,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 12.dp,
        tonalElevation = 0.dp,
        border = BorderStroke(1.dp, YaxcTheme.extendedColors.cardBorder),
    ) {
        Box(
            modifier = Modifier.background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        MaterialTheme.colorScheme.surface,
                    ),
                ),
            ),
        ) {
            Box(
                Modifier.matchParentSize().background(
                    Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = if (light) 0.035f else 0.07f), Color.Transparent),
                        radius = 820f,
                    ),
                ),
            )
            Column(
                modifier = Modifier.fillMaxWidth().padding(contentPadding),
                content = content,
            )
        }
    }
}

@Composable
private fun feedbackAccent(kind: FeedbackKind): Color = when (kind) {
    FeedbackKind.Info -> MaterialTheme.colorScheme.primary
    FeedbackKind.Success -> YaxcTheme.extendedColors.success
    FeedbackKind.Warning -> YaxcTheme.extendedColors.warning
    FeedbackKind.Error -> YaxcTheme.extendedColors.danger
}

private fun feedbackIcon(kind: FeedbackKind): ImageVector = when (kind) {
    FeedbackKind.Info -> Icons.Outlined.Info
    FeedbackKind.Success -> Icons.Outlined.CheckCircle
    FeedbackKind.Warning -> Icons.Outlined.WarningAmber
    FeedbackKind.Error -> Icons.Outlined.ErrorOutline
}

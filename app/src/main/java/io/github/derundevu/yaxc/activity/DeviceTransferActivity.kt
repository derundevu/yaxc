package io.github.derundevu.yaxc.activity

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.presentation.designsystem.YaxcAppTheme
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTvTypographyScope
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcCard
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcGlassPanel
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcScaffold
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcTopAppBar
import io.github.derundevu.yaxc.presentation.designsystem.components.isYaxcTv
import io.github.derundevu.yaxc.presentation.designsystem.components.yaxcTvFocus
import io.github.derundevu.yaxc.presentation.designsystem.yaxcSoftFill
import io.github.derundevu.yaxc.presentation.designsystem.yaxcSoftStroke
import io.github.derundevu.yaxc.transfer.DeviceTransferState
import io.github.derundevu.yaxc.transfer.DeviceTransferViewModel
import io.github.derundevu.yaxc.transfer.TransferSummary

class DeviceTransferActivity : AppCompatActivity() {
    private val model: DeviceTransferViewModel by viewModels()
    private val scanner = registerForActivityResult(StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) result.data?.getStringExtra("link")?.let(model::scanned)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)
        if (savedInstanceState == null) intent.getStringExtra(EXTRA_TRANSFER_CODE)?.let(model::scanned)
        intent.removeExtra(EXTRA_TRANSFER_CODE)
        val canScan = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA)
        setContent {
            YaxcAppTheme {
                val state by model.state.collectAsStateWithLifecycle()
                val onBack = if (state == DeviceTransferState.Home) ::finish else model::reset
                BackHandler(enabled = state != DeviceTransferState.Home, onBack = onBack)
                DeviceTransferScreen(
                    state = state,
                    canScan = canScan,
                    onBack = onBack,
                    onReceive = model::receive,
                    onScan = { scanner.launch(ScannerActivity.deviceTransferIntent(this)) },
                    onSelectGroup = model::selectGroup,
                    onSelectSettings = model::selectSettings,
                    onSelectRouting = model::selectRouting,
                    onSend = model::send,
                )
            }
        }
    }

    override fun onStop() {
        if (!isChangingConfigurations) model.stopShowingCode()
        super.onStop()
    }

    companion object {
        private const val EXTRA_TRANSFER_CODE = "device_transfer_code"

        internal fun intent(context: Context, code: String? = null): Intent =
            Intent(context, DeviceTransferActivity::class.java).apply {
                if (code != null) putExtra(EXTRA_TRANSFER_CODE, code)
            }
    }
}

@Composable
private fun DeviceTransferScreen(
    state: DeviceTransferState,
    canScan: Boolean,
    onBack: () -> Unit,
    onReceive: () -> Unit,
    onScan: () -> Unit,
    onSelectGroup: (Long?, Boolean) -> Unit,
    onSelectSettings: (Boolean) -> Unit,
    onSelectRouting: (Boolean) -> Unit,
    onSend: () -> Unit,
) {
    val isTv = isYaxcTv()
    val entryFocus = remember(state::class) { FocusRequester() }
    val listState = key(state::class) { rememberLazyListState() }
    val entryModifier = if (isTv) Modifier.focusRequester(entryFocus) else Modifier
    val focusingState = state == DeviceTransferState.Home || state is DeviceTransferState.Receive ||
        state is DeviceTransferState.Select || state is DeviceTransferState.Complete || state is DeviceTransferState.Failed
    if (isTv && focusingState) LaunchedEffect(entryFocus) { entryFocus.requestFocus() }

    val horizontalPadding = if (isTv) 48.dp else 20.dp
    val verticalPadding = if (isTv) 28.dp else 20.dp

    YaxcTvTypographyScope(enabled = isTv) {
        YaxcScaffold(
            contentColor = MaterialTheme.colorScheme.onBackground,
            topBar = {
                if (!isTv) YaxcTopAppBar(
                    title = stringResource(R.string.deviceTransferTitle),
                    onBack = onBack,
                    backEnabled = state != DeviceTransferState.Applying,
                )
            },
        ) { insets ->
            Column(
                modifier = Modifier.fillMaxSize().padding(insets).navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    val selection = state as? DeviceTransferState.Select
                    val wide = selection != null && maxWidth >= 840.dp && maxHeight >= 420.dp
                    Row(Modifier.widthIn(max = if (wide) 960.dp else 760.dp).fillMaxSize()) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = verticalPadding),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            if (isTv) {
                                item {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.QrCodeScanner,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(28.dp),
                                        )
                                        Text(
                                            text = stringResource(R.string.deviceTransferTitle),
                                            modifier = Modifier.semantics { heading() },
                                            style = MaterialTheme.typography.headlineMedium,
                                            color = MaterialTheme.colorScheme.onBackground,
                                        )
                                    }
                                }
                            }
                            when (state) {
                                DeviceTransferState.Home -> {
                                    item {
                                        YaxcGlassPanel(
                                            shadowElevation = 10.dp,
                                            contentPadding = PaddingValues(24.dp),
                                            accentColor = MaterialTheme.colorScheme.primary,
                                            accentAlpha = 0.12f,
                                        ) {
                                            Column(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalArrangement = Arrangement.spacedBy(18.dp),
                                            ) {
                                                TransferDescription(R.string.deviceTransferIntro)
                                                Column(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                                ) {
                                                    TransferButton(
                                                        label = R.string.deviceTransferReceive,
                                                        onClick = onReceive,
                                                        modifier = entryModifier.then(
                                                            if (isTv) Modifier.widthIn(min = 260.dp) else Modifier.fillMaxWidth(),
                                                        ),
                                                    )
                                                    if (canScan) {
                                                        TransferButton(
                                                            label = R.string.deviceTransferScan,
                                                            onClick = onScan,
                                                            modifier = if (isTv) Modifier.widthIn(min = 260.dp) else Modifier.fillMaxWidth(),
                                                            outlined = true,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                is DeviceTransferState.Receive -> {
                                    item { ReceiveCard(state) }
                                    item { TransferButton(R.string.cancel, onBack, entryModifier, outlined = true) }
                                }
                                is DeviceTransferState.Select -> {
                                    item {
                                        YaxcGlassPanel(
                                            shadowElevation = 8.dp,
                                            contentPadding = PaddingValues(20.dp),
                                        ) {
                                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                TransferHeading(R.string.deviceTransferSelectionTitle)
                                                TransferDescription(R.string.deviceTransferSelectionHelp)
                                            }
                                        }
                                    }
                                    item { Text(stringResource(R.string.deviceTransferGroups), style = MaterialTheme.typography.titleMedium) }
                                    if (state.groups.isEmpty()) item { TransferDescription(R.string.deviceTransferNoGroups) }
                                    items(state.groups, key = { it.id ?: 0L }) { group ->
                                        TransferCheckRow(
                                            title = if (group.id == null) stringResource(R.string.deviceTransferManual) else group.name,
                                            description = pluralStringResource(R.plurals.deviceTransferProfileCount, group.profileCount, group.profileCount),
                                            checked = group.id in state.selection.groups,
                                            onChecked = { onSelectGroup(group.id, it) },
                                            modifier = if (group.id == state.groups.firstOrNull()?.id) entryModifier else Modifier,
                                        )
                                    }
                                    if (!wide) transferOptions(state, onSelectSettings, onSelectRouting, entryModifier)
                                }
                                DeviceTransferState.OpeningReceiver, DeviceTransferState.LoadingSelection -> item {
                                    TransferProgress(R.string.deviceTransferPrepare)
                                }
                                DeviceTransferState.Sending -> item { TransferProgress(R.string.deviceTransferSending) }
                                DeviceTransferState.Applying -> item { TransferProgress(R.string.deviceTransferApplying) }
                                is DeviceTransferState.Complete -> {
                                    item { TransferReceipt(state.sent, state.summary) }
                                    item { TransferButton(R.string.deviceTransferDone, onBack, entryModifier) }
                                }
                                is DeviceTransferState.Failed -> {
                                    item {
                                        YaxcGlassPanel(
                                            shadowElevation = 10.dp,
                                            contentPadding = PaddingValues(24.dp),
                                            accentColor = MaterialTheme.colorScheme.error,
                                            accentAlpha = 0.14f,
                                            borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.42f),
                                        ) {
                                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Outlined.ErrorOutline,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.error,
                                                        modifier = Modifier.size(26.dp),
                                                    )
                                                    TransferHeading(R.string.deviceTransferFailed)
                                                }
                                                TransferDescription(state.message)
                                            }
                                        }
                                    }
                                    item { TransferButton(R.string.deviceTransferDone, onBack, entryModifier) }
                                }
                            }
                        }
                        if (wide) {
                            LazyColumn(
                                modifier = Modifier.width(340.dp).fillMaxHeight(),
                                contentPadding = PaddingValues(top = if (isTv) 80.dp else 60.dp, end = horizontalPadding, bottom = verticalPadding),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                            ) { transferOptions(selection, onSelectSettings, onSelectRouting, entryModifier) }
                        }
                    }
                }
                if (state is DeviceTransferState.Select) {
                    TransferButton(
                        label = R.string.deviceTransferSend,
                        onClick = onSend,
                        enabled = state.canSend,
                        modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = horizontalPadding, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

private fun LazyListScope.transferOptions(
    state: DeviceTransferState.Select,
    onSelectSettings: (Boolean) -> Unit,
    onSelectRouting: (Boolean) -> Unit,
    entryModifier: Modifier,
) {
    item {
        TransferCheckRow(
            title = stringResource(R.string.deviceTransferSettings),
            description = stringResource(R.string.deviceTransferSettingsHelp),
            checked = state.selection.settings,
            onChecked = onSelectSettings,
            modifier = if (state.groups.isEmpty()) entryModifier else Modifier,
        )
    }
    item {
        TransferCheckRow(
            title = stringResource(R.string.deviceTransferRouting),
            description = stringResource(R.string.deviceTransferRoutingHelp),
            checked = state.selection.routing,
            onChecked = onSelectRouting,
        )
    }
}

@Composable
private fun ReceiveCard(state: DeviceTransferState.Receive) {
    val image = remember(state.qr) { state.qr.asImageBitmap() }
    val description = stringResource(R.string.deviceTransferQrDescription)
    val qr: @Composable (Modifier) -> Unit = { modifier ->
        Surface(
            modifier = modifier.aspectRatio(1f),
            shape = MaterialTheme.shapes.medium,
            color = Color.White,
        ) {
            Image(
                bitmap = image,
                contentDescription = description,
                modifier = Modifier.fillMaxSize().padding(8.dp),
            )
        }
    }
    val guidance: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TransferHeading(R.string.deviceTransferReceiveTitle)
            TransferDescription(R.string.deviceTransferReceiveHelp)
            TransferDescription(R.string.deviceTransferReceiveEffect)
        }
    }
    YaxcGlassPanel(
        shape = MaterialTheme.shapes.large,
        contentPadding = PaddingValues(20.dp),
        shadowElevation = 10.dp,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 570.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    qr(Modifier.width(256.dp))
                    guidance(Modifier.weight(1f))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    qr(Modifier.widthIn(max = 256.dp).fillMaxWidth())
                    guidance(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun TransferCheckRow(
    title: String,
    description: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    YaxcCard(
        shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        onClick = { onChecked(!checked) },
        modifier = modifier.semantics {
            role = Role.Checkbox
            toggleableState = ToggleableState(checked)
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Checkbox(checked, onCheckedChange = null)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TransferButton(
    @StringRes label: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    outlined: Boolean = false,
    enabled: Boolean = true,
) {
    val shape = CircleShape
    val focusModifier = modifier
        .heightIn(min = 48.dp)
        .yaxcTvFocus(shape)
    val contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
    if (outlined) {
        OutlinedButton(
            onClick = onClick,
            modifier = focusModifier,
            enabled = enabled,
            shape = shape,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = yaxcSoftFill(darkAlpha = 0.06f, lightAlpha = 0.72f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            border = BorderStroke(1.dp, yaxcSoftStroke(darkAlpha = 0.24f, lightAlpha = 0.55f)),
            contentPadding = contentPadding,
        ) {
            Text(
                text = stringResource(label),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
            )
        }
    } else {
        Button(
            onClick = onClick,
            modifier = focusModifier,
            enabled = enabled,
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                disabledContainerColor = yaxcSoftFill(darkAlpha = 0.08f, lightAlpha = 0.65f),
                disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            ),
            border = BorderStroke(
                1.dp,
                if (enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)
                else yaxcSoftStroke(darkAlpha = 0.14f, lightAlpha = 0.45f),
            ),
            contentPadding = contentPadding,
        ) {
            Text(
                text = stringResource(label),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TransferProgress(@StringRes text: Int) {
    YaxcGlassPanel(
        shadowElevation = 10.dp,
        contentPadding = PaddingValues(24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.5.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(text),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun TransferReceipt(sent: Boolean, summary: TransferSummary) {
    YaxcGlassPanel(
        shape = MaterialTheme.shapes.large,
        contentPadding = PaddingValues(24.dp),
        shadowElevation = 10.dp,
        accentColor = YaxcTheme.extendedColors.success,
        accentAlpha = 0.14f,
        borderColor = YaxcTheme.extendedColors.success.copy(alpha = 0.40f),
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = YaxcTheme.extendedColors.success,
                    modifier = Modifier.size(26.dp),
                )
                TransferHeading(if (sent) R.string.deviceTransferSent else R.string.deviceTransferReceived)
            }
            val groupChanges = summary.groupsAdded != 0 || summary.groupsUpdated != 0
            val profileChanges = summary.profilesAdded != 0 || summary.profilesUpdated != 0
            if (groupChanges || profileChanges || (!summary.settings && !summary.routing)) {
                Text(stringResource(R.string.deviceTransferGroupsResult, summary.groupsAdded, summary.groupsUpdated))
                Text(stringResource(R.string.deviceTransferProfilesResult, summary.profilesAdded, summary.profilesUpdated))
            }
            if (summary.settings) Text(stringResource(R.string.deviceTransferSettingsResult))
            if (summary.routing) Text(stringResource(R.string.deviceTransferRoutingResult))
        }
    }
}

@Composable
private fun TransferHeading(@StringRes text: Int) {
    Text(stringResource(text), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
}

@Composable
private fun TransferDescription(@StringRes text: Int) {
    Text(stringResource(text), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
}

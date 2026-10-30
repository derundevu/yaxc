package io.github.derundevu.yaxc.fragment

import android.app.Dialog
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.viewmodel.LinksManagerViewModel
import io.github.derundevu.yaxc.database.Link
import io.github.derundevu.yaxc.helper.HttpHelper
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.YaxcAppTheme
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcProgressDialog
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class LinkFormFragment : DialogFragment() {
    companion object {
        private const val LINK_ARGUMENT = "source"

        fun newInstance(link: Link) = LinkFormFragment().apply {
            arguments = Bundle().apply { putParcelable(LINK_ARGUMENT, link) }
        }
    }

    private val state by lazy { ViewModelProvider(requireActivity())[LinksManagerViewModel::class.java] }

    @Suppress("DEPRECATION")
    private val link: Link by lazy {
        requireNotNull(requireArguments().getParcelable<Link>(LINK_ARGUMENT))
    }

    private enum class SourceMode {
        Subscription,
        Manual,
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return ComponentDialog(requireContext()).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCanceledOnTouchOutside(false)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setContentView(
                ComposeView(context).apply {
                    setViewCompositionStrategy(
                        ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
                    )
                    setContent {
                        YaxcAppTheme {
                            LinkFormDialogContent(
                                link = link,
                                busy = state.busy,
                                formError = state.formError,
                                onDismiss = state::cancel,
                                onConfirm = { updated ->
                                    state.save(link.copy(
                                        name = updated.name,
                                        address = updated.address,
                                        userAgent = updated.userAgent,
                                        xHwid = updated.xHwid,
                                        customHeaders = updated.customHeaders,
                                        isActive = if (link.id == 0L) true else link.isActive,
                                    ))
                                },
                            )
                            LaunchedEffect(state.busy) {
                                isCancelable = !state.busy
                            }
                            if (state.busy) {
                                YaxcProgressDialog(
                                    title = textResource(R.string.feedbackSavingSource),
                                    onCancel = if (state.canCancel) ({ state.cancel() }) else null,
                                )
                            }
                        }
                    }
                }
            )
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        state.cancel()
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun LinkFormDialogContent(
        link: Link,
        busy: Boolean,
        formError: Int?,
        onDismiss: () -> Unit,
        onConfirm: (UpdatedLinkForm) -> Unit,
    ) {
        val context = requireContext()
        val settings = remember { Settings(context.applicationContext) }
        val defaultLinkName = textResource(R.string.newSource)
        val subscriptionAddressRequiredText = textResource(R.string.subscriptionAddressRequired)
        val invalidLinkText = textResource(R.string.invalidLink)
        val onlyHttpsText = textResource(R.string.onlyHttps)
        val initialMode = remember(link.id, link.address) {
            when {
                link.id == 0L && link.address.isBlank() -> SourceMode.Subscription
                link.address.isBlank() -> SourceMode.Manual
                else -> SourceMode.Subscription
            }
        }

        var sourceMode by rememberSaveable { mutableStateOf(initialMode) }
        var name by rememberSaveable(link.id) { mutableStateOf(link.name) }
        var address by rememberSaveable(link.id) { mutableStateOf(link.address) }
        var userAgent by rememberSaveable(link.id) { mutableStateOf(link.userAgent.orEmpty()) }
        var xHwid by rememberSaveable(link.id) { mutableStateOf(link.xHwid.orEmpty()) }
        var customHeaders by rememberSaveable(link.id) {
            mutableStateOf(link.customHeaders.orEmpty())
        }
        var addressError by rememberSaveable { mutableStateOf<String?>(null) }

        fun shouldAutofillName(): Boolean {
            val trimmed = name.trim()
            return trimmed.isEmpty() || trimmed == defaultLinkName
        }

        LaunchedEffect(sourceMode, address, userAgent, xHwid, customHeaders, busy) {
            if (busy || sourceMode != SourceMode.Subscription || !shouldAutofillName()) return@LaunchedEffect

            val trimmedAddress = address.trim()
            val uri = runCatching { URI(trimmedAddress) }.getOrNull() ?: return@LaunchedEffect
            if (uri.scheme != "https") return@LaunchedEffect

            delay(350)
            val title = withContext(Dispatchers.IO) {
                runCatching {
                    HttpHelper.fetch(
                        link = trimmedAddress,
                        userAgent = HttpHelper.resolveSubscriptionUserAgent(
                            settings,
                            userAgent.ifBlank { null },
                        ),
                        headers = HttpHelper.buildSubscriptionHeaders(
                            settings = settings,
                            customHeaders = customHeaders,
                            overrideXHwid = xHwid.ifBlank { null },
                        ),
                    ).let { response ->
                        HttpHelper.extractSubscriptionTitle(response.headers)
                    }
                }.getOrNull()
            }

            if (!title.isNullOrBlank() && shouldAutofillName()) {
                name = title
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .imePadding()
                .navigationBarsPadding(),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.985f),
                tonalElevation = 0.dp,
                shadowElevation = 18.dp,
                border = BorderStroke(
                    width = 1.dp,
                    color = YaxcTheme.extendedColors.cardBorder.copy(alpha = 0.88f),
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        text = if (link.id == 0L) {
                            textResource(R.string.newSource)
                        } else {
                            textResource(R.string.editSource)
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    Text(
                        text = textResource(R.string.sourceDialogLead),
                        style = MaterialTheme.typography.bodyMedium,
                        color = YaxcTheme.extendedColors.textMuted,
                    )

                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = textResource(R.string.sourceKind),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        SingleChoiceSegmentedButtonRow(
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            SourceMode.entries.forEachIndexed { index, mode ->
                                SegmentedButton(
                                    selected = sourceMode == mode,
                                    onClick = {
                                        sourceMode = mode
                                        addressError = null
                                    },
                                    enabled = !busy,
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = SourceMode.entries.size,
                                    ),
                                    label = {
                                        Text(
                                            text = when (mode) {
                                                SourceMode.Subscription -> textResource(
                                                    R.string.sourceKindSubscription
                                                )
                                                SourceMode.Manual -> textResource(
                                                    R.string.sourceKindManual
                                                )
                                            }
                                        )
                                    },
                                )
                            }
                        }
                        Text(
                            text = when (sourceMode) {
                                SourceMode.Subscription -> textResource(
                                    R.string.sourceModeSubscriptionLead
                                )
                                SourceMode.Manual -> textResource(
                                    R.string.sourceModeManualLead
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = YaxcTheme.extendedColors.textMuted,
                        )
                    }

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(text = textResource(R.string.linkName)) },
                        singleLine = true,
                    )

                    if (sourceMode == SourceMode.Subscription) {
                        OutlinedTextField(
                            value = address,
                            onValueChange = {
                                address = it
                                addressError = null
                            },
                            enabled = !busy,
                            isError = addressError != null,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(text = textResource(R.string.linkAddress)) },
                            supportingText = {
                                Text(text = addressError ?: textResource(R.string.sourceAddressRemoteLead))
                            },
                            singleLine = true,
                        )

                        OutlinedTextField(
                            value = userAgent,
                            onValueChange = { userAgent = it },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(text = textResource(R.string.linkUserAgent)) },
                            supportingText = {
                                Text(text = textResource(R.string.sourceUserAgentLead))
                            },
                            singleLine = true,
                        )

                        OutlinedTextField(
                            value = xHwid,
                            onValueChange = { xHwid = it },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busy,
                            label = { Text(text = textResource(R.string.linkXHwid)) },
                            supportingText = {
                                Text(text = textResource(R.string.sourceXHwidLead))
                            },
                            singleLine = true,
                        )

                        OutlinedTextField(
                            value = customHeaders,
                            onValueChange = { customHeaders = it },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(text = textResource(R.string.linkCustomHeaders)) },
                            supportingText = {
                                Text(text = textResource(R.string.sourceHeadersLead))
                            },
                            minLines = 4,
                            maxLines = 8,
                        )
                    }
                    if (formError != null) {
                        Text(
                            text = textResource(formError),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDismiss, enabled = !busy) {
                            Text(text = textResource(R.string.close))
                        }
                        Button(
                            enabled = !busy,
                            onClick = {
                                val trimmedAddress = address.trim()
                                val modeAddress = if (sourceMode == SourceMode.Subscription) {
                                    trimmedAddress
                                } else {
                                    ""
                                }
                                if (sourceMode == SourceMode.Subscription && modeAddress.isBlank()) {
                                    addressError = subscriptionAddressRequiredText
                                    return@Button
                                }
                                val uri = runCatching { URI(modeAddress) }.getOrNull()
                                if (modeAddress.isNotBlank() && uri == null) {
                                    addressError = invalidLinkText
                                    return@Button
                                }
                                if (modeAddress.isNotBlank() && uri?.scheme != "https") {
                                    addressError = onlyHttpsText
                                    return@Button
                                }
                                onConfirm(
                                    UpdatedLinkForm(
                                        name = name.trim().ifBlank { defaultLinkName },
                                        address = modeAddress,
                                        userAgent = userAgent.trim().ifBlank { null }
                                            .takeIf { sourceMode == SourceMode.Subscription },
                                        xHwid = xHwid.trim().ifBlank { null }
                                            .takeIf { sourceMode == SourceMode.Subscription },
                                        customHeaders = customHeaders.trim().ifBlank { null }
                                            .takeIf { sourceMode == SourceMode.Subscription },
                                    )
                                )
                            },
                        ) {
                            Text(
                                text = if (link.id == 0L) {
                                    textResource(R.string.createSource)
                                } else {
                                    textResource(R.string.updateSource)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun textResource(id: Int): String {
        return androidx.compose.ui.res.stringResource(id)
    }

    private data class UpdatedLinkForm(
        val name: String,
        val address: String,
        val userAgent: String?,
        val xHwid: String?,
        val customHeaders: String?,
    )
}

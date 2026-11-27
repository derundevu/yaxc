package io.github.derundevu.yaxc.transfer

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.derundevu.yaxc.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface DeviceTransferState {
    data object Home : DeviceTransferState
    data object OpeningReceiver : DeviceTransferState
    data object LoadingSelection : DeviceTransferState
    data class Receive(val qr: Bitmap) : DeviceTransferState
    data class Select(
        val groups: List<TransferGroup>,
        val selection: TransferSelection = TransferSelection(emptySet(), settings = false, routing = false),
    ) : DeviceTransferState {
        val canSend: Boolean
            get() = selection.groups.isNotEmpty() || selection.settings || selection.routing
    }
    data object Sending : DeviceTransferState
    data object Applying : DeviceTransferState
    data class Complete(val sent: Boolean, val summary: TransferSummary) : DeviceTransferState
    data class Failed(@param:StringRes val message: Int) : DeviceTransferState
}

internal class DeviceTransferViewModel(application: Application) : AndroidViewModel(application) {
    private val store = DeviceTransferStore(application)
    private val mutableState = MutableStateFlow<DeviceTransferState>(DeviceTransferState.Home)
    val state = mutableState.asStateFlow()
    private var operation: Job? = null
    private var target: TransferEndpoint? = null

    fun reset() {
        if (mutableState.value == DeviceTransferState.Applying) return
        operation?.cancel()
        operation = null
        target = null
        mutableState.value = DeviceTransferState.Home
    }

    fun stopShowingCode() {
        when (mutableState.value) {
            DeviceTransferState.OpeningReceiver, is DeviceTransferState.Receive -> reset()
            else -> Unit
        }
    }

    fun receive() {
        if (mutableState.value != DeviceTransferState.Home) return
        mutableState.value = DeviceTransferState.OpeningReceiver
        runOperation(DeviceTransferDirection.Receive) {
            DeviceTransferTransport.open(getApplication()).use { receiver ->
                val qr = withContext(Dispatchers.Default) { createQr(receiver.code) }
                mutableState.value = DeviceTransferState.Receive(qr)
                val receipt = receiver.receive { payload ->
                    withContext(Dispatchers.Main.immediate) {
                        mutableState.value = DeviceTransferState.Applying
                    }
                    store.importPayload(payload).encode()
                }
                mutableState.value = DeviceTransferState.Complete(sent = false, summary = TransferSummary.decode(receipt))
            }
        }
    }

    fun scanned(code: String) {
        if (mutableState.value != DeviceTransferState.Home) return
        target = try {
            DeviceTransferTransport.parse(code)
        } catch (_: IllegalArgumentException) {
            mutableState.value = DeviceTransferState.Failed(R.string.deviceTransferBadCode)
            return
        }
        mutableState.value = DeviceTransferState.LoadingSelection
        runOperation(DeviceTransferDirection.Send) {
            mutableState.value = DeviceTransferState.Select(store.groups())
        }
    }

    fun selectGroup(id: Long?, checked: Boolean) = updateSelection {
        copy(groups = if (checked) groups + id else groups - id)
    }

    fun selectSettings(checked: Boolean) = updateSelection { copy(settings = checked) }

    fun selectRouting(checked: Boolean) = updateSelection { copy(routing = checked) }

    private fun updateSelection(update: TransferSelection.() -> TransferSelection) {
        val current = mutableState.value as? DeviceTransferState.Select ?: return
        mutableState.value = current.copy(selection = current.selection.update())
    }

    fun send() {
        val endpoint = target ?: return
        val selected = mutableState.value as? DeviceTransferState.Select ?: return
        if (!selected.canSend) return
        target = null
        mutableState.value = DeviceTransferState.Sending
        runOperation(DeviceTransferDirection.Send) {
            val payload = store.export(selected.selection)
            val receipt = DeviceTransferTransport.send(getApplication(), endpoint, payload)
            mutableState.value = DeviceTransferState.Complete(sent = true, summary = TransferSummary.decode(receipt))
        }
    }

    private fun runOperation(direction: DeviceTransferDirection, block: suspend () -> Unit) {
        operation?.cancel()
        operation = viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                ensureActive()
                target = null
                val message = when {
                    failure is TransferNetworkUnavailableException -> R.string.deviceTransferNoNetwork
                    failure is TransferConfirmationException -> R.string.deviceTransferTimeout
                    failure is TransferTimeoutException -> if (direction == DeviceTransferDirection.Send) {
                        R.string.deviceTransferTimeout
                    } else {
                        R.string.deviceTransferExpired
                    }
                    failure is TransferRoutingDependencyException ||
                        (failure is TransferApplyException && failure.reason == TransferApplyFailure.ROUTING_DEPENDENCY) ->
                        R.string.deviceTransferMissingProfiles
                    else -> R.string.deviceTransferGenericError
                }
                mutableState.value = DeviceTransferState.Failed(message)
            }
        }
    }
}

private enum class DeviceTransferDirection { Send, Receive }

private fun createQr(code: String): Bitmap {
    val matrix = QRCodeWriter().encode(
        code,
        BarcodeFormat.QR_CODE,
        512,
        512,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
    )
    val pixels = IntArray(matrix.width * matrix.height)
    for (y in 0 until matrix.height) {
        for (x in 0 until matrix.width) {
            pixels[y * matrix.width + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        }
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}

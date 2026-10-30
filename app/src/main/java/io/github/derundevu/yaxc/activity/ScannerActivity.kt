package io.github.derundevu.yaxc.activity

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import com.budiyev.android.codescanner.AutoFocusMode
import com.budiyev.android.codescanner.CodeScanner
import com.budiyev.android.codescanner.CodeScannerView
import com.budiyev.android.codescanner.DecodeCallback
import com.budiyev.android.codescanner.ErrorCallback
import com.budiyev.android.codescanner.ScanMode
import com.google.zxing.BarcodeFormat
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.presentation.designsystem.YaxcAppTheme
import io.github.derundevu.yaxc.presentation.designsystem.components.FeedbackKind
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcFeedbackState
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcMessageDialog
import io.github.derundevu.yaxc.presentation.scanner.ScannerScreen

class ScannerActivity : AppCompatActivity() {

    private val feedback = YaxcFeedbackState()
    private var cameraError by mutableStateOf<String?>(null)
    private var codeScanner: CodeScanner? = null
    private val cameraPermission = registerForActivityResult(RequestPermission()) { granted ->
        if (!granted) {
            showCameraError(getString(R.string.scannerCameraPermissionDenied))
            return@registerForActivityResult
        }
        startScannerPreview()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cameraError = savedInstanceState?.getString(STATE_CAMERA_ERROR)

        setContent {
            YaxcAppTheme(feedback = feedback) {
                ScannerScreen(
                    onBack = ::finish,
                    onScannerViewReady = ::setupScanner,
                )
                cameraError?.let { message ->
                    YaxcMessageDialog(
                        title = getString(R.string.scanQrCode),
                        message = message,
                        confirmLabel = getString(R.string.close),
                        onConfirm = ::finish,
                        onDismiss = ::finish,
                        kind = FeedbackKind.Error,
                    )
                }
            }
        }

        if (cameraError == null) ensureCameraPermission()
    }

    private fun setupScanner(scannerView: CodeScannerView) {
        if (codeScanner != null) return

        codeScanner = CodeScanner(this, scannerView).apply {
            camera = CodeScanner.CAMERA_BACK
            formats = listOf(BarcodeFormat.QR_CODE)
            autoFocusMode = AutoFocusMode.SAFE
            scanMode = ScanMode.SINGLE
            isAutoFocusEnabled = true
            isFlashEnabled = false

            decodeCallback = DecodeCallback {
                runOnUiThread {
                    if (cameraError != null || isFinishing || isDestroyed) return@runOnUiThread
                    val intent = Intent().also { result ->
                        result.putExtra("link", it.text)
                    }
                    setResult(RESULT_OK, intent)
                    finish()
                }
            }
            errorCallback = ErrorCallback { error ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    val detail = error.message?.takeIf { it.isNotBlank() }
                        ?: getString(R.string.feedbackOperationFailed)
                    showCameraError(getString(R.string.scannerCameraError, detail))
                }
            }
        }

        scannerView.setOnClickListener { startScannerPreview() }
        startScannerPreview()
    }

    private fun showCameraError(message: String) {
        cameraError = message
        codeScanner?.releaseResources()
    }

    private fun ensureCameraPermission() {
        if (hasCameraPermission()) return
        cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startScannerPreview() {
        window.decorView.post {
            if (
                cameraError == null &&
                !isFinishing &&
                !isDestroyed &&
                hasCameraPermission() &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            ) {
                codeScanner?.startPreview()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_CAMERA_ERROR, cameraError)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        codeScanner?.releaseResources()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        startScannerPreview()
    }

    companion object {
        private const val STATE_CAMERA_ERROR = "camera_error"
    }
}

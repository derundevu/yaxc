package io.github.derundevu.yaxc.activity

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import com.budiyev.android.codescanner.AutoFocusMode
import com.budiyev.android.codescanner.CodeScanner
import com.budiyev.android.codescanner.CodeScannerView
import com.budiyev.android.codescanner.DecodeCallback
import com.budiyev.android.codescanner.ErrorCallback
import com.budiyev.android.codescanner.ScanMode
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.presentation.designsystem.YaxcAppTheme
import io.github.derundevu.yaxc.presentation.scanner.ScannerScreen

class ScannerActivity : AppCompatActivity() {

    private var codeScanner: CodeScanner? = null
    private val cameraPermission = registerForActivityResult(RequestPermission()) { granted ->
        if (!granted) {
            Toast.makeText(
                this,
                getString(R.string.scannerCameraPermissionDenied),
                Toast.LENGTH_LONG,
            ).show()
            finish()
            return@registerForActivityResult
        }
        startScannerPreview()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            YaxcAppTheme {
                ScannerScreen(
                    onBack = ::finish,
                    onScannerViewReady = ::setupScanner,
                )
            }
        }

        ensureCameraPermission()
    }

    private fun setupScanner(scannerView: CodeScannerView) {
        if (codeScanner != null) return

        codeScanner = CodeScanner(this, scannerView).apply {
            camera = CodeScanner.CAMERA_BACK
            formats = CodeScanner.ALL_FORMATS
            autoFocusMode = AutoFocusMode.SAFE
            scanMode = ScanMode.SINGLE
            isAutoFocusEnabled = true
            isFlashEnabled = false

            decodeCallback = DecodeCallback {
                runOnUiThread {
                    val intent = Intent().also { result ->
                        result.putExtra("link", it.text)
                    }
                    setResult(RESULT_OK, intent)
                    finish()
                }
            }
            errorCallback = ErrorCallback {
                runOnUiThread {
                    Toast.makeText(
                        this@ScannerActivity,
                        getString(R.string.scannerCameraError, it.message.orEmpty()),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }

        scannerView.setOnClickListener { codeScanner?.startPreview() }
        startScannerPreview()
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
        if (!hasCameraPermission()) return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        window.decorView.post { codeScanner?.startPreview() }
    }

    override fun onPause() {
        codeScanner?.releaseResources()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        startScannerPreview()
    }
}

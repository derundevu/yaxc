package io.github.derundevu.yaxc.activity

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import com.budiyev.android.codescanner.AutoFocusMode
import com.budiyev.android.codescanner.CodeScanner
import com.budiyev.android.codescanner.CodeScannerView
import com.budiyev.android.codescanner.DecodeCallback
import com.budiyev.android.codescanner.ErrorCallback
import com.budiyev.android.codescanner.ScanMode
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.YaxcThemeStyle
import io.github.derundevu.yaxc.presentation.scanner.ScannerScreen

class ScannerActivity : AppCompatActivity() {

    private var codeScanner: CodeScanner? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            YaxcTheme(style = YaxcThemeStyle.MidnightBlue) {
                ScannerScreen(
                    onBack = ::finish,
                    onScannerViewReady = ::setupScanner,
                )
            }
        }
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
    }

    override fun onPause() {
        codeScanner?.releaseResources()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        codeScanner?.startPreview()
    }
}

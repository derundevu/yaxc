package io.github.derundevu.yaxc.activity

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import io.github.derundevu.yaxc.dto.SubscriptionMetadata
import io.github.derundevu.yaxc.presentation.connection.ConnectionInfoScreen
import io.github.derundevu.yaxc.presentation.connection.ConnectionInfoScreenState
import io.github.derundevu.yaxc.presentation.designsystem.YaxcAppTheme

class ConnectionInfoActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_SELECTED_SOURCE_NAME = "selectedSourceName"
        private const val EXTRA_SELECTED_PROFILE_NAME = "selectedProfileName"
        private const val EXTRA_SELECTED_SOURCE_METADATA = "selectedSourceMetadata"
        private const val EXTRA_SELECTED_SERVER_LABEL = "selectedServerLabel"
        private const val EXTRA_SOCKS_ADDRESS = "socksAddress"
        private const val EXTRA_SOCKS_PORT = "socksPort"
        private const val EXTRA_SOCKS_USERNAME = "socksUsername"
        private const val EXTRA_SOCKS_PASSWORD = "socksPassword"
        private const val EXTRA_PING_ADDRESS = "pingAddress"

        fun getIntent(
            context: Context,
            selectedSourceName: String,
            selectedProfileName: String,
            selectedSourceMetadataJson: String?,
            selectedServerLabel: String,
            socksAddress: String,
            socksPort: String,
            socksUsername: String,
            socksPassword: String,
            pingAddress: String,
        ): Intent {
            return Intent(context, ConnectionInfoActivity::class.java).apply {
                putExtra(EXTRA_SELECTED_SOURCE_NAME, selectedSourceName)
                putExtra(EXTRA_SELECTED_PROFILE_NAME, selectedProfileName)
                putExtra(EXTRA_SELECTED_SOURCE_METADATA, selectedSourceMetadataJson)
                putExtra(EXTRA_SELECTED_SERVER_LABEL, selectedServerLabel)
                putExtra(EXTRA_SOCKS_ADDRESS, socksAddress)
                putExtra(EXTRA_SOCKS_PORT, socksPort)
                putExtra(EXTRA_SOCKS_USERNAME, socksUsername)
                putExtra(EXTRA_SOCKS_PASSWORD, socksPassword)
                putExtra(EXTRA_PING_ADDRESS, pingAddress)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        val state = ConnectionInfoScreenState(
            selectedSourceName = intent.getStringExtra(EXTRA_SELECTED_SOURCE_NAME).orEmpty(),
            selectedProfileName = intent.getStringExtra(EXTRA_SELECTED_PROFILE_NAME).orEmpty(),
            selectedSourceMetadata = SubscriptionMetadata.fromJsonString(
                intent.getStringExtra(EXTRA_SELECTED_SOURCE_METADATA)
            ),
            selectedServerLabel = intent.getStringExtra(EXTRA_SELECTED_SERVER_LABEL).orEmpty(),
            socksAddress = intent.getStringExtra(EXTRA_SOCKS_ADDRESS).orEmpty(),
            socksPort = intent.getStringExtra(EXTRA_SOCKS_PORT).orEmpty(),
            socksUsername = intent.getStringExtra(EXTRA_SOCKS_USERNAME).orEmpty(),
            socksPassword = intent.getStringExtra(EXTRA_SOCKS_PASSWORD).orEmpty(),
            pingAddress = intent.getStringExtra(EXTRA_PING_ADDRESS).orEmpty(),
        )

        setContent {
            YaxcAppTheme {
                ConnectionInfoScreen(
                    state = state,
                    onBack = ::finish,
                )
            }
        }
    }
}

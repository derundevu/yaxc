package io.github.derundevu.yaxc.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.helper.ToastHelper
import io.github.derundevu.yaxc.service.TProxyService
import io.github.derundevu.yaxc.service.VpnTileService

class VpnActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action ?: return
        if (action == TProxyService.STOP_FAILED_SERVICE_ACTION_NAME) {
            ToastHelper.show(context, context.getString(R.string.connectionStopFailed), Toast.LENGTH_LONG)
            return
        }
        val allowed = listOf(
            TProxyService.START_VPN_SERVICE_ACTION_NAME,
            TProxyService.STOP_VPN_SERVICE_ACTION_NAME,
            TProxyService.NEW_CONFIG_SERVICE_ACTION_NAME,
            TProxyService.STATUS_VPN_SERVICE_ACTION_NAME,
        )
        val label = intent.getStringExtra("profile")?.takeIf(String::isNotBlank)
            ?: context.getString(R.string.appName)
        if (!allowed.contains(action)) return
        val message = when (action) {
            TProxyService.START_VPN_SERVICE_ACTION_NAME -> context.getString(R.string.feedbackConnected, label)
            TProxyService.NEW_CONFIG_SERVICE_ACTION_NAME -> context.getString(R.string.feedbackConfigApplied, label)
            TProxyService.STOP_VPN_SERVICE_ACTION_NAME -> context.getString(R.string.feedbackDisconnected)
            else -> null
        }
        if (message != null) ToastHelper.show(context, message)
        Intent(context, VpnTileService::class.java).also {
            val tileAction = if (action == TProxyService.STATUS_VPN_SERVICE_ACTION_NAME) {
                if (intent.getBooleanExtra("isRunning", false)) TProxyService.START_VPN_SERVICE_ACTION_NAME
                else TProxyService.STOP_VPN_SERVICE_ACTION_NAME
            } else action
            it.putExtra("action", tileAction)
            it.putExtra("label", label)
            context.startService(it)
        }
    }

}

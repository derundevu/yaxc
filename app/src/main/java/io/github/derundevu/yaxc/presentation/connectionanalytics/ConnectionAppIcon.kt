package io.github.derundevu.yaxc.presentation.connectionanalytics

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.widget.ImageView
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Positive and negative results are cached; package labels always come from captured records. */
internal class ConnectionIconCache(context: Context) {
    private val packages = context.applicationContext.packageManager
    private data class Entry(val drawable: Drawable?)
    private val entries = LruCache<String, Entry>(128)

    suspend fun load(packageName: String): Drawable? = withContext(Dispatchers.IO) {
        synchronized(entries) {
            entries.get(packageName) ?: Entry(try {
                packages.getApplicationInfo(packageName, 0).loadIcon(packages)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            } catch (_: SecurityException) {
                null
            }).also { entries.put(packageName, it) }
        }.drawable
    }
}

@Composable
internal fun ConnectionAppIcon(identity: ConnectionIdentity, cache: ConnectionIconCache, size: Dp = 32.dp) {
    val packageName = identity.packages.firstOrNull()
    val drawable by produceState<Drawable?>(null, packageName, cache) {
        value = packageName?.let { cache.load(it) }
    }
    if (drawable != null) {
        AndroidView(factory = { ImageView(it) }, update = { it.setImageDrawable(drawable) }, modifier = Modifier.size(size))
    } else {
        Icon(if (identity.kind == ConnectionIdentityKind.App) Icons.Outlined.Apps else Icons.Outlined.Dns,
            contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(size))
    }
}

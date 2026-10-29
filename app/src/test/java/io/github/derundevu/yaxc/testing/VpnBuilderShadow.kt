package io.github.derundevu.yaxc.testing

import android.net.VpnService
import java.net.InetAddress
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject

/** Android's string overloads call a hidden InetAddress API absent from the host JDK. */
@Implements(VpnService.Builder::class)
class VpnBuilderShadow {
    @RealObject private lateinit var builder: VpnService.Builder

    @Implementation
    fun addAddress(address: String, prefix: Int): VpnService.Builder =
        builder.addAddress(InetAddress.getByName(address), prefix)

    @Implementation
    fun addRoute(address: String, prefix: Int): VpnService.Builder =
        builder.addRoute(InetAddress.getByName(address), prefix)

    @Implementation
    fun addDnsServer(address: String): VpnService.Builder =
        builder.addDnsServer(InetAddress.getByName(address))

    @Implementation
    fun verifyApp(packageName: String) {
        // Builder's private verifier uses the absent system-server binder on the host.
        RuntimeEnvironment.getApplication().packageManager.getApplicationInfo(packageName, 0)
    }
}

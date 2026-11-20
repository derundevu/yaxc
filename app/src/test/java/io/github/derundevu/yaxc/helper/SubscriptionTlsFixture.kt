package io.github.derundevu.yaxc.helper

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.net.InetAddress
import java.security.KeyStore
import java.util.Base64
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** Fixed, test-only localhost identity. Never used for provider or production trust. */
internal class SubscriptionTlsFixture : Closeable {
    private val previousFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
    val server: SubscriptionHttpServer

    init {
        val store = KeyStore.getInstance("PKCS12")
        store.load(ByteArrayInputStream(Base64.getDecoder().decode(IDENTITY)), "synthetic-only".toCharArray())
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keys.init(store, "synthetic-only".toCharArray())
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trust.init(store)
        val serverContext = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
        val clientContext = SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
        server = SubscriptionHttpServer(serverContext.serverSocketFactory.createServerSocket(
            0, 16, InetAddress.getByName("127.0.0.1")), "https")
        HttpsURLConnection.setDefaultSSLSocketFactory(clientContext.socketFactory)
    }

    override fun close() {
        try { server.close() }
        finally { HttpsURLConnection.setDefaultSSLSocketFactory(previousFactory) }
    }

    private companion object {
        const val IDENTITY = "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"
    }
}

package com.soheil.ember.vpn.upstream

import com.soheil.ember.data.AppLogger
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

private const val TAG = "UpstreamTrust"

private val PEM_BLOCK = Regex("-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----", RegexOption.DOT_MATCHES_ALL)

object UpstreamTrust {

    @Volatile private var bundledRoots: (() -> InputStream?)? = null
    @Volatile private var cached: TrustManagerFactory? = null
    @Volatile private var built = false
    private val lock = Any()

    /** [bundledRoots] opens the bundled GlobalSign root PEM (an Android raw resource, or a classpath resource on desktop). */
    fun init(bundledRoots: () -> InputStream?) {
        this.bundledRoots = bundledRoots
    }

    fun trustManagerFactory(): TrustManagerFactory? {
        if (built) return cached
        synchronized(lock) {
            if (!built) {
                cached = runCatching { build() }
                    .onFailure { AppLogger.e(TAG, "could not build the merged trust store; using the system default", it) }
                    .getOrNull()
                built = true
            }
            return cached
        }
    }

    private fun build(): TrustManagerFactory? {
        val merged = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }

        var systemCount = 0
        runCatching {
            val system = runCatching { KeyStore.getInstance("AndroidCAStore").apply { load(null, null) } }.getOrNull()
            if (system != null) {
                for (alias in system.aliases()) {
                    val cert = system.getCertificate(alias) as? X509Certificate ?: continue
                    merged.setCertificateEntry("system:$alias", cert)
                    systemCount++
                }
            } else {
                val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                tmf.init(null as KeyStore?)
                for (tm in tmf.trustManagers.filterIsInstance<X509TrustManager>()) {
                    for (cert in tm.acceptedIssuers) {
                        merged.setCertificateEntry("system:$systemCount", cert)
                        systemCount++
                    }
                }
            }
        }.onFailure {
            AppLogger.w(TAG, "could not read the system CA store; relying on the bundled roots only", it)
        }

        var bundledCount = 0
        val open = bundledRoots
        if (open == null) {
            AppLogger.w(TAG, "no bundled-roots provider; the bundled roots were not loaded")
        } else {
            runCatching {
                val pem = (open() ?: error("bundled roots resource missing")).use { it.readBytes() }
                    .toString(Charsets.US_ASCII)
                val factory = CertificateFactory.getInstance("X.509")
                for (block in PEM_BLOCK.findAll(pem)) {
                    runCatching {
                        val cert = factory.generateCertificate(
                            ByteArrayInputStream(block.value.toByteArray(Charsets.US_ASCII)),
                        ) as X509Certificate
                        merged.setCertificateEntry("bundled:$bundledCount", cert)
                        bundledCount++
                    }
                }
            }.onFailure {
                AppLogger.w(TAG, "could not load the bundled GlobalSign roots", it)
            }
        }

        if (systemCount == 0 && bundledCount == 0) return null

        AppLogger.i(TAG, "upstream TLS trust: $systemCount system root(s) + $bundledCount bundled root(s)")
        return TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(merged) }
    }
}

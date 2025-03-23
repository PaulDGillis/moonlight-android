package com.limelight.nvstream.http

import android.annotation.SuppressLint
import com.limelight.nvstream.http.model.ServerInfo
import com.limelight.utils.DeviceUtils
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.URLProtocol
import io.ktor.serialization.kotlinx.xml.DefaultXml
import io.ktor.serialization.kotlinx.xml.xml
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import nl.adaptivity.xmlutil.serialization.XML
import java.net.Socket
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.NoSuchAlgorithmException
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

class KtorClient(
    private val addrHost: String,
    private val httpPort: Int,
    private val uniqueId: String,
    internal val cryptoProvider: LimelightCryptoProvider,
    serverCert: X509Certificate? = null
) {
    companion object {
        const val SHORT_CONNECTION_TIMEOUT = 3000L
        const val LONG_CONNECTION_TIMEOUT = 5000L
        const val READ_TIMEOUT = 7000L
    }

    var serverCert: X509Certificate? = serverCert
        private set

    private val deviceName: String = DeviceUtils.getModel()
    val xml: XML = DefaultXml

    var isHttps: Boolean = serverCert != null
        private set

    val clientLock = Mutex()
    var client = buildClient(URLProtocol.HTTP, httpPort)
        private set

    private val keyManager = object : X509KeyManager {
        override fun chooseClientAlias(keyTypes: Array<String?>?, issuers: Array<Principal?>?, socket: Socket?): String
            = "Limelight-RSA"

        override fun chooseServerAlias(keyType: String?, issuers: Array<Principal?>?, socket: Socket?): String?
            = null

        override fun getCertificateChain(alias: String?): Array<X509Certificate?>
            = arrayOf<X509Certificate?>(cryptoProvider.clientCertificate)

        override fun getClientAliases(keyType: String?, issuers: Array<Principal?>?): Array<String?>?
            = null

        override fun getPrivateKey(alias: String?): PrivateKey? = cryptoProvider.clientPrivateKey

        override fun getServerAliases(keyType: String?, issuers: Array<Principal?>?): Array<String?>?
            = null
    }

    private val defaultTrustManager: X509TrustManager = try {
        TrustManagerFactory
            .getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers
            .firstNotNullOfOrNull { it as? X509TrustManager }
            ?: throw IllegalStateException("No X509 trust manager found")
    } catch (e: NoSuchAlgorithmException) {
        throw RuntimeException(e)
    } catch (e: KeyStoreException) {
        throw RuntimeException(e)
    }

    @SuppressLint("CustomX509TrustManager")
    private val serverTrustManager = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate?> = arrayOfNulls<X509Certificate>(0)

        override fun checkClientTrusted(certs: Array<X509Certificate?>?, authType: String?) {
            throw IllegalStateException("Should never be called")
        }

        @Throws(CertificateException::class)
        override fun checkServerTrusted(certs: Array<X509Certificate?>, authType: String?) {
            try {
                // Try the default trust manager first to allow pairing with certificates
                // that chain up to a trusted root CA. This will raise CertificateException
                // if the certificate is not trusted (expected for GFE's self-signed certs).
                defaultTrustManager.checkServerTrusted(certs, authType)
            } catch (e: CertificateException) {
                // Check the server certificate if we've paired to this host
                if (certs.size == 1 && serverCert != null) {
                    if (certs[0] != serverCert) {
                        throw CertificateException("Certificate mismatch")
                    }
                } else {
                    // The cert chain doesn't look like a self-signed cert or we don't have
                    // a certificate pinned, so re-throw the original validation error.
                    throw e
                }
            }
        }
    }

    fun buildClient(protocol: URLProtocol, port: Int) = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            xml(format = xml)
        }
        install(HttpTimeout) {
            connectTimeoutMillis = LONG_CONNECTION_TIMEOUT
            socketTimeoutMillis = READ_TIMEOUT
        }
        defaultRequest {
            host = addrHost
            this.port = port
            url {
                this.protocol = protocol
                parameters.append("devicename", deviceName)
                parameters.append("uniqueid", uniqueId)
            }
        }
        engine {
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(arrayOf(keyManager), arrayOf(serverTrustManager), SecureRandom())
            config {
                sslSocketFactory(sslContext.socketFactory, serverTrustManager)
                hostnameVerifier { hostname, session ->
                    try {
                        val certificates = session.peerCertificates
                        if (certificates.size == 1 && certificates[0].equals(serverCert)) {
                            // Allow any hostname if it's our pinned cert
                            return@hostnameVerifier true
                        }
                    } catch (e: SSLPeerUnverifiedException) {
                        e.printStackTrace()
                    }

                    // Fall back to default HostnameVerifier for validating CA-issued certs
                    HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, session)
                }
            }
        }
    }

    suspend fun updateClientProtocol(serverCert: X509Certificate? = null) {
        if (serverCert == null) {
            clientLock.withLock {
                client.close()
                isHttps = false
                client = buildClient(URLProtocol.HTTP, httpPort)
            }
        } else {
            val httpsPort = getServerInfo().httpsPort
            clientLock.withLock {
                client.close()
                isHttps = true
                client = buildClient(URLProtocol.HTTPS, httpsPort)
            }
        }
    }

    suspend fun getServerInfo(): ServerInfo {
        val response: HttpResponse = client.get("serverinfo")
        return xml.decodeFromString<ServerInfo>(response.body())
    }
}
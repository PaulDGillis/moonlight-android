package com.limelight.nvstream.http

import com.limelight.nvstream.http.ComputerDetails.AddressTuple
import com.limelight.nvstream.http.model.ServerInfo
import io.ktor.client.HttpClient
import io.ktor.client.call.HttpClientCall
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.request
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.xml.DefaultXml
import io.ktor.serialization.kotlinx.xml.xml
import io.ktor.util.date.GMTDate
import io.ktor.util.filter
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import kotlinx.serialization.decodeFromString
import nl.adaptivity.xmlutil.serialization.XML
import org.bouncycastle.util.Arrays.append
import java.net.Socket
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.NoSuchAlgorithmException
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.CoroutineContext

class KtorClient(
    address: AddressTuple,
    cryptoProvider: LimelightCryptoProvider,
    var serverCert: X509Certificate? = null
) {
    private val keyManager = object : X509KeyManager {
        override fun chooseClientAlias(keyTypes: Array<String?>?, issuers: Array<Principal?>?, socket: Socket?): String
            = "Limelight-RSA"

        override fun chooseServerAlias(keyType: String?, issuers: Array<Principal?>?, socket: Socket?): String?
            = null

        override fun getCertificateChain(alias: String?): Array<X509Certificate?>
            = arrayOf<X509Certificate?>(cryptoProvider.getClientCertificate())

        override fun getClientAliases(keyType: String?, issuers: Array<Principal?>?): Array<String?>?
            = null

        override fun getPrivateKey(alias: String?): PrivateKey? = cryptoProvider.getClientPrivateKey()

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

    val xml: XML = DefaultXml

    val client = HttpClient(Android) {
        install(ContentNegotiation) {
            xml(format = xml)
        }
        defaultRequest {
            host = address.address
            port = address.port
            url {
                protocol = URLProtocol.HTTP
            }
        }
        engine {
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(arrayOf(keyManager), arrayOf(serverTrustManager), SecureRandom())
            sslManager = { httpsURLConnection ->
                httpsURLConnection.sslSocketFactory = sslContext.socketFactory
            }
        }
    }

    suspend fun getServerInfo(): ServerInfo {
        val response: HttpResponse = client.get("serverinfo")
        return xml.decodeFromString<ServerInfo>(response.body())
    }
}
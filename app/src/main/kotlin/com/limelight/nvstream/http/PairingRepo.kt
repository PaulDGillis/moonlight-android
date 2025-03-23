package com.limelight.nvstream.http

import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.appendPathSegments
import io.ktor.http.parameters
import kotlinx.serialization.decodeFromString
import io.ktor.serialization.kotlinx.xml.DefaultXml
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XML
import nl.adaptivity.xmlutil.serialization.XmlElement
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@SerialName("root")
@Serializable
data class GetServerCertResponse(
    @SerialName("status_code")
    val statusCode: Int = 0,

    @XmlElement
    @SerialName("paired")
    val pairedStatus: Int,

    @XmlElement
    @SerialName("plaincert")
    val plainCert: String? = null
)

@Serializable
data class ChallengeResponse(
    @XmlElement
    @SerialName("paired")
    val pairedStatus: Int,

    @XmlElement
    @SerialName("challengeresponse")
    val challengeResponse: String? = null,
)

@Serializable
data class ServerChallengeResponse(
    @XmlElement
    @SerialName("paired")
    val pairedStatus: Int,

    @XmlElement
    @SerialName("pairingsecret")
    val pairingSecret: String? = null
)

@Serializable
data class GenericPairingResponse(
    @XmlElement
    @SerialName("paired")
    val pairedStatus: Int
)

class PairingRepo(
    val ktorClient: KtorClient
) {
    // Testing out this idea, Probably will remove later
    sealed interface Params {
        object UpdateState: Params {
            const val KEY = "updateState"

            const val ONE = "1"
        }

        object Phrase: Params {
            const val KEY = "phrase"

            const val GET_SERVER_CERT = "getservercert"
            const val PAIR_CHALLENGE = "pairchallenge"
        }

        object Salt { const val KEY = "salt" }
        object ClientCert { const val KEY = "clientcert" }
        object OtpAuth { const val KEY = "otpauth" }
        object ClientChallenge { const val KEY = "clientchallenge" }
        object ServerChallengeResponse { const val KEY = "serverchallengeresp" }
        object ClientPairingSecret { const val KEY = "clientpairingsecret" }
    }

    private val xml: XML = DefaultXml
    // TODO find better solution for this
    private val notHTTPSException = Exception("HTTPS Required for function")

    @OptIn(ExperimentalUuidApi::class)
    suspend fun getServerCert(
        salt: String,
        clientCert: String,
        passphraseHexString: String? = null
    ): GetServerCertResponse {
        val response = ktorClient.client.get {
            timeout {
                connectTimeoutMillis = KtorClient.LONG_CONNECTION_TIMEOUT
            }
            url {
                appendPathSegments("pair")
                parameters.apply {
                    append(Params.UpdateState.KEY, Params.UpdateState.ONE)
                    append(Params.Phrase.KEY, Params.Phrase.GET_SERVER_CERT)
                    append(Params.Salt.KEY, salt)
                    append(Params.ClientCert.KEY, clientCert)
                    if (passphraseHexString != null) {
                        append(Params.OtpAuth.KEY, passphraseHexString)
                    }
                    append("uuid", Uuid.random().toString())
                }
            }
        }

        return xml.decodeFromString<GetServerCertResponse>(response.bodyAsText())
    }

    suspend fun sendClientChallenge(
        encryptedChallengeHexString: String
    ): ChallengeResponse {
        if (ktorClient.isHttps.not()) throw notHTTPSException

        val response = ktorClient.client.get {
            url {
                appendPathSegments("pair")
                parameters.apply {
                    append(Params.UpdateState.KEY, Params.UpdateState.ONE)
                    append(Params.ClientChallenge.KEY, encryptedChallengeHexString)
                }
            }
        }

        return xml.decodeFromString(response.bodyAsText())
    }

    suspend fun sendServerChallengeResponse(
        encryptedChallengeHexString: String
    ): ServerChallengeResponse {
        if (ktorClient.isHttps.not()) throw notHTTPSException

        val response = ktorClient.client.get {
            url {
                appendPathSegments("pair")
                parameters.apply {
                    append(Params.UpdateState.KEY, Params.UpdateState.ONE)
                    append(Params.ServerChallengeResponse.KEY, encryptedChallengeHexString)
                }
            }
        }

        return xml.decodeFromString(response.bodyAsText())
    }

    suspend fun sendClientPairingSecret(
        clientPairingSecretHexString: String
    ): GenericPairingResponse {
        if (ktorClient.isHttps.not()) throw notHTTPSException

        val response = ktorClient.client.get {
            url {
                appendPathSegments("pair")
                parameters.apply {
                    append(Params.UpdateState.KEY, Params.UpdateState.ONE)
                    append(Params.ClientPairingSecret.KEY, clientPairingSecretHexString)
                }
            }
        }
        return xml.decodeFromString(response.bodyAsText())
    }

    // Attempts this only on https
    suspend fun sendPairingChallenge(): GenericPairingResponse {
        if (ktorClient.isHttps.not()) throw notHTTPSException

        val response = ktorClient.client.get {
            url {
                appendPathSegments("pair")
                parameters.apply {
                    append(Params.UpdateState.KEY, Params.UpdateState.ONE)
                    append(Params.Phrase.KEY, Params.Phrase.PAIR_CHALLENGE)
                }
            }
        }

        return xml.decodeFromString(response.bodyAsText())
    }

    // TODO should have read timeout
    suspend fun unpair() {
        ktorClient.client.get {
            url.appendPathSegments("unpair")
        }.bodyAsText()
    }
}
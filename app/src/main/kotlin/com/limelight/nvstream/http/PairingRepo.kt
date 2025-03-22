package com.limelight.nvstream.http

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ParametersBuilder
import io.ktor.http.appendPathSegments
import kotlinx.serialization.decodeFromString
import io.ktor.serialization.kotlinx.xml.DefaultXml
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XML
import nl.adaptivity.xmlutil.serialization.XmlElement

@Serializable
data class GetServerCertResponse(
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
    sealed class Params(open val pValue: String) {
        sealed class UpdateState(override val pValue: String): Params(pValue) {
            object ToOne: UpdateState("1")
        }

        sealed class Phrase(override val pValue: String): Params(pValue) {
            object GetServerCert: Phrase("getservercert")
            object PairChallenge: Phrase("pairchallenge")
        }

        class Salt(override val pValue: String): Params(pValue)
        class ClientCert(override val pValue: String): Params(pValue)
        class OtpAuth(override val pValue: String): Params(pValue)
        class ClientChallenge(override val pValue: String): Params(pValue)
        class ServerChallengeResponse(override val pValue: String): Params(pValue)
        class ClientPairingSecret(override val pValue: String): Params(pValue)

        val pKey: String = when (this) {
            is Phrase -> "phrase"
            is UpdateState -> "updateState"
            is Salt -> "salt"
            is ClientCert -> "clientcert"
            is ClientChallenge -> "clientchallenge"
            is ClientPairingSecret -> "clientpairingsecret"
            is OtpAuth -> "otpauth"
            is ServerChallengeResponse -> "serverchallengeresp"
        }
    }

    private fun ParametersBuilder.append(builderAction: MutableList<Params>.() -> Unit) {
        buildList(builderAction).forEach { append(it.pKey, it.pValue) }
    }

    private val xml: XML = DefaultXml
    private val notHTTPSException = Exception("HTTPS Required for function")

    suspend fun getServerCert(
        salt: String,
        clientCert: String,
        passphraseHexString: String? = null
    ): GetServerCertResponse {
        val response = ktorClient.client.get {
            url {
                appendPathSegments("pair")
                parameters.append {
                    add(Params.UpdateState.ToOne)
                    add(Params.Phrase.GetServerCert)
                    add(Params.Salt(salt))
                    add(Params.ClientCert(clientCert))
                    if (passphraseHexString != null) {
                        add(Params.OtpAuth(passphraseHexString))
                    }
                }
            }
        }

        return xml.decodeFromString<GetServerCertResponse>(response.bodyAsText())
    }

    // TODO Should have read timeout
    // TODO Force https with earlier Server Cert
    suspend fun sendClientChallenge(
        encryptedChallengeHexString: String
    ): ChallengeResponse {
        if (ktorClient.isHttps.not()) throw notHTTPSException

        val response = ktorClient.client.get {
            url {
                appendPathSegments("pair")
                parameters.append {
                    add(Params.UpdateState.ToOne)
                    add(Params.ClientChallenge(encryptedChallengeHexString))
                }
            }
        }

        return xml.decodeFromString(response.bodyAsText())
    }

    // TODO Should have read timeout
    // TODO Force https with earlier Server Cert
    suspend fun sendServerChallengeResponse(
        encryptedChallengeHexString: String
    ): ServerChallengeResponse {
        if (ktorClient.isHttps.not()) throw notHTTPSException

        val response = ktorClient.client.get {
            url {
                appendPathSegments("pair")
                parameters.append {
                    add(Params.UpdateState.ToOne)
                    add(Params.ServerChallengeResponse(encryptedChallengeHexString))
                }
            }
        }

        return xml.decodeFromString(response.bodyAsText())
    }

    // TODO should have read timeout
    // TODO Force https with earlier Server Cert
    suspend fun sendClientPairingSecret(
        clientPairingSecretHexString: String
    ): GenericPairingResponse {
        if (ktorClient.isHttps.not()) throw notHTTPSException

        val response = ktorClient.client.get {
            url {
                appendPathSegments("pair")
                parameters.append {
                    add(Params.UpdateState.ToOne)
                    add(Params.ClientPairingSecret(clientPairingSecretHexString))
                }
            }
        }
        return xml.decodeFromString(response.bodyAsText())
    }

    // TODO should have read timeout
    // TODO Force https with earlier Server Cert
    // Attempts this only on https
    suspend fun sendPairingChallenge(): GenericPairingResponse {
        if (ktorClient.isHttps.not()) throw notHTTPSException

        val response = ktorClient.client.get {
            url {
                appendPathSegments("pair")
                parameters.append {
                    add(Params.UpdateState.ToOne)
                    add(Params.Phrase.PairChallenge)
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
package com.limelight.nvstream.http.model

import com.limelight.nvstream.http.NvHTTP
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import kotlin.text.split

@Serializable
@SerialName("root")
data class ServerInfo(
    @SerialName("status_code")
    val statusCode: Int = 0,
    @XmlElement
    val hostname: String? = null,
    @XmlElement @SerialName("appversion")
    val appVersion: String,
    @XmlElement @SerialName("GfeVersion")
    val gfeVersion: String? = null,
    @XmlElement @SerialName("uniqueid")
    val uniqueId: String? = null,
    @XmlElement @SerialName("HttpsPort")
    val httpsPort: Int = NvHTTP.DEFAULT_HTTPS_PORT,
    @XmlElement @SerialName("ExternalPort")
    val externalPort: Int = 0,
    @XmlElement @SerialName("MaxLumaPixelsHEVC")
    val maxLumaPixelsHEVC: Int = 0,
    @XmlElement val mac: String? = null,
    @XmlElement @SerialName("Permission")
    val permission: Int = 0,
    @XmlElement @SerialName("LocalIP")
    val localIP: String? = null,
    @XmlElement @SerialName("ServerCodecModeSupport")
    val serverCodecModeSupport: Int = 262145,
    @XmlElement @SerialName("PairStatus")
    val pairStatus: Int = 0,
    @XmlElement @SerialName("currentgame")
    val currentGame: Int = 0,
    @XmlElement
    val state: String? = null,
) {
    val serverAppVersionQuad by lazy {
        requireNotNull(appVersion) { "Missing server version field" }

        val serverVersionSplit = appVersion.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }
        require(serverVersionSplit.size == 4) { "Malformed server version field: $appVersion" }

        serverVersionSplit.map(String::toInt).toTypedArray()
    }

    val serverMajorVersion: Int by lazy { serverAppVersionQuad[0] }
}
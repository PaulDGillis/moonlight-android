package com.limelight.nvstream.http.model

import com.limelight.nvstream.http.NvHTTPKtx
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement

@Serializable
@SerialName("root")
data class ServerInfo(
    @SerialName("status_code")
    val statusCode: Int = 0,
    @XmlElement
    val hostname: String? = null,
    @XmlElement @SerialName("appversion")
    val appVersion: String? = null,
    @XmlElement @SerialName("GfeVersion")
    val gfeVersion: String? = null,
    @XmlElement @SerialName("uniqueid")
    val uniqueId: String? = null,
    @XmlElement @SerialName("HttpsPort")
    val httpsPort: Int = NvHTTPKtx.DEFAULT_HTTPS_PORT,
    @XmlElement @SerialName("ExternalPort")
    val externalPort: Int = 0,
    @XmlElement @SerialName("MaxLumaPixelsHEVC")
    val maxLumaPixelsHEVC: Int = 0,
    @XmlElement val mac: String? = null,
    @XmlElement @SerialName("Permission")
    val permission: Int = 0,
    @XmlElement @SerialName("localIP")
    val localIP: String? = null,
    @XmlElement @SerialName("ServerCodecModeSupport")
    val serverCodecModeSupport: Int = 262145,
    @XmlElement @SerialName("PairStatus")
    val pairStatus: Int = 0,
    @XmlElement @SerialName("currentgame")
    val currentGame: Int = 0,
    @XmlElement
    val state: String? = null,
)
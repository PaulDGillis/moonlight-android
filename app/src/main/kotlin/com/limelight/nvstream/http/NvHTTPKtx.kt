package com.limelight.nvstream.http

import com.limelight.BuildConfig
import com.limelight.LimeLog
import com.limelight.nvstream.ConnectionContext
import com.limelight.nvstream.http.ComputerDetails.AddressTuple
import com.limelight.nvstream.http.NvHTTPKtx.Companion.DEFAULT_HTTPS_PORT
import com.limelight.nvstream.http.NvHTTPKtx.Companion.bytesToHex
import com.limelight.nvstream.http.NvHTTPKtx.Companion.getDefaultTrustManager
import com.limelight.nvstream.http.NvHTTPKtx.Companion.hexArray
import com.limelight.nvstream.http.NvHTTPKtx.Companion.makeTuple
import com.limelight.nvstream.http.NvHTTPKtx.Companion.verbose
import com.limelight.nvstream.http.NvHTTPKtx.Companion.verifyResponseStatus
import com.limelight.nvstream.http.PairingManager.PairState
import com.limelight.nvstream.jni.MoonBridge
import com.limelight.utils.DeviceUtils
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.ResponseBody
import org.koin.dsl.module
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.io.StringReader
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Proxy
import java.net.Socket
import java.security.KeyManagementException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.NoSuchAlgorithmException
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.LinkedList
import java.util.Stack
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

class NvHTTPKtx(
    val client: HttpClient,
    address: AddressTuple,
    httpsPort: Int,
    private val uniqueId: String?,
    private var serverCert: X509Certificate?,
    cryptoProvider: LimelightCryptoProvider
) {

    private val deviceName: String? = DeviceUtils.getModel()

    private var baseUrlHttp: HttpUrl? = null

    private var httpsPort: Int

    private var httpClientLongConnectTimeout: OkHttpClient? = null
    private var httpClientLongConnectNoReadTimeout: OkHttpClient? = null
    private var httpClientShortConnectTimeout: OkHttpClient? = null

    private var defaultTrustManager: X509TrustManager? = null
    private var trustManager: X509TrustManager? = null
    private var keyManager: X509KeyManager? = null

    fun setServerCert(serverCert: X509Certificate?) {
        this.serverCert = serverCert
    }

    private fun initializeHttpState(cryptoProvider: LimelightCryptoProvider) {
        keyManager = object : X509KeyManager {
            override fun chooseClientAlias(
                keyTypes: Array<String?>?,
                issuers: Array<Principal?>?, socket: Socket?
            ): String {
                return "Limelight-RSA"
            }

            override fun chooseServerAlias(
                keyType: String?, issuers: Array<Principal?>?,
                socket: Socket?
            ): String? {
                return null
            }

            override fun getCertificateChain(alias: String?): Array<X509Certificate?> {
                return arrayOf<X509Certificate?>(cryptoProvider.getClientCertificate())
            }

            override fun getClientAliases(
                keyType: String?,
                issuers: Array<Principal?>?
            ): Array<String?>? {
                return null
            }

            override fun getPrivateKey(alias: String?): PrivateKey? {
                return cryptoProvider.getClientPrivateKey()
            }

            override fun getServerAliases(
                keyType: String?,
                issuers: Array<Principal?>?
            ): Array<String?>? {
                return null
            }
        }

        defaultTrustManager = getDefaultTrustManager()

        val hv: HostnameVerifier = object : HostnameVerifier {
            override fun verify(hostname: String?, session: SSLSession): Boolean {
                try {
                    val certificates = session.peerCertificates
                    if (certificates.size == 1 && certificates[0] == this@NvHTTPKtx.serverCert) {
                        // Allow any hostname if it's our pinned cert
                        return true
                    }
                } catch (e: SSLPeerUnverifiedException) {
                    e.printStackTrace()
                }

                // Fall back to default HostnameVerifier for validating CA-issued certs
                return HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, session)
            }
        }

        httpClientLongConnectTimeout = OkHttpClient.Builder()
            .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
            .hostnameVerifier(hv)
            .readTimeout(READ_TIMEOUT.toLong(), TimeUnit.MILLISECONDS)
            .connectTimeout(LONG_CONNECTION_TIMEOUT.toLong(), TimeUnit.MILLISECONDS)
            .proxy(Proxy.NO_PROXY)
            .build()

        httpClientShortConnectTimeout = httpClientLongConnectTimeout!!.newBuilder()
            .connectTimeout(SHORT_CONNECTION_TIMEOUT.toLong(), TimeUnit.MILLISECONDS)
            .build()

        httpClientLongConnectNoReadTimeout = httpClientLongConnectTimeout!!.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    @Throws(IOException::class)
    fun getHttpsUrl(likelyOnline: Boolean): HttpUrl {
        if (httpsPort == 0) {
            // Fetch the HTTPS port if we don't have it already
            httpsPort = getHttpsPort(
                openHttpConnectionToString(
                    (if (likelyOnline) httpClientLongConnectTimeout else httpClientShortConnectTimeout)!!,
                    baseUrlHttp!!, "serverinfo"
                )
            )
        }

        return HttpUrl.Builder().scheme("https").host(baseUrlHttp!!.host).port(httpsPort).build()
    }

    @Throws(IOException::class, XmlPullParserException::class)
    fun getServerInfo(likelyOnline: Boolean): String {
        val resp: String

        // If we believe the PC is online, give it a little extra time to respond
        val client =
            (if (likelyOnline) httpClientLongConnectTimeout else httpClientShortConnectTimeout)!!


        //
        // TODO: Shield Hub uses HTTP for this and is able to get an accurate PairStatus with HTTP.
        // For some reason, we always see PairStatus is 0 over HTTP and only 1 over HTTPS. It looks
        // like there are extra request headers required to make this stuff work over HTTP.
        //

        // When we have a pinned cert, use HTTPS to fetch serverinfo and fall back on cert mismatch
        if (serverCert != null) {
            try {
                try {
                    resp =
                        openHttpConnectionToString(client, getHttpsUrl(likelyOnline), "serverinfo")
                } catch (e: SSLHandshakeException) {
                    // Detect if we failed due to a server cert mismatch
                    if (e.cause is CertificateException) {
                        // Jump to the GfeHttpResponseException exception handler to retry
                        // over HTTP which will allow us to pair again to update the cert
                        throw HostHttpResponseException(401, "Server certificate mismatch")
                    } else {
                        throw e
                    }
                }

                // This will throw an exception if the request came back with a failure status.
                // We want this because it will throw us into the HTTP case if the client is unpaired.
                getServerVersion(resp)
            } catch (e: HostHttpResponseException) {
                if (e.errorCode == 401) {
                    // Cert validation error - fall back to HTTP
                    return openHttpConnectionToString(client, baseUrlHttp!!, "serverinfo")
                }

                // If it's not a cert validation error, throw it
                throw e
            }

            return resp
        } else {
            // No pinned cert, so use HTTP
            return openHttpConnectionToString(client, baseUrlHttp!!, "serverinfo")
        }
    }

    @Throws(IOException::class, XmlPullParserException::class)
    fun getComputerDetails(serverInfo: String?): ComputerDetails {
        val details = ComputerDetails()

        details.name = getXmlString(serverInfo, "hostname", false)
        if (details.name == null || details.name.isEmpty()) {
            details.name = "UNKNOWN"
        }

        // UUID is mandatory to determine which machine is responding
        details.uuid = getXmlString(serverInfo, "uniqueid", true)

        val permStr: String? = getXmlString(serverInfo, "Permission", false)
        if (permStr != null) {
            try {
                details.permission = permStr.toInt()
            } catch (_: Exception) {
                details.permission = -1
            }
        }

        details.httpsPort = getHttpsPort(serverInfo)

        details.macAddress = getXmlString(serverInfo, "mac", false)

        // FIXME: Do we want to use the current port?
        details.localAddress =
            makeTuple(getXmlString(serverInfo, "LocalIP", false), baseUrlHttp!!.port)

        // This is missing on on recent GFE versions, but it's present on Sunshine
        details.externalPort = getExternalPort(serverInfo)
        details.remoteAddress =
            makeTuple(getXmlString(serverInfo, "ExternalIP", false), details.externalPort)

        details.vDisplaySupported = getServerSupportsVDisplay(serverInfo)
        if (details.vDisplaySupported) {
            details.vDisplayDriverReady = getServerVDisplayDriverReady(serverInfo)
        }

        details.serverCommands = getServerCmds(serverInfo)

        details.pairState = getPairState(serverInfo)
        details.runningGameId = getCurrentGame(serverInfo)

        // The MJOLNIR codename was used by GFE but never by any third-party server
        details.nvidiaServer = getXmlString(serverInfo, "state", true)!!.contains("MJOLNIR")

        // We could reach it so it's online
        details.state = ComputerDetails.State.ONLINE

        return details
    }

    @Throws(IOException::class, XmlPullParserException::class)
    fun getComputerDetails(likelyOnline: Boolean): ComputerDetails {
        return getComputerDetails(getServerInfo(likelyOnline))
    }

    // This hack is Android-specific but we do it on all platforms
    // because it doesn't really matter
    private fun performAndroidTlsHack(client: OkHttpClient): OkHttpClient {
        // Doing this each time we create a socket is required
        // to avoid the SSLv3 fallback that causes connection failures
        try {
            val sc = SSLContext.getInstance("TLS")
            sc.init(
                arrayOf<KeyManager?>(keyManager),
                arrayOf<TrustManager?>(trustManager),
                SecureRandom()
            )
            return client.newBuilder().sslSocketFactory(sc.socketFactory, trustManager!!)
                .build()
        } catch (e: NoSuchAlgorithmException) {
            throw RuntimeException(e)
        } catch (e: KeyManagementException) {
            throw RuntimeException(e)
        }
    }

    private fun getCompleteUrl(baseUrl: HttpUrl, path: String, query: String?): HttpUrl {
        return baseUrl.newBuilder()
            .addPathSegments(path)
            .query(query)
            .addQueryParameter("devicename", deviceName)
            .addQueryParameter("uniqueid", uniqueId)
            .addQueryParameter("uuid", UUID.randomUUID().toString())
            .build()
    }

    // Read timeout should be enabled for any HTTP query that requires no outside action
    // on the GFE server. Examples of queries that DO require outside action are launch, resume, and quit.
    // The initial pair query does require outside action (user entering a PIN) but subsequent pairing
    // queries do not.
    @Throws(IOException::class)
    private fun openHttpConnection(
        client: OkHttpClient,
        baseUrl: HttpUrl,
        path: String,
        query: String?,
        requestBody: RequestBody?
    ): ResponseBody? {
        val completeUrl = getCompleteUrl(baseUrl, path, query)
        val builder = Request.Builder().url(completeUrl)
        val request = if (requestBody == null) builder.get().build()
                        else builder.post(requestBody).build()

        val response = performAndroidTlsHack(client).newCall(request).execute()

        val body = response.body

        if (response.isSuccessful) {
            return body
        }


        // Unsuccessful, so close the response body
        body?.close()

        if (response.code == 404) {
            throw FileNotFoundException(completeUrl.toString())
        } else {
            throw HostHttpResponseException(response.code, response.message)
        }
    }

    @Throws(IOException::class)
    private fun openHttpConnectionToString(
        client: OkHttpClient,
        baseUrl: HttpUrl,
        path: String,
        query: String? = null,
        requestBody: RequestBody? = null
    ): String {
        try {
            val resp = openHttpConnection(client, baseUrl, path, query, requestBody)
            val respString = resp!!.string()
            resp.close()

            if (verbose && path != "serverinfo") {
                LimeLog.info(getCompleteUrl(baseUrl, path, query).toString() + " -> " + respString)
            }

            return respString
        } catch (e: IOException) {
            if (verbose && path != "serverinfo") {
                LimeLog.warning(
                    getCompleteUrl(
                        baseUrl,
                        path,
                        query
                    ).toString() + " -> " + e.message
                )
                e.printStackTrace()
            }

            throw e
        }
    }

    @Throws(XmlPullParserException::class, IOException::class)
    fun getServerVersion(serverInfo: String?): String {
        // appversion is present in all supported GFE versions
        return getXmlString(serverInfo, "appversion", true)!!
    }

    @Throws(XmlPullParserException::class, IOException::class)
    fun getServerSupportsVDisplay(serverInfo: String?): Boolean {
        val supportVdisplay: String? = getXmlString(serverInfo, "VirtualDisplayCapable", false)
        if (supportVdisplay == null) {
            return false
        }

        return supportVdisplay == "true"
    }

    @Throws(XmlPullParserException::class, IOException::class)
    fun getServerVDisplayDriverReady(serverInfo: String?): Boolean {
        val driverReady: String? = getXmlString(serverInfo, "VirtualDisplayDriverReady", false)
        if (driverReady == null) {
            return false
        }

        return driverReady == "true"
    }

    @Throws(XmlPullParserException::class, IOException::class)
    fun getServerCmds(serverInfo: String?): MutableList<String?> {
        return getXmlArray(serverInfo, "ServerCommand", false)
    }

    @get:Throws(IOException::class, XmlPullParserException::class)
    val pairState: PairState
        get() = getPairState(getServerInfo(true))

    @Throws(IOException::class, XmlPullParserException::class)
    fun getPairState(serverInfo: String?): PairState {
        // appversion is present in all supported GFE versions
        return if (getXmlString(
                serverInfo,
                "PairStatus",
                true
            ) == "1"
        ) PairState.PAIRED else PairState.NOT_PAIRED
    }

    @Throws(XmlPullParserException::class, IOException::class)
    fun getMaxLumaPixelsH264(serverInfo: String?): Long {
        // MaxLumaPixelsH264 wasn't present on old GFE versions
        return getXmlString(serverInfo, "MaxLumaPixelsH264", false)?.toLong() ?: 0
    }

    @Throws(XmlPullParserException::class, IOException::class)
    fun getMaxLumaPixelsHEVC(serverInfo: String?): Long {
        // MaxLumaPixelsHEVC wasn't present on old GFE versions
        return getXmlString(serverInfo, "MaxLumaPixelsHEVC", false)?.toLong() ?: 0
    }

    // Possible meaning of bits
    // Bit 0: H.264 Baseline
    // Bit 1: H.264 High
    // ----
    // Bit 8: HEVC Main
    // Bit 9: HEVC Main10
    // Bit 10: HEVC Main10 4:4:4
    // Bit 11: ???
    @Throws(XmlPullParserException::class, IOException::class)
    fun getServerCodecModeSupport(serverInfo: String?): Long {
        // ServerCodecModeSupport wasn't present on old GFE versions
        return getXmlString(serverInfo, "ServerCodecModeSupport", false)?.toLong() ?: 0
    }

    @Throws(XmlPullParserException::class, IOException::class)
    fun getGpuType(serverInfo: String?): String? {
        // ServerCodecModeSupport wasn't present on old GFE versions
        return getXmlString(serverInfo, "gputype", false)
    }

    @Throws(XmlPullParserException::class, IOException::class)
    fun getGfeVersion(serverInfo: String?): String? {
        // ServerCodecModeSupport wasn't present on old GFE versions
        return getXmlString(serverInfo, "GfeVersion", false)
    }

    @Throws(XmlPullParserException::class, IOException::class)
    fun supports4K(serverInfo: String?): Boolean {
        // Only allow 4K on GFE 3.x. GfeVersion wasn't present on very old versions of GFE.
        val gfeVersionStr: String? = getXmlString(serverInfo, "GfeVersion", false)
        return !(gfeVersionStr == null || gfeVersionStr.startsWith("2."))
    }

    @Throws(IOException::class, XmlPullParserException::class)
    fun getCurrentGame(serverInfo: String?): Int {
        // GFE 2.8 started keeping currentgame set to the last game played. As a result, it no longer
        // has the semantics that its name would indicate. To contain the effects of this change as much
        // as possible, we'll force the current game to zero if the server isn't in a streaming session.
        return if (getXmlString(serverInfo, "state", true)!!.endsWith("_SERVER_BUSY")) {
            getXmlString(serverInfo, "currentgame", true)!!.toInt()
        } else {
            0
        }
    }

    fun getHttpsPort(serverInfo: String?): Int {
        try {
            return getXmlString(serverInfo, "HttpsPort", true)!!.toInt()
        } catch (e: XmlPullParserException) {
            e.printStackTrace()
            return DEFAULT_HTTPS_PORT
        } catch (e: IOException) {
            e.printStackTrace()
            return DEFAULT_HTTPS_PORT
        }
    }

    fun getExternalPort(serverInfo: String?): Int {
        // This is an extension which is not present in GFE. It is present for Sunshine to be able
        // to support dynamic HTTP WAN ports without requiring the user to manually enter the port.
        try {
            return getXmlString(serverInfo, "ExternalPort", true)!!.toInt()
        } catch (e: XmlPullParserException) {
            // Expected on non-Sunshine servers
            return baseUrlHttp!!.port
        } catch (e: IOException) {
            e.printStackTrace()
            return baseUrlHttp!!.port
        }
    }

    /**
     * Get an app by ID
     * @param appId The ID of the app
     * @see .getAppByName
     * @return app details, or null if no app with that ID exists
     */
    @Throws(IOException::class, XmlPullParserException::class)
    fun getAppById(appId: Int): NvApp? {
        val appList = this.appList
        for (appFromList in appList) {
            if (appFromList.appId == appId) {
                return appFromList
            }
        }
        return null
    }

    /**
     * Get an app by name
     * NOTE: It is perfectly valid for multiple apps to have the same name,
     * this function will only return the first one it finds.
     * Consider using getAppById instead.
     * @param appName The name of the app
     * @see .getAppById
     * @return app details, or null if no app with that name exists
     */
    @Throws(IOException::class, XmlPullParserException::class)
    fun getAppByName(appName: String?): NvApp? {
        val appList = this.appList
        for (appFromList in appList) {
            if (appFromList.appName.equals(appName, ignoreCase = true)) {
                return appFromList
            }
        }
        return null
    }

    @get:Throws(IOException::class)
    val appListRaw: String
        get() = openHttpConnectionToString(
            httpClientLongConnectTimeout!!,
            getHttpsUrl(true),
            "applist"
        )

    @get:Throws(
        HostHttpResponseException::class,
        IOException::class,
        XmlPullParserException::class
    )
    val appList: LinkedList<NvApp>
        get() {
            if (verbose) {
                // Use the raw function so the app list is printed
                return getAppListByReader(StringReader(this.appListRaw))
            } else {
                openHttpConnection(
                    httpClientLongConnectTimeout!!,
                    getHttpsUrl(true),
                    "applist",
                    null,
                    null
                ).use { resp ->
                    return getAppListByReader(InputStreamReader(resp!!.byteStream()))
                }
            }
        }

    @Throws(HostHttpResponseException::class, IOException::class)
    fun executePairingCommand(additionalArguments: String?, enableReadTimeout: Boolean): String {
        return openHttpConnectionToString(
            (if (enableReadTimeout) httpClientLongConnectTimeout else httpClientLongConnectNoReadTimeout)!!,
            baseUrlHttp!!, "pair", "updateState=1&$additionalArguments"
        )
    }

    @Throws(HostHttpResponseException::class, IOException::class)
    fun executePairingChallenge(): String {
        return openHttpConnectionToString(
            httpClientLongConnectTimeout!!, getHttpsUrl(true),
            "pair", "updateState=1&phrase=pairchallenge"
        )
    }

    @Throws(IOException::class)
    fun unpair() {
        openHttpConnectionToString(httpClientLongConnectTimeout!!, baseUrlHttp!!, "unpair")
    }

    @Throws(IOException::class)
    fun getBoxArt(app: NvApp): InputStream {
        val resp = openHttpConnection(
            httpClientLongConnectTimeout!!,
            getHttpsUrl(true),
            "appasset",
            "appid=" + app.appId + "&AssetType=2&AssetIdx=0",
            null
        )
        return resp!!.byteStream()
    }

    @Throws(XmlPullParserException::class, IOException::class)
    fun getServerMajorVersion(serverInfo: String?): Int {
        return getServerAppVersionQuad(serverInfo)[0]
    }

    @Throws(XmlPullParserException::class, IOException::class)
    fun getServerAppVersionQuad(serverInfo: String?): IntArray {
        val serverVersion = getServerVersion(serverInfo)
        requireNotNull(serverVersion) { "Missing server version field" }
        val serverVersionSplit: Array<String?> =
            serverVersion.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        require(serverVersionSplit.size == 4) { "Malformed server version field: $serverVersion" }
        val ret = IntArray(serverVersionSplit.size)
        for (i in ret.indices) {
            ret[i] = serverVersionSplit[i]!!.toInt()
        }
        return ret
    }

    init {

        initializeHttpState(cryptoProvider)

        this.httpsPort = httpsPort

        try {
            // If this is an IPv4-mapped IPv6 address, OkHTTP will choke on it if it's
            // in IPv6 form, because InetAddress.getByName() will return an Inet4Address
            // for what OkHTTP thinks is an IPv6 address. Normalize it into IPv4 form
            // to avoid triggering this bug.
            var addressString = address.address
            if (addressString!!.contains(":") && addressString.contains(".")) {
                val addr = InetAddress.getByName(addressString)
                if (addr is Inet4Address) {
                    addressString = addr.hostAddress
                }
            }

            this.baseUrlHttp = HttpUrl.Builder()
                .scheme("http")
                .host(addressString!!)
                .port(address.port)
                .build()
        } catch (e: IllegalArgumentException) {
            // Encapsulate IllegalArgumentException into IOException for callers to handle more easily
            throw IOException(e)
        }

//        this.pairingManager = PairingManagerKtx(this, cryptoProvider)
    }

    @Throws(IOException::class, XmlPullParserException::class)
    fun launchApp(
        context: ConnectionContext,
        verb: String,
        appId: Int,
        enableHdr: Boolean
    ): Boolean {
        // Using an FPS value over 60 causes SOPS to default to 720p60,
        // so force it to 0 to ensure the correct resolution is set. We
        // used to use 60 here but that locked the frame rate to 60 FPS
        // on GFE 3.20.3.
        val fps =
            if (context.isNvidiaServerSoftware && context.streamConfig.launchRefreshRate > 60) 0 else context.streamConfig.launchRefreshRate

        var enableSops = context.streamConfig.sops
        if (context.isNvidiaServerSoftware) {
            // Using an unsupported resolution (not 720p, 1080p, or 4K) causes
            // GFE to force SOPS to 720p60. This is fine for < 720p resolutions like
            // 360p or 480p, but it is not ideal for 1440p and other resolutions.
            // When we detect an unsupported resolution, disable SOPS unless it's under 720p.
            // FIXME: Detect support resolutions using the serverinfo response, not a hardcoded list
            if (context.negotiatedWidth * context.negotiatedHeight > 1280 * 720 && context.negotiatedWidth * context.negotiatedHeight != 1920 * 1080 && context.negotiatedWidth * context.negotiatedHeight != 3840 * 2160) {
                LimeLog.info("Disabling SOPS due to non-standard resolution: " + context.negotiatedWidth + "x" + context.negotiatedHeight)
                enableSops = false
            }
        }

        val xmlStr = openHttpConnectionToString(
            httpClientLongConnectNoReadTimeout!!, getHttpsUrl(true), verb,
            "appid=" + appId +
                    "&mode=" + context.negotiatedWidth + "x" + context.negotiatedHeight + "x" + fps +
                    "&scaleFactor=" + context.streamConfig.resolutionScaleFactor +
                    "&additionalStates=1&sops=" + (if (enableSops) 1 else 0) +
                    "&rikey=" + bytesToHex(context.riKey.encoded) +
                    "&rikeyid=" + context.riKeyId +
                    (if (!enableHdr) "" else "&hdrMode=1&clientHdrCapVersion=0&clientHdrCapSupportedFlagsInUint32=0&clientHdrCapMetaDataId=NV_STATIC_METADATA_TYPE_1&clientHdrCapDisplayData=0x0x0x0x0x0x0x0x0x0x0") +
                    "&virtualDisplay=" + (if (context.streamConfig.virtualDisplay) 1 else 0) +
                    "&localAudioPlayMode=" + (if (context.streamConfig.playLocalAudio) 1 else 0) +
                    "&surroundAudioInfo=" + context.streamConfig.audioConfiguration
                .surroundAudioInfo +
                    "&remoteControllersBitmap=" + context.streamConfig.attachedGamepadMask +
                    "&gcmap=" + context.streamConfig.attachedGamepadMask +
                    "&gcpersist=" + (if (context.streamConfig.persistGamepadsAfterDisconnect) 1 else 0) +
                    MoonBridge.getLaunchUrlQueryParameters()
        )
        if ((verb == "launch" && getXmlString(xmlStr, "gamesession", true) != "0" ||
                    (verb == "resume" && getXmlString(xmlStr, "resume", true) != "0"))
        ) {
            // sessionUrl0 will be missing for older GFE versions
            context.rtspSessionUrl = getXmlString(xmlStr, "sessionUrl0", false)
            return true
        } else {
            return false
        }
    }

    @Throws(IOException::class, XmlPullParserException::class)
    fun quitApp(): Boolean {
        val xmlStr = openHttpConnectionToString(
            httpClientLongConnectNoReadTimeout!!,
            getHttpsUrl(true),
            "cancel"
        )
        if (getXmlString(xmlStr, "cancel", true) == "0") {
            return false
        }

        // Newer GFE versions will just return success even if quitting fails
        // if we're not the original requestor.
        if (getCurrentGame(getServerInfo(true)) != 0) {
            // Generate a synthetic GfeResponseException letting the caller know
            // that they can't kill someone else's stream.
            throw HostHttpResponseException(599, "")
        }

        return true
    }

    @get:Throws(IOException::class)
    val clipboard: String
        get() =// Add type for future-proof
            // Might return arbitrary type from host if not set
            openHttpConnectionToString(
                httpClientLongConnectTimeout!!,
                getHttpsUrl(true),
                "actions/clipboard",
                "type=text"
            )

    // We currently only support plain text
    @Throws(IOException::class)
    fun sendClipboard(content: String): Boolean {
//        val resp = openHttpConnectionToString(
//            httpClientLongConnectTimeout!!,
//            getHttpsUrl(true),
//            "actions/clipboard",
//            "type=text",
//            RequestBody.create(content, parse.parse("text/plain"))
//        )
//        // For handling the 200ed 404 from Sunshine
//        return resp.isEmpty()
        TODO()
    }

    companion object {
        const val DEFAULT_HTTPS_PORT = 47984
        const val DEFAULT_HTTP_PORT: Int = 47989
        const val SHORT_CONNECTION_TIMEOUT: Int = 3000
        const val LONG_CONNECTION_TIMEOUT: Int = 5000
        const val READ_TIMEOUT: Int = 7000

        // Print URL and content to logcat on debug builds
        private val verbose = BuildConfig.DEBUG

        private fun getDefaultTrustManager(): X509TrustManager {
            try {
                val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                tmf.init(null as KeyStore?)

                return tmf.trustManagers.firstNotNullOf { it as? X509TrustManager }
            } catch (e: NoSuchAlgorithmException) {
                throw RuntimeException(e)
            } catch (e: KeyStoreException) {
                throw RuntimeException(e)
            }

            throw IllegalStateException("No X509 trust manager found")
        }

        @Throws(XmlPullParserException::class, IOException::class)
        fun getXmlString(r: Reader?, tagname: String?, throwIfMissing: Boolean): String? {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val xpp = factory.newPullParser()

            xpp.setInput(r)
            var eventType = xpp.eventType
            val currentTag = Stack<String?>()

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    (XmlPullParser.START_TAG) -> {
                        if (xpp.name == "root") {
                            verifyResponseStatus(xpp)
                        }
                        currentTag.push(xpp.name)
                    }

                    (XmlPullParser.END_TAG) -> currentTag.pop()
                    (XmlPullParser.TEXT) -> if (currentTag.peek() == tagname) {
                        return xpp.text
                    }
                }
                eventType = xpp.next()
            }

            if (throwIfMissing) {
                // We throw an XmlPullParserException here for ease of handling in all the various callers.
                // We could also throw an IOException, but some callers expect those in cases where the
                // host may not be reachable. We want to distinguish unreachable hosts vs. hosts that
                // are returning garbage XML to us, so we use XmlPullParserException instead.
                throw XmlPullParserException("Missing mandatory field in host response: $tagname")
            }

            return null
        }

        @Throws(XmlPullParserException::class, IOException::class)
        fun getXmlString(str: String?, tagname: String?, throwIfMissing: Boolean): String? {
            return getXmlString(StringReader(str), tagname, throwIfMissing)
        }

        @Throws(XmlPullParserException::class, IOException::class)
        fun getXmlArray(
            r: Reader?,
            tagname: String?,
            throwIfMissing: Boolean
        ): MutableList<String?> {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val xpp = factory.newPullParser()

            xpp.setInput(r)
            var eventType = xpp.eventType
            val currentTag = Stack<String?>()

            val array: MutableList<String?> = ArrayList<String?>()

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    (XmlPullParser.START_TAG) -> currentTag.push(xpp.name)
                    (XmlPullParser.END_TAG) -> currentTag.pop()
                    (XmlPullParser.TEXT) -> if (currentTag.peek() == tagname) {
                        array.add(xpp.text)
                    }
                }
                eventType = xpp.next()
            }

            if (throwIfMissing && array.isEmpty()) {
                throw XmlPullParserException("Missing mandatory field in host response: $tagname")
            }

            return array
        }

        @Throws(XmlPullParserException::class, IOException::class)
        fun getXmlArray(
            str: String?,
            tagname: String?,
            throwIfMissing: Boolean
        ): MutableList<String?> {
            return getXmlArray(StringReader(str), tagname, throwIfMissing)
        }

        @Throws(HostHttpResponseException::class)
        private fun verifyResponseStatus(xpp: XmlPullParser) {
            // We use Long.parseLong() because in rare cases GFE can send back a status code of
            // 0xFFFFFFFF, which will cause Integer.parseInt() to throw a NumberFormatException due
            // to exceeding Integer.MAX_VALUE. We'll get the desired error code of -1 by just casting
            // the resulting long into an int.
            var statusCode =
                xpp.getAttributeValue(XmlPullParser.NO_NAMESPACE, "status_code").toLong().toInt()
            if (statusCode != 200) {
                var statusMsg = xpp.getAttributeValue(XmlPullParser.NO_NAMESPACE, "status_message")
                if (statusCode == -1 && "Invalid" == statusMsg) {
                    // Special case handling an audio capture error which GFE doesn't
                    // provide any useful status message for.
                    statusCode = 418
                    statusMsg = "Missing audio capture device. Reinstall GeForce Experience."
                }
                throw HostHttpResponseException(statusCode, statusMsg)
            }
        }

        private fun makeTuple(address: String?, port: Int): AddressTuple? {
            if (address == null) {
                return null
            }

            return AddressTuple(address, port)
        }

        @Throws(XmlPullParserException::class, IOException::class)
        fun getAppListByReader(r: Reader?): LinkedList<NvApp> {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val xpp = factory.newPullParser()

            xpp.setInput(r)
            var eventType = xpp.eventType
            val appList = LinkedList<NvApp>()
            val currentTag = Stack<String?>()
            var rootTerminated = false

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    (XmlPullParser.START_TAG) -> {
                        if (xpp.name == "root") {
                            verifyResponseStatus(xpp)
                        }
                        currentTag.push(xpp.name)
                        if (xpp.name == "App") {
                            appList.addLast(NvApp())
                        }
                    }

                    (XmlPullParser.END_TAG) -> {
                        currentTag.pop()
                        if (xpp.name == "root") {
                            rootTerminated = true
                        }
                    }

                    (XmlPullParser.TEXT) -> {
                        val app = appList.getLast()
                        if (currentTag.peek() == "AppTitle") {
                            app.appName = xpp.text
                        } else if (currentTag.peek() == "ID") {
                            app.setAppId(xpp.text)
                        } else if (currentTag.peek() == "IsHdrSupported") {
                            app.isHdrSupported = xpp.text == "1"
                        }
                    }
                }
                eventType = xpp.next()
            }


            // Throw a malformed XML exception if we've not seen the root tag ended
            if (!rootTerminated) {
                throw XmlPullParserException("Malformed XML: Root tag was not terminated")
            }


            // Ensure that all apps in the list are initialized
            return LinkedList(appList.filter { app -> app.isInitialized })
        }

        private val hexArray = "0123456789ABCDEF".toCharArray()
        private fun bytesToHex(bytes: ByteArray): String {
            val hexChars = CharArray(bytes.size * 2)
            for (j in bytes.indices) {
                val v = bytes[j].toInt() and 0xFF
                hexChars[j * 2] = hexArray[v ushr 4]
                hexChars[j * 2 + 1] = hexArray[v and 0x0F]
            }
            return String(hexChars)
        }
    }
}

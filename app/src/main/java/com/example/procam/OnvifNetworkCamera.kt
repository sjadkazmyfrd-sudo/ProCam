package com.example.procam

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import android.util.Base64
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Shared ONVIF/RTSP Digest credentials and RFC 2617 MD5 authentication. */
internal object ProCamCameraAuth {
    const val USERNAME = "procam"
    const val PASSWORD = "ProCam@2026"
    private const val REALM = "ProCam Wireless Camera"
    private const val NONCE_LIFETIME_MS = 5 * 60 * 1000L

    private val random = SecureRandom()
    private val nonceExpiry = ConcurrentHashMap<String, Long>()
    private val highestNonceCount = ConcurrentHashMap<String, Long>()

    fun challengeHeader(): String {
        val now = System.currentTimeMillis()
        for ((nonce, expiry) in nonceExpiry) {
            if (expiry < now) {
                nonceExpiry.remove(nonce, expiry)
                highestNonceCount.keys.removeAll { it.contains(":$nonce:") }
            }
        }
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        val nonce = Base64.encodeToString(bytes, Base64.NO_WRAP)
        nonceExpiry[nonce] = now + NONCE_LIFETIME_MS
        return "Digest realm=\"$REALM\", nonce=\"$nonce\", algorithm=MD5, qop=\"auth\""
    }

    /** Validates an Authorization: Digest header for HTTP or RTSP. */
    fun verifyDigest(authorization: String?, method: String, requestUri: String): Boolean {
        if (authorization.isNullOrBlank() || !authorization.startsWith("Digest ", ignoreCase = true)) return false
        val fields = parseDigestFields(authorization.substringAfter(' '))
        val username = fields["username"] ?: return false
        val realm = fields["realm"] ?: return false
        val nonce = fields["nonce"] ?: return false
        val uri = fields["uri"] ?: return false
        val response = fields["response"] ?: return false
        if (username != USERNAME || realm != REALM || uri != requestUri) return false
        if (nonceExpiry[nonce]?.let { it >= System.currentTimeMillis() } != true) return false

        val algorithm = fields["algorithm"]?.uppercase(Locale.US) ?: "MD5"
        if (algorithm != "MD5") return false
        val ha1 = md5Hex("$USERNAME:$REALM:$PASSWORD")
        val ha2 = md5Hex("$method:$uri")
        val qop = fields["qop"]?.lowercase(Locale.US)
        val expected = if (qop == null) {
            md5Hex("$ha1:$nonce:$ha2")
        } else {
            if (qop != "auth") return false
            val nonceCount = fields["nc"] ?: return false
            val clientNonce = fields["cnonce"] ?: return false
            if (!nonceCount.matches(Regex("[0-9a-fA-F]{8}"))) return false
            md5Hex("$ha1:$nonce:$nonceCount:$clientNonce:$qop:$ha2")
        }
        if (!MessageDigest.isEqual(
                expected.lowercase(Locale.US).toByteArray(StandardCharsets.US_ASCII),
                response.lowercase(Locale.US).toByteArray(StandardCharsets.US_ASCII)
            )) return false

        // Reject repeated or decreasing nonce-count values for the same client nonce.
        if (qop == "auth") {
            val nonceCount = fields["nc"]!!.toLongOrNull(16) ?: return false
            val replayKey = "$username:$nonce:${fields["cnonce"]}"
            var accepted = false
            highestNonceCount.compute(replayKey) { _, previous ->
                if (previous == null || nonceCount > previous) {
                    accepted = true
                    nonceCount
                } else previous
            }
            if (!accepted) return false
        }
        return true
    }

    private fun parseDigestFields(value: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val pattern = Regex("""([A-Za-z][A-Za-z0-9_-]*)\s*=\s*(?:"((?:\\.|[^"])*)"|([^,\s]+))""")
        for (match in pattern.findAll(value)) {
            val raw = match.groupValues[2].ifEmpty { match.groupValues[3] }
            result[match.groupValues[1].lowercase(Locale.US)] = raw
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        }
        return result
    }

    private fun md5Hex(value: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray(StandardCharsets.UTF_8))
        val chars = "0123456789abcdef"
        val output = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val unsigned = byte.toInt() and 0xff
            output.append(chars[unsigned ushr 4]).append(chars[unsigned and 0x0f])
        }
        return output.toString()
    }
}

/**
 * Small ONVIF Profile-S-style device service for Windows network-camera discovery.
 * It exposes ONVIF Device/Media SOAP endpoints, WS-Discovery and an H.264 RTSP stream.
 */
internal class OnvifNetworkCamera(
    context: Context,
    private val ipAddress: String,
    private val httpPort: Int = 8080,
    private val rtspPort: Int = 8554
) {
    companion object {
        private const val TAG = "ProCamONVIF"
        private const val WSD_PORT = 3702
        private const val SOAP_NS = "http://www.w3.org/2003/05/soap-envelope"
        private const val WSA_NS = "http://schemas.xmlsoap.org/ws/2004/08/addressing"
        private const val WSD_NS = "http://schemas.xmlsoap.org/ws/2005/04/discovery"
        private const val DEVICE_NS = "http://www.onvif.org/ver10/device/wsdl"
        private const val MEDIA_NS = "http://www.onvif.org/ver10/media/wsdl"
        private const val SCHEMA_NS = "http://www.onvif.org/ver10/schema"
        private const val NETWORK_NS = "http://www.onvif.org/ver10/network/wsdl"
        private const val DEVICE_UUID = "urn:uuid:4c5a8c32-2b17-4c37-bcad-20a84a6aa001"
    }

    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)
    private val httpExecutor: ExecutorService = Executors.newCachedThreadPool()
    private val deviceId = DEVICE_UUID
    private var httpSocket: ServerSocket? = null
    private var httpThread: Thread? = null
    private var discoverySocket: MulticastSocket? = null
    private var discoveryThread: Thread? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val rtspServer = RtspH264Server(ipAddress, rtspPort)
    @Volatile private var videoWidth: Int = 640
    @Volatile private var videoHeight: Int = 480

    val deviceServiceUrl: String
        get() = "http://$ipAddress:$httpPort/onvif/device_service"
    val mediaServiceUrl: String
        get() = "http://$ipAddress:$httpPort/onvif/media_service"
    val streamUrl: String
        get() = "rtsp://$ipAddress:$rtspPort/live"

    @Synchronized
    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            startHttpServer()
            rtspServer.start()
            startDiscovery()
        } catch (e: Exception) {
            stop()
            throw e
        }
    }

    fun updateCodecConfiguration(sps: ByteArray, pps: ByteArray) {
        rtspServer.updateCodecConfiguration(sps, pps)
    }

    fun updateVideoDimensions(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            videoWidth = width
            videoHeight = height
        }
    }

    fun publishFrame(accessUnit: ByteArray, presentationTimeUs: Long) {
        rtspServer.publishFrame(accessUnit, presentationTimeUs)
    }

    private fun startHttpServer() {
        val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress("0.0.0.0", httpPort), 8)
        }
        httpSocket = server
        httpThread = Thread({
            try {
                while (running.get()) {
                    val socket = try { server.accept() } catch (_: SocketException) { break }
                    httpExecutor.execute { handleHttpClient(socket) }
                }
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "ONVIF HTTP service failed", e)
            }
        }, "ProCam-ONVIF-HTTP").apply {
            isDaemon = true
            start()
        }
    }

    private fun handleHttpClient(socket: Socket) {
        try {
            socket.soTimeout = 5000
            val input = BufferedInputStream(socket.getInputStream())
            val requestLine = readLine(input) ?: return
            val requestParts = requestLine.split(' ')
            val method = requestParts.firstOrNull()?.uppercase() ?: "GET"
            val path = requestParts.getOrNull(1) ?: "/"
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
            val contentLength = (headers["content-length"] ?: "0").toIntOrNull()?.coerceIn(0, 128 * 1024) ?: 0
            val bodyBytes = ByteArray(contentLength)
            var offset = 0
            while (offset < contentLength) {
                val count = input.read(bodyBytes, offset, contentLength - offset)
                if (count <= 0) break
                offset += count
            }
            val body = String(bodyBytes, 0, offset, StandardCharsets.UTF_8)

            // Keep the clock query open so Windows can synchronize before generating
            // Digest credentials. Protect the actual ONVIF device/media operations.
            val isTimeQuery = body.contains("GetSystemDateAndTime")
            val isOnvifRequest = path.contains("device_service") || path == "/onvif/device" ||
                path.contains("media_service") || path == "/onvif/media"
            if (isOnvifRequest && !isTimeQuery &&
                !ProCamCameraAuth.verifyDigest(headers["authorization"], method, path)) {
                writeUnauthorizedHttp(socket)
                return
            }

            val responseBody = when {
                path.contains("device_service") || path == "/onvif/device" -> deviceSoapResponse(body)
                path.contains("media_service") || path == "/onvif/media" -> mediaSoapResponse(body)
                path.contains("/snapshot") -> null
                method == "GET" -> "ProCam ONVIF network camera is running."
                else -> null
            }
            if (responseBody == null) {
                writeHttp(socket, 404, "Not Found", "text/plain; charset=utf-8", "Not found".toByteArray())
            } else {
                writeHttp(socket, 200, "OK", "application/soap+xml; charset=utf-8", responseBody.toByteArray(StandardCharsets.UTF_8))
            }
        } catch (e: Exception) {
            Log.d(TAG, "ONVIF HTTP client closed", e)
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun writeUnauthorizedHttp(socket: Socket) {
        val body = "Authentication required".toByteArray(StandardCharsets.UTF_8)
        val header = "HTTP/1.1 401 Unauthorized\r\n" +
            "WWW-Authenticate: ${ProCamCameraAuth.challengeHeader()}\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(header.toByteArray(StandardCharsets.US_ASCII))
            write(body)
            flush()
        }
    }

    private fun writeHttp(socket: Socket, code: Int, reason: String, contentType: String, body: ByteArray) {
        val header = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(header.toByteArray(StandardCharsets.US_ASCII))
            write(body)
            flush()
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) return if (bytes.size() == 0) null else bytes.toString("UTF-8")
            if (value == 10) break
            if (value != 13) bytes.write(value)
            if (bytes.size() > 16 * 1024) throw IllegalStateException("HTTP header too long")
        }
        return bytes.toString("UTF-8")
    }

    private fun deviceSoapResponse(request: String): String {
        val operation = when {
            request.contains("GetDeviceInformation") -> "GetDeviceInformation"
            request.contains("GetCapabilities") -> "GetCapabilities"
            request.contains("GetServices") -> "GetServices"
            request.contains("GetSystemDateAndTime") -> "GetSystemDateAndTime"
            request.contains("GetScopes") -> "GetScopes"
            request.contains("GetHostname") -> "GetHostname"
            request.contains("GetNetworkInterfaces") -> "GetNetworkInterfaces"
            request.contains("GetDiscoveryMode") -> "GetDiscoveryMode"
            request.contains("GetServiceCapabilities") -> "GetServiceCapabilities"
            else -> "Unknown"
        }
        val result = when (operation) {
            "GetDeviceInformation" -> """
                <tds:GetDeviceInformationResponse>
                  <tds:Manufacturer>ProCam</tds:Manufacturer>
                  <tds:Model>ProCam Wireless Camera</tds:Model>
                  <tds:FirmwareVersion>1.2</tds:FirmwareVersion>
                  <tds:SerialNumber>PROCAM-WIFI-001</tds:SerialNumber>
                  <tds:HardwareId>Android</tds:HardwareId>
                </tds:GetDeviceInformationResponse>
            """.trimIndent()
            "GetCapabilities" -> """
                <tds:GetCapabilitiesResponse><tds:Capabilities>
                  <tt:Device>
                    <tt:XAddr>$deviceServiceUrl</tt:XAddr>
                    <tt:Network><tt:IPFilter>false</tt:IPFilter><tt:ZeroConfiguration>false</tt:ZeroConfiguration><tt:IPVersion6>false</tt:IPVersion6><tt:DynDNS>false</tt:DynDNS></tt:Network>
                    <tt:System><tt:DiscoveryResolve>true</tt:DiscoveryResolve><tt:DiscoveryBye>false</tt:DiscoveryBye><tt:RemoteDiscovery>false</tt:RemoteDiscovery><tt:SystemBackup>false</tt:SystemBackup><tt:SystemLogging>false</tt:SystemLogging><tt:FirmwareUpgrade>false</tt:FirmwareUpgrade><tt:SupportedVersions><tt:Major>2</tt:Major><tt:Minor>6</tt:Minor></tt:SupportedVersions></tt:System>
                    <tt:Security><tt:TLS1.0>false</tt:TLS1.0><tt:TLS1.1>false</tt:TLS1.1><tt:TLS1.2>false</tt:TLS1.2><tt:OnboardKeyGeneration>false</tt:OnboardKeyGeneration><tt:AccessPolicyConfig>false</tt:AccessPolicyConfig><tt:X.509Token>false</tt:X.509Token><tt:SAMLToken>false</tt:SAMLToken><tt:KerberosToken>false</tt:KerberosToken><tt:RELToken>false</tt:RELToken></tt:Security>
                  </tt:Device>
                  <tt:Media><tt:XAddr>$mediaServiceUrl</tt:XAddr><tt:StreamingCapabilities><tt:RTPMulticast>false</tt:RTPMulticast><tt:RTP_TCP>false</tt:RTP_TCP><tt:RTP_RTSP_TCP>false</tt:RTP_RTSP_TCP></tt:StreamingCapabilities></tt:Media>
                </tds:Capabilities></tds:GetCapabilitiesResponse>
            """.trimIndent()
            "GetServices" -> """
                <tds:GetServicesResponse>
                  <tds:Service><tds:Namespace>$DEVICE_NS</tds:Namespace><tds:XAddr>$deviceServiceUrl</tds:XAddr><tds:Version><tt:Major>2</tt:Major><tt:Minor>6</tt:Minor></tds:Version></tds:Service>
                  <tds:Service><tds:Namespace>$MEDIA_NS</tds:Namespace><tds:XAddr>$mediaServiceUrl</tds:XAddr><tds:Version><tt:Major>2</tt:Major><tt:Minor>6</tt:Minor></tds:Version></tds:Service>
                </tds:GetServicesResponse>
            """.trimIndent()
            "GetSystemDateAndTime" -> {
                val now = Date()
                val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US).apply { time = now }
                val local = Calendar.getInstance().apply { time = now }
                val zone = TimeZone.getDefault()
                fun dateTime(calendar: Calendar): String = """
                    <tt:Time><tt:Hour>${calendar.get(Calendar.HOUR_OF_DAY)}</tt:Hour><tt:Minute>${calendar.get(Calendar.MINUTE)}</tt:Minute><tt:Second>${calendar.get(Calendar.SECOND)}</tt:Second></tt:Time>
                    <tt:Date><tt:Year>${calendar.get(Calendar.YEAR)}</tt:Year><tt:Month>${calendar.get(Calendar.MONTH) + 1}</tt:Month><tt:Day>${calendar.get(Calendar.DAY_OF_MONTH)}</tt:Day></tt:Date>
                """.trimIndent()
                """
                <tds:GetSystemDateAndTimeResponse><tds:SystemDateAndTime>
                  <tt:DateTimeType>Manual</tt:DateTimeType><tt:DaylightSavings>${zone.inDaylightTime(now)}</tt:DaylightSavings>
                  <tt:UTCDateTime>${dateTime(utc)}</tt:UTCDateTime>
                  <tt:LocalDateTime>${dateTime(local)}</tt:LocalDateTime><tt:TimeZone><tt:TZ>${zone.id}</tt:TZ></tt:TimeZone>
                </tds:SystemDateAndTime></tds:GetSystemDateAndTimeResponse>
                """.trimIndent()
            }
            "GetScopes" -> """
                <tds:GetScopesResponse><tds:Scopes><tt:ScopeDef>Fixed</tt:ScopeDef><tt:ScopeItem>onvif://www.onvif.org/type/video_encoder</tt:ScopeItem></tds:Scopes>
                <tds:Scopes><tt:ScopeDef>Fixed</tt:ScopeDef><tt:ScopeItem>onvif://www.onvif.org/name/ProCam_Wireless_Camera</tt:ScopeItem></tds:Scopes>
                <tds:Scopes><tt:ScopeDef>Fixed</tt:ScopeDef><tt:ScopeItem>onvif://www.onvif.org/Profile/Streaming</tt:ScopeItem></tds:Scopes></tds:GetScopesResponse>
            """.trimIndent()
            "GetHostname" -> """<tds:GetHostnameResponse><tds:HostnameInformation><tt:Name>ProCam</tt:Name></tds:HostnameInformation></tds:GetHostnameResponse>"""
            "GetNetworkInterfaces" -> """
                <tds:GetNetworkInterfacesResponse><tds:NetworkInterfaces token="wlan0"><tt:Enabled>true</tt:Enabled><tt:Info><tt:Name>wlan0</tt:Name><tt:HwAddress>02:00:00:00:00:01</tt:HwAddress><tt:MTU>1500</tt:MTU></tt:Info><tt:IPv4><tt:Enabled>true</tt:Enabled><tt:Config><tt:Manual><tt:Address>$ipAddress</tt:Address><tt:PrefixLength>24</tt:PrefixLength></tt:Manual><tt:DHCP>false</tt:DHCP></tt:Config></tt:IPv4></tds:NetworkInterfaces></tds:GetNetworkInterfacesResponse>
            """.trimIndent()
            "GetDiscoveryMode" -> """<tds:GetDiscoveryModeResponse><tds:DiscoveryMode>Discoverable</tds:DiscoveryMode></tds:GetDiscoveryModeResponse>"""
            "GetServiceCapabilities" -> """<tds:GetServiceCapabilitiesResponse><tds:Capabilities><tds:Network IPFilter="false" ZeroConfiguration="false" IPVersion6="false" DynDNS="false"/><tds:Security TLS1.0="false" TLS1.1="false" TLS1.2="false" OnboardKeyGeneration="false"/></tds:Capabilities></tds:GetServiceCapabilitiesResponse>"""
            else -> "<tds:GenericResponse/>"
        }
        return soapEnvelope(result)
    }

    private fun mediaSoapResponse(request: String): String {
        val operation = when {
            request.contains("GetStreamUri") -> "GetStreamUri"
            request.contains("GetProfiles") -> "GetProfiles"
            request.contains("GetVideoSources") -> "GetVideoSources"
            request.contains("GetVideoSourceConfigurations") -> "GetVideoSourceConfigurations"
            request.contains("GetVideoSourceConfiguration") -> "GetVideoSourceConfiguration"
            request.contains("GetVideoEncoderConfigurations") -> "GetVideoEncoderConfigurations"
            request.contains("GetVideoEncoderConfigurationOptions") -> "GetVideoEncoderConfigurationOptions"
            request.contains("GetVideoEncoderConfiguration") -> "GetVideoEncoderConfiguration"
            request.contains("GetCompatibleVideoEncoderConfigurations") -> "GetCompatibleVideoEncoderConfigurations"
            request.contains("GetServiceCapabilities") -> "GetServiceCapabilities"
            request.contains("SetVideoEncoderConfiguration") -> "SetVideoEncoderConfiguration"
            else -> "Unknown"
        }
        val profile = videoProfile()
        val result = when (operation) {
            "GetStreamUri" -> """
                <trt:GetStreamUriResponse><trt:MediaUri><tt:Uri>$streamUrl</tt:Uri><tt:InvalidAfterConnect>false</tt:InvalidAfterConnect><tt:InvalidAfterReboot>false</tt:InvalidAfterReboot><tt:Timeout>PT0S</tt:Timeout></trt:MediaUri></trt:GetStreamUriResponse>
            """.trimIndent()
            "GetProfiles" -> """<trt:GetProfilesResponse>$profile</trt:GetProfilesResponse>"""
            "GetVideoSources" -> """
                <trt:GetVideoSourcesResponse><trt:VideoSources token="VideoSource_1"><tt:Framerate>15</tt:Framerate><tt:Resolution><tt:Width>$videoWidth</tt:Width><tt:Height>$videoHeight</tt:Height></tt:Resolution></trt:VideoSources></trt:GetVideoSourcesResponse>
            """.trimIndent()
            "GetVideoSourceConfigurations" -> """
                <trt:GetVideoSourceConfigurationsResponse><trt:Configurations token="VideoSourceConfig_1"><tt:Name>Camera</tt:Name><tt:UseCount>1</tt:UseCount><tt:SourceToken>VideoSource_1</tt:SourceToken><tt:Bounds x="0" y="0" width="$videoWidth" height="$videoHeight"/></trt:Configurations></trt:GetVideoSourceConfigurationsResponse>
            """.trimIndent()
            "GetVideoSourceConfiguration" -> """<trt:GetVideoSourceConfigurationResponse><trt:Configuration token="VideoSourceConfig_1"><tt:Name>Camera</tt:Name><tt:UseCount>1</tt:UseCount><tt:SourceToken>VideoSource_1</tt:SourceToken><tt:Bounds x="0" y="0" width="$videoWidth" height="$videoHeight"/></trt:Configuration></trt:GetVideoSourceConfigurationResponse>"""
            "GetVideoEncoderConfigurations" -> """<trt:GetVideoEncoderConfigurationsResponse><trt:Configurations token="VideoEncoderConfig_1">${videoEncoderConfiguration()}</trt:Configurations></trt:GetVideoEncoderConfigurationsResponse>"""
            "GetVideoEncoderConfiguration" -> """<trt:GetVideoEncoderConfigurationResponse><trt:Configuration token="VideoEncoderConfig_1">${videoEncoderConfiguration()}</trt:Configuration></trt:GetVideoEncoderConfigurationResponse>"""
            "GetVideoEncoderConfigurationOptions" -> """
                <trt:GetVideoEncoderConfigurationOptionsResponse><trt:Options><tt:QualityRange><tt:Min>1</tt:Min><tt:Max>5</tt:Max></tt:QualityRange><tt:H264><tt:ResolutionsAvailable><tt:Width>$videoWidth</tt:Width><tt:Height>$videoHeight</tt:Height></tt:ResolutionsAvailable><tt:GovLengthRange><tt:Min>1</tt:Min><tt:Max>60</tt:Max></tt:GovLengthRange><tt:FrameRateRange><tt:Min>1</tt:Min><tt:Max>15</tt:Max></tt:FrameRateRange><tt:EncodingIntervalRange><tt:Min>1</tt:Min><tt:Max>1</tt:Max></tt:EncodingIntervalRange><tt:H264ProfilesSupported>Baseline</tt:H264ProfilesSupported><tt:H264ProfilesSupported>Main</tt:H264ProfilesSupported></tt:H264></trt:Options></trt:GetVideoEncoderConfigurationOptionsResponse>
            """.trimIndent()
            "GetCompatibleVideoEncoderConfigurations" -> """<trt:GetCompatibleVideoEncoderConfigurationsResponse><trt:Configurations token="VideoEncoderConfig_1">${videoEncoderConfiguration()}</trt:Configurations></trt:GetCompatibleVideoEncoderConfigurationsResponse>"""
            "GetServiceCapabilities" -> """<trt:GetServiceCapabilitiesResponse><trt:Capabilities SnapshotUri="false" Rotation="false" VideoSourceMode="false" OSD="false" TemporaryOSDText="false" EXICompression="false"/> </trt:GetServiceCapabilitiesResponse>"""
            "SetVideoEncoderConfiguration" -> """<trt:SetVideoEncoderConfigurationResponse/>"""
            else -> "<trt:GenericResponse/>"
        }
        return soapEnvelope(result)
    }

    private fun videoProfile(): String = """
        <trt:Profiles token="Profile_1" fixed="true">
          <tt:Name>ProCamProfile</tt:Name>
          <tt:VideoSourceConfiguration token="VideoSourceConfig_1"><tt:Name>Camera</tt:Name><tt:UseCount>1</tt:UseCount><tt:SourceToken>VideoSource_1</tt:SourceToken><tt:Bounds x="0" y="0" width="$videoWidth" height="$videoHeight"/></tt:VideoSourceConfiguration>
          <tt:VideoEncoderConfiguration token="VideoEncoderConfig_1">${videoEncoderConfiguration()}</tt:VideoEncoderConfiguration>
        </trt:Profiles>
    """.trimIndent()

    private fun videoEncoderConfiguration(): String = """
        <tt:Name>ProCam H264</tt:Name><tt:UseCount>1</tt:UseCount><tt:Encoding>H264</tt:Encoding>
        <tt:Resolution><tt:Width>$videoWidth</tt:Width><tt:Height>$videoHeight</tt:Height></tt:Resolution>
        <tt:Quality>4</tt:Quality>
        <tt:RateControl><tt:FrameRateLimit>15</tt:FrameRateLimit><tt:EncodingInterval>1</tt:EncodingInterval><tt:BitrateLimit>1500</tt:BitrateLimit></tt:RateControl>
        <tt:H264><tt:GovLength>15</tt:GovLength><tt:H264Profile>Baseline</tt:H264Profile></tt:H264>
        <tt:Multicast><tt:Address><tt:Type>IPv4</tt:Type><tt:IPv4Address>0.0.0.0</tt:IPv4Address></tt:Address><tt:Port>0</tt:Port><tt:TTL>1</tt:TTL><tt:AutoStart>false</tt:AutoStart></tt:Multicast>
        <tt:SessionTimeout>PT60S</tt:SessionTimeout>
    """.trimIndent()

    private fun soapEnvelope(body: String): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <s:Envelope xmlns:s="$SOAP_NS" xmlns:tds="$DEVICE_NS" xmlns:trt="$MEDIA_NS" xmlns:tt="$SCHEMA_NS" xmlns:dn="$NETWORK_NS">
          <s:Body>$body</s:Body>
        </s:Envelope>
    """.trimIndent().trimStart()

    private fun startDiscovery() {
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifi != null) {
            try {
                multicastLock = wifi.createMulticastLock("ProCam-ONVIF-Discovery").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not acquire Wi-Fi multicast lock", e)
            }
        }

        discoveryThread = Thread({
            var socket: MulticastSocket? = null
            try {
                val created = MulticastSocket(null)
                created.reuseAddress = true
                created.bind(InetSocketAddress(WSD_PORT))
                created.timeToLive = 1
                socket = created
                discoverySocket = created

                val group = InetAddress.getByName("239.255.255.250")
                val interfaces = try { Collections.list(NetworkInterface.getNetworkInterfaces()) } catch (_: Exception) { emptyList() }
                interfaces.filter { iface ->
                    try { iface.isUp && !iface.isLoopback } catch (_: Exception) { false }
                }.forEach { iface ->
                    try { created.joinGroup(InetSocketAddress(group, WSD_PORT), iface) } catch (e: Exception) {
                        Log.d(TAG, "Could not join WSD multicast on ${iface.name}", e)
                    }
                }

                while (running.get()) {
                    val data = ByteArray(16 * 1024)
                    val packet = DatagramPacket(data, data.size)
                    try {
                        created.receive(packet)
                    } catch (_: SocketException) {
                        break
                    }
                    val request = String(packet.data, packet.offset, packet.length, StandardCharsets.UTF_8)
                    if (request.contains("Probe", ignoreCase = true)) {
                        val response = buildProbeMatch(request, resolve = false).toByteArray(StandardCharsets.UTF_8)
                        created.send(DatagramPacket(response, response.size, packet.address, packet.port))
                    } else if (request.contains("Resolve", ignoreCase = true)) {
                        val response = buildProbeMatch(request, resolve = true).toByteArray(StandardCharsets.UTF_8)
                        created.send(DatagramPacket(response, response.size, packet.address, packet.port))
                    }
                }
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "WS-Discovery failed", e)
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }, "ProCam-ONVIF-Discovery").apply {
            isDaemon = true
            start()
        }
    }

    private fun buildProbeMatch(request: String, resolve: Boolean): String {
        val relatesTo = Regex("<(?:[A-Za-z0-9_]+:)?MessageID[^>]*>([^<]+)</(?:[A-Za-z0-9_]+:)?MessageID>")
            .find(request)?.groupValues?.getOrNull(1) ?: "urn:uuid:${UUID.randomUUID()}"
        val action = if (resolve) "$WSD_NS/ResolveMatches" else "$WSD_NS/ProbeMatches"
        val body = if (resolve) """
            <d:ResolveMatches><d:ResolveMatch>
              <a:EndpointReference><a:Address>$deviceId</a:Address></a:EndpointReference>
              <d:Types>dn:NetworkVideoTransmitter</d:Types>
              <d:Scopes>onvif://www.onvif.org/type/video_encoder onvif://www.onvif.org/name/ProCam_Wireless_Camera onvif://www.onvif.org/Profile/Streaming</d:Scopes>
              <d:XAddrs>$deviceServiceUrl</d:XAddrs><d:MetadataVersion>1</d:MetadataVersion>
            </d:ResolveMatch></d:ResolveMatches>
        """.trimIndent() else """
            <d:ProbeMatches><d:ProbeMatch>
              <a:EndpointReference><a:Address>$deviceId</a:Address></a:EndpointReference>
              <d:Types>dn:NetworkVideoTransmitter</d:Types>
              <d:Scopes>onvif://www.onvif.org/type/video_encoder onvif://www.onvif.org/name/ProCam_Wireless_Camera onvif://www.onvif.org/Profile/Streaming</d:Scopes>
              <d:XAddrs>$deviceServiceUrl</d:XAddrs><d:MetadataVersion>1</d:MetadataVersion>
            </d:ProbeMatch></d:ProbeMatches>
        """.trimIndent()
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <s:Envelope xmlns:s="$SOAP_NS" xmlns:a="$WSA_NS" xmlns:d="$WSD_NS" xmlns:dn="$NETWORK_NS">
              <s:Header><a:MessageID>urn:uuid:${UUID.randomUUID()}</a:MessageID><a:RelatesTo>$relatesTo</a:RelatesTo><a:To>http://schemas.xmlsoap.org/ws/2004/08/addressing/role/anonymous</a:To><a:Action>$action</a:Action></s:Header>
              <s:Body>$body</s:Body>
            </s:Envelope>
        """.trimIndent().trimStart()
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false)) return
        try { httpSocket?.close() } catch (_: Exception) {}
        httpSocket = null
        try { discoverySocket?.close() } catch (_: Exception) {}
        discoverySocket = null
        try { rtspServer.stop() } catch (_: Exception) {}
        try { httpExecutor.shutdownNow() } catch (_: Exception) {}
        try {
            multicastLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
        multicastLock = null
    }
}

internal object LocalNetworkAddress {
    /** Returns a private IPv4 address, preferring Android Wi-Fi/SoftAP interfaces. */
    fun findWifiIpv4(): String? {
        val interfaces = try { Collections.list(NetworkInterface.getNetworkInterfaces()) } catch (_: Exception) { return null }
        val candidates = interfaces.filter { iface ->
            try { iface.isUp && !iface.isLoopback } catch (_: Exception) { false }
        }.sortedBy { iface ->
            val name = iface.name.lowercase(Locale.US)
            when {
                name.startsWith("ap") || name.contains("softap") || name.startsWith("swlan") -> 0
                name.contains("wlan") || name.contains("wifi") -> 1
                name.contains("p2p") -> 3
                name.contains("rmnet") || name.contains("ccmni") -> 5
                else -> 2
            }
        }
        for (iface in candidates) {
            val addresses = try { Collections.list(iface.inetAddresses) } catch (_: Exception) { emptyList() }
            for (address in addresses) {
                if (address !is Inet4Address || address.isLoopbackAddress || address.isLinkLocalAddress) continue
                if (address.isSiteLocalAddress) return address.hostAddress
            }
        }
        return null
    }
}

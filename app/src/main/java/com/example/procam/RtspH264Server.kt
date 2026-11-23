package com.example.procam

import android.util.Log
import android.util.Base64
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/** Minimal RTSP/RTP H.264 unicast server for Windows ONVIF Network Camera support. */
internal class RtspH264Server(
    private val advertisedIp: String,
    private val port: Int = 8554
) {
    companion object {
        private const val TAG = "ProCamRTSP"
        private const val RTP_PAYLOAD_TYPE = 96
        private const val MAX_RTP_PAYLOAD = 1200
    }

    private val running = AtomicBoolean(false)
    private val clients = ConcurrentHashMap<String, ClientSession>()
    private val tcpClients = ConcurrentHashMap.newKeySet<Socket>()
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var sps: ByteArray? = null
    @Volatile private var pps: ByteArray? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val server = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress("0.0.0.0", port), 6)
            }
        } catch (e: Exception) {
            running.set(false)
            throw e
        }
        serverSocket = server
        executor.execute {
            try {
                while (running.get()) {
                    val client = try { server.accept() } catch (_: SocketException) { break }
                    client.tcpNoDelay = true
                    tcpClients.add(client)
                    executor.execute { handleClient(client) }
                }
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "RTSP server failed", e)
            }
        }
    }

    fun updateCodecConfiguration(newSps: ByteArray, newPps: ByteArray) {
        sps = newSps.copyOf()
        pps = newPps.copyOf()
    }

    fun publishFrame(accessUnit: ByteArray, presentationTimeUs: Long) {
        if (!running.get() || clients.isEmpty()) return
        val nals = splitAnnexB(accessUnit)
        if (nals.isEmpty()) return
        val active = clients.values.filter { it.playing && it.rtpSocket != null && it.clientRtpPort > 0 }
        for (session in active) {
            try {
                for (i in nals.indices) {
                    val nal = nals[i]
                    if (nal.isNotEmpty()) sendNal(session, nal, presentationTimeUs, i == nals.lastIndex)
                }
            } catch (e: Exception) {
                Log.w(TAG, "RTP send failed", e)
                session.playing = false
            }
        }
    }

    private fun handleClient(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        var session: ClientSession? = null
        try {
            socket.soTimeout = 0
            while (running.get() && !socket.isClosed) {
                val requestLine = readLine(input) ?: break
                if (requestLine.isBlank()) continue
                val parts = requestLine.split(' ')
                if (parts.size < 2) break
                val method = parts[0].uppercase()
                val headers = LinkedHashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val split = line.indexOf(':')
                    if (split > 0) headers[line.substring(0, split).trim().lowercase()] = line.substring(split + 1).trim()
                }
                val cseq = headers["cseq"] ?: "1"
                val existingSession = headers["session"]?.substringBefore(';')
                val response = when (method) {
                    "OPTIONS" -> rtspResponse(cseq, "200 OK", "Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN, GET_PARAMETER\r\n")
                    "DESCRIBE" -> {
                        val description = buildSdp()
                        if (description == null) {
                            rtspResponse(cseq, "503 Service Unavailable", "Retry-After: 1\r\n")
                        } else {
                            val bytes = description.toByteArray(Charsets.UTF_8)
                            rtspResponse(
                                cseq,
                                "200 OK",
                                "Content-Type: application/sdp\r\nContent-Base: rtsp://$advertisedIp:$port/live/\r\n",
                                bytes
                            )
                        }
                    }
                    "SETUP" -> {
                        val transport = headers["transport"] ?: ""
                        val clientPort = Regex("client_port\\s*=\\s*(\\d+)").find(transport)?.groupValues?.get(1)?.toIntOrNull()
                        if (clientPort == null) {
                            rtspResponse(cseq, "461 Unsupported Transport")
                        } else {
                            val udpPair = createUdpPair()
                            val newSessionId = UUID.randomUUID().toString().replace("-", "").take(16)
                            session = ClientSession(newSessionId, socket.inetAddress, clientPort, udpPair.first, udpPair.second)
                            clients[newSessionId] = session!!
                            val serverPort = udpPair.first.localPort
                            val rtcpPort = udpPair.second.localPort
                            val ssrc = String.format("%08X", session!!.ssrc)
                            rtspResponse(
                                cseq,
                                "200 OK",
                                "Session: $newSessionId;timeout=60\r\n" +
                                    "Transport: RTP/AVP;unicast;client_port=$clientPort-${clientPort + 1};server_port=$serverPort-$rtcpPort;ssrc=$ssrc;mode=PLAY\r\n"
                            )
                        }
                    }
                    "PLAY" -> {
                        val id = existingSession ?: session?.id
                        val found = id?.let { clients[it] }
                        if (found == null) rtspResponse(cseq, "454 Session Not Found")
                        else {
                            found.playing = true
                            session = found
                            rtspResponse(
                                cseq,
                                "200 OK",
                                "Session: ${found.id}\r\nRTP-Info: url=rtsp://$advertisedIp:$port/live/trackID=1;seq=${found.sequence};rtptime=0\r\n"
                            )
                        }
                    }
                    "GET_PARAMETER" -> rtspResponse(cseq, "200 OK", "Session: ${existingSession ?: session?.id ?: ""}\r\n")
                    "TEARDOWN" -> {
                        val id = existingSession ?: session?.id
                        if (id != null) removeSession(id)
                        rtspResponse(cseq, "200 OK", "Session: ${id ?: ""}\r\n")
                    }
                    else -> rtspResponse(cseq, "405 Method Not Allowed", "Allow: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN, GET_PARAMETER\r\n")
                }
                output.write(response)
                output.flush()
                if (method == "TEARDOWN") break
            }
        } catch (e: Exception) {
            if (running.get()) Log.d(TAG, "RTSP client disconnected", e)
        } finally {
            session?.let { removeSession(it.id) }
            try { socket.close() } catch (_: Exception) {}
            tcpClients.remove(socket)
        }
    }

    private fun buildSdp(): String? {
        // The first CameraX frame initializes MediaCodec and supplies SPS/PPS.
        // Allow a short window for that initial encoder setup before rejecting DESCRIBE.
        var attempts = 0
        while ((sps == null || pps == null) && attempts < 30) {
            try { Thread.sleep(50) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
            attempts++
        }
        val currentSps = sps ?: return null
        val currentPps = pps ?: return null
        val sps64 = Base64.encodeToString(currentSps, Base64.NO_WRAP)
        val pps64 = Base64.encodeToString(currentPps, Base64.NO_WRAP)
        return "v=0\r\n" +
            "o=- 0 0 IN IP4 $advertisedIp\r\n" +
            "s=ProCam Wireless Camera\r\n" +
            "c=IN IP4 $advertisedIp\r\n" +
            "t=0 0\r\n" +
            "a=control:*\r\n" +
            "m=video 0 RTP/AVP $RTP_PAYLOAD_TYPE\r\n" +
            "a=rtpmap:$RTP_PAYLOAD_TYPE H264/90000\r\n" +
            "a=fmtp:$RTP_PAYLOAD_TYPE packetization-mode=1;profile-level-id=${profileLevelId(currentSps)};sprop-parameter-sets=$sps64,$pps64\r\n" +
            "a=control:trackID=1\r\n"
    }

    private fun profileLevelId(sps: ByteArray): String {
        if (sps.size < 4) return "42E01F"
        return String.format(
            "%02X%02X%02X",
            sps[1].toInt() and 0xFF,
            sps[2].toInt() and 0xFF,
            sps[3].toInt() and 0xFF
        )
    }

    private fun createUdpPair(): Pair<DatagramSocket, DatagramSocket> {
        repeat(24) {
            val rtp = DatagramSocket(null)
            try {
                rtp.reuseAddress = true
                rtp.bind(InetSocketAddress("0.0.0.0", 0))
                if (rtp.localPort >= 65535) {
                    rtp.close()
                    return@repeat
                }
                val rtcp = DatagramSocket(null)
                try {
                    rtcp.reuseAddress = true
                    rtcp.bind(InetSocketAddress("0.0.0.0", rtp.localPort + 1))
                    return rtp to rtcp
                } catch (_: Exception) {
                    rtcp.close()
                    rtp.close()
                }
            } catch (e: Exception) {
                rtp.close()
                if (it == 23) throw e
            }
        }
        throw SocketException("Could not allocate adjacent RTP/RTCP UDP ports")
    }

    private fun sendNal(session: ClientSession, nal: ByteArray, ptsUs: Long, lastNal: Boolean) {
        val udp = session.rtpSocket ?: return
        val address = session.clientAddress
        val timestamp = ((ptsUs * 90L) / 1_000L).toInt()
        if (nal.size <= MAX_RTP_PAYLOAD) {
            sendRtpPacket(session, udp, address, session.clientRtpPort, nal, timestamp, lastNal)
            return
        }

        val nalHeader = nal[0].toInt() and 0xFF
        val indicator = (nalHeader and 0xE0) or 28
        val nalType = nalHeader and 0x1F
        val chunkSize = MAX_RTP_PAYLOAD - 2
        var offset = 1
        while (offset < nal.size) {
            val remaining = nal.size - offset
            val length = minOf(chunkSize, remaining)
            val start = offset == 1
            val end = offset + length >= nal.size
            val fuHeader = (if (start) 0x80 else 0) or (if (end) 0x40 else 0) or nalType
            val payload = ByteArray(length + 2)
            payload[0] = indicator.toByte()
            payload[1] = fuHeader.toByte()
            System.arraycopy(nal, offset, payload, 2, length)
            sendRtpPacket(session, udp, address, session.clientRtpPort, payload, timestamp, lastNal && end)
            offset += length
        }
    }

    private fun sendRtpPacket(
        session: ClientSession,
        socket: DatagramSocket,
        address: InetAddress,
        port: Int,
        payload: ByteArray,
        timestamp: Int,
        marker: Boolean
    ) {
        val packet = ByteArray(12 + payload.size)
        packet[0] = 0x80.toByte()
        packet[1] = ((if (marker) 0x80 else 0) or RTP_PAYLOAD_TYPE).toByte()
        packet[2] = (session.sequence ushr 8).toByte()
        packet[3] = session.sequence.toByte()
        session.sequence = (session.sequence + 1) and 0xFFFF
        packet[4] = (timestamp ushr 24).toByte()
        packet[5] = (timestamp ushr 16).toByte()
        packet[6] = (timestamp ushr 8).toByte()
        packet[7] = timestamp.toByte()
        packet[8] = (session.ssrc ushr 24).toByte()
        packet[9] = (session.ssrc ushr 16).toByte()
        packet[10] = (session.ssrc ushr 8).toByte()
        packet[11] = session.ssrc.toByte()
        System.arraycopy(payload, 0, packet, 12, payload.size)
        socket.send(DatagramPacket(packet, packet.size, address, port))
    }

    private fun splitAnnexB(data: ByteArray): List<ByteArray> {
        val starts = ArrayList<Int>()
        var index = 0
        while (index + 3 < data.size) {
            if (data[index] == 0.toByte() && data[index + 1] == 0.toByte() &&
                (data[index + 2] == 1.toByte() ||
                    (data[index + 2] == 0.toByte() && data[index + 3] == 1.toByte()))
            ) {
                starts += index
                index += if (data[index + 2] == 1.toByte()) 3 else 4
            } else index++
        }
        if (starts.isEmpty()) return if (data.isEmpty()) emptyList() else listOf(data)
        val result = ArrayList<ByteArray>()
        for (i in starts.indices) {
            val start = starts[i] + if (data[starts[i] + 2] == 1.toByte()) 3 else 4
            val end = if (i + 1 < starts.size) starts[i + 1] else data.size
            if (end > start) result += data.copyOfRange(start, end)
        }
        return result
    }

    private fun rtspResponse(cseq: String, status: String, extraHeaders: String = "", body: ByteArray = ByteArray(0)): ByteArray {
        val header = "RTSP/1.0 $status\r\nCSeq: $cseq\r\n$extraHeaders" +
            "Content-Length: ${body.size}\r\n\r\n"
        val headBytes = header.toByteArray(Charsets.US_ASCII)
        return headBytes + body
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) return if (bytes.size() == 0) null else bytes.toString("US-ASCII")
            if (value == 10) break
            if (value != 13) bytes.write(value)
            if (bytes.size() > 8192) throw IllegalStateException("RTSP header too long")
        }
        return bytes.toString("US-ASCII")
    }

    private fun removeSession(id: String) {
        val removed = clients.remove(id) ?: return
        removed.playing = false
        try { removed.rtpSocket?.close() } catch (_: Exception) {}
        try { removed.rtcpSocket?.close() } catch (_: Exception) {}
        removed.rtpSocket = null
        removed.rtcpSocket = null
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        clients.keys.toList().forEach(::removeSession)
        tcpClients.toList().forEach { try { it.close() } catch (_: Exception) {} }
        tcpClients.clear()
        executor.shutdownNow()
    }

    private class ClientSession(
        val id: String,
        val clientAddress: InetAddress,
        val clientRtpPort: Int,
        @Volatile var rtpSocket: DatagramSocket?,
        @Volatile var rtcpSocket: DatagramSocket?
    ) {
        @Volatile var playing: Boolean = false
        @Volatile var sequence: Int = Random.nextInt(0, 65536)
        val ssrc: Int = Random.nextInt()
    }
}

package com.axzora.os.call

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * A small RFC 6455 WebSocket client — enough for the voice relay: text and
 * binary frames, ping/pong, clean close. Written on plain sockets so the app
 * picks up no new dependency for it.
 *
 * Callbacks fire on the reader thread; keep them short.
 */
class WsClient(
    private val url: String,
    private val onText: (String) -> Unit = {},
    private val onBinary: (ByteArray) -> Unit = {},
    private val onClose: (String) -> Unit = {},
) {
    private var socket: Socket? = null
    private var out: OutputStream? = null
    private val open = AtomicBoolean(false)
    private val writeLock = Any()

    val isOpen: Boolean get() = open.get()

    /** Connects and completes the handshake; throws on failure. */
    fun connect(timeoutMs: Int = 8000) {
        val u = URI(url)
        val secure = u.scheme.equals("wss", true)
        val port = if (u.port > 0) u.port else if (secure) 443 else 80
        // The server pings every 10 s, so a live link always has inbound traffic;
        // a 25 s read timeout is how a dead network gets noticed.
        val raw = Socket().apply { connect(InetSocketAddress(u.host, port), timeoutMs); tcpNoDelay = true; soTimeout = 25_000 }
        val s: Socket = if (secure) {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val ssl = factory.createSocket(raw as Socket, u.host as String, port, true) as javax.net.ssl.SSLSocket
            ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            ssl.startHandshake()
            ssl
        } else raw
        val input = BufferedInputStream(s.getInputStream())
        val output = BufferedOutputStream(s.getOutputStream())
        val key = Base64.getEncoder().encodeToString(Random.nextBytes(16))
        val path = (u.rawPath.ifBlank { "/" }) + (u.rawQuery?.let { "?$it" } ?: "")
        val req = "GET $path HTTP/1.1\r\nHost: ${u.host}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n"
        output.write(req.toByteArray()); output.flush()
        // read the response head
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = input.read(); if (b < 0) throw IllegalStateException("connection closed during handshake")
            head.append(b.toChar()); if (head.length > 16384) throw IllegalStateException("handshake too long")
        }
        if (!head.startsWith("HTTP/1.1 101")) throw IllegalStateException("handshake refused: " + head.lineSequence().first())
        val expect = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
        )
        if (!head.contains(expect)) throw IllegalStateException("bad accept key")
        socket = s; out = output; open.set(true)
        thread(name = "ws-reader", isDaemon = true) { readLoop(input) }
    }

    private fun readLoop(input: InputStream) {
        var reason = "closed"
        try {
            var fragOp = 0; var frag = java.io.ByteArrayOutputStream()
            while (open.get()) {
                val b0 = input.read(); if (b0 < 0) break
                val b1 = input.read(); if (b1 < 0) break
                val fin = (b0 and 0x80) != 0; val op = b0 and 0x0f
                var len = (b1 and 0x7f).toLong()
                if (len == 126L) len = ((input.read() shl 8) or input.read()).toLong()
                else if (len == 127L) { len = 0; repeat(8) { len = (len shl 8) or input.read().toLong() } }
                val masked = (b1 and 0x80) != 0
                val mask = if (masked) ByteArray(4).also { readFully(input, it) } else null
                if (len > (1 shl 20)) { reason = "frame too large"; break }
                val payload = ByteArray(len.toInt()).also { readFully(input, it) }
                if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
                when (op) {
                    0x8 -> { reason = "closed by server"; break }
                    0x9 -> frame(0xA, payload)
                    0xA -> {}
                    0x1, 0x2, 0x0 -> {
                        if (op != 0) fragOp = op
                        frag.write(payload)
                        if (fin) { val whole = frag.toByteArray(); frag = java.io.ByteArrayOutputStream()
                            if (fragOp == 0x1) onText(String(whole)) else if (fragOp == 0x2) onBinary(whole) }
                    }
                }
            }
        } catch (e: Exception) { reason = e.message ?: "error" }
        if (open.getAndSet(false)) { runCatching { socket?.close() }; onClose(reason) }
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) { val n = input.read(buf, off, buf.size - off); if (n < 0) throw IllegalStateException("eof"); off += n }
    }

    private fun frame(op: Int, payload: ByteArray) {
        val o = out ?: return
        val mask = Random.nextBytes(4)
        val len = payload.size
        val head = java.io.ByteArrayOutputStream()
        head.write(0x80 or op)
        when {
            len < 126 -> head.write(0x80 or len)
            len < 65536 -> { head.write(0x80 or 126); head.write(len shr 8); head.write(len and 0xff) }
            else -> { head.write(0x80 or 127); repeat(4) { head.write(0) }; head.write(len ushr 24); head.write((len shr 16) and 0xff); head.write((len shr 8) and 0xff); head.write(len and 0xff) }
        }
        head.write(mask)
        val masked = ByteArray(len) { i -> (payload[i].toInt() xor mask[i and 3].toInt()).toByte() }
        synchronized(writeLock) {
            runCatching { o.write(head.toByteArray()); o.write(masked); o.flush() }
                .onFailure { if (open.getAndSet(false)) { runCatching { socket?.close() }; onClose("write failed") } }
        }
    }

    fun sendText(s: String) { if (open.get()) frame(0x1, s.toByteArray()) }
    fun sendBinary(b: ByteArray) { if (open.get()) frame(0x2, b) }
    fun ping() { if (open.get()) frame(0x9, ByteArray(0)) }

    /** Closes at once without waiting for a stuck write; the close frame is best-effort. */
    fun close() {
        if (open.getAndSet(false)) {
            val s = socket
            thread(isDaemon = true) { runCatching { frame(0x8, ByteArray(0)) } }
            runCatching { s?.close() }
        }
    }
}

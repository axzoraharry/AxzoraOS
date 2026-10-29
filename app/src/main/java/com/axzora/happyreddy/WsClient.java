package com.axzora.happyreddy;

import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Minimal RFC 6455 WebSocket client over TLS — zero external dependencies.
 * Text frames only (the Gemini Live API speaks JSON text messages).
 * connect() performs the handshake and then runs the read loop on the
 * calling thread; call it from a background thread.
 */
class WsClient {

    interface Handler {
        void onOpen();
        void onText(String message);
        void onClose(int code, String reason);
        void onFailure(Exception e);
    }

    private final String host;
    private final String path;
    private final Handler handler;
    private final SecureRandom rnd = new SecureRandom();

    private SSLSocket socket;
    private InputStream in;
    private OutputStream out;
    private volatile boolean closed;

    WsClient(String host, String path, Handler handler) {
        this.host = host;
        this.path = path;
        this.handler = handler;
    }

    void connect() {
        try {
            socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket(host, 443);
            socket.startHandshake();
            in  = new BufferedInputStream(socket.getInputStream(), 1 << 16);
            out = new BufferedOutputStream(socket.getOutputStream(), 1 << 16);

            byte[] nonce = new byte[16];
            rnd.nextBytes(nonce);
            String key = Base64.encodeToString(nonce, Base64.NO_WRAP);
            String req = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + host + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + key + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "\r\n";
            out.write(req.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            String head = readHttpHead();
            if (!head.startsWith("HTTP/1.1 101")) {
                String firstLine = head.split("\r\n", 2)[0];
                throw new IOException("WebSocket handshake failed: " + firstLine);
            }

            handler.onOpen();
            readLoop();
        } catch (Exception e) {
            if (!closed) {
                handler.onFailure(e);
            }
            closeQuietly();
        }
    }

    private String readHttpHead() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int state = 0; // count of consecutive \r\n\r\n bytes matched
        while (state < 4) {
            int b = in.read();
            if (b < 0) throw new IOException("Connection closed during handshake");
            buf.write(b);
            if ((state % 2 == 0 && b == '\r') || (state % 2 == 1 && b == '\n')) {
                state++;
            } else {
                state = (b == '\r') ? 1 : 0;
            }
            if (buf.size() > 65536) throw new IOException("Handshake response too large");
        }
        return buf.toString("ISO-8859-1");
    }

    // ── Read loop ────────────────────────────────────────────────────────────

    private void readLoop() throws IOException {
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        while (!closed) {
            int b0 = in.read();
            int b1 = in.read();
            if (b0 < 0 || b1 < 0) {
                if (!closed) handler.onClose(1006, "Connection lost");
                return;
            }
            boolean fin    = (b0 & 0x80) != 0;
            int     opcode = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            long    len    = b1 & 0x7F;
            if (len == 126) {
                len = ((long) readByte() << 8) | readByte();
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) len = (len << 8) | readByte();
            }
            if (len > (16L << 20)) throw new IOException("Frame too large: " + len);

            byte[] mask = null;
            if (masked) {
                mask = new byte[4];
                readFully(mask);
            }
            byte[] payload = new byte[(int) len];
            readFully(payload);
            if (mask != null) {
                for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
            }

            switch (opcode) {
                case 0x1: // text
                case 0x2: // binary (treat as text — Live API is JSON either way)
                    message.reset();
                    message.write(payload, 0, payload.length);
                    if (fin) deliver(message);
                    break;
                case 0x0: // continuation
                    message.write(payload, 0, payload.length);
                    if (fin) deliver(message);
                    break;
                case 0x8: // close
                    int code = 1005;
                    String reason = "";
                    if (payload.length >= 2) {
                        code = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
                        reason = new String(payload, 2, payload.length - 2,
                                StandardCharsets.UTF_8);
                    }
                    try { sendFrame(0x8, payload); } catch (IOException ignored) { }
                    if (!closed) handler.onClose(code, reason);
                    closeQuietly();
                    return;
                case 0x9: // ping → pong
                    sendFrame(0xA, payload);
                    break;
                default:  // pong / reserved — ignore
                    break;
            }
        }
    }

    private void deliver(ByteArrayOutputStream message) {
        handler.onText(new String(message.toByteArray(), StandardCharsets.UTF_8));
        message.reset();
    }

    private int readByte() throws IOException {
        int b = in.read();
        if (b < 0) throw new IOException("Connection closed mid-frame");
        return b;
    }

    private void readFully(byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new IOException("Connection closed mid-frame");
            off += n;
        }
    }

    // ── Send ─────────────────────────────────────────────────────────────────

    void sendText(String text) throws IOException {
        sendFrame(0x1, text.getBytes(StandardCharsets.UTF_8));
    }

    private synchronized void sendFrame(int opcode, byte[] payload) throws IOException {
        if (closed || out == null) throw new IOException("WebSocket is closed");
        ByteArrayOutputStream frame = new ByteArrayOutputStream(payload.length + 14);
        frame.write(0x80 | opcode);
        if (payload.length < 126) {
            frame.write(0x80 | payload.length);
        } else if (payload.length < 65536) {
            frame.write(0x80 | 126);
            frame.write(payload.length >> 8);
            frame.write(payload.length & 0xFF);
        } else {
            frame.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) {
                frame.write((int) (((long) payload.length >> (8 * i)) & 0xFF));
            }
        }
        byte[] mask = new byte[4];
        rnd.nextBytes(mask);
        frame.write(mask, 0, 4);
        for (int i = 0; i < payload.length; i++) {
            frame.write(payload[i] ^ mask[i & 3]);
        }
        out.write(frame.toByteArray());
        out.flush();
    }

    void close() {
        if (closed) return;
        closed = true;
        try { sendFrameUnchecked(); } catch (Exception ignored) { }
        closeQuietly();
    }

    private void sendFrameUnchecked() throws IOException {
        // polite close frame (code 1000)
        byte[] payload = {0x03, (byte) 0xE8};
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0x88);
        frame.write(0x82);
        byte[] mask = new byte[4];
        rnd.nextBytes(mask);
        frame.write(mask, 0, 4);
        for (int i = 0; i < payload.length; i++) frame.write(payload[i] ^ mask[i & 3]);
        out.write(frame.toByteArray());
        out.flush();
    }

    private void closeQuietly() {
        closed = true;
        try { if (socket != null) socket.close(); } catch (Exception ignored) { }
    }
}

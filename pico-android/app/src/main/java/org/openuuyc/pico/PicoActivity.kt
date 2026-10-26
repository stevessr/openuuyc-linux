package org.openuuyc.pico

import android.app.Activity
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PicoActivity : Activity() {
    private external fun nativeRun(activity: PicoActivity)
    private external fun nativeStop()
    private val http = OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).build()
    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    private var xrThread: Thread? = null
    private var socket: WebSocket? = null
    private var decoder: AvcDecoder? = null
    private var xrSurface: Surface? = null
    private lateinit var status: TextView
    private lateinit var token: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        System.loadLibrary("openuuyc_pico")
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        status = TextView(this).apply { text = "PICO OpenXR · USB ADB reverse tcp:18765 tcp:18765" }
        token = EditText(this).apply {
            hint = "Enter Linux bridge token (32+ characters)"
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val connect = Button(this).apply {
            text = "Connect headset / start OpenXR"
            setOnClickListener { startSession() }
        }
        val stop = Button(this).apply {
            text = "Disconnect"
            setOnClickListener { stopSession() }
        }
        layout.addView(status)
        layout.addView(token, ViewGroup.LayoutParams(-1, -2))
        layout.addView(connect)
        layout.addView(stop)
        setContentView(layout)
    }

    private fun startSession() {
        if (running.get()) return
        val secret = token.text.toString()
        if (secret.length < 32 || secret.length > 256) {
            status.text = "Token must contain 32–256 characters"
            return
        }
        running.set(true)
        status.text = "Initializing PICO OpenXR..."
        xrThread = Thread({ nativeRun(this) }, "OpenUUYC-OpenXR").also { it.start() }
    }

    // Invoked from native OpenXR thread when the runtime's compositor gives
    // us an Android Surface-backed quad swapchain.
    @Suppress("unused")
    fun onXrSurface(surface: Surface) {
        main.post {
            if (!running.get()) { surface.release(); return@post }
            xrSurface = surface
            val secret = token.text.toString()
            connectBridge(secret, surface)
        }
    }

    @Suppress("unused")
    fun onXrError(message: String) {
        main.post { status.text = "OpenXR: $message"; stopSession() }
    }

    // Called from the native tracking thread; u,v are normalized quad hit
    // coordinates, not raw 6DoF samples or local desktop coordinates.
    @Suppress("unused")
    fun onPointer(u: Float, v: Float, down: Boolean) {
        if (!connected.get()) return
        socket?.send(JSONObject().apply {
            put("type", "pointer")
            put("u", u.toDouble())
            put("v", v.toDouble())
            put("down", down)
        }.toString())
    }

    private fun connectBridge(secret: String, surface: Surface) {
        status.text = "Opening USB loopback bridge..."
        val request = Request.Builder().url("ws://127.0.0.1:18765").build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                ws.send(JSONObject().put("type", "auth").put("token", secret).toString())
            }
            override fun onMessage(ws: WebSocket, text: String) {
                val obj = JSONObject(text)
                when (obj.optString("type")) {
                    "hello" -> {
                        if (obj.optInt("version") != 1 || obj.optString("codec") != "h264") {
                            ws.close(1003, "unsupported video bridge")
                            return
                        }
                        connected.set(true)
                        ws.send("""{"type":"control","enabled":true}""")
                        main.post { status.text = "Connected · aim controller and press trigger" }
                    }
                    "error" -> main.post { status.text = "Bridge busy / denied" }
                }
            }
            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                if (!connected.get()) return
                val payload = bytes.toByteArray()
                if (payload.size > 2 && payload[0] == 1.toByte()) {
                    main.post {
                        if (running.get()) {
                            if (decoder == null) decoder = AvcDecoder(surface)
                            decoder?.feed(payload, 2, payload.size - 2, payload[1] == 1.toByte())
                        }
                    }
                }
            }
            override fun onClosing(ws: WebSocket, code: Int, reason: String) { ws.close(code, reason) }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) { disconnected() }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                disconnected()
                main.post { status.text = "Bridge: ${t.message}" }
            }
        })
    }

    private fun disconnected() {
        connected.set(false)
        main.post { decoder?.close(); decoder = null }
    }

    private fun stopSession() {
        connected.set(false)
        socket?.send("""{"type":"control","enabled":false}""")
        socket?.close(1000, "headset session ended")
        socket = null
        running.set(false)
        nativeStop()
        decoder?.close()
        decoder = null
        xrSurface?.release()
        xrSurface = null
        status.text = "Stopped"
    }

    override fun onDestroy() {
        stopSession()
        http.dispatcher.executorService.shutdown()
        super.onDestroy()
    }
}

/** MediaCodec to compositor Surface: no CPU pixel readback/copy. */
internal class AvcDecoder(private val surface: Surface) {
    private var codec: MediaCodec? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var ptsUs: Long = 0
    private val info = MediaCodec.BufferInfo()
    private var waitForIdr = true

    fun feed(bytes: ByteArray, offset: Int, length: Int, keyframe: Boolean) {
        if (length <= 0 || length > 4 * 1024 * 1024) return
        for (nal in AnnexB.nals(bytes, offset, length)) {
            when (bytes[nal.first].toInt() and 0x1f) {
                7 -> sps = bytes.copyOfRange(nal.first - nal.startCode, nal.last + 1)
                8 -> pps = bytes.copyOfRange(nal.first - nal.startCode, nal.last + 1)
            }
        }
        if (codec == null) {
            val sequence = sps ?: return
            val picture = pps ?: return
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080)
                format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(sequence))
                format.setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(picture))
                configure(format, surface, null, 0)
                start()
            }
        }
        if (!keyframe && waitForIdr) return
        val dec = codec ?: return
        try {
            val index = dec.dequeueInputBuffer(0)
            if (index >= 0) {
                val input = dec.getInputBuffer(index) ?: return
                if (input.capacity() < length) { dec.queueInputBuffer(index, 0, 0, ptsUs, 0); return }
                input.clear()
                input.put(bytes, offset, length)
                dec.queueInputBuffer(index, 0, length, ptsUs, if (keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                ptsUs += 16_667 // Monotonic presentation order; XR compositor owns display timing.
                if (keyframe) waitForIdr = false
            }
            var out = dec.dequeueOutputBuffer(info, 0)
            while (out >= 0) {
                dec.releaseOutputBuffer(out, true)
                out = dec.dequeueOutputBuffer(info, 0)
            }
        } catch (error: IllegalStateException) {
            close()
        }
    }

    fun close() {
        val value = codec
        codec = null
        if (value != null) {
            runCatching { value.stop() }
            runCatching { value.release() }
        }
        waitForIdr = true
    }
}

/** Extract H.264 Annex-B NALs including three- and four-byte start codes. */
internal object AnnexB {
    internal data class Nal(val first: Int, val last: Int, val startCode: Int)
    fun nals(bytes: ByteArray, offset: Int, length: Int): List<Nal> {
        val end = (offset + length).coerceAtMost(bytes.size)
        val marks = ArrayList<Pair<Int, Int>>()
        var i = offset
        while (i + 3 <= end) {
            val size = when {
                i + 4 <= end && bytes[i] == 0.toByte() && bytes[i+1] == 0.toByte() && bytes[i+2] == 0.toByte() && bytes[i+3] == 1.toByte() -> 4
                bytes[i] == 0.toByte() && bytes[i+1] == 0.toByte() && bytes[i+2] == 1.toByte() -> 3
                else -> 0
            }
            if (size > 0) { marks.add(Pair(i, size)); i += size } else i++
        }
        return marks.mapIndexedNotNull { index, mark ->
            val start = mark.first + mark.second
            val stop = if (index + 1 < marks.size) marks[index + 1].first else end
            if (start < stop) Nal(start, stop - 1, mark.second) else null
        }
    }
}

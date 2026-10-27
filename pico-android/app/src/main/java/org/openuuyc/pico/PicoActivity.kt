package org.openuuyc.pico

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import org.openuuyc.pico.uu.UuDirectSession
import org.openuuyc.pico.uu.UuJoinGate
import org.openuuyc.pico.uu.UuNrdApi
import org.openuuyc.pico.uu.UuDevice
import org.openuuyc.pico.uu.UuQr
import org.openuuyc.pico.uu.UuQrStatusTracker
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Direct PICO UU client: account QR -> device list -> UU room -> Socket.IO ->
 * WebRTC -> OpenXR compositor. Does not require a desktop proxy or USB.
 */
class PicoActivity : Activity() {
    private external fun nativeRun(activity: PicoActivity)
    private external fun nativeStop()

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val loginEpoch = AtomicLong(0L)
    private val joinGate = UuJoinGate()
    private val running = AtomicBoolean(false)
    @Volatile private var session: UuDirectSession? = null
    private var xrThread: Thread? = null
    private var xrSurface: Surface? = null
    private lateinit var status: TextView
    private lateinit var qrImage: ImageView
    private lateinit var devices: LinearLayout
    private lateinit var api: UuNrdApi

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        System.loadLibrary("openuuyc_pico")
        api = UuNrdApi(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        status = TextView(this).apply {
            text = "OpenUUYC PICO · UU 协议直连\n请用 UU 官方客户端扫码登录"
            textSize = 18f
        }
        qrImage = ImageView(this).apply {
            adjustViewBounds = true
            visibility = ImageView.GONE
        }
        devices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(status)
        root.addView(Button(this).apply {
            text = "使用 UU 二维码登录"
            setOnClickListener { login() }
        })
        root.addView(Button(this).apply {
            text = "刷新 UU 设备列表"
            setOnClickListener { refresh() }
        })
        root.addView(Button(this).apply {
            text = "断开当前会话"
            setOnClickListener { stopSession() }
        })
        root.addView(qrImage, ViewGroup.LayoutParams(-1, 440))
        root.addView(devices)
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun message(text: String) {
        ui.post {
            if (!isFinishing && !isDestroyed) status.text = text
        }
    }

    private fun login() {
        val loginTicket = loginEpoch.incrementAndGet()
        worker.submit {
            if (loginEpoch.get() != loginTicket || isDestroyed) return@submit
            try {
                api.initializeDevice()
                var qr = api.createQr()
                val tracker = UuQrStatusTracker()
                var deadline = System.currentTimeMillis() + 300_000
                while (loginEpoch.get() == loginTicket && !isDestroyed) {
                    val image = qrBitmap(qr.url)
                    ui.post {
                        if (loginEpoch.get() == loginTicket && !isDestroyed) {
                            qrImage.setImageBitmap(image)
                            qrImage.visibility = ImageView.VISIBLE
                        }
                    }
                    message("请用手机 UU 客户端扫码确认，二维码有效期 5 分钟")
                    while (loginEpoch.get() == loginTicket && !isDestroyed &&
                        System.currentTimeMillis() < deadline) {
                        val state = api.queryQr(qr, tracker.status)
                        if (loginEpoch.get() != loginTicket || isDestroyed) return@submit
                        when (tracker.receive(state)) {
                            UuQrStatusTracker.Transition.WAITING -> Thread.sleep(1_000)
                            UuQrStatusTracker.Transition.SCANNED -> {
                                message("已扫码，等待手机确认")
                                Thread.sleep(1_000)
                            }
                            UuQrStatusTracker.Transition.REFRESH -> break
                            UuQrStatusTracker.Transition.CONFIRMED -> {
                                api.confirmQr(qr)
                                if (loginEpoch.get() != loginTicket || isDestroyed) return@submit
                                message("UU 登录成功，可从设备列表直接发起 PICO 远控")
                                ui.post { qrImage.visibility = ImageView.GONE }
                                refresh()
                                return@submit
                            }
                        }
                    }
                    if (loginEpoch.get() != loginTicket || isDestroyed) return@submit
                    qr = api.createQr()
                    tracker.reset()
                    deadline = System.currentTimeMillis() + 300_000
                }
            } catch (e: Exception) {
                if (loginEpoch.get() == loginTicket && !isDestroyed)
                    message("UU 原生登录失败：${e.javaClass.simpleName}")
            }
        }
    }

    private fun qrBitmap(url: String): Bitmap {
        require(url.startsWith("https://"))
        val size = 384
        val bits = MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            pixels[y * size + x] = if (bits.get(x, y)) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun refresh() {
        worker.submit {
            try {
                val list = api.devices()
                ui.post {
                    devices.removeAllViews()
                    if (list.isEmpty()) {
                        message("UU 账号下没有可连接设备")
                    }
                    list.forEach { device ->
                        devices.addView(Button(this).apply {
                            text = "${device.alias} · " +
                                (if (device.online) "在线" else "离线") +
                                (if (device.occupants > 0) " · 已占用" else "")
                            isEnabled = device.online && device.controllable && device.occupants == 0
                            setOnClickListener { connect(device) }
                        })
                    }
                }
            } catch (e: Exception) {
                message("UU 设备列表失败：${e.javaClass.simpleName}")
            }
        }
    }

    private fun connect(device: UuDevice) {
        val ticket = joinGate.begin() ?: return
        message("加入 UU 远控房间：${device.alias}")
        worker.submit {
            try {
                // Only the network request runs off-thread. Adoption and
                // Session/Surface ownership are serialized on the UI thread
                // with Stop and Activity.onDestroy.
                val room = api.join(device)
                ui.post {
                    if (isFinishing || isDestroyed || !joinGate.adopt(ticket)) return@post
                    try {
                        val direct = UuDirectSession(applicationContext, room, api.identity, ::message) {
                            ui.post { if (running.get() && !isDestroyed) stopSession() }
                        }
                        session = direct
                        running.set(true)
                        xrThread = Thread({
                            try { nativeRun(this) }
                            finally {
                                ui.post {
                                    if (!isDestroyed) stopSession()
                                    xrSurface?.release()
                                    xrSurface = null
                                }
                            }
                        }, "OpenUUYC-PICO-OpenXR").also { it.start() }
                    } catch (e: Exception) {
                        joinGate.stop()
                        message("UU 头显启动失败：${e.javaClass.simpleName}")
                    }
                }
            } catch (e: Exception) {
                joinGate.failed(ticket)
                if (!isDestroyed) message("UU 设备连接失败：${e.javaClass.simpleName}")
            }
        }
    }

    @Suppress("unused")
    fun onXrSurface(surface: Surface) {
        ui.post {
            if (!running.get()) { surface.release(); return@post }
            xrSurface = surface
            try { session?.start(surface) }
            catch (e: Exception) {
                message("UU 直连启动失败：${e.javaClass.simpleName}")
                stopSession()
            }
        }
    }

    @Suppress("unused")
    fun onPointer(u: Float, v: Float, down: Boolean) {
        session?.pointer(u, v, down)
    }

    @Suppress("unused")
    fun onScroll(delta: Int) {
        session?.scroll(delta)
    }

    @Suppress("unused")
    fun onXrError(reason: String) {
        message("OpenXR：$reason")
        ui.post { stopSession() }
    }

    private fun stopSession() {
        loginEpoch.incrementAndGet()
        joinGate.stop()
        session?.close()
        session = null
        if (running.getAndSet(false)) nativeStop()
        message("会话已断开")
    }

    override fun onDestroy() {
        stopSession()
        joinGate.destroy()
        worker.shutdownNow()
        api.close()
        super.onDestroy()
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

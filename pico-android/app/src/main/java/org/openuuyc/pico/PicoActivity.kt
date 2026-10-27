package org.openuuyc.pico

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.app.Activity
import android.content.Intent
import android.util.Log
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
import org.openuuyc.pico.uu.UuNrdResponseException
import org.openuuyc.pico.uu.UuSavedLoginFlow
import org.openuuyc.pico.uu.UuRoom
import org.openuuyc.pico.uu.UuIdentity
import java.util.concurrent.atomic.AtomicReference
import org.openuuyc.pico.uu.UuQrStatusTracker
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Direct PICO UU client: account QR -> device list -> UU room -> Socket.IO ->
 * WebRTC -> OpenXR compositor. Does not require a desktop proxy or USB.
 */
open class PicoActivity : Activity() {
    companion object {
        // One-use, process-local handoff: never put room credentials in Intents.
        private data class ViewerLaunch(val room: UuRoom, val identity: UuIdentity, val passthrough: Boolean, val alias: String,val deviceId: String = "")
        private val pendingViewer = AtomicReference<ViewerLaunch?>(null)
        private const val CAMERA_REQUEST = 8341
        private const val XR_REQUEST = 1
    }
    private external fun nativeRun(activity: PicoActivity)
    private external fun nativeResetLayout()
    private external fun nativeSetFollowView(enabled: Boolean)
    private var xrWindowFrame: PicoXrWindowFrame? = null
    private var xrToolbar: PicoXrToolbar? = null
    private var xrDeviceAlias="远程设备"
    private var xrDeviceId=""
    private var xrMainAlive=true
    private val xrClosingPrimary=mutableSetOf<Int>()
    private val xrExtraSessions=java.util.concurrent.ConcurrentHashMap<Int,UuDirectSession>()
    private val xrExtraDeviceIds=java.util.concurrent.ConcurrentHashMap<Int,String>()
    private val xrReservedSlots=java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    private val xrPaneVisible=java.util.concurrent.atomic.AtomicIntegerArray(6)
    @Volatile private var xrActivePane=0
    @Volatile private var xrSettingsSession: UuDirectSession?=null
    private fun currentXrSession()=xrSettingsSession ?: xrExtraSessions[xrActivePane] ?: session

    private var xrFollowing = false
    private var xrTouchMode=false
    private external fun nativeStop()
    private external fun nativeDockMenu(pane: Int)
    private external fun nativeSetMenuPortrait(portrait: Boolean)
    private external fun nativeSetWindowAspect(pane: Int,width: Int,height: Int)
    private external fun nativeSetWindowVisible(index: Int, visible: Boolean)
    private external fun nativeSetPassthroughEnabled(enabled: Boolean)
    private var passthroughEnabled = true
    private lateinit var xrAppearance: PicoXrAppearance
    private lateinit var cameraPreview: PicoCameraPreview

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val loginEpoch = AtomicLong(0L)
    private val joinGate = UuJoinGate()
    private val running = AtomicBoolean(false)
    @Volatile private var session: UuDirectSession? = null
    private var xrThread: Thread? = null
    private var panelVideo: PicoPanelVideoView? = null
    private var remoteKeyboard: PicoRemoteKeyboard? = null
    private var smartIme = true
    private var lastRemoteTap = 0L
    private var lastSessionMessage = ""
    private val xrSurfaces = mutableMapOf<Int, Surface>()
    private lateinit var status: TextView
    private lateinit var qrImage: ImageView
    private var loginView: PicoLoginView? = null
    private lateinit var api: UuNrdApi

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        xrAppearance=PicoXrAppearance(this)
        passthroughEnabled=xrAppearance.mr
        System.loadLibrary("openuuyc_pico")
        worker.execute {xrAppearance.prepareBackground();ui.post {if(!isDestroyed && running.get()) applyXrAppearance()}}
        status = TextView(this)
        if (this is XrActivity || this is PanelViewerActivity) {
            val pending = pendingViewer.getAndSet(null)
            if (pending == null) { finish(); return }
            xrDeviceId=pending.deviceId
            xrDeviceAlias=pending.alias
            passthroughEnabled = pending.passthrough
            if (this is XrActivity) {
                nativeSetPassthroughEnabled(passthroughEnabled)
                startXrSession(pending.room, pending.identity)
            } else startPanelSession(pending.room, pending.identity, pending.alias)
            return
        }
        api = UuNrdApi(this)
        cameraPreview = PicoCameraPreview(this, ::message)
        buildPanel()
        restoreLogin()
    }

    private fun buildPanel() {
        loginView = PicoLoginView(this, ::login, ::refresh, ::logout,
            { enabled -> passthroughEnabled = enabled; nativeSetPassthroughEnabled(enabled) },
            ::toggleCamera, cameraPreview.preview, passthroughEnabled,
            { device -> connect(device) }, ::restoreLogin,
            { device -> connect(device, PicoViewerMode.IMMERSIVE) }, ::smsLogin)
        status = loginView!!.status
        qrImage = loginView!!.qrImage
        setContentView(loginView)
        val account = api.account
        loginView!!.setLoginState(false, account != null, account?.nickname.orEmpty())
    }

    private fun restoreLogin() {
        val ticket = loginEpoch.incrementAndGet()
        api.cancelPendingRequests()
        loginView?.setRestoreState(busy = true)
        message("正在恢复已保存的登录…")
        worker.submit {
            if (ticket != loginEpoch.get() || isDestroyed) return@submit
            val result = api.restoreSavedLogin()
            if(result==UuSavedLoginFlow.Result.RESTORED && ticket==loginEpoch.get()) {
                runCatching { api.initializeDevice(force=true) }.onFailure { message("设备硬件信息更新未完成；已保留账号和设备身份") }
            }
            ui.post {
                if (ticket != loginEpoch.get() || isDestroyed) return@post
                when (result) {
                    UuSavedLoginFlow.Result.RESTORED -> {
                        loginView?.setLoginState(false, true, api.account?.nickname.orEmpty())
                        message("登录已自动恢复")
                        refresh()
                    }
                    UuSavedLoginFlow.Result.NONE -> {
                        loginView?.setRestoreState(false)
                        message("使用 UU 远程手机端扫码；登录后将安全保存在本机")
                    }
                    UuSavedLoginFlow.Result.REVOKED -> {
                        loginView?.setRestoreState(false)
                        message("已保存的登录已失效，请重新扫码")
                    }
                    UuSavedLoginFlow.Result.RETRY -> {
                        loginView?.setRestoreState(false, retry = true)
                        message("暂时无法校验本机登录，凭证已保留。请检查网络后重试恢复。")
                    }
                }
            }
        }
    }

    private fun toggleCamera() {
        if (cameraPreview.active) cameraPreview.stop()
        else if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) cameraPreview.start()
        else requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST)
    }

    private fun logout() {
        android.app.AlertDialog.Builder(this)
            .setTitle("退出登录？").setMessage("将清除本机加密保存的登录凭证，不会移除电脑上的账号。")
            .setNegativeButton("取消", null).setPositiveButton("退出登录") { _, _ ->
                loginEpoch.incrementAndGet()
                api.cancelPendingRequests()
                joinGate.stop()
                // Complete the local deletion before acknowledging logout, so
                // closing/killing the Activity cannot drop a queued clear task.
                try {
                    api.logoutLocal()
                    loginView?.setRestoreState(false)
                    loginView?.setLoginState(false, false)
                    message("已退出登录并清除本机保存的凭证")
                } catch (_: Exception) { message("清除本机登录失败，请重试退出登录") }
            }.show()
    }

    private fun message(text: String) {
        ui.post { xrToolbar?.status(text) }
        ui.post {
            if (!isFinishing && !isDestroyed) {
                if (!text.startsWith("会话已断开")) lastSessionMessage = text
                status.text = text; loginView?.setStatus(text); panelVideo?.showStatus(text)
            }
            Log.i("OpenUUYC-PICO", text)
        }
    }

    private fun smsLogin() {
        val ticket=loginEpoch.incrementAndGet();api.cancelPendingRequests()
        loginView?.setLoginState(false,false)
        PicoSmsLogin.show(this,api,worker,{loginEpoch.get()==ticket && !isDestroyed}) {
                loginView?.setLoginState(false,true,api.account?.nickname.orEmpty())
                message("UU 手机号登录成功，已加密保存登录")
                refresh()
            }
    }

    private fun login() {
        val loginTicket = loginEpoch.incrementAndGet()
        api.cancelPendingRequests()
        loginView?.setLoginState(busy = true, authenticated = false)
        worker.submit {
            if (loginEpoch.get() != loginTicket || isDestroyed) return@submit
            var stage = "设备注册"
            try {
                api.initializeDevice(force = true)
                stage = "生成二维码"
                var qr = api.createQr()
                val tracker = UuQrStatusTracker()
                var deadline = System.currentTimeMillis() + 300_000
                while (loginEpoch.get() == loginTicket && !isDestroyed) {
                    val image = qrBitmap(qr.url)
                    ui.post {
                        if (loginEpoch.get() == loginTicket && !isDestroyed) {
                            loginView?.showQr(image)
                        }
                    }
                    message("请用手机 UU 客户端扫码确认，二维码有效期 5 分钟")
                    while (loginEpoch.get() == loginTicket && !isDestroyed &&
                        System.currentTimeMillis() < deadline) {
                        stage = "扫码状态查询"
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
                                stage = "确认登录/交换凭证"
                                val confirmed = api.confirmQr(qr)
                                if (loginEpoch.get() != loginTicket || isDestroyed) { api.clearAccount(); return@submit }
                                stage = "加密保存登录"
                                if (!api.acceptLogin(confirmed) { loginEpoch.get() == loginTicket && !isDestroyed }) return@submit
                                if (loginEpoch.get() != loginTicket || isDestroyed) return@submit
                                message("UU 登录成功，已加密保存在本机")
                                ui.post {
                                    qrImage.visibility = ImageView.GONE
                                    loginView?.setLoginState(busy = false, authenticated = true, accountName = api.account?.nickname.orEmpty())
                                }
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
                if (loginEpoch.get() == loginTicket && !isDestroyed) {
                    ui.post { loginView?.setLoginState(busy = false, authenticated = false) }
                    message("UU 登录失败（$stage）：" +
                        if (e is UuNrdResponseException) e.message else e.javaClass.simpleName)
                }
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
        val epoch = loginEpoch.get()
        worker.submit {
            if (epoch != loginEpoch.get() || isDestroyed || api.account == null) return@submit
            try {
                val list = api.devices()
                ui.post {
                    if (epoch != loginEpoch.get() || isDestroyed || api.account == null) return@post
                    loginView?.showDevices(list)
                    if (list.isEmpty()) message("UU 账号下没有可连接设备")
                }
            } catch (e: Exception) {
                if (epoch == loginEpoch.get() && !isDestroyed) message("UU 设备列表失败：${e.javaClass.simpleName}")
            }
        }
    }

    private fun connect(device: UuDevice, mode: PicoViewerMode = PicoViewerMode.DEFAULT, forceJoin: Boolean = false) {
        if (device.occupants > 0 && !forceJoin) {
            android.app.AlertDialog.Builder(this).setTitle("抢占连接")
                .setMessage("${device.alias} 正在被控制。抢占会断开现有会话，是否继续？")
                .setNegativeButton("取消", null).setPositiveButton("确认抢占") { _, _ -> connect(device, mode, true) }.show()
            return
        }
        val ticket = joinGate.begin() ?: return
        message("加入 UU 远控房间：${device.alias}")
        worker.submit {
            try {
                // Only the network request runs off-thread. Adoption and
                // Session/Surface ownership are serialized on the UI thread
                // with Stop and Activity.onDestroy.
                val room = api.join(device, forceJoin)
                ui.post {
                    if (isFinishing || isDestroyed || !joinGate.adopt(ticket)) {org.openuuyc.pico.uu.UuRoomRelease.cancel(room);return@post}
                    pendingViewer.set(ViewerLaunch(room, api.identity.copy(), passthroughEnabled, device.alias,device.id))
                    cameraPreview.stop()
                    try {
                        val viewer = if (mode == PicoViewerMode.IMMERSIVE) XrActivity::class.java else PanelViewerActivity::class.java
                        startActivityForResult(Intent(this, viewer), XR_REQUEST)
                    } catch (e: Exception) {
                        pendingViewer.set(null)
                        org.openuuyc.pico.uu.UuRoomRelease.cancel(room)
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

    private fun startPanelSession(room: UuRoom, identity: UuIdentity, alias: String) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(PicoTheme.BG)
        }
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
        val toolbar = LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8)); setBackgroundColor(PicoTheme.SIDEBAR)
        }
        toolbar.addView(TextView(this).apply {
            text = "$alias · 普通窗口控制"; textSize = 17f; setTextColor(PicoTheme.TEXT)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        toolbar.addView(Button(this).apply {
            text = "断开"; setTextColor(PicoTheme.TEXT)
            setOnClickListener { panelVideo?.releasePointer(); stopSession(); finish() }
        }, LinearLayout.LayoutParams(dp(96), dp(44)))
        root.addView(toolbar)
        status = TextView(this).apply {
            text = "正在建立连接…"; textSize = 13f; setTextColor(PicoTheme.MUTED)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        root.addView(status)
        try {
            val direct = UuDirectSession(applicationContext, room, identity, ::message,
                onUnexpectedDisconnect = { ui.post { if (!isDestroyed) finishViewer() } })
            session = direct
            running.set(true)
            val keyboard = PicoRemoteKeyboard(this, direct::text, direct::key)
            remoteKeyboard = keyboard
            smartIme = getPreferences(MODE_PRIVATE).getBoolean("smart_ime", true)
            direct.textCursorListener = { text -> ui.post {
                if (text && smartIme && android.os.SystemClock.elapsedRealtime() - lastRemoteTap < 1500 && !isDestroyed) keyboard.show()
            } }
            fun action(label: String, run: () -> Unit): Button {
                val button=Button(this).apply { text=label;setTextColor(PicoTheme.TEXT);setOnClickListener { run() } }
                toolbar.addView(button,LinearLayout.LayoutParams(dp(84),dp(44)))
                return button
            }
            lateinit var screenButton: Button
            screenButton=action("屏幕") {
                PicoScreenMenu.show(this,screenButton,direct,{
                    panelVideo?.releasePointer();keyboard.releaseKeys();direct.releaseInputs()
                },::message)
            }
            action("设置") { PicoRemoteSettingsDialog.show(this, direct, smartIme, { enabled ->
                smartIme = enabled; getPreferences(MODE_PRIVATE).edit().putBoolean("smart_ime", enabled).apply()
            }, ::message) }
            action("键盘") { keyboard.hide();PicoKeyboardPopup.show(this,toolbar,direct,keyboard::show) }
            val inputMode = Button(this).apply {
                text = "鼠标模式"; setTextColor(PicoTheme.TEXT)
                setOnClickListener {
                    panelVideo?.releasePointer(); keyboard.releaseKeys(); direct.releaseInputs()
                    panelVideo?.let { it.touchMode = !it.touchMode; text = if (it.touchMode) "触摸模式" else "鼠标模式" }
                }
            }
            toolbar.addView(inputMode, LinearLayout.LayoutParams(dp(100), dp(44)))
            root.addView(keyboard, LinearLayout.LayoutParams(1, 1))
            val display = PicoPanelVideoView(this,
                { surface -> if (running.get()) direct.attachMainSurface(surface) },
                { direct.detachMainSurface() },
                { u, v, down -> direct.pointer(0, u, v, down) },
                { delta -> direct.scroll(delta) },
                onTap = {
                    lastRemoteTap = android.os.SystemClock.elapsedRealtime()
                    if (smartIme && direct.textCursorVisible) keyboard.show()
                }, touch = direct::touch)
            panelVideo = display
            direct.videoSizeListener = { width, height -> ui.post { if (!isDestroyed) display.setVideoSize(width, height) } }
            root.addView(display, LinearLayout.LayoutParams(-1, 0, 1f))
            setContentView(root)
        } catch (error: Exception) {
            message("普通窗口串流启动失败：${error.javaClass.simpleName}")
            stopSession(); finish()
        }
    }

    private fun startXrSession(room: UuRoom, identity: UuIdentity) {
        try {
            session = UuDirectSession(applicationContext, room, identity, ::message,
                { index, visible -> if(index !in xrReservedSlots) {xrPaneVisible.set(index,if(visible) 1 else 0);nativeSetWindowVisible(index, visible)} }) {
                ui.post { if (!isDestroyed) {closePrimaryStream();message("主串流已断开，其他设备不受影响；可打开主页面重新连接")} }
            }
            session?.onDisposed={ui.post {
                xrClosingPrimary.toList().forEach {pane->xrReservedSlots.remove(pane);xrPaneVisible.set(pane,0);nativeSetWindowVisible(pane,false)}
                xrClosingPrimary.clear()
            }}
            session?.windowInfoListener={pane,_,name,width,height-> ui.post {
                if(pane !in xrReservedSlots) {nativeSetWindowAspect(pane,width,height);xrWindowFrame?.title(pane,"$xrDeviceAlias - $name")}
            } }
            remoteKeyboard=PicoRemoteKeyboard(this,{currentXrSession()?.text(it)==true},{code,down->currentXrSession()?.key(code,down)==true})
            setContentView(remoteKeyboard)
            running.set(true)
            xrThread = Thread({
                try { nativeRun(this) }
                finally {
                    ui.post {
                        stopSession()
                        xrSurfaces.values.forEach { it.release() }
                        xrSurfaces.clear()
                        xrToolbar?.close(); xrToolbar=null
                        xrWindowFrame?.close(); xrWindowFrame=null
                        if (!isDestroyed) finishViewer()
                    }
                }
            }, "OpenUUYC-PICO-OpenXR").also { it.start() }
        } catch (e: Exception) {
            message("UU 头显启动失败：${e.javaClass.simpleName}")
            finish()
        }
    }

    private fun closePrimaryStream() {
        if(!xrMainAlive) return
        xrMainAlive=false
        (0..5).filter {!xrExtraSessions.containsKey(it)}.forEach {xrClosingPrimary.add(it);xrReservedSlots.add(it)}
        session?.close()
        if(xrSettingsSession===session) xrSettingsSession=null
        xrActivePane=xrExtraSessions.keys.firstOrNull() ?: 0
    }

    private fun openXrHome() {
        xrSettingsSession=null;nativeDockMenu(-1);message("正在载入主页面设备列表…")
        worker.submit {
            try {
                if(!::api.isInitialized) api=UuNrdApi(applicationContext)
                if(api.account==null) check(api.restoreSavedLogin()==UuSavedLoginFlow.Result.RESTORED) {"登录已失效，请退出会话后重新登录"}
                val list=api.devices()
                ui.post {if(running.get() && !isDestroyed) xrToolbar?.showHome(list)}
            } catch(error: Exception) {message("主页面加载失败：${error.javaClass.simpleName}；可重试或返回登录")}
        }
    }

    private fun connectXrDevice(device: UuDevice,force: Boolean) {
        if((xrMainAlive && device.id==xrDeviceId) || device.id in xrExtraDeviceIds.values) {message("该设备已经在沉浸模式中打开");return}
        val pane=PicoXrSlotPolicy.pick(xrMainAlive,(0..5).filter {xrPaneVisible.get(it)!=0}.toSet(),xrReservedSlots.toSet(),xrSurfaces.filterValues {it.isValid}.keys)
        if(pane==null) {message("可用空间窗口已满（应用最多6个），请先关闭一个窗口");return}
        xrReservedSlots.add(pane)
        message("正在打开沉浸式串流：${device.alias}")
        worker.submit {
            try {
                val room=api.join(device,force)
                ui.post {
                    if(!running.get() || isDestroyed) {xrReservedSlots.remove(pane);org.openuuyc.pico.uu.UuRoomRelease.cancel(room);return@post}
                    val surface=xrSurfaces[pane]
                    if(surface==null || !surface.isValid) {xrReservedSlots.remove(pane);org.openuuyc.pico.uu.UuRoomRelease.cancel(room);message("窗口 Surface 已失效");return@post}
                    try {
                        lateinit var extra: UuDirectSession
                        extra=UuDirectSession(applicationContext,room,api.identity.copy(),{message("${device.alias} · $it")},
                            {index,visible->if(index==0) {xrPaneVisible.set(pane,if(visible) 1 else 0);nativeSetWindowVisible(pane,visible)}},
                            {ui.post {xrExtraSessions[pane]?.close()}})
                        extra.windowInfoListener={index,_,name,width,height->if(index==0) ui.post {
                            if(xrExtraSessions[pane]===extra) {nativeSetWindowAspect(pane,width,height);xrWindowFrame?.title(pane,"${device.alias} - $name")}
                        }}
                        extra.onDisposed={ui.post {
                            if(xrExtraSessions.remove(pane,extra)) {
                                xrExtraDeviceIds.remove(pane);xrReservedSlots.remove(pane);xrPaneVisible.set(pane,0);nativeSetWindowVisible(pane,false)
                                if(xrSettingsSession===extra) xrSettingsSession=null
                                if(running.get() && xrMainAlive && pane>0) xrSurfaces[pane]?.let {session?.addWindowSurface(pane,it)}
                            }
                        }}
                        xrExtraSessions[pane]=extra;xrExtraDeviceIds[pane]=device.id
                        worker.execute {
                            try {
                                if(!running.get()) {extra.close();org.openuuyc.pico.uu.UuRoomRelease.cancel(room);return@execute}
                                if(xrMainAlive) session?.detachWindowSurface(pane)
                                extra.start(surface)
                                message("已请求打开 ${device.alias}，等待首帧；其他串流保持运行")
                            } catch(error: Exception) {
                                extra.close();org.openuuyc.pico.uu.UuRoomRelease.cancel(room)
                                message("新串流渲染启动失败：${error.javaClass.simpleName}")
                            }
                        }
                    } catch(error: Exception) {
                        xrExtraSessions[pane]?.close()
                        if(xrExtraSessions[pane]==null) {xrReservedSlots.remove(pane);org.openuuyc.pico.uu.UuRoomRelease.cancel(room)}
                        message("新串流启动失败：${error.javaClass.simpleName}")
                    }
                }
            } catch(error: Exception) {xrReservedSlots.remove(pane);message("连接其他设备失败：${error.javaClass.simpleName}")}
        }
    }

    private fun finishViewer() {
        setResult(RESULT_OK, Intent().putExtra("session_status", lastSessionMessage.take(512)))
        finish()
    }

    @Deprecated("Activity result compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if(requestCode==8342) {
            if(resultCode==RESULT_OK) data?.data?.let {uri -> worker.execute {
                val result=runCatching {xrAppearance.importImage(uri)}
                ui.post {if(!isDestroyed) {
                    if(result.isSuccess) {xrAppearance.update(false,xrAppearance.color,xrAppearance.visibility);applyXrAppearance();xrToolbar?.setMr(false);xrToolbar?.setAppearance(xrAppearance.visibility,xrAppearance.custom,xrAppearance.color,xrAppearance.presetId,xrAppearance.brightness);message("自定义图片已保存，已切换到图片背景")}
                    else message(result.exceptionOrNull()?.message ?: "图片加载失败")
                }}
            }}
            return
        }
        if (requestCode == XR_REQUEST) {
            pendingViewer.set(null)
            joinGate.stop()
            message(data?.getStringExtra("session_status") ?: "会话已断开，可重新连接")
            // The server processes leave asynchronously; do not keep an old
            // occupants count on the launcher after a successful disconnect.
            listOf(350L,1800L).forEach { delay -> ui.postDelayed({
                if(!isDestroyed && !isFinishing && api.account!=null) refresh()
            },delay) }
        }
    }

    private fun setXrCustomBackground(enabled: Boolean) {
        worker.execute {
            xrAppearance.useImage(enabled);xrAppearance.prepareBackground()
            ui.post {if(!isDestroyed) {
                applyXrAppearance()
                xrToolbar?.setAppearance(xrAppearance.visibility,xrAppearance.custom,xrAppearance.color,xrAppearance.presetId,xrAppearance.brightness)
                if(enabled && !xrAppearance.custom) message("请先选择自定义图片")
            }}
        }
    }

    private fun changeXrBackground(action: String) {
        worker.execute {
            val result=runCatching {
                when(action) {
                    "regenerate" -> {check(!xrAppearance.custom && PicoBackgroundPattern.find(xrAppearance.presetId).procedural) {"请先选择程序生成背景"};xrAppearance.regenerate()}
                    "brightness" -> xrAppearance.cycleBrightness()
                    else -> xrAppearance.selectPreset(action)
                }
            }
            ui.post {if(!isDestroyed) {
                if(result.isSuccess) {
                    applyXrAppearance();xrToolbar?.setMr(xrAppearance.mr)
                    xrToolbar?.setAppearance(xrAppearance.visibility,xrAppearance.custom,xrAppearance.color,xrAppearance.presetId,xrAppearance.brightness)
                    message("背景已保存 · ${if(xrAppearance.custom) "自定义图片" else PicoBackgroundPattern.find(xrAppearance.presetId).label}")
                } else message(result.exceptionOrNull()?.message ?: "背景生成失败")
            }}
        }
    }

    private fun applyXrAppearance() {
        passthroughEnabled=xrAppearance.mr
        nativeSetPassthroughEnabled(passthroughEnabled)
        xrWindowFrame?.background(xrAppearance.color,xrAppearance.backgroundImage(),
            if(passthroughEnabled) ((100-xrAppearance.visibility)*255/100) else 255,xrAppearance.brightness)
    }
    private fun pickXrBackground() {
        runCatching {startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type="image/*";addCategory(Intent.CATEGORY_OPENABLE)
        },8342)}.onFailure {message("无法打开系统图片选择器")}
    }

    @Suppress("unused")
    fun onXrSurface(index: Int, surface: Surface) {
        ui.post {
            if (index == 7 && running.get()) {
                xrWindowFrame?.close(); xrWindowFrame=PicoXrWindowFrame(surface).also { it.draw() };applyXrAppearance();session?.publishWindowInfo(); return@post
            }
            if (index == 6 && running.get()) {
                xrToolbar?.close()
                xrToolbar = PicoXrToolbar(surface, { currentXrSession() }, ::nativeSetFollowView, { session?.showAllWindows();nativeResetLayout() }, { enabled -> xrTouchMode=enabled }, { mode -> if(mode==PicoKeyboardMode.SYSTEM) remoteKeyboard?.show() else remoteKeyboard?.hide() }, { mr,color,opacity -> xrAppearance.update(mr,color,opacity);applyXrAppearance() }, { xrSettingsSession=null;nativeDockMenu(-1) }, ::openXrHome, ::connectXrDevice, ::pickXrBackground, ::setXrCustomBackground, ::changeXrBackground, ::nativeSetMenuPortrait, {xrExtraDeviceIds.values.toSet()+if(xrMainAlive) setOf(xrDeviceId) else emptySet()}) {
                    stopSession(); finishViewer()
                }.also { it.setMr(passthroughEnabled);it.setAppearance(xrAppearance.visibility,xrAppearance.custom,xrAppearance.color,xrAppearance.presetId,xrAppearance.brightness) }
                return@post
            }
            if (!running.get() || index !in 0..5) { surface.release(); return@post }
            xrSurfaces.put(index, surface)?.release()
            try {
                if (index == 0) session?.start(surface)
                else session?.addWindowSurface(index, surface)
            } catch (e: Exception) {
                message("UU 直连启动失败：" + e.javaClass.simpleName)
                stopSession()
            }
        }
    }

    @Suppress("unused")
    fun onPointer(index: Int, u: Float, v: Float, down: Boolean) {
        if(index in 0..5) {
            xrActivePane=index
            val extra=xrExtraSessions[index];val target=extra ?: session;val local=if(extra!=null) 0 else index
            if(xrTouchMode) target?.pointerAsTouch(local,u,v,down) else target?.pointer(local,u,v,down)
        }
    }

    @Suppress("unused")
    fun onXrWindowMenu(pane: Int,section: Int) {ui.post {
        val extra=xrExtraSessions[pane];val target=extra ?: session
        xrActivePane=pane;xrSettingsSession=target
        when(section) {
            1 -> openXrHome()
            2 -> xrToolbar?.openScreens()
            3 -> xrToolbar?.confirmVirtualScreen()
            else -> target?.windowScreen(if(extra!=null) 0 else pane)?.let {xrToolbar?.openScreenSettings(it)}
        }
    }}

    @Suppress("unused")
    fun onXrWindowClose(pane: Int) { ui.post {
        val extra=xrExtraSessions[pane]
        if(extra!=null) extra.close() else {
            session?.closeWindow(pane)
            if((0..5).none {it !in xrReservedSlots && xrPaneVisible.get(it)!=0}) closePrimaryStream()
        }
    } }

    @Suppress("unused")
    fun onXrMenuClick(u: Float,v: Float) { ui.post { xrToolbar?.activate(u,v) } }

    @Suppress("unused")
    fun onXrFollowState(enabled: Boolean) { ui.post { xrFollowing=enabled;xrToolbar?.followState(enabled) } }

    override fun onKeyDown(keyCode: Int,event: android.view.KeyEvent): Boolean {
        if(this is XrActivity && keyCode==android.view.KeyEvent.KEYCODE_BUTTON_B) {
            nativeSetFollowView(false);return true
        }
        return super.onKeyDown(keyCode,event)
    }

    @Deprecated("Legacy headset back button compatibility")
    override fun onBackPressed() {
        if(this is XrActivity && xrFollowing) { nativeSetFollowView(false); return }
        super.onBackPressed()
    }

    @Suppress("unused")
    fun onXrHover(index: Int, u: Float, v: Float, down: Boolean) {
        ui.post { xrWindowFrame?.pointer(index,u,v,down,xrTouchMode); xrToolbar?.hover(if(index==6) u else -1f, if(index==6) v else -1f, down) }
    }

    @Suppress("unused")
    fun onScroll(pane: Int,delta: Int) {
        xrActivePane=pane;(xrExtraSessions[pane] ?: session)?.scroll(delta)
    }

    @Suppress("unused")
    fun onPassthroughSupported(supported: Boolean) {
        if (!supported && passthroughEnabled) message("当前 PICO OpenXR 运行时不支持 Alpha Blend 透视模式，已使用普通 VR 背景")
        else if (supported) message("PICO 系统视频透视可用；远程窗口可叠加在现实环境上")
    }

    @Suppress("unused")
    fun onXrError(reason: String) {
        message("OpenXR：$reason")
        ui.post { stopSession() }
    }

    private fun stopSession() {
        loginEpoch.incrementAndGet()
        joinGate.stop()
        xrExtraSessions.values.toList().forEach {it.close()}
        session?.close()
        session = null
        if (running.getAndSet(false)) nativeStop()
        message("会话已断开")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_REQUEST && ::cameraPreview.isInitialized) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) cameraPreview.start()
            else message("摄像头权限被拒绝：无法调用头显相机")
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) { panelVideo?.releasePointer(); remoteKeyboard?.releaseKeys(); xrToolbar?.releaseKeyboardKeys(); session?.releaseInputs() }
    }

    override fun onPause() {
        panelVideo?.releasePointer(); remoteKeyboard?.releaseKeys(); xrToolbar?.releaseKeyboardKeys(); session?.releaseInputs()
        if (::cameraPreview.isInitialized) cameraPreview.stop()
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (this !is XrActivity && this !is PanelViewerActivity) loginView?.reflow(newConfig.screenWidthDp)
    }

    override fun onDestroy() {
        stopSession()
        if (::cameraPreview.isInitialized) cameraPreview.stop()
        joinGate.destroy()
        worker.shutdownNow()
        if (::api.isInitialized) api.close()
        super.onDestroy()
    }
}

/** Dedicated immersive Activity; the launcher remains a visible Android panel. */
class XrActivity : PicoActivity()

/** Default control route; no immersive metadata or OpenXR calls. */
class PanelViewerActivity : PicoActivity()

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

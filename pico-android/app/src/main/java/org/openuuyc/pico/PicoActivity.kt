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
import android.widget.HorizontalScrollView
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
    private external fun nativeStereoFormat(generation: Int,width: Int,height: Int)
    private external fun nativeStereoFrame(generation: Int,frame: Long,views: FloatArray)
    fun onXrStereoSurface(surface: android.view.Surface?,width: Int,height: Int) {
        org.openuuyc.pico.uu.UuPcvrSession.stereoTarget()?.stereoSurface(surface,width,height)
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
    private val xrQuickAppNames=java.util.concurrent.ConcurrentHashMap<Int,String>()
    private fun quickAppConnections(): List<Pair<String,UuDirectSession>> {
        val result=mutableListOf<Pair<String,UuDirectSession>>()
        session?.let {result.add(xrDeviceAlias to it)}
        xrExtraSessions.entries.sortedBy {it.key}.forEach {(pane,direct)->
            if(result.none {it.second===direct}) result.add((xrQuickAppNames[pane] ?: "连接 ${pane+1}") to direct)
        }
        return result
    }
    private fun currentXrSession()=xrSettingsSession ?: xrExtraSessions[xrActivePane] ?: session

    private var panelMicButton: Button?=null
    private var panelClipboardButton: Button?=null
    private fun toggleMicrophone(target: UuDirectSession?) {
        if(target==null) {message("请先打开串流");return}
        if(target.microphoneEnabled) {target.setMicrophoneEnabled(false);return}
        if(!target.microphoneNegotiated) {message("当前连接未协商麦克风回传，请等待连接完成或检查主机支持");return}
        if(!org.openuuyc.pico.uu.UuMicrophonePermissionActivity.request(this) {granted ->
            if(granted) target.setMicrophoneEnabled(true) else message("未授予麦克风权限，未开启录音")
        }) message("无法发起麦克风授权，请稍后重试")
    }
    private fun refreshSharingButtons() {
        panelMicButton?.text=if(session?.microphoneActive==true) "麦克风：开" else if(session?.microphoneEnabled==true) "麦克风…" else "麦克风：关"
        panelClipboardButton?.text=if(session?.clipboardEnabled==true) "剪贴板：开" else "剪贴板：关"
        for(pane in 0..5) {val target=xrExtraSessions[pane] ?: session;xrWindowFrame?.sharing(pane,target?.microphoneEnabled==true,target?.clipboardEnabled==true)}
    }

    private var xrFollowing = false
    private var xrTouchMode=false
    private external fun nativeSetPcvrProbe(enabled: Boolean)
    private var pcvrProbe=false
    private var pcvrPointerProbe=false
    private val pointerProbeStats=PicoPointerProbeStats()
    private external fun nativeSetFocusDots(enabled: Boolean)
    private external fun nativeSetPointerProbe(enabled: Boolean)
    private val pcvrProbeStats=PicoPcvrProbeStats()
    @Volatile internal var pcvrProbeStopped=false; private set
    private external fun nativeStop()
    private external fun nativeDockMenu(pane: Int)
    private external fun nativeSetEnvironment(mode: Int,color: Int,opacity: Int,brightness: Int,flow: Boolean,seed: Int)
    private external fun nativeSetEnvironmentImage(width: Int,height: Int,rgba: ByteArray?)
    private var uploadedEnvironmentImage:PicoXrAppearance.EnvironmentImage?=null
    private external fun nativeSetControllerMenu(layout: Int,hand: Int)
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
    private var xrKeyboardTarget: UuDirectSession?=null
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
        nativeSetFocusDots(xrAppearance.focusDots)
        nativeSetControllerMenu(0,xrAppearance.menuHand)
        worker.execute {xrAppearance.prepareBackground();ui.post {if(!isDestroyed && running.get()) applyXrAppearance()}}
        status = TextView(this)
        if(this is XrActivity && intent.getBooleanExtra("openuuyc.pcvr.probe",false) &&
            applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            pcvrProbe=true;pcvrPointerProbe=intent.getBooleanExtra("openuuyc.pcvr.pointer",false);startPcvrProbe();return
        }
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

    private val appDirectorySessions=mutableMapOf<String,UuDirectSession>()
    private val appDirectoryLoading=mutableSetOf<String>()
    private var appDirectoryEpoch=0
    private fun closeAppDirectories() {
        ++appDirectoryEpoch;appDirectoryLoading.clear()
        appDirectorySessions.values.toList().forEach {it.close()};appDirectorySessions.clear()
    }
    private fun deviceAppSession(device: UuDevice): UuDirectSession? =
        (if(device.id==xrDeviceId) session else xrExtraDeviceIds.entries.firstOrNull {it.value==device.id}?.key?.let {xrExtraSessions[it]}) ?: appDirectorySessions[device.id]

    private fun loadDeviceQuickApps(device: UuDevice,connect: Boolean,callback: (List<org.openuuyc.pico.uu.UuQuickApps.App>?,String?)->Unit,forFiles: Boolean=false) {
        val direct=deviceAppSession(device)
        if(direct!=null) {if(forFiles) callback(emptyList(),null) else direct.quickApps.list(callback);return}
        if(!connect) {callback(null,"点击添加应用或连接应用目录，无需打开桌面串流");return}
        if(!device.online || !device.controllable) {callback(null,"该设备离线或未允许控制");return}
        if(device.occupants>0) {callback(null,"该设备已被占用；请先使用连接设备并明确确认抢占");return}
        android.app.AlertDialog.Builder(this).setTitle("连接 ${device.alias} 的${if(forFiles) "文件管理" else "应用目录"}？")
            .setMessage("将建立该设备的纯数据控制连接，不协商音视频轨道、不打开桌面画面。文件管理遵循远端文件权限，应用启动另需 Linux OpenUUYC 的远程终端授权；离开页面或打开串流后释放连接。")
            .setNegativeButton("取消") {_,_->callback(null,"已取消应用连接")}
            .setOnCancelListener {callback(null,"已取消应用连接")}
            .setPositiveButton("连接") {_,_->
                if(!appDirectoryLoading.add(device.id)) {callback(null,"该设备正在连接，请稍候");return@setPositiveButton}
                val epoch=appDirectoryEpoch
                worker.submit {
                    try {
                        val room=api.join(device,false)
                        ui.post {
                            if(isDestroyed || epoch!=appDirectoryEpoch) {org.openuuyc.pico.uu.UuRoomRelease.cancel(room);return@post}
                            try {
                                val connection=UuDirectSession(applicationContext,room,api.identity.copy(),::message)
                                connection.applicationsOnly=true
                                appDirectorySessions[device.id]=connection
                                connection.onDisposed={ui.post {if(appDirectorySessions[device.id]===connection) appDirectorySessions.remove(device.id)}}
                                connection.start(null)
                                val deadline=android.os.SystemClock.elapsedRealtime()+30000
                                fun poll() {
                                    if(isDestroyed || epoch!=appDirectoryEpoch || appDirectorySessions[device.id]!==connection) {appDirectoryLoading.remove(device.id);callback(null,"应用连接已关闭");return}
                                    if(forFiles && connection.filesReady) {appDirectoryLoading.remove(device.id);callback(emptyList(),null);return}
                                    if(!forFiles && connection.quickApps.initialized) {appDirectoryLoading.remove(device.id);connection.quickApps.list(callback);return}
                                    if(android.os.SystemClock.elapsedRealtime()>=deadline) {
                                        appDirectoryLoading.remove(device.id);appDirectorySessions.remove(device.id);connection.close()
                                        callback(null,"应用连接超时：远端可能不支持快速启动扩展或尚未授权");return
                                    }
                                    ui.postDelayed({poll()},250)
                                }
                                poll()
                            } catch(error: Exception) {
                                appDirectoryLoading.remove(device.id);appDirectorySessions.remove(device.id)?.close()
                                org.openuuyc.pico.uu.UuRoomRelease.cancel(room);callback(null,error.message ?: "应用连接失败")
                            }
                        }
                    } catch(error: Exception) {ui.post {appDirectoryLoading.remove(device.id);callback(null,error.message ?: "应用连接失败")}}
                }
            }.show()
    }

    private var pendingTransferDocument: ((android.net.Uri)->Unit)?=null
    private fun pickTransferDocument(create: Boolean,name: String,selected: (android.net.Uri)->Unit) {
        if(pendingTransferDocument!=null) {message("请先完成当前文件选择");return}
        pendingTransferDocument=selected
        val intent=Intent(if(create) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE);type="*/*"
            if(create) putExtra(Intent.EXTRA_TITLE,name)
        }
        try {startActivityForResult(intent,8345)} catch(error: Exception) {pendingTransferDocument=null;message(error.message ?: "系统文件选择器不可用")}
    }

    private fun showDeviceFiles(device: UuDevice) {
        loadDeviceQuickApps(device,true,{_,error->
            val direct=deviceAppSession(device)
            if(error!=null || direct==null) message(error ?: "文件连接不可用")
            else PicoFilesMenu.show(this,direct,::message,::pickTransferDocument)
        },forFiles=true)
    }

    private fun launchDeviceQuickApp(device: UuDevice,app: org.openuuyc.pico.uu.UuQuickApps.App) {
        val direct=deviceAppSession(device) ?: run {message("该设备未连接，请先连接");return}
        android.app.AlertDialog.Builder(this).setTitle("${device.alias} · ${app.name}")
            .setMessage(if(app.vr) "仅在此设备启动 VR 游戏；尚无内置双眼画面。" else "在此设备启动应用？")
            .setNegativeButton("取消",null).setPositiveButton("启动") {_,_->
                if(deviceAppSession(device)!==direct) {message("该设备连接已变化，请刷新应用列表");return@setPositiveButton}
                direct.quickApps.launch(app,launchOnlyAck=app.vr) {_,result->message("${device.alias} · $result")}
            }.show()
    }

    private fun buildPanel() {
        loginView = PicoLoginView(this, ::login, ::refresh, ::logout,
            { enabled -> passthroughEnabled = enabled; nativeSetPassthroughEnabled(enabled) },
            ::toggleCamera, cameraPreview.preview, passthroughEnabled,
            { device -> connect(device) }, ::restoreLogin,
            { device -> connect(device, PicoViewerMode.IMMERSIVE) }, ::smsLogin, {PicoQuickAppsMenu.showConnections(this,quickAppConnections(),::message)}, {device,connect,callback->loadDeviceQuickApps(device,connect,callback)}, ::launchDeviceQuickApp, ::closeAppDirectories, ::showDeviceFiles)
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
        closeAppDirectories()
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
                status.text = text; loginView?.setStatus(text); panelVideo?.showStatus(text);refreshSharingButtons()
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
        closeAppDirectories()
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

    private var panelDeviceLoading=false
    private fun openPanelDeviceMenu(anchor: android.view.View) {
        if(panelDeviceLoading) return
        panelDeviceLoading=true
        worker.execute {
            try {
                if(!::api.isInitialized) api=UuNrdApi(this)
                if(api.account==null) api.restoreSavedLogin()
                check(api.account!=null) {"登录尚未恢复"}
                val devices=api.devices()
                ui.post {if(!isDestroyed) PicoDeviceMenu.show(this,anchor,devices,xrDeviceId,{openPanelDeviceMenu(anchor)}) {device ->
                    setResult(RESULT_OK,Intent().putExtra("next_device",device.id).putExtra("session_status","正在切换到 ${device.alias}"));finish()
                }}
            } catch(error: Exception) {message("设备列表加载失败：${error.javaClass.simpleName}")}
            finally {ui.post {panelDeviceLoading=false}}
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
        toolbar.addView(Button(this).apply {
            text="$alias ▾";textSize=16f;isAllCaps=false;setTextColor(PicoTheme.TEXT)
            maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END
            setOnClickListener {openPanelDeviceMenu(this)}
        },LinearLayout.LayoutParams(minOf(dp(220),resources.displayMetrics.widthPixels/3),dp(44)))
        val controls=LinearLayout(this).apply {gravity=android.view.Gravity.CENTER_VERTICAL}
        toolbar.addView(HorizontalScrollView(this).apply {isHorizontalScrollBarEnabled=false;addView(controls)},LinearLayout.LayoutParams(0,-2,1f))
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
                controls.addView(button,LinearLayout.LayoutParams(dp(84),dp(44)))
                return button
            }
            lateinit var screenButton: Button
            screenButton=action("屏幕") {
                PicoScreenMenu.show(this,screenButton,direct,{
                    panelVideo?.releasePointer();keyboard.releaseKeys();direct.releaseInputs()
                },::message)
            }
            screenButton.layoutParams.width=dp(220)
            direct.windowInfoListener={pane,_,name,_,_-> if(pane==0) ui.post {if(!isDestroyed) screenButton.text="▣ $name ▾"}}
            action("＋") {
                android.app.AlertDialog.Builder(this).setTitle("创建虚拟显示器？").setMessage("将在远端请求创建1920×1080显示器。")
                    .setNegativeButton("取消",null).setPositiveButton("确认") {_,_-> try {check(direct.createVirtualDisplay());message("请求已发送，等待远端屏幕列表")} catch(error: Exception) {message(error.message ?: "虚拟屏未创建")} }.show()
            }
            action("文件管理") {PicoFilesMenu.show(this,direct,::message,::pickTransferDocument)}
            action("游戏 / 应用") {PicoQuickAppsMenu.showConnections(this,quickAppConnections(),::message)}
            action("设置") { PicoRemoteSettingsDialog.show(this, direct, smartIme, { enabled ->
                smartIme = enabled; getPreferences(MODE_PRIVATE).edit().putBoolean("smart_ime", enabled).apply()
            }, ::message) }
            action("键盘") { keyboard.hide();PicoKeyboardPopup.show(this,toolbar,direct,keyboard::show,keyboard::showVoiceInput) }
            panelMicButton=action("麦克风：关") {toggleMicrophone(direct)}
            panelClipboardButton=action("剪贴板：关") {direct.setClipboardEnabled(!direct.clipboardEnabled)}
            val inputMode = Button(this).apply {
                text = "鼠标模式"; setTextColor(PicoTheme.TEXT)
                setOnClickListener {
                    panelVideo?.releasePointer(); keyboard.releaseKeys(); direct.releaseInputs()
                    panelVideo?.let { it.touchMode = !it.touchMode; text = if (it.touchMode) "触摸模式" else "鼠标模式" }
                }
            }
            controls.addView(inputMode, LinearLayout.LayoutParams(dp(100), dp(44)))
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

    private fun startPcvrProbe() {
        // Only an internal debug Activity/instrumentation can reach this path.
        // No UU login, remote media, microphone, clipboard or network is started.
        setContentView(status);status.text="内置 PCVR 双眼投影诊断（非远端游戏画面）"
        nativeSetPcvrProbe(!pcvrPointerProbe);nativeSetPointerProbe(pcvrPointerProbe);nativeSetPassthroughEnabled(false)
        for(i in 0..5) nativeSetWindowVisible(i,false)
        running.set(true)
        xrThread=Thread({
            try {nativeRun(this)} finally {
                pcvrProbeStopped=true
                ui.post {
                    stopSession();xrSurfaces.values.forEach {it.release()};xrSurfaces.clear()
                    xrToolbar?.close();xrToolbar=null;xrWindowFrame?.close();xrWindowFrame=null
                    if(!isDestroyed) finish()
                }
            }
        },"OpenUUYC-PCVR-Probe").also {it.start()}
    }

    private fun startXrSession(room: UuRoom, identity: UuIdentity) {
        try {
            session = UuDirectSession(applicationContext, room, identity, ::message,
                { index, visible -> if(index !in xrReservedSlots) {xrPaneVisible.set(index,if(visible) 1 else 0);nativeSetWindowVisible(index, visible)} }) {
                ui.post { if (!isDestroyed) {closePrimaryStream();message("主串流已断开，其他设备不受影响；可打开主页面重新连接")} }
            }.also {it.immersiveRendering=true}
            session?.onDisposed={ui.post {
                xrClosingPrimary.toList().forEach {pane->xrReservedSlots.remove(pane);xrPaneVisible.set(pane,0);nativeSetWindowVisible(pane,false)}
                xrClosingPrimary.clear()
            }}
            session?.windowInfoListener={pane,_,name,width,height-> ui.post {
                if(pane !in xrReservedSlots) {nativeSetWindowAspect(pane,width,height);xrWindowFrame?.title(pane,"$xrDeviceAlias - $name")}
            } }
            remoteKeyboard=PicoRemoteKeyboard(this,{xrKeyboardTarget?.text(it)==true},{code,down->currentXrSession()?.key(code,down)==true})
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
        // The caller owns placement: root navigation detaches explicitly,
        // while window-device menus and refresh retain the monitor anchor.
        xrSettingsSession=null;message("正在载入主页面设备列表…")
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
                        extra.immersiveRendering=true
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
                        extra.vr.setStereoCallbacks(::nativeStereoFormat,::nativeStereoFrame);xrExtraSessions[pane]=extra;xrQuickAppNames[pane]=device.alias;xrExtraDeviceIds[pane]=device.id
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
        if(requestCode==8345) {val selected=pendingTransferDocument;pendingTransferDocument=null;if(resultCode==RESULT_OK) data?.data?.let {selected?.invoke(it)};return}
        if(requestCode==8342) {
            if(resultCode==RESULT_OK) data?.data?.let {uri -> worker.execute {
                val result=runCatching {xrAppearance.importImage(uri)}
                ui.post {if(!isDestroyed) {
                    if(result.isSuccess) {xrAppearance.update(false,xrAppearance.color,xrAppearance.visibility);applyXrAppearance();xrToolbar?.setMr(false);xrToolbar?.setAppearance(xrAppearance.visibility,xrAppearance.custom,xrAppearance.color,xrAppearance.presetId,xrAppearance.brightness,xrAppearance.flow);message("自定义图片已保存，已切换到图片背景")}
                    else message(result.exceptionOrNull()?.message ?: "图片加载失败")
                }}
            }}
            return
        }
        if (requestCode == XR_REQUEST) {
            pendingViewer.set(null)
            joinGate.stop()
            val nextDevice=data?.getStringExtra("next_device")
            if(nextDevice!=null && this !is PanelViewerActivity && this !is XrActivity) worker.execute {
                try {val device=api.devices().firstOrNull {it.id==nextDevice && it.online && it.controllable} ?: error("目标设备不可连接")
                    ui.post {if(!isDestroyed && !isFinishing) connect(device,PicoViewerMode.WINDOW)}
                } catch(error: Exception) {message("切换设备失败：${error.javaClass.simpleName}")}
            }
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
                xrToolbar?.setAppearance(xrAppearance.visibility,xrAppearance.custom,xrAppearance.color,xrAppearance.presetId,xrAppearance.brightness,xrAppearance.flow)
                if(enabled && !xrAppearance.custom) message("请先选择自定义图片")
            }}
        }
    }

    private fun changeXrBackground(action: String) {
        worker.execute {
            val result=runCatching {
                when(action) {
                    "flow" -> xrAppearance.toggleFlow()
                    "regenerate" -> {check(!xrAppearance.custom && PicoBackgroundPattern.find(xrAppearance.presetId).procedural) {"请先选择程序生成背景"};xrAppearance.regenerate()}
                    else -> if(action.startsWith("brightness:")) xrAppearance.setBrightness(action.substringAfter(":").toInt()) else xrAppearance.selectPreset(action)
                }
            }
            ui.post {if(!isDestroyed) {
                if(result.isSuccess) {
                    applyXrAppearance();xrToolbar?.setMr(xrAppearance.mr)
                    xrToolbar?.setAppearance(xrAppearance.visibility,xrAppearance.custom,xrAppearance.color,xrAppearance.presetId,xrAppearance.brightness,xrAppearance.flow)
                    message("背景已保存 · ${if(xrAppearance.custom) "自定义图片" else PicoBackgroundPattern.find(xrAppearance.presetId).label}")
                } else message(result.exceptionOrNull()?.message ?: "背景生成失败")
            }}
        }
    }

    private fun applyXrAppearance() {
        passthroughEnabled=xrAppearance.mr
        nativeSetPassthroughEnabled(passthroughEnabled)
        val opacity=if(passthroughEnabled) ((100-xrAppearance.visibility)*255/100) else 255
        val image=xrAppearance.environmentImage()
        if(image!==uploadedEnvironmentImage) {
            nativeSetEnvironmentImage(image?.width ?: 0,image?.height ?: 0,image?.rgba);uploadedEnvironmentImage=image
        }
        nativeSetEnvironment(if(xrAppearance.custom) 12 else PicoBackgroundPattern.find(xrAppearance.presetId).mode,
            xrAppearance.color,opacity,xrAppearance.brightness,xrAppearance.flow,xrAppearance.seed)
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
        session?.vr?.setStereoCallbacks(::nativeStereoFormat,::nativeStereoFrame)
        xrExtraSessions.values.forEach {it.vr.setStereoCallbacks(::nativeStereoFormat,::nativeStereoFrame)}
        ui.post {
            if (index == 7 && running.get()) {
                xrWindowFrame?.close(); xrWindowFrame=PicoXrWindowFrame(surface).also { it.draw() };applyXrAppearance();session?.publishWindowInfo(); return@post
            }
            if (index == 6 && running.get()) {
                xrToolbar?.close()
                xrToolbar = PicoXrToolbar(surface, { currentXrSession() }, ::nativeSetFollowView, { session?.showAllWindows();nativeResetLayout() }, { enabled -> xrTouchMode=enabled }, { mode -> when(mode) {PicoKeyboardMode.SYSTEM->{xrKeyboardTarget=currentXrSession();remoteKeyboard?.show()};PicoKeyboardMode.VOICE->{xrKeyboardTarget=currentXrSession();remoteKeyboard?.showVoiceInput()};else->{xrKeyboardTarget=null;remoteKeyboard?.hide()}} }, { mr,color,opacity -> xrAppearance.update(mr,color,opacity);applyXrAppearance() }, { xrSettingsSession=null;nativeDockMenu(-1) }, ::openXrHome, ::connectXrDevice, ::pickXrBackground, ::setXrCustomBackground, ::changeXrBackground, ::nativeSetMenuPortrait, {xrExtraDeviceIds.values.toSet()+if(xrMainAlive) setOf(xrDeviceId) else emptySet()}) {
                    stopSession(); finishViewer()
                } .also { it.setLocalVrApps({callback->worker.execute {
                    val apps=runCatching {PicoLocalVrApps.list(this)}.getOrDefault(emptyList())
                    ui.post {if(!isDestroyed) callback(apps)}
                }},{app->runCatching {PicoLocalVrApps.launch(this,app)}.onFailure {error->message(error.message ?: "VR 应用启动失败")}});it.setQuickAppConnections(::quickAppConnections);it.setControllerMenu(xrAppearance.menuHand) {layout,hand->xrAppearance.setMenuHand(hand);nativeSetControllerMenu(layout,hand)};it.setFocusDots(xrAppearance.focusDots) {enabled->xrAppearance.setFocusDots(enabled);nativeSetFocusDots(enabled)};it.setMr(passthroughEnabled);it.setAppearance(xrAppearance.visibility,xrAppearance.custom,xrAppearance.color,xrAppearance.presetId,xrAppearance.brightness,xrAppearance.flow) }
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
            4 -> toggleMicrophone(target)
            5 -> target?.setClipboardEnabled(!target.clipboardEnabled)
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
    fun onXrMenuClick(u: Float,v: Float) { ui.post { menuClicksHandled++;xrToolbar?.activate(u,v) } }

    @Suppress("unused")
    // Called on the XR render thread. No network traffic or allocations when disabled.
    fun onXrTrackingEnabled(): Boolean = pcvrProbe || UuDirectSession.vrTrackingTarget()!=null
    fun onXrTracking(bytes: ByteArray) {
        if(pcvrProbe) pcvrProbeStats.accept(bytes) else UuDirectSession.vrTrackingTarget()?.vr?.frame(bytes)
    }
    private val inputDiagnostics=java.util.concurrent.ThreadPoolExecutor(1,1,0L,java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(1),java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy())
    private var menuClicksHandled=0L
    fun onXrInputDiagnostics(json: String) {
        if(json.length>2048 || inputDiagnostics.isShutdown) return
        runCatching {inputDiagnostics.execute {
            runCatching {
                val report=org.json.JSONObject(json).put("menuClicksHandled",menuClicksHandled)
                val tmp=java.io.File(filesDir,"xr-input-diagnostics.tmp")
                tmp.writeText(report.toString());tmp.renameTo(java.io.File(filesDir,"xr-input-diagnostics.json"))
            }
        }}
    }
    fun onXrMenuProbe(flags: Int,x: Float,y: Float,z: Float) {
        if(pcvrPointerProbe) pointerProbeStats.menu(flags,x,y,z)
    }
    fun onXrPointerProbe(mask: Int,lx: Float,ly: Float,lz: Float,rx: Float,ry: Float,rz: Float) {
        if(!pcvrPointerProbe) return
        pointerProbeStats.accept(mask,floatArrayOf(lx,ly,lz,rx,ry,rz))
        if(pointerProbeStats.samples%60==0L) {
            val temporary=java.io.File(filesDir,"pointer-probe.json.tmp")
            runCatching {temporary.writeText(pointerProbeStats.report().toString());check(temporary.renameTo(java.io.File(filesDir,"pointer-probe.json")))}
        }
    }
    fun onXrStereoProbe(frames: Long,lw: Int,lh: Int,rw: Int,rh: Int,ipd: Float) {
        if(!pcvrProbe) return
        val report=pcvrProbeStats.report(frames,lw,lh,rw,rh,ipd)
        val temporary=java.io.File(filesDir,"pcvr-probe.json.tmp")
        runCatching {
            temporary.writeText(report.toString());check(temporary.renameTo(java.io.File(filesDir,"pcvr-probe.json")))
        }
    }

    fun onXrFollowState(enabled: Boolean) { ui.post { xrFollowing=enabled;xrToolbar?.followState(enabled) } }

    private data class HoverFrame(val pane: Int,val u: Float,val v: Float,val down: Boolean)
    private val hoverFrames=PicoLatestHover<HoverFrame>({task->ui.post {task()}},{event->
        if(!isDestroyed && running.get()) {
            xrWindowFrame?.pointer(event.pane,event.down)
            xrToolbar?.hover(if(event.pane==6) event.u else -1f,if(event.pane==6) event.v else -1f,event.down)
        }
    })
    @Suppress("unused")
    fun onXrHover(index: Int,u: Float,v: Float,down: Boolean) {hoverFrames.offer(HoverFrame(index,u,v,down))}

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
        if(pcvrProbe) runCatching {java.io.File(filesDir,"pcvr-probe-error.txt").writeText(reason.take(512))}
        message("OpenXR：$reason")
        ui.post { stopSession() }
    }

    private fun stopSession() {
        hoverFrames.clear();remoteKeyboard?.hide();xrKeyboardTarget=null
        loginEpoch.incrementAndGet()
        joinGate.stop()
        xrExtraSessions.values.toList().forEach {it.close()}
        session?.close()
        session = null
        if (running.getAndSet(false)) nativeStop()
        if(pcvrProbe) {nativeSetPcvrProbe(false);nativeSetPointerProbe(false)}
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

    override fun onResume() {super.onResume();UuDirectSession.setApplicationFocused(true)}

    override fun onPause() {
        if(pendingTransferDocument==null) closeAppDirectories()
        UuDirectSession.setApplicationFocused(false)
        panelVideo?.releasePointer(); remoteKeyboard?.releaseKeys(); xrToolbar?.releaseKeyboardKeys(); session?.releaseInputs()
        if (::cameraPreview.isInitialized) cameraPreview.stop()
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (this !is XrActivity && this !is PanelViewerActivity) loginView?.reflow(newConfig.screenWidthDp)
    }

    override fun onDestroy() {
        closeAppDirectories()
        stopSession()
        if (::cameraPreview.isInitialized) cameraPreview.stop()
        joinGate.destroy()
        inputDiagnostics.shutdownNow()
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

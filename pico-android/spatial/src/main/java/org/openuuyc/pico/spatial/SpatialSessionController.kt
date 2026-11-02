package org.openuuyc.pico.spatial

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.compose.runtime.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import org.openuuyc.pico.uu.*
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Shared UU wire implementation; the OS 6 shell only owns window/session lifecycle. */
internal class SpatialSessionController(context: Context) : AutoCloseable {
    private val app=context.applicationContext
    fun context(): Context=app
    private val api=UuNrdApi(app)
    private val worker=Executors.newSingleThreadExecutor()
    private val ui=Handler(Looper.getMainLooper())
    private val alive=AtomicBoolean(true)
    private val loginEpoch=AtomicLong()
    var status by mutableStateOf("恢复加密登录…");private set
    var signedIn by mutableStateOf(false);private set
    var initializing by mutableStateOf(true);private set
    var refreshing by mutableStateOf(false);private set
    var canRetryRestore by mutableStateOf(false);private set
    var loginPending by mutableStateOf(false);private set
    var nickname by mutableStateOf("");private set
    var lastError by mutableStateOf("");private set
    var qr by mutableStateOf<Bitmap?>(null);private set
    val devices=mutableStateListOf<UuDevice>()
    val windows=mutableStateListOf<StreamWindow>()
    val opening=mutableStateListOf<String>()
    internal class StreamWindow(val id: String,val device: UuDevice,val session: UuDirectSession,val cancelUnusedRoom: ()->Unit) {
        val attached=AtomicBoolean(false)
        var status by mutableStateOf("等待首帧…")
        var screenName by mutableStateOf("屏幕")
        var settingsStatus by mutableStateOf("")
        var width by mutableIntStateOf(1920)
        var height by mutableIntStateOf(1080)
    }
    init {submit {restoreNow()}}
    private fun restoreNow() {
        try {
            val restored=api.restoreSavedLogin()
            signedInOnUi(restored==UuSavedLoginFlow.Result.RESTORED)
            onUi {canRetryRestore=restored==UuSavedLoginFlow.Result.RETRY;if(canRetryRestore) status="登录验证未完成，请检查网络后重试恢复"}
            if(api.account!=null) refreshNow()
        } finally {onUi {initializing=false}}
    }
    fun retryRestore() {if(initializing || loginPending) return;initializing=true;lastError="";submit {restoreNow()}}
    private fun onUi(action: ()->Unit) {ui.post {if(alive.get()) action()}}
    private fun note(message: String) {onUi {status=message}}
    private fun submit(action: ()->Unit) {if(alive.get()) worker.execute {try {if(alive.get()) action()} catch(e: Exception) {onUi {lastError="操作失败：${e.javaClass.simpleName}";status=lastError}}}}
    private fun signedInOnUi(value: Boolean) {onUi {signedIn=value;nickname=api.account?.nickname.orEmpty();status=if(value) "登录已恢复" else "请扫码登录；凭证与兼容版共用"}}
    fun refresh() {
        if(refreshing || initializing || !signedIn) return
        refreshing=true;lastError="";submit {refreshNow()}
    }
    private fun refreshNow() {
        onUi {refreshing=true}
        try {
            val result=api.devices()
            onUi {devices.clear();devices.addAll(result);signedIn=true;nickname=api.account?.nickname.orEmpty();lastError="";status="设备列表已更新"}
        } finally {onUi {refreshing=false}}
    }
    fun cancelLogin() {loginEpoch.incrementAndGet();api.cancelPendingRequests();qr=null;loginPending=false;status="已取消登录"}
    fun login() {
        if(loginPending || initializing) return
        loginPending=true;lastError="";qr=null
        val epoch=loginEpoch.incrementAndGet();api.cancelPendingRequests()
        submit {
            try {
            api.initializeDevice(force=true)
            val ticket=api.createQr();val tracker=UuQrStatusTracker()
            val matrix=MultiFormatWriter().encode(ticket.url,BarcodeFormat.QR_CODE,320,320)
            val pixels=IntArray(320*320) {i -> if(matrix[i%320,i/320]) 0xff000000.toInt() else 0xffffffff.toInt()}
            val bitmap=Bitmap.createBitmap(pixels,320,320,Bitmap.Config.ARGB_8888)
            onUi {if(loginEpoch.get()==epoch) {qr=bitmap;status="用手机 UU 扫码并确认登录"}}
            val deadline=System.currentTimeMillis()+300000
            while(alive.get() && epoch==loginEpoch.get() && System.currentTimeMillis()<deadline) {
                when(tracker.receive(api.queryQr(ticket,tracker.status))) {
                    UuQrStatusTracker.Transition.CONFIRMED -> {
                        val account=api.confirmQr(ticket)
                        if(api.acceptLogin(account) {alive.get() && epoch==loginEpoch.get()}) {
                            onUi {qr=null;signedIn=true;nickname=account.nickname};refreshNow()
                        }
                        return@submit
                    }
                    UuQrStatusTracker.Transition.REFRESH -> {note("二维码过期，请重新生成");return@submit}
                    UuQrStatusTracker.Transition.SCANNED -> note("等待手机确认登录")
                    else -> Unit
                }
                Thread.sleep(1000)
            }
            if(alive.get() && epoch==loginEpoch.get()) note("二维码已过期，请重新生成")
            } catch(error: Exception) {if(epoch==loginEpoch.get() && alive.get()) throw error}
            finally {onUi {if(epoch==loginEpoch.get()) {loginPending=false;if(!signedIn) qr=null}}}
        }
    }
    /** Must be called on UI. Busy targets require explicit confirmation in the shell. */
    fun connect(device: UuDevice,force: Boolean=false) {
        if(!device.online || !device.controllable) {status="设备不可连接";return}
        if(device.occupants>0 && !force) {status="设备正被使用，需确认抢占";return}
        if(windows.size+opening.size>=6) {status="空间窗口已满，请先关闭一个";return}
        if(windows.any {it.device.id==device.id} || device.id in opening) return
        opening.add(device.id);lastError="";status="正在连接 ${device.alias}…"
        submit {
            try {
                val room=api.join(device,force)
                if(!alive.get()) {UuRoomRelease.cancel(room);return@submit}
                val id=UUID.randomUUID().toString()
                val session=UuDirectSession(app,room,api.identity.copy(),{message -> onUi {
                    windows.firstOrNull {it.id==id}?.let {window ->
                        window.status=message
                        if(message.contains("设置")) window.settingsStatus=message
                    }
                    if(message.contains("失败") || message.contains("超时") || message.contains("拒绝")) {lastError="${device.alias} · $message";status=lastError}
                }}, {_,_->}, {onUi {remove(id)}})
                val window=StreamWindow(id,device,session) {UuRoomRelease.cancel(room)}
                session.videoSizeListener={w,h -> onUi {window.width=w;window.height=h}}
                session.windowInfoListener={pane,_,name,w,h -> if(pane==0) onUi {window.screenName=name;window.width=w;window.height=h}}
                ui.post {if(alive.get()) windows.add(window) else {session.close();UuRoomRelease.cancel(room)}}
            } finally {onUi {opening.remove(device.id)}}
        }
    }
    fun attach(window: StreamWindow,surface: Surface)=submit {
        try {window.attached.set(window.session.replacePrimarySurface(surface))}
        catch(error: Exception) {window.session.close();window.cancelUnusedRoom();throw error}
    }
    fun remove(id: String) {windows.firstOrNull {it.id==id}?.let {window ->
        windows.remove(window);window.session.close()
        submit {if(!window.attached.get()) window.cancelUnusedRoom()}
    }}
    override fun close() {
        if(!alive.getAndSet(false)) return
        loginEpoch.incrementAndGet();api.cancelPendingRequests()
        windows.toList().forEach {it.session.close();if(!it.attached.get()) it.cancelUnusedRoom()};windows.clear();api.close();worker.shutdownNow()
    }
}

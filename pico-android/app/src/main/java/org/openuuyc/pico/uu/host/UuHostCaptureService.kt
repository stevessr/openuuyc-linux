package org.openuuyc.pico.uu.host

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.os.*
import android.view.WindowManager
import org.webrtc.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

/** Local capture foundation only: no host publication, network listener or input injection. */
internal class UuHostCaptureService: Service() {
    companion object {
        const val GRANT="projection_grant"
        const val STOP="org.openuuyc.pico.STOP_HOST_CAPTURE"
        @Volatile var active=false;private set
        @Volatile var status="被控端离线 · UU 被控信令尚未接通";private set
        val frames=AtomicLong(0)
        val busy=AtomicBoolean(false)
    }
    private val worker=Executors.newSingleThreadExecutor()
    private var capturer: ScreenCapturerAndroid?=null
    private var helper: SurfaceTextureHelper?=null
    private var egl: EglBase?=null
    private var starting=false
    private var destroyed=false
    override fun onBind(intent: Intent?)=null
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if(intent?.action==STOP) {stopSelf();return START_NOT_STICKY}
        if(starting) return START_NOT_STICKY
        @Suppress("DEPRECATION") val grant=intent?.getParcelableExtra<Intent>(GRANT)
        if(grant==null) {stopSelf();return START_NOT_STICKY}
        if(!busy.compareAndSet(false,true)) {stopSelf();return START_NOT_STICKY}
        starting=true
        try {
            val nm=getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("host_capture","本机屏幕共享",NotificationManager.IMPORTANCE_LOW))
            val stop=PendingIntent.getService(this,920,Intent(this,javaClass).setAction(STOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val open=PendingIntent.getActivity(this,921,Intent(this,UuHostCaptureActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notice=Notification.Builder(this,"host_capture").setSmallIcon(android.R.drawable.presence_video_online)
                .setContentTitle("OpenUUYC 正在捕获本机屏幕").setContentText("仅本机采集测试，未连接远端；点击管理或停止")
                .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null,"停止共享",stop).build()).build()
            startForeground(920,notice,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } catch(_: Exception) {status="屏幕捕获前台服务启动失败";stopSelf();return START_NOT_STICKY}
        frames.set(0);status="等待系统屏幕帧 · 未连接远端"
        Handler(Looper.getMainLooper()).postDelayed({
            if(!destroyed && frames.get()==0L) {status="屏幕捕获没有返回视频帧，已停止";stopSelf()}
        },15000)
        val size=if(Build.VERSION.SDK_INT>=30) getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds.let {it.width() to it.height()}
            else resources.displayMetrics.let {it.widthPixels to it.heightPixels}
        worker.execute {
            try {
                PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(applicationContext).createInitializationOptions())
                egl=EglBase.create()
                helper=SurfaceTextureHelper.create("UUHostCapture",egl!!.eglBaseContext)
                capturer=ScreenCapturerAndroid(grant,object: MediaProjection.Callback() {
                    override fun onStop() {Handler(Looper.getMainLooper()).post {if(!destroyed) {status="系统已结束屏幕共享";stopSelf()}}}
                    override fun onCapturedContentResize(width: Int,height: Int) {
                        if(width<=0 || height<=0) return
                        runCatching {worker.execute {val (w,h)=UuHostCaptureSize.fit(width,height);capturer?.changeCaptureFormat(w,h,30)}}
                    }
                })
                capturer!!.initialize(helper,applicationContext,object: CapturerObserver {
                    override fun onCapturerStarted(success: Boolean) {
                        active=success
                        if(!success) Handler(Looper.getMainLooper()).post {status="系统拒绝屏幕捕获";stopSelf()}
                    }
                    override fun onCapturerStopped() {active=false}
                    override fun onFrameCaptured(frame: VideoFrame) {
                        frames.incrementAndGet()
                        status="本机捕获 ${frame.rotatedWidth}×${frame.rotatedHeight} · 未连接远端"
                        // No retain, recording or network transfer before a verified UU host session exists.
                    }
                })
                val (w,h)=UuHostCaptureSize.fit(size.first,size.second)
                capturer!!.startCapture(w,h,30)
            } catch(_: Exception) {status="屏幕捕获失败，请重新授权";Handler(Looper.getMainLooper()).post {stopSelf()}}
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        destroyed=true
        if(!starting) {worker.shutdown();super.onDestroy();return}
        worker.execute {
            runCatching {capturer?.stopCapture()};runCatching {capturer?.dispose()};capturer=null
            runCatching {helper?.dispose()};helper=null;runCatching {egl?.release()};egl=null
            active=false;frames.set(0)
            if(status.startsWith("本机捕获") || status.startsWith("等待")) status="被控端离线 · 屏幕捕获已释放"
            busy.set(false)
        }
        worker.shutdown();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()
    }
}

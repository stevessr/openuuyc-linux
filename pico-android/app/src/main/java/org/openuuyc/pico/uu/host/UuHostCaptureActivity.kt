package org.openuuyc.pico.uu.host

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.*
import android.widget.*

/** Explicit local consent UI, never automatic/background authorization. */
internal class UuHostCaptureActivity: Activity() {
    private val handler=Handler(Looper.getMainLooper())
    private lateinit var info: TextView
    private lateinit var start: Button
    private var requesting=false
    private val update=object: Runnable {
        override fun run() {
            info.text=UuHostCaptureService.status+"\n已捕获帧数："+UuHostCaptureService.frames.get()
            start.isEnabled=!requesting && !UuHostCaptureService.busy.get()
            handler.postDelayed(this,500)
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(32,32,32,32)}
        fun text(value: String)=TextView(this).apply {text=value;textSize=17f;setPadding(0,12,0,12)}
        root.addView(text("被控端 · 本机捕获准备"))
        root.addView(text("当前阶段尚不能从 UU 设备列表连接本机。此处测试系统捕获授权，不会上传、保存屏幕或开启远端输入。每次开始都需要系统确认；可从通知随时停止。PICO 可能只允许捕获 Android 面板，而非完整 MR/系统界面。"))
        info=text("");root.addView(info)
        start=Button(this).apply {text="授权并测试屏幕捕获";setOnClickListener {
            requesting=true;isEnabled=false
            runCatching {startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(),920)}
                .onFailure {requesting=false;Toast.makeText(this@UuHostCaptureActivity,"系统不支持屏幕捕获",Toast.LENGTH_LONG).show()}
        }}
        root.addView(start)
        root.addView(Button(this).apply {text="停止捕获";setOnClickListener {stopService(Intent(this@UuHostCaptureActivity,UuHostCaptureService::class.java))}})
        root.addView(Button(this).apply {text="返回";setOnClickListener {finish()}})
        setContentView(ScrollView(this).apply {addView(root)})
    }
    @Deprecated("Legacy permission result")
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=920) return
        requesting=false
        if(resultCode==RESULT_OK && data!=null) runCatching {
            startForegroundService(Intent(this,UuHostCaptureService::class.java).putExtra(UuHostCaptureService.GRANT,data))
        }.onFailure {Toast.makeText(this,"无法启动屏幕捕获服务",Toast.LENGTH_LONG).show()}
    }
    override fun onResume() {super.onResume();handler.post(update)}
    override fun onPause() {handler.removeCallbacks(update);super.onPause()}
}

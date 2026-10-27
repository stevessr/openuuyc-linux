package org.openuuyc.pico

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import org.openuuyc.pico.uu.*

/** Linux-style layered popup menus. Only implemented/advertised capabilities act. */
internal object PicoRemoteSettingsDialog {
    fun show(activity: Activity,session: UuDirectSession,smartIme: Boolean,onSmartIme: (Boolean)->Unit,report: (String)->Unit) {
        val d=activity.resources.displayMetrics.density
        fun dp(n: Int)=(n*d).toInt()
        var page="画质";var ime=smartIme;var dragging=false;var lastSignature=""
        val initial=session.settingsSnapshot().let {s -> s.screens.firstOrNull {it.id==s.selected}?.current}
        var draft=session.settingsSnapshot().current.bitrateMbps
        val body=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(12),dp(18),dp(14))}
        val scroller=ScrollView(activity).apply {addView(body);isFillViewport=false}
        val shape=GradientDrawable().apply {setColor(PicoTheme.BG);cornerRadius=dp(8).toFloat();setStroke(dp(1),PicoTheme.LINE)}
        scroller.background=shape
        val popup=PopupWindow(scroller,minOf(dp(460),activity.resources.displayMetrics.widthPixels-dp(24)),ViewGroup.LayoutParams.WRAP_CONTENT,true).apply {
            elevation=dp(10).toFloat();isOutsideTouchable=true;setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        }
        fun background(selected: Boolean)=GradientDrawable().apply {setColor(if(selected) PicoTheme.SELECTED else PicoTheme.BG);cornerRadius=dp(6).toFloat()}
        fun label(text: String,muted: Boolean=true) {body.addView(TextView(activity).apply {this.text=text;setTextColor(if(muted) PicoTheme.MUTED else PicoTheme.TEXT);textSize=14f;setPadding(dp(2),dp(12),dp(2),dp(8))})}
        fun line() {body.addView(android.view.View(activity).apply {setBackgroundColor(PicoTheme.LINE)},LinearLayout.LayoutParams(-1,dp(1)).apply {topMargin=dp(8);bottomMargin=dp(8)})}
        lateinit var render: ()->Unit
        fun row(title: String,value: String="",selected: Boolean=false,enabled: Boolean=true,click: ()->Unit) {
            val button=TextView(activity).apply {
                text="${if(selected) "✓  " else "     "}$title${if(value.isNotBlank()) "     $value" else ""}"
                textSize=17f;setTextColor(if(enabled) PicoTheme.TEXT else PicoTheme.MUTED);gravity=Gravity.CENTER_VERTICAL
                setPadding(dp(10),0,dp(8),0);background=background(selected);isEnabled=enabled;isClickable=enabled;isFocusable=enabled
                setOnFocusChangeListener {_,focus -> background=background(selected || focus)}
                setOnHoverListener {_,event -> background=background(selected || event.action!=android.view.MotionEvent.ACTION_HOVER_EXIT);false}
                setOnClickListener {click()}
            }
            body.addView(button,LinearLayout.LayoutParams(-1,dp(46)))
        }
        fun apply(settings: UuCaptureSettings,mode: UuDisplayMode?=null,dpi: Int=0) {
            try {
                val target=session.selectedScreen() ?: error("等待屏幕映射")
                if(session.applySettings(settings,mode,dpi,target)) {report("设置已发送，等待远端确认");render()} else report("控制通道未就绪，未发送")
            } catch(e: Exception) {report(e.message?.takeIf {e is IllegalArgumentException || e is IllegalStateException} ?: "设置未完成")}
        }
        render={
            body.removeAllViews()
            val state=session.settingsSnapshot();val current=state.current;val screen=state.screens.firstOrNull {it.id==state.selected}
            lastSignature="${state.current}|${state.pending?.first}|${state.screens}|${session.microphoneEnabled}|${session.microphoneActive}|${session.clipboardEnabled}"
            val ready=state.pending==null && screen!=null && state.profiles.isNotEmpty()
            val header=LinearLayout(activity).apply {gravity=Gravity.CENTER_VERTICAL}
            header.addView(TextView(activity).apply {text=if(page=="画质") "画质" else "‹  $page";textSize=18f;setTextColor(PicoTheme.TEXT);setOnClickListener {page=when(page) {"分辨率","DPI 缩放" -> "显示设置";else -> "画质"};render()}},LinearLayout.LayoutParams(0,dp(42),1f))
            header.addView(TextView(activity).apply {text="×";textSize=25f;gravity=Gravity.CENTER;contentDescription="关闭菜单";setTextColor(PicoTheme.MUTED);setOnClickListener {popup.dismiss()}},LinearLayout.LayoutParams(dp(42),dp(42)))
            body.addView(header)
            when(page) {
                "画质" -> {
                    row("此设备编码偏好","${state.selectableProfiles().firstOrNull {it.codec==current.codec} ?: "自动"} ›") {page="编码偏好";render()}
                    line()
                    listOf(5 to "自动（原画）",4 to "原画",3 to "超清",2 to "高清").forEach {(quality,name) -> row(name,selected=current.quality==quality,enabled=ready) {apply(current.copy(quality=quality))}}
                    row("自定义码率","${current.bitrateMbps} Mbps ›",current.quality==6) {draft=current.bitrateMbps;page="自定义码率";render()}
                    row("显示器分辨率与 DPI","当前屏幕 ›") {page="显示设置";render()}
                    label("帧率上限                                          FPS")
                    val ceiling=minOf(screen?.fps ?: 0,state.profiles.filter {current.codec==0 || it.codec==current.codec}.maxOfOrNull {it.fps} ?: 0)
                    val rates=LinearLayout(activity)
                    listOf(165,144,90,60,30).forEach {fps -> rates.addView(Button(activity).apply {text="$fps";textSize=14f;isAllCaps=false;isEnabled=ready && fps<=ceiling;setTextColor(PicoTheme.TEXT);background=background(current.fps==fps);setOnClickListener {apply(current.copy(fps=fps))}},LinearLayout.LayoutParams(0,dp(42),1f).apply {marginEnd=dp(6)})};body.addView(rates)
                    label("色度采样 · YUV 4:2:0\nHDR / 4:4:4 当前未开放")
                    line();row("高级设置","›") {page="高级设置";render()}
                }
                "自定义码率" -> {
                    val value=TextView(activity).apply {text="视频码率                         $draft/500 Mbps";textSize=17f;setTextColor(PicoTheme.TEXT);setPadding(0,dp(20),0,dp(20))};body.addView(value)
                    body.addView(SeekBar(activity).apply {max=499;progress=draft.coerceIn(1,500)-1;setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(bar: SeekBar?,p: Int,user: Boolean) {draft=p+1;value.text="视频码率                         $draft/500 Mbps"}
                        override fun onStartTrackingTouch(bar: SeekBar?) {dragging=true}
                        override fun onStopTrackingTouch(bar: SeekBar?) {dragging=false}
                    })})
                    label("1                                                     500")
                    row("${if(draft==current.bitrateMbps && current.quality==6) "已应用" else "尚未应用"}","应用",enabled=ready) {apply(current.copy(quality=6,bitrateMbps=draft))}
                }
                "编码偏好" -> state.selectableProfiles().forEach {profile -> row(profile.toString(),selected=profile.codec==current.codec,enabled=ready) {apply(current.copy(codec=profile.codec,fps=minOf(current.fps,profile.fps),maxWidth=minOf(current.maxWidth,profile.width),maxHeight=minOf(current.maxHeight,profile.height)))}}
                "显示设置" -> {
                    label(screen?.name ?: "等待显示器信息")
                    row("分辨率","${screen?.current ?: "未知"} ›") {page="分辨率";render()}
                    row("DPI 缩放","${screen?.dpi ?: 0}% ›") {page="DPI 缩放";render()}
                    line();row("切换超级屏","暂未实现",enabled=false) {}
                }
                "分辨率" -> {
                    label(screen?.name ?: "当前屏幕")
                    if(screen?.modes.isNullOrEmpty()) label("远端没有提供可用显示模式")
                    initial?.let {mode -> row("初始 · $mode",selected=screen?.current==mode,enabled=ready && state.captureLevel>=5 && mode in screen?.modes.orEmpty()) {apply(current,mode=mode)}}
                    val local=UuDisplayMode(activity.resources.displayMetrics.widthPixels,activity.resources.displayMetrics.heightPixels)
                    row("跟随本窗口 · $local",enabled=ready && state.captureLevel>=5 && local in screen?.modes.orEmpty()) {apply(current,mode=local)}
                    screen?.modes.orEmpty().groupBy {mode -> val gcd=java.math.BigInteger.valueOf(mode.width.toLong()).gcd(java.math.BigInteger.valueOf(mode.height.toLong())).toInt().coerceAtLeast(1);"${mode.width/gcd}:${mode.height/gcd}"}.forEach {(ratio,modes) ->
                        label(ratio);modes.forEach {mode -> row(mode.toString(),selected=screen?.current==mode,enabled=ready && state.captureLevel>=5) {apply(current,mode=mode)}}
                    }
                }
                "DPI 缩放" -> screen?.dpis.orEmpty().forEach {dpi -> row("$dpi%",if(screen?.dpi==dpi) "当前" else "",screen?.dpi==dpi,ready && state.captureLevel>=5) {apply(current,dpi=dpi)}}
                "高级设置" -> {
                    row("本地显示比例","保持画面比例",enabled=false) {}
                    row("仅音频模式","暂未实现",enabled=false) {};line()
                    row("高速中转连接 Beta","暂未实现",enabled=false) {}
                    row("性能监控常驻","暂未实现",enabled=false) {}
                    row("鼠标模式","使用顶部鼠标/触摸开关",enabled=false) {}
                    row("拦截本机快捷键","受系统限制",enabled=false) {}
                    row("麦克风",if(session.microphoneActive) "开启" else if(session.microphoneEnabled) "等待远端" else "关闭") {
                        if(session.microphoneEnabled) session.setMicrophoneEnabled(false)
                        else UuMicrophonePermissionActivity.request(activity) {if(it) session.setMicrophoneEnabled(true) else report("麦克风未授权")}
                    }
                    row("剪贴板同步",if(session.clipboardEnabled) "开启 · 仅文本" else "关闭") {session.setClipboardEnabled(!session.clipboardEnabled);render()}
                    row("文件复制","暂未实现",enabled=false) {}
                    row("智能输入法",if(ime) "开启" else "关闭") {ime=!ime;onSmartIme(ime);render()}
                    row("发送 Ctrl+Alt+Del","暂未实现安全注意序列",enabled=false) {}
                }
            }
            if(state.pending!=null) label("等待远端确认…")
            val available=(activity.resources.displayMetrics.heightPixels-dp(96)).coerceAtLeast(dp(200))
            body.measure(android.view.View.MeasureSpec.makeMeasureSpec(popup.width,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(0,android.view.View.MeasureSpec.UNSPECIFIED))
            popup.height=minOf(body.measuredHeight,available)
            if(popup.isShowing) popup.update(popup.width,popup.height)
        }
        val handler=android.os.Handler(android.os.Looper.getMainLooper())
        val poll=object: Runnable {override fun run() {
            if(!popup.isShowing) return
            val state=session.settingsSnapshot()
            val signature="${state.current}|${state.pending?.first}|${state.screens}|${session.microphoneEnabled}|${session.microphoneActive}|${session.clipboardEnabled}"
            if(!dragging && signature!=lastSignature) render()
            handler.postDelayed(this,700)
        }}
        popup.setOnDismissListener {handler.removeCallbacks(poll)}
        render();handler.postDelayed(poll,700);popup.showAtLocation(activity.window.decorView,Gravity.TOP or Gravity.END,dp(24),dp(64))
    }
}

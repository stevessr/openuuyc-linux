package org.openuuyc.pico

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.Surface
import org.openuuyc.pico.uu.UuDirectSession

/** Drawn into a compositor Surface: ordinary Android dialogs are not visible in XR. */
internal class PicoXrToolbar(
    private val surface: Surface, private val session: () -> UuDirectSession?,
    private val followView: (Boolean) -> Unit, private val reset: () -> Unit,
    private val touchMode: (Boolean) -> Unit,
    private val keyboardModeChanged: (PicoKeyboardMode) -> Unit,
    private val appearance: (Boolean,Int,Int) -> Unit,
    private val closeScreenMenu: () -> Unit,
    private val home: () -> Unit,
    private val connectImmersive: (org.openuuyc.pico.uu.UuDevice,Boolean) -> Unit,
    private val pickBackground: () -> Unit,
    private val useImage: (Boolean) -> Unit,
    private val backgroundAction: (String) -> Unit,
    private val menuShape: (Boolean) -> Unit,
    private val connectedIds: () -> Set<String>,
    private val disconnect: () -> Unit
) {
    private var devicePage=false
    private var deviceOffset=0
    private var devices=emptyList<org.openuuyc.pico.uu.UuDevice>()
    private var pendingTakeover: org.openuuyc.pico.uu.UuDevice?=null
    fun showHome(list: List<org.openuuyc.pico.uu.UuDevice>) {devices=list;backgroundPage=false;environmentPage=false;spacePage=false;devicePage=true;screenPage=false;keyboardPage=false;targetScreen=null;notice="主页面 · 仅沉浸式连接 · 当前串流保持运行";draw()}
    private var targetScreen: Int?=null
    private var advanced=false
    private var portrait=false
    private fun qualityPage()=targetScreen!=null && !advanced && !confirmingVirtual

    private var mr=true
    private var environmentPage=false
    private var backgroundPage=false
    private var selectedColor=Color.BLACK
    private var selectedPreset="black"
    private var brightness=75
    private var spacePage=false
    private var mrVisibility=100
    private var customImage=false
    fun setAppearance(visibility: Int,custom: Boolean,color: Int,preset: String="black",brightness: Int=75) {
        mrVisibility=visibility;customImage=custom;selectedColor=color;selectedPreset=preset;this.brightness=brightness
        backgroundIndex=backgrounds.indexOf(color).coerceAtLeast(0);draw()
    }
    private fun isRoot()=!devicePage && !screenPage && !keyboardPage && !confirmingVirtual && targetScreen==null && !environmentPage && !backgroundPage && !spacePage && pendingTakeover==null
    private val backgrounds=listOf(Color.BLACK,Color.rgb(15,20,28),Color.rgb(12,22,42))
    private var backgroundIndex=0
    fun setMr(enabled: Boolean) {mr=enabled;draw()}
    fun openScreenSettings(screen: Int) {targetScreen=screen;advanced=false;backgroundPage=false;environmentPage=false;spacePage=false;devicePage=false;screenPage=false;keyboardPage=false;notice="屏幕设置 · ${session()?.screenSnapshot()?.firstOrNull {it.id==screen} ?: screen}";draw()}
    fun openScreens() {targetScreen=null;advanced=false;devicePage=false;environmentPage=false;backgroundPage=false;screenPage=true;menuScreens=session()?.screenSnapshot().orEmpty();notice="选择显示器";draw()}
    fun confirmVirtualScreen() {
        if((session()?.settingsSnapshot()?.captureLevel ?: 0)<5) {status("远端未开放虚拟屏能力");return}
        confirmingVirtual=true;devicePage=false;screenPage=false;notice="确认在远端创建1920×1080虚拟屏？";draw()
    }
    private var keyboardPage=false
    private var keyboardMode=PicoKeyboardMode.CUSTOM
    private val keyboardKeys=PicoKeyboardKeys { code,down -> session()?.key(code,down)==true }
    private var usingTouch=false
    private var following = false
    private var followPending=false
    private var hoverU = -1f
    private var hoverV = -1f
    private var pressed = false
    private var confirmingVirtual = false
    private var screenPage=false
    private var menuScreens=emptyList<org.openuuyc.pico.uu.UuRemoteScreen>()
    private var notice = "参数按远端能力显示 · 最多六个空间窗口"
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    fun hover(u: Float, v: Float, down: Boolean) {
        if (kotlin.math.abs(u-hoverU)<.003f && kotlin.math.abs(v-hoverV)<.003f && pressed==down) return
        hoverU=u; hoverV=v; pressed=down; draw()
    }
    fun followState(enabled: Boolean) {
        following=enabled;followPending=false
        notice=if(enabled) "窗口与菜单按点击时相对视角跟随；B 键关闭" else "桌面已固定在当前位置，跟随已关闭"
        draw()
    }
    fun status(message: String) { notice = message.take(95); draw() }
    fun activate(u: Float, v: Float) {
        if((devicePage || screenPage) && pendingTakeover==null) {
            PicoXrSelectionCanvas.hit(u,v,if(devicePage) minOf(8,(devices.size-deviceOffset).coerceAtLeast(0)) else minOf(8,menuScreens.size))?.let(::click)
            return
        }
        if(qualityPage()) {
            PicoXrQualityLayout.hit(u,v)?.let {action ->
                if(action==99 || action==98) {advanced=true;draw();return}
                if(action in 102..105 || action>=200) {
                    try {
                        val direct=session() ?: return
                        val current=direct.settingsSnapshot(targetScreen).current
                        val next=if(action>=200) current.copy(fps=action-200) else current.copy(quality=action-100)
                        check(direct.applySettings(next,targetScreen=targetScreen)) {"控制通道未就绪"}
                    } catch(e: Exception) {notice=e.message ?: "设置未完成"}
                    draw()
                } else click(action)
            }
            return
        }
        if(keyboardPage) {
            if(v*400 in 48f..90f && u in 0f..<1f) {
                keyboardKeys.release()
                val choice=(u*4).toInt()
                if(choice==3) {keyboardPage=false;keyboardModeChanged(PicoKeyboardMode.CUSTOM)} else {
                    keyboardMode=PicoKeyboardMode.entries[choice]
                    keyboardModeChanged(keyboardMode)
                }
            } else PicoKeyboardLayout.keyAt(keyboardMode,u,v)?.let { if(!keyboardKeys.pressed(it)) notice="键盘发送未完成，检查控制通道" }
            draw();return
        }
        if(isRoot()) PicoXrMenuHit.toolbar(u,v)?.let {index ->
            when(index) {
                3 -> {spacePage=true;notice="空间布局 · B 键关闭跟随";draw()}
                4 -> {environmentPage=true;notice="环境 · MR 透视与背景";draw()}
                else -> click(listOf(10,8,2,0,4,6,7,11)[index])
            }
        } else PicoXrMenuHit.settings(u,v,if(confirmingVirtual) 4 else 12)?.let(::click)
    }
    fun click(cell: Int) {
        try {
            if(backgroundPage) {
                if(cell==11) {backgroundPage=false;environmentPage=true}
                else PicoBackgroundPattern.presets.getOrNull(cell)?.let {backgroundAction(it.id);notice="正在准备 ${it.label}…"}
                draw();return
            }
            if(environmentPage) {
                when(cell) {
                    0 -> mr=!mr
                    1 -> mrVisibility=(mrVisibility+10).let {if(it>100) 0 else it}
                    2 -> {pickBackground();notice="请在系统选择器中选择图片"}
                    3 -> {customImage=!customImage;useImage(customImage)}
                    4,5,6 -> {backgroundAction(PicoBackgroundPattern.presets[cell-4].id);return}
                    7 -> {backgroundPage=true;environmentPage=false;notice="背景库 · 选择后自动保存"}
                    8 -> {backgroundAction("regenerate");return}
                    9 -> {backgroundAction("brightness");return}
                    11 -> environmentPage=false
                }
                appearance(mr,selectedColor,mrVisibility);draw();return
            }
            if(spacePage) {
                when(cell) {
                    0 -> if(!followPending) {session()?.releaseInputs();followPending=true;followView(!following)}
                    1 -> reset()
                    11 -> spacePage=false
                }
                draw();return
            }
            pendingTakeover?.let {device ->
                pendingTakeover=null
                if(cell==0) connectImmersive(device,true) else notice="已取消抢占"
                draw();return
            }
            if(devicePage) {
                when(cell) {
                    8 -> deviceOffset=(deviceOffset-8).coerceAtLeast(0)
                    9 -> if(deviceOffset+8<devices.size) deviceOffset+=8
                    10 -> home()
                    11 -> {devicePage=false;notice="已返回沉浸式会话"}
                    else -> devices.getOrNull(deviceOffset+cell)?.let {device ->
                        check(device.online && device.controllable) {"设备离线或未开放控制"}
                        if(device.occupants>0) {pendingTakeover=device;notice="抢占 ${device.alias} 会断开现有会话，是否确认？"}
                        else connectImmersive(device,false)
                    }
                }
                draw();return
            }
            // Global navigation/appearance must remain selectable after the
            // active stream is closed or before any stream is available.
            if(targetScreen==null && !screenPage && !confirmingVirtual) {
                when(cell) {
                    4,5 -> {environmentPage=true;notice="环境 · MR 与背景"}
                    6 -> {closeScreenMenu();home()}
                    7 -> notice="内侧扳机即时抓取；菜单外始终显示射线与焦点"
                    9 -> notice="窗口最大对角线100英寸，保持屏幕比例"
                    11 -> disconnect()
                    else -> {requireNotNull(session()) {"请先打开一个串流窗口"};null}
                }
                if(cell in setOf(4,5,6,7,9,11)) {draw();return}
            }
            val direct=requireNotNull(session()) {"当前没有活动串流，可从主页面打开设备"}
            if(screenPage) {
                when(cell) {
                    11 -> { screenPage=false;notice="已返回串流设置" }
                    10 -> { menuScreens=direct.screenSnapshot();notice="选择远端屏幕 · 当前项显示勾选" }
                    else -> menuScreens.getOrNull(cell)?.let {
                        check(direct.switchScreen(it.id)) { "切屏未发送：屏幕已变化或通道未就绪" }
                    }
                }
                draw();return
            }
            if (confirmingVirtual) {
                confirmingVirtual = false
                if (cell == 0) {
                    check(direct.createVirtualDisplay()) { "控制通道未就绪" }
                    notice = "已请求创建；等待远端屏幕列表，不代表创建成功"
                } else notice = "已取消创建"
                draw(); return
            }
            val state = direct.settingsSnapshot(targetScreen)
            val current = state.current
            fun apply(next: org.openuuyc.pico.uu.UuCaptureSettings) {
                check(direct.applySettings(next)) { "控制通道未就绪" }
            }
            targetScreen?.let {screen ->
                val source=state.screens.firstOrNull {it.id==screen} ?: error("屏幕已移除")
                fun set(next: org.openuuyc.pico.uu.UuCaptureSettings) {check(direct.applySettings(next,targetScreen=screen)) {"控制通道未就绪"}}
                when(cell) {
                    0 -> {val ceiling=minOf(source.fps,state.profiles.filter {current.codec==0 || it.codec==current.codec}.maxOfOrNull {it.fps} ?: 0);val choices=listOf(30,60,90,120,144,165,240).filter {it<=ceiling};check(choices.isNotEmpty());set(current.copy(fps=choices[(choices.indexOf(current.fps)+1)%choices.size]))}
                    1 -> set(current.copy(quality=current.quality%6+1))
                    2 -> {val values=listOf(10,20,30,50,80,100);set(current.copy(quality=6,bitrateMbps=values[(values.indexOf(current.bitrateMbps)+1)%values.size]))}
                    3 -> set(current.copy(codec=0))
                    4 -> set(current.copy(cursor=!current.cursor))
                    5 -> {val values=listOf(1280 to 720,1920 to 1080,2560 to 1440,3840 to 2160).filter {v->state.profiles.any {v.first<=it.width && v.second<=it.height}};check(values.isNotEmpty());val next=values[(values.indexOf(current.maxWidth to current.maxHeight)+1)%values.size];set(current.copy(maxWidth=next.first,maxHeight=next.second))}
                    6 -> {check(source.modes.isNotEmpty());val next=source.modes[(source.modes.indexOf(source.current)+1)%source.modes.size];check(direct.applySettings(current,physical=next,targetScreen=screen))}
                    7 -> {check(source.dpis.isNotEmpty());val dpi=source.dpis[(source.dpis.indexOf(source.dpi)+1)%source.dpis.size];check(direct.applySettings(current,dpi=dpi,targetScreen=screen))}
                    8 -> {confirmingVirtual=true;notice="确认创建 1920×1080 虚拟屏？"}
                    9 -> notice="拖动四角调整大小；最大对角线 100 英寸"
                    10 -> notice="${source.name} · ${source.current} · DPI ${source.dpi}%"
                    11 -> {targetScreen=null;closeScreenMenu();notice="串流设置请从各窗口顶部展开"}
                }
                draw();return
            }
            when (cell) {
                0 -> { if(!followPending) { direct.releaseInputs(); followPending=true; followView(!following); notice="正在切换跟随模式…" } }
                1 -> { reset(); notice = "已重置六个桌面的空间布局" }
                2 -> { screenPage=true;menuScreens=direct.screenSnapshot();notice=if(menuScreens.isEmpty()) "等待远端屏幕列表，可点刷新" else "选择远端屏幕 · 当前项显示勾选" }
                3 -> { check(state.captureLevel>=5) { "远端未开放虚拟显示器功能" }; confirmingVirtual=true; notice="将在远端创建 1920×1080 虚拟显示器，是否确认？" }
                4 -> {environmentPage=true;notice="环境 · MR 与背景"}
                5 -> {environmentPage=true}
                6 -> {closeScreenMenu();home()}
                7 -> notice="内侧扳机即时抓取桌面/菜单；A 跟随，B 固定"
                8 -> { keyboardPage=true;keyboardModeChanged(keyboardMode);notice="键盘模式可随时切换 · 修饰键再次点击释放" }
                9 -> notice="最大尺寸：对角线100英寸；保持屏幕真实比例"
                10 -> { usingTouch=!usingTouch;direct.releaseInputs();touchMode(usingTouch);notice=if(usingTouch) "触摸输入：扳机按下为单指接触，拖动滑动，松开抬起" else "鼠标输入：扳机点击 / 拖动" }
                11 -> disconnect()
            }
        } catch (e: Exception) { notice=e.message ?: "操作未完成" }
        draw()
    }
    fun draw() {
        val nextPortrait=qualityPage()
        if(portrait!=nextPortrait) {portrait=nextPortrait;menuShape(portrait)}
        if (!surface.isValid) return
        var canvas: Canvas? = null
        try {
            canvas=surface.lockCanvas(null)
            if((devicePage || screenPage) && pendingTakeover==null) {
                val rows=if(devicePage) devices.drop(deviceOffset).take(8).map {PicoXrSelectionCanvas.Row(it.alias,if(it.online && it.controllable) "在线" else "不可连接",it.id in connectedIds(),it.online && it.controllable)}
                    else menuScreens.take(8).map {PicoXrSelectionCanvas.Row(it.toString(),it.current?.toString() ?: "",it.id==session()?.selectedScreen(),true)}
                PicoXrSelectionCanvas.draw(canvas,if(devicePage) "切换设备  ▾  ·  连接到新空间窗口" else "显示器  ▾  ·  当前屏幕",rows,hoverU,hoverV);return
            }
            if(qualityPage()) {PicoXrQualityCanvas.draw(canvas,session()?.settingsSnapshot(targetScreen),hoverU,hoverV,pressed,notice);return}
            canvas.drawColor(Color.TRANSPARENT,android.graphics.PorterDuff.Mode.CLEAR)
            paint.color=Color.rgb(22,26,33)
            canvas.drawRoundRect(RectF(1f,1f,1199f,399f),8f,8f,paint)
            paint.color=Color.rgb(45,53,66);paint.style=Paint.Style.STROKE;paint.strokeWidth=2f
            canvas.drawRoundRect(RectF(1f,1f,1199f,399f),8f,8f,paint);paint.style=Paint.Style.FILL
            paint.color=Color.rgb(230,235,244); paint.textSize=25f
            val visible=paint.breakText(notice,true,1160f,null)
            canvas.drawText(if(visible<notice.length) notice.take((visible-1).coerceAtLeast(0))+"…" else notice,20f,32f,paint)
            val state=session()?.settingsSnapshot(targetScreen); val c=state?.current
            if(keyboardPage) PicoKeyboardCanvas.draw(canvas,keyboardMode,keyboardKeys,hoverU,hoverV)
            val labels=if(backgroundPage) PicoBackgroundPattern.presets.map {"${if(!customImage && selectedPreset==it.id) "✓ " else ""}${it.label}${if(it.procedural) " · 程序生成" else ""}"}+listOf("","返回环境设置") else if(environmentPage) listOf(if(mr) "MR 透视：开启" else "MR 透视：关闭","透视可见度：${mrVisibility}%","选择自定义图片…",if(customImage) "图片背景：开启" else "图片背景：关闭","背景：全黑","背景：深灰","背景：深蓝","背景库 · 更多…","重新生成图案","背景亮度：${brightness}%","","返回主菜单") else if(spacePage) listOf(if(followPending) "跟随：切换中" else if(following) "跟随视角：开" else "跟随视角：关","重置到当前视角","拖动条：按住移动","摇杆前后：调节远近","A 跟随 / B 固定","窗口最大100英寸","","","","","","返回主菜单") else if(isRoot()) listOf(if(usingTouch) "触摸" else "鼠标","键盘","屏幕","空间","环境","主页面","帮助","断开") else if(pendingTakeover!=null) listOf("确认抢占","取消抢占") else if(devicePage) (0..11).map {i -> when(i) {
                8 -> "上一页";9 -> "下一页";10 -> "刷新设备";11 -> "返回串流"
                else -> devices.getOrNull(deviceOffset+i)?.let { "${it.alias} · ${if(it.online && it.controllable) "沉浸式连接" else "不可连接"}" } ?: ""
            }} else if(keyboardPage) emptyList() else if(screenPage) (0..11).map { index -> when(index) {
                10 -> "刷新屏幕";11 -> "返回设置";else -> menuScreens.getOrNull(index)?.let {
                    "${if(it.id==session()?.selectedScreen()) "✓ " else ""}${it.name.ifBlank { "屏幕 ${it.id+1}" }} ${it.current ?: ""}"
                } ?: ""
            }} else if(confirmingVirtual) listOf("确认创建","取消","取消","取消") else if(targetScreen!=null) listOf("帧率 ${c?.fps}","画质 ${c?.quality}","码率 ${c?.bitrateMbps}M","编码：自动","远端光标","串流尺寸","屏幕分辨率","DPI 缩放","创建虚拟屏","最大100英寸","屏幕信息","收起设置") else listOf(
                if(followPending) "跟随：切换中" else if(following) "跟随视角：开" else "跟随视角：关", "重置布局", "切换屏幕", "创建虚拟屏",
                if(mr) "MR：开" else "MR：关", "背景：${listOf("全黑","深灰","深蓝")[backgroundIndex]}", "主页面", "操作帮助",
                "键盘", "最大100英寸", if(usingTouch) "输入：触摸" else "输入：鼠标", "断开全部")
            labels.forEachIndexed { i,label ->
                if(label.isBlank()) return@forEachIndexed
                val box=if(isRoot()) PicoXrMenuHit.toolbarRect(i) else PicoXrMenuHit.settingsRect(i)
                val x=box[0];val y=box[1];val right=box[2];val bottom=box[3]
                val hovered = hoverU*1200 in x..right && hoverV*400 in y..bottom
                paint.color=if(hovered && pressed) Color.rgb(105,166,255) else if(hovered || (i==0 && following)) Color.rgb(75,136,235) else Color.rgb(31,37,47)
                canvas.drawRoundRect(RectF(x,y,right,bottom),6f,6f,paint)
                paint.color=Color.rgb(230,235,244); paint.textSize=25f
                val count=paint.breakText(label,true,right-x-28f,null)
                canvas.drawText(if(count<label.length) label.take((count-1).coerceAtLeast(0))+"…" else label,x+14,(y+bottom)/2+9,paint)
            }
            paint.color=if(hoverV>=.92f) PicoTheme.ACCENT else Color.rgb(128,138,152)
            canvas.drawRoundRect(RectF(400f,374f,800f,390f),12f,12f,paint)
            if (hoverU in 0f..1f && hoverV in 0f..1f) {
                paint.color=Color.WHITE; paint.style=Paint.Style.STROKE; paint.strokeWidth=3f
                canvas.drawCircle(hoverU*1200,hoverV*400,if(pressed) 7f else 10f,paint)
                paint.style=Paint.Style.FILL
            }
        } catch (_: IllegalArgumentException) { /* runtime has destroyed the swapchain */ }
        catch (_: IllegalStateException) { /* Activity teardown */ }
        finally { canvas?.let { runCatching { surface.unlockCanvasAndPost(it) } } }
    }
    fun releaseKeyboardKeys() {keyboardKeys.release();draw()}
    fun close() { keyboardKeys.release();surface.release() }
}

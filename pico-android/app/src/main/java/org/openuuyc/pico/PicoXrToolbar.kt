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
    private var quickAppConnections: ()->List<Pair<String,UuDirectSession>> = {emptyList()}
    fun setQuickAppConnections(provider: ()->List<Pair<String,UuDirectSession>>) {quickAppConnections=provider}
    private fun remoteQuickApps(direct: UuDirectSession,name: String) {
        direct.quickApps.list {apps,error ->
            if(apps==null) status(error ?: "应用列表不可用") else {
                options("$name · 快速启动",apps.map {it.name+if(!it.enabled) " · 暂不可用" else ""},-1) choose@ {index ->
                    val app=apps[index]
                    if(!app.enabled) {status("此应用暂不可用");return@choose}
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        options("$name · ${if(app.vr) "仅启动，无双眼画面" else "确认启动"}",listOf("启动 ${app.name}","取消"),-1) {choice ->
                            if(choice==0) direct.quickApps.launch(app,launchOnlyAck=app.vr) {_,message->status("$name · $message")}
                        };draw()
                    }
                };draw()
            }
        }
    }
    private var localVrApps: ((List<PicoLocalVrApp>)->Unit)->Unit={it(emptyList())}
    private var launchLocalVr: (PicoLocalVrApp)->Unit={}
    fun setLocalVrApps(load: ((List<PicoLocalVrApp>)->Unit)->Unit,launch: (PicoLocalVrApp)->Unit) {localVrApps=load;launchLocalVr=launch}
    private var controllerHand=0
    private var collapsed=false
    private var previousLayout=-1
    private var controllerLayout: (Int,Int)->Unit={_,_->}
    fun setControllerMenu(hand: Int,apply: (Int,Int)->Unit) {
        controllerHand=if(hand==1) 1 else 0;controllerLayout=apply;previousLayout=-1;draw()
    }
    private fun setFollowing(enabled: Boolean) {
        if(followPending || following==enabled) return
        session()?.releaseInputs();followPending=true;followView(enabled)
    }
    private var focusDots=false
    private var focusDotsChanged: (Boolean)->Unit={}
    fun setFocusDots(enabled: Boolean,onChanged: (Boolean)->Unit) {focusDots=enabled;focusDotsChanged=onChanged;draw()}
    private var devicePage=false
    private var deviceOffset=0
    private var screenOffset=0
    private var devices=emptyList<org.openuuyc.pico.uu.UuDevice>()
    private var pendingTakeover: org.openuuyc.pico.uu.UuDevice?=null
    fun showHome(list: List<org.openuuyc.pico.uu.UuDevice>) {optionTitle=null;optionApply=null;pendingTakeover=null;confirmingVirtual=false;devices=list;deviceOffset=deviceOffset.coerceAtMost(PicoXrOptionLayout.pageOf((list.size-1).coerceAtLeast(0)));backgroundPage=false;environmentPage=false;spacePage=false;devicePage=true;screenPage=false;keyboardPage=false;targetScreen=null;notice="主页面 · 仅沉浸式连接 · 当前串流保持运行";draw()}
    private var targetScreen: Int?=null
    private var advanced=false
    private var portrait=false
    private fun qualityPage()=targetScreen!=null && !advanced && !confirmingVirtual && optionTitle==null

    private var optionTitle: String?=null
    private var optionLabels=emptyList<String>()
    private var optionSelected=-1
    private var optionOffset=0
    private var optionApply: ((Int)->Unit)?=null
    private var bitrateDraft=30
    private fun options(title: String, labels: List<String>, selected: Int, apply: (Int)->Unit) {
        optionTitle=title;optionLabels=labels;optionSelected=selected;optionOffset=PicoXrOptionLayout.pageOf(selected);optionApply=apply
    }
    private fun bitrate(current: org.openuuyc.pico.uu.UuCaptureSettings, direct: UuDirectSession) {
        bitrateDraft=current.bitrateMbps.coerceIn(1,500)
        options("自定义码率",emptyList(),-1) {
            check(direct.applySettings(current.copy(quality=6,bitrateMbps=bitrateDraft),targetScreen=targetScreen)) {"控制通道未就绪"}
        }
    }
    private fun optionClick(u: Float,v: Float) {
        try {
            if(optionTitle=="自定义码率") {
                if(v<.1f) {optionTitle=null;optionApply=null}
                else if(v in .25f.. .65f) bitrateDraft=PicoXrOptionLayout.bitrate(u)
                else if(v in .7f.. .92f) {
                    if(u>.5f) optionApply?.invoke(bitrateDraft)
                    optionTitle=null
                }
            } else PicoXrOptionLayout.hit(u,v,(optionLabels.size-optionOffset).coerceAtLeast(0))?.let {i ->
                when(i) {
                    8 -> optionOffset=(optionOffset-8).coerceAtLeast(0)
                    9 -> if(optionOffset+8<optionLabels.size) optionOffset+=8
                    10,11 -> optionTitle=null
                    else -> if(optionOffset+i<optionLabels.size) {optionApply?.invoke(optionOffset+i);optionTitle=null}
                }
            }
        } catch(e: Exception) {notice=e.message ?: "设置未完成"}
        draw()
    }

    private var mr=true
    private var environmentPage=false
    private var backgroundPage=false
    private var selectedColor=Color.BLACK
    private var selectedPreset="black"
    private var brightness=75
    private var backgroundFlow=true
    private var spacePage=false
    private var mrVisibility=100
    private var customImage=false
    fun setAppearance(visibility: Int,custom: Boolean,color: Int,preset: String="black",brightness: Int=75,flow: Boolean=true) {
        backgroundFlow=flow;mrVisibility=visibility;customImage=custom;selectedColor=color;selectedPreset=preset;this.brightness=brightness
        backgroundIndex=backgrounds.indexOf(color).coerceAtLeast(0);draw()
    }
    private fun isRoot()=optionTitle==null && !devicePage && !screenPage && !keyboardPage && !confirmingVirtual && targetScreen==null && !environmentPage && !backgroundPage && !spacePage && pendingTakeover==null
    private val backgrounds=listOf(Color.BLACK,Color.rgb(15,20,28),Color.rgb(12,22,42))
    private var backgroundIndex=0
    fun setMr(enabled: Boolean) {mr=enabled;draw()}
    fun openScreenSettings(screen: Int) {optionTitle=null;optionApply=null;pendingTakeover=null;confirmingVirtual=false;targetScreen=screen;advanced=false;backgroundPage=false;environmentPage=false;spacePage=false;devicePage=false;screenPage=false;keyboardPage=false;notice="屏幕设置 · ${session()?.screenSnapshot()?.firstOrNull {it.id==screen} ?: screen}";draw()}
    fun openScreens() {optionTitle=null;optionApply=null;pendingTakeover=null;confirmingVirtual=false;keyboardPage=false;spacePage=false;screenOffset=0;targetScreen=null;advanced=false;devicePage=false;environmentPage=false;backgroundPage=false;screenPage=true;menuScreens=session()?.screenSnapshot().orEmpty();screenOffset=PicoXrOptionLayout.pageOf(menuScreens.indexOfFirst {it.id==session()?.selectedScreen()});notice="选择显示器";draw()}
    fun confirmVirtualScreen() {optionTitle=null;
        if((session()?.settingsSnapshot()?.captureLevel ?: 0)<5) {status("远端未开放虚拟屏能力");return}
        confirmingVirtual=true;pendingTakeover=null;keyboardPage=false;environmentPage=false;backgroundPage=false;spacePage=false;devicePage=false;screenPage=false;notice="确认在远端创建1920×1080虚拟屏？";draw()
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
    private var notice = "主菜单固定在手柄 · 所有功能直接点选"
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun hoverCell(u: Float,v: Float): Int? = when {
        optionTitle!=null -> PicoXrOptionLayout.hit(u,v,optionLabels.size)
        devicePage || screenPage -> PicoXrSelectionCanvas.hit(u,v,8)
        qualityPage() -> PicoXrQualityLayout.hit(u,v)
        isRoot() -> PicoXrMenuHit.toolbar(u,v)
        else -> PicoXrMenuHit.settings(u,v)
    }
    fun hover(u: Float,v: Float,down: Boolean) {
        val previousCell=hoverCell(hoverU,hoverV)
        val nextCell=hoverCell(u,v)
        val continuous=keyboardPage || (optionTitle=="自定义码率" && down)
        if(optionTitle=="自定义码率" && down && v in .25f.. .65f) bitrateDraft=PicoXrOptionLayout.bitrate(u)
        val redraw=pressed!=down || previousCell!=nextCell ||
            (continuous && (kotlin.math.abs(u-hoverU)>=.003f || kotlin.math.abs(v-hoverV)>=.003f))
        hoverU=u;hoverV=v;pressed=down
        if(redraw) draw()
    }
    fun followState(enabled: Boolean) {
        following=enabled;followPending=false
        notice=if(enabled) "窗口按当前相对视角跟随；手柄菜单独立固定" else "桌面已固定在当前位置，跟随已关闭"
        draw()
    }
    fun status(message: String) { notice = message.take(95); draw() }
    fun activate(u: Float, v: Float) {
        if(isRoot() && collapsed) {collapsed=false;draw();return}
        if(optionTitle!=null) {optionClick(u,v);return}
        if((devicePage || screenPage) && pendingTakeover==null) {
            PicoXrSelectionCanvas.hit(u,v,if(devicePage) minOf(8,(devices.size-deviceOffset).coerceAtLeast(0)) else minOf(8,(menuScreens.size-screenOffset).coerceAtLeast(0)))?.let(::click)
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
                val choice=(u*5).toInt()
                if(choice==4) {keyboardPage=false;keyboardModeChanged(PicoKeyboardMode.CUSTOM)} else {
                    keyboardMode=PicoKeyboardMode.entries[choice]
                    keyboardModeChanged(keyboardMode)
                }
            } else PicoKeyboardLayout.keyAt(keyboardMode,u,v)?.let { if(!keyboardKeys.pressed(it)) notice="键盘发送未完成，检查控制通道" }
            draw();return
        }
        if(isRoot()) PicoXrMenuHit.toolbar(u,v)?.let {index ->
            when(index) {
                3 -> {spacePage=true;notice="空间布局 · 跟随与固定使用菜单按钮";draw()}
                8 -> {setFollowing(true);draw()}
                9 -> {setFollowing(false);draw()}
                10 -> {collapsed=true;draw()}
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
                    1 -> options("MR 透视可见度",(0..10).map {"${it*10}%"},mrVisibility/10) {mrVisibility=it*10;appearance(mr,selectedColor,mrVisibility)}
                    2 -> {pickBackground();notice="请在系统选择器中选择图片"}
                    3 -> {customImage=!customImage;useImage(customImage)}
                    4,5,6 -> {backgroundAction(PicoBackgroundPattern.presets[cell-4].id);return}
                    7 -> options("环境背景 · 世界固定",PicoBackgroundPattern.presets.map {it.label},PicoBackgroundPattern.presets.indexOfFirst {it.id==selectedPreset}) {backgroundAction(PicoBackgroundPattern.presets[it].id)}
                    8 -> {backgroundAction("regenerate");return}
                    9 -> options("背景亮度",listOf("25%","50%","75%","100%"),brightness/25-1) {backgroundAction("brightness:${(it+1)*25}")}
                    10 -> {backgroundAction("flow");return}
                    11 -> environmentPage=false
                }
                appearance(mr,selectedColor,mrVisibility);draw();return
            }
            if(spacePage) {
                when(cell) {
                    0 -> if(!followPending) {session()?.releaseInputs();followPending=true;followView(!following)}
                    1 -> reset()
                    2 -> setFollowing(true)
                    3 -> setFollowing(false)
                    7 -> {controllerHand=1-controllerHand;previousLayout=-1}
                    6 -> {focusDots=!focusDots;focusDotsChanged(focusDots);notice=if(focusDots) "焦点圆点已开启" else "焦点圆点已隐藏；射线与点击保持可用"}
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
                    7 -> {
                        options("应用与 VR 游戏",listOf("远端快速应用","内置 VR 追踪诊断（无双眼画面）","本机 VR 应用 / 游戏"),-1) {entry ->
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                if(entry==2) {
                                    localVrApps {apps ->
                                        if(apps.isEmpty()) status("未找到带 VR 声明的可启动应用") else {
                                            options("本机 VR 应用 / 游戏",apps.map {it.name},-1) {index ->
                                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                    options("启动后会离开当前串流",listOf("启动 ${apps[index].name}","取消"),-1) {confirm ->if(confirm==0) launchLocalVr(apps[index])};draw()
                                                }
                                            };draw()
                                        }
                                    }
                                    return@post
                                }
                                val direct=session()
                                if(entry==1 && direct==null) {status("请先连接 Linux OpenUUYC");return@post}
                                if(entry==1 && direct!=null) {
                                    options("内置 VR · 追踪诊断",listOf("开启追踪桥接","停止追踪桥接","刷新状态：${direct.vr.status}"),-1) {choice ->
                                        if(choice<2) direct.vr.request(choice==0)
                                        status(direct.vr.status)
                                    };draw()
                                } else {
                                    val connections=quickAppConnections()
                                    if(connections.isEmpty()) status("请先连接远端设备") else {
                                        options("快速启动 · 选择连接",connections.map {it.first},-1) {index ->
                                            val target=connections[index]
                                            remoteQuickApps(target.second,target.first)
                                        };draw()
                                    }
                                }
                            }
                        }

                    }
                    9 -> notice="窗口最大对角线100英寸，保持屏幕比例"
                    11 -> disconnect()
                    else -> {requireNotNull(session()) {"请先打开一个串流窗口"};null}
                }
                if(cell in setOf(4,5,6,7,9,11)) {draw();return}
            }
            val direct=requireNotNull(session()) {"当前没有活动串流，可从主页面打开设备"}
            if(screenPage) {
                when(cell) {
                    8 -> screenOffset=(screenOffset-8).coerceAtLeast(0)
                    9 -> if(screenOffset+8<menuScreens.size) screenOffset+=8
                    11 -> { screenPage=false;closeScreenMenu();notice="已返回串流设置" }
                    10 -> { menuScreens=direct.screenSnapshot();screenOffset=screenOffset.coerceAtMost(PicoXrOptionLayout.pageOf((menuScreens.size-1).coerceAtLeast(0)));notice="选择远端屏幕 · 当前项显示勾选" }
                    else -> menuScreens.getOrNull(screenOffset+cell)?.let {
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
                    0 -> {val ceiling=minOf(source.fps,state.profiles.filter {current.codec==0 || it.codec==current.codec}.maxOfOrNull {it.fps} ?: 0);val values=listOf(30,60,90,120,144,165,240).filter {it<=ceiling};options("帧率上限",values.map {"$it FPS"},values.indexOf(current.fps)) {set(current.copy(fps=values[it]))}}
                    1 -> {val values=listOf(2,3,4,5);options("画质",listOf("高清","超清","原画","自动（原画）"),values.indexOf(current.quality)) {set(current.copy(quality=values[it]))}}
                    2 -> bitrate(current,direct)
                    3 -> {val values=state.selectableProfiles();options("编码偏好",values.map {when(it.codec) {0->"自动";1->"H.264";2->"H.265";else->"编码 ${it.codec}"}},values.indexOfFirst {it.codec==current.codec}) {set(current.copy(codec=values[it].codec))}}
                    4 -> set(current.copy(cursor=!current.cursor))
                    5 -> {val values=listOf(1280 to 720,1920 to 1080,2560 to 1440,3840 to 2160).filter {v->state.profiles.any {(current.codec==0 || current.codec==it.codec) && v.first<=it.width && v.second<=it.height}};options("串流尺寸",values.map {"${it.first} × ${it.second}"},values.indexOf(current.maxWidth to current.maxHeight)) {val next=values[it];set(current.copy(maxWidth=next.first,maxHeight=next.second))}}
                    6 -> options("屏幕分辨率",source.modes.map {it.toString()},source.modes.indexOf(source.current)) {check(direct.applySettings(current,physical=source.modes[it],targetScreen=screen)) {"控制通道未就绪"}}
                    7 -> options("DPI 缩放",source.dpis.map {"$it%"},source.dpis.indexOf(source.dpi)) {check(direct.applySettings(current,dpi=source.dpis[it],targetScreen=screen)) {"控制通道未就绪"}}
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
                2 -> openScreens()
                3 -> { check(state.captureLevel>=5) { "远端未开放虚拟显示器功能" }; confirmingVirtual=true; notice="将在远端创建 1920×1080 虚拟显示器，是否确认？" }
                4 -> {environmentPage=true;notice="环境 · MR 与背景"}
                5 -> {environmentPage=true}
                6 -> {closeScreenMenu();home()}
                7 -> notice="主菜单固定在手柄；窗口用内侧扳机移动，跟随使用菜单按钮"
                8 -> { keyboardPage=true;keyboardModeChanged(keyboardMode);notice="键盘模式可随时切换 · 修饰键再次点击释放" }
                9 -> notice="最大尺寸：对角线100英寸；保持屏幕真实比例"
                10 -> { usingTouch=!usingTouch;direct.releaseInputs();touchMode(usingTouch);notice=if(usingTouch) "触摸输入：扳机按下为单指接触，拖动滑动，松开抬起" else "鼠标输入：扳机点击 / 拖动" }
                11 -> disconnect()
            }
        } catch (e: Exception) { notice=e.message ?: "操作未完成" }
        draw()
    }
    fun draw() {
        val nextPortrait=qualityPage() || optionTitle!=null
        val layout=if(nextPortrait) 2 else if(isRoot() && collapsed) 3 else if(isRoot()) 0 else 1
        if(previousLayout!=layout) {previousLayout=layout;controllerLayout(layout,controllerHand)}
        if(portrait!=nextPortrait) {portrait=nextPortrait;menuShape(portrait)}
        if (!surface.isValid) return
        var canvas: Canvas? = null
        try {
            canvas=surface.lockCanvas(null)
            canvas.scale(1f,canvas.height/(if(isRoot() && !collapsed) 800f else 400f)) // Preserve logical hit coordinates on the high-resolution menu Surface.
            if(isRoot()) {
                if(collapsed) PicoXrControllerCanvas.collapsed(canvas,controllerHand)
                else PicoXrControllerCanvas.draw(canvas,listOf(if(usingTouch) "触摸" else "鼠标","键盘","屏幕","空间","环境","主页面","应用","断开"),following,followPending,controllerHand,hoverU,hoverV,notice)
                return
            }
            if(optionTitle!=null) {
                PicoXrOptionCanvas.draw(canvas,optionTitle!!,optionLabels,optionSelected,optionOffset,bitrateDraft,hoverU,hoverV,notice,pressed)
                return
            }
            if((devicePage || screenPage) && pendingTakeover==null) {
                val rows=if(devicePage) devices.drop(deviceOffset).take(8).map {PicoXrSelectionCanvas.Row(it.alias,if(it.online && it.controllable) "在线" else "不可连接",it.id in connectedIds(),it.online && it.controllable)}
                    else menuScreens.drop(screenOffset).take(8).map {PicoXrSelectionCanvas.Row(it.toString(),it.current?.toString() ?: "",it.id==session()?.selectedScreen(),true)}
                PicoXrSelectionCanvas.draw(canvas,if(devicePage) "切换设备  ▾  ·  连接到新空间窗口" else "显示器  ▾  ·  当前屏幕",rows,hoverU,hoverV);return
            }
            if(qualityPage()) {PicoXrQualityCanvas.draw(canvas,session()?.settingsSnapshot(targetScreen),hoverU,hoverV,pressed,notice);return}
            PicoXrMaterial.panel(canvas,1200f,400f)
            PicoXrMaterial.label(canvas,notice,24f,32f,1152f,24f,medium=true)
            val state=session()?.settingsSnapshot(targetScreen); val c=state?.current
            if(keyboardPage) PicoKeyboardCanvas.draw(canvas,keyboardMode,keyboardKeys,hoverU,hoverV)
            val labels=if(backgroundPage) PicoBackgroundPattern.presets.map {"${if(!customImage && selectedPreset==it.id) "✓ " else ""}${it.label}${if(it.procedural) " · 程序生成" else ""}"}+listOf("","返回环境设置") else if(environmentPage) listOf(if(mr) "MR 透视：开启" else "MR 透视：关闭","透视可见度：${mrVisibility}%","选择图片 / 全景图…",if(customImage) "图片背景：开启" else "图片背景：关闭","背景：全黑","背景：深灰","背景：深蓝","背景库 · 更多…","重新生成图案","背景亮度：${brightness}%",if(backgroundFlow) "背景流动：开" else "背景流动：关","返回主菜单") else if(spacePage) listOf(if(followPending) "跟随：切换中" else if(following) "跟随视角：开" else "跟随视角：关","重置到当前视角","跟随视角","固定窗口","窗口可独立拖动","窗口最大100英寸",if(focusDots) "焦点圆点：开" else "焦点圆点：关（默认）",if(controllerHand==0) "主菜单：左手柄" else "主菜单：右手柄","","","","返回主菜单") else if(pendingTakeover!=null) listOf("确认抢占","取消抢占") else if(devicePage) (0..11).map {i -> when(i) {
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
                val box=PicoXrMenuHit.settingsRect(i)
                val x=box[0];val y=box[1];val right=box[2];val bottom=box[3]
                val hovered = hoverU*1200 in x..right && hoverV*400 in y..bottom
                PicoXrMaterial.control(canvas,RectF(x,y,right,bottom),(i==0 && ((spacePage && following) || (environmentPage && mr))) || (i==6 && spacePage && focusDots),hovered,pressed)
                PicoXrMaterial.label(canvas,label,x+16,(y+bottom)/2+9,right-x-32,24f)

            }

        } catch (_: IllegalArgumentException) { /* runtime has destroyed the swapchain */ }
        catch (_: IllegalStateException) { /* Activity teardown */ }
        finally { canvas?.let { runCatching { surface.unlockCanvasAndPost(it) } } }
    }
    fun releaseKeyboardKeys() {keyboardKeys.release();draw()}
    fun close() { optionApply=null;keyboardKeys.release();surface.release() }
}

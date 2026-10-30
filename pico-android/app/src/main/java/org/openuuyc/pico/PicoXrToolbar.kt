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
    private var terminalTarget: UuDirectSession?=null
    private var terminalScreen=PicoTerminalScreen()
    private var terminalTextEntry: (org.openuuyc.pico.uu.UuTerminal)->Unit={}
    private var terminalHideInput: ()->Unit={}
    fun setTerminalInput(open: (org.openuuyc.pico.uu.UuTerminal)->Unit,close: ()->Unit) {terminalTextEntry=open;terminalHideInput=close}
    private fun closeTerminal() {terminalTarget?.terminal?.endShell();terminalTarget=null;terminalHideInput()}
    private fun openTerminal(direct: UuDirectSession) {
        closeTerminal();optionTitle=null;optionApply=null;keyboardPage=false;terminalTarget=direct;terminalScreen=PicoTerminalScreen()
        direct.terminal.changed={notice=it;draw()};direct.terminal.output={terminalScreen.append(it);draw()}
        notice="远端终端 · 点击打开 Shell；命令在所选设备执行";draw()
    }
    private var voiceStart: ()->Unit={}
    private var voiceStop: ()->Unit={}
    private var voiceCancel: ()->Unit={}
    private var voiceActive=false
    private var voicePressed=false
    private val voiceGesture=PicoVoiceGesture()
    fun setVoiceControl(start: ()->Unit,stop: ()->Unit,cancel: ()->Unit) {voiceStart=start;voiceStop=stop;voiceCancel=cancel}
    fun voiceState(active: Boolean,message: String) {voiceActive=active;notice=message;draw()}
    private var pointerStyle=4
    private var pointerStyleChanged: (Int)->Unit={}
    fun setPointerStyle(value: Int,changed: (Int)->Unit) {pointerStyle=value.coerceIn(0,4);pointerStyleChanged=changed}
    private fun keyboardOptions() {
        keyboardPage=false;voiceCancel();voiceGesture.reset();voicePressed=false
        options("键盘与语音",listOf("系统键盘","自定义键盘","全键盘","语音 · 麦克风"),keyboardMode.ordinal) {index->
            keyboardMode=PicoKeyboardMode.entries[index];keyboardPage=true;keyboardModeChanged(keyboardMode)
        };draw()
    }
    private fun inputOptions() {
        options("输入模式",listOf("输入类型：${if(usingTouch) "触摸" else "鼠标"}","指针与触摸提示","键盘与语音"),-1) {entry->
            when(entry) {
                0->options("输入类型",listOf("鼠标","触摸"),if(usingTouch) 1 else 0) {index->session()?.releaseInputs();usingTouch=index==1;touchMode(usingTouch)}
                1->options("指针与触摸提示",listOf("Windows 7 风格触摸提示","Windows 11 风格触摸提示","被控端鼠标","主控端鼠标","自适应鼠标"),pointerStyle) {index->pointerStyle=index;pointerStyleChanged(index)}
                2->keyboardOptions()
            }
        };draw()
    }
    private var quickAppConnections: ()->List<Pair<String,UuDirectSession>> = {emptyList()}
    fun setQuickAppConnections(provider: ()->List<Pair<String,UuDirectSession>>) {quickAppConnections=provider}
    private fun remoteQuickApps(direct: UuDirectSession,name: String) {
        options("$name · 正在读取应用",listOf("等待远端应用目录…","返回"),-1) {}
        draw()
        direct.quickApps.list {apps,error ->
            if(apps.isNullOrEmpty()) {
                val reason=error ?: "远端返回了空应用目录"
                status(reason)
                options("$name · $reason",listOf("重新读取应用","返回"),-1) {choice->
                    if(choice==0) remoteQuickApps(direct,name)
                }
                draw()
            } else {
                options("$name · 快速启动",apps.map {it.name+if(!it.enabled) " · 暂不可用" else ""},-1) choose@ {index ->
                    val app=apps[index]
                    if(!app.enabled) {status("此应用暂不可用");return@choose}
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        options("$name · ${if(app.vr) "启动 PCVR 会话与游戏" else "确认启动"}",listOf("启动 ${app.name}","取消"),-1) {choice ->
                            if(choice==0) {
                                fun launch() {
                                    direct.quickApps.launch(app,launchOnlyAck=app.vr) {ok,message->
                                        status("$name · $message")
                                        if(!ok) {options("启动未完成",listOf(message,"返回"),-1) {};draw()}
                                    }
                                }
                                if(app.vr) {
                                    status("正在准备真实 PCVR 会话…")
                                    direct.vr.prepareStereo {ready,message->
                                        if(ready) launch() else {
                                            status(message)
                                            options("PCVR 会话未就绪",listOf(message,"返回"),-1) {};draw()
                                        }
                                    }
                                } else launch()
                            }
                        };draw()
                    }
                }
                optionIcons=apps.map {PicoAppIcon.decode(it.icon)}
                draw()
            }
        }
    }
    private var localVrApps: ((List<PicoLocalVrApp>)->Unit)->Unit={it(emptyList())}
    private var launchLocalVr: (PicoLocalVrApp)->Unit={}
    fun setLocalVrApps(load: ((List<PicoLocalVrApp>)->Unit)->Unit,launch: (PicoLocalVrApp)->Unit) {localVrApps=load;launchLocalVr=launch}
    private var menuVisible=true
    private var quickHand=-1
    private var navigationCell=0
    private var stickFocused=false
    private var stickSelectionValid=false
    fun quickMenu(hand: Int) {
        closeTerminal();voiceCancel();voicePressed=false;voiceGesture.reset()
        closeScreenMenu()
        menuVisible=!(menuVisible && quickHand==hand);quickHand=if(menuVisible) hand else -1
        controllerHand=2;previousLayout=-1;navigationCell=0;stickFocused=false
        optionTitle=null;optionApply=null;devicePage=false;screenPage=false;spacePage=false;environmentPage=false;backgroundPage=false;keyboardPage=false;targetScreen=null;collapsed=false
        notice="左 X / 右 A 唤起 · 摇杆选择、松开执行 · 内侧扳机拖动"
        draw();if(menuVisible) navigate(0,false)
    }
    fun navigate(direction: Int,confirm: Boolean) {
        if(direction==9) {stickFocused=false;stickSelectionValid=false;draw();return}
        if(!menuVisible || keyboardPage || terminalTarget!=null) return
        if(direction>=100 && keyboardPage) return
        if(confirm && !stickSelectionValid) return
        val hit: (Float,Float)->Int? = if(!isRoot() && !keyboardPage) {
            val count=ringEntries().size;val page=ringPage
            {u,v->PicoXrMenuHit.toolbar(u,v)?.takeIf {PicoXrRingPage.enabled(it,page,count)}}
        } else ::hoverCell
        val selected=PicoMenuNavigation.select(navigationCell,direction,hit) ?: run {stickSelectionValid=false;return}
        if(direction>=100) stickSelectionValid=true
        navigationCell=selected.cell
        stickFocused=true;hoverU=selected.u;hoverV=selected.v
        if(confirm) {stickSelectionValid=false;activate(selected.u,selected.v);stickFocused=true} else draw()
    }
    private var controllerHand=2
    private var collapsed=false
    private var previousLayout=-1
    private var controllerLayout: (Int,Int)->Unit={_,_->}
    fun setControllerMenu(hand: Int,apply: (Int,Int)->Unit) {
        controllerHand=hand.coerceIn(0,2);controllerLayout=apply;previousLayout=-1;draw()
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
    private fun qualityPage()=!keyboardPage && terminalTarget==null && targetScreen!=null && !advanced && !confirmingVirtual && optionTitle==null

    private var optionTitle: String?=null
    private var optionLabels=emptyList<String>()
    private var optionIcons=emptyList<android.graphics.Bitmap?>()
    private var optionSelected=-1
    private var optionOffset=0
    private var optionApply: ((Int)->Unit)?=null
    private var bitrateDraft=30
    private fun options(title: String, labels: List<String>, selected: Int, apply: (Int)->Unit) {
        optionIcons=emptyList();optionTitle=title;optionLabels=labels;optionSelected=selected;optionOffset=PicoXrOptionLayout.pageOf(selected);optionApply=apply
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
    private fun isRoot()=terminalTarget==null && optionTitle==null && !devicePage && !screenPage && !keyboardPage && !confirmingVirtual && targetScreen==null && !environmentPage && !backgroundPage && !spacePage && pendingTakeover==null
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
    private var notice = "悬浮菜单 · 左 X / 右 A 唤起 · 摇杆选择、松开执行"
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun hoverCell(u: Float,v: Float): Int? = when {
        terminalTarget!=null -> if(v in .81f.. .89f && u in 0f..<1f) (u*8).toInt() else null
        !isRoot() && !keyboardPage -> ringHit(u,v)
        optionTitle!=null -> PicoXrOptionLayout.hit(u,v,optionLabels.size)
        devicePage || screenPage -> PicoXrSelectionCanvas.hit(u,v,8)
        qualityPage() -> PicoXrQualityLayout.hit(u,v)
        isRoot() -> PicoXrMenuHit.toolbar(u,v)
        else -> PicoXrMenuHit.settings(u,v)
    }
    fun hover(u: Float,v: Float,down: Boolean) {
        if(stickFocused && !down) return
        if(down) stickFocused=false
        if(keyboardPage && keyboardMode==PicoKeyboardMode.VOICE) {
            val mic=(u-.5f)*(u-.5f)*9+(v-.55f)*(v-.55f)<.16f*.16f
            if(down && !pressed && !voicePressed && mic) {
                voicePressed=true
                if(voiceGesture.down(android.os.SystemClock.elapsedRealtime(),voiceActive)==PicoVoiceGesture.Action.START) voiceStart()
            }
            if(!down && voicePressed) {
                voicePressed=false
                if(voiceGesture.up(android.os.SystemClock.elapsedRealtime())==PicoVoiceGesture.Action.STOP) voiceStop()
            }
        }
        val previousCell=hoverCell(hoverU,hoverV)
        val nextCell=hoverCell(u,v)
        val continuous=keyboardPage
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
        terminalTarget?.let {direct->
            if(v in .81f.. .89f && u in 0f..<1f) {
                when((u*8).toInt()) {
                    0->if(!direct.terminal.open()) notice="终端通道未就绪，或 Shell 已打开"
                    1->terminalTextEntry(direct.terminal)
                    2->direct.terminal.input("\u0003")
                    3->direct.terminal.input("\t")
                    4->direct.terminal.input("\u001b[A")
                    5->direct.terminal.input("\u001b[B")
                    6->direct.terminal.input("\u001b")
                    7->closeTerminal()
                };draw()
            };return
        }
        if(!isRoot() && !keyboardPage) {
            try {
                val entries=ringEntries()
                when(val cell=ringHit(u,v)) {
                    6->{ringPage--;draw()};7->{ringPage++;draw()};10->ringBack()
                    null->Unit
                    else->entries.getOrNull(ringPage*6+cell)?.run?.invoke()
                }
            } catch(e: Exception) {notice=e.message ?: "操作未完成";draw()}
            return
        }
        activateLegacy(u,v)
    }
    private fun activateLegacy(u: Float, v: Float) {
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
            when(PicoKeyboardLayout.headerAt(keyboardMode,u,v)) {
                PicoKeyboardLayout.Header.MODE->{keyboardKeys.release();voiceCancel();keyboardOptions()}
                PicoKeyboardLayout.Header.BACK->{keyboardKeys.release();voiceCancel();keyboardPage=false;keyboardModeChanged(PicoKeyboardMode.CUSTOM)}
                PicoKeyboardLayout.Header.CODEX_VOICE->{
                    voiceCancel()
                    notice=if(keyboardKeys.codexVoice()) "已发送 Codex CLI F8 · 请先聚焦远端 Codex，麦克风回传需单独开启" else "Codex 语音键未发送，请检查控制通道"
                }
                null->if(keyboardMode!=PicoKeyboardMode.VOICE) PicoKeyboardLayout.keyAt(keyboardMode,u,v)?.let {if(!keyboardKeys.pressed(it)) notice="键盘发送未完成，检查控制通道"}
            }
            draw();return
        }
        if(isRoot()) PicoXrMenuHit.toolbar(u,v)?.let {index ->
            when(index) {
                0 -> inputOptions()
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
                    7 -> options("主菜单位置",listOf("悬浮菜单（推荐）","左手柄","右手柄"),if(controllerHand==2) 0 else controllerHand+1) {controllerHand=if(it==0) 2 else it-1;previousLayout=-1}
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
                        options("应用与 VR 游戏",listOf("远端快速应用","附加已有 VR 应用（内置运行时）","本机 VR 应用 / 游戏","此连接的 VR 后端","远端终端"),-1) {entry ->
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
                                if(entry==4) {
                                    val connections=quickAppConnections()
                                    if(connections.isEmpty()) status("请先连接设备") else {
                                        options("终端 · 选择设备",connections.map {it.first},-1) {index->openTerminal(connections[index].second)};draw()
                                    }
                                    return@post
                                }
                                val direct=session()
                                if(entry==3) {
                                    if(direct==null) {status("先连接 Linux OpenUUYC");return@post}
                                    direct.quickApps.runtime {value,error->
                                        if(value==null) status(error ?: "远端不支持后端设置") else {
                                            val keys=listOf("open_xr_first","open_xr_only","steam_vr")
                                            options("VR 后端 · 下次连接生效",listOf("OpenXR 优先 / SteamVR 备用","仅内置 OpenXR","SteamVR 备用"),keys.indexOf(value.optString("preference"))) {choice->
                                                direct.quickApps.setRuntime(keys[choice],::status)
                                            };draw()
                                        }
                                    };return@post
                                }
                                if(entry==1 && direct==null) {status("请先连接 Linux OpenUUYC");return@post}
                                if(entry==1 && direct!=null) {
                                    options("附加已有 VR 应用",listOf("附加当前应用 · 不启动或重启游戏","停止连接 · 不关闭游戏","状态：${direct.vr.status}","SteamVR 已运行应用：独立适配中"),-1) {choice ->
                                        if(choice==0) direct.quickApps.runningVr {apps,error->
                                            if(apps.isNullOrEmpty()) {
                                                val reason=error ?: "未检测到运行中的内置 OpenXR 应用；本操作不会自动启动游戏"
                                                status(reason);options("没有可附加的应用",listOf(reason,"返回"),-1) {};draw()
                                            } else direct.vr.prepareStereo {ready,message->
                                                status(if(ready) "PCVR 会话已连接，等待已有应用的真实双眼帧：${apps.joinToString()}；未启动新游戏" else message)
                                                draw()
                                            }
                                        } else if(choice==1) {direct.vr.request(false);status("已停止 PCVR 连接，未关闭游戏")}
                                        else status(direct.vr.status)
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
                8 -> keyboardOptions()
                9 -> notice="最大尺寸：对角线100英寸；保持屏幕真实比例"
                10 -> inputOptions()
                11 -> disconnect()
            }
        } catch (e: Exception) { notice=e.message ?: "操作未完成" }
        draw()
    }
    private fun settingsLabels(): List<String> {
        val state=session()?.settingsSnapshot(targetScreen);val c=state?.current
        return if(backgroundPage) PicoBackgroundPattern.presets.map {"${if(!customImage && selectedPreset==it.id) "✓ " else ""}${it.label}${if(it.procedural) " · 程序生成" else ""}"}+listOf("","返回环境设置") else if(environmentPage) listOf(if(mr) "MR 透视：开启" else "MR 透视：关闭","透视可见度：${mrVisibility}%","选择图片 / 全景图…",if(customImage) "图片背景：开启" else "图片背景：关闭","背景：全黑","背景：深灰","背景：深蓝","背景库 · 更多…","重新生成图案","背景亮度：${brightness}%",if(backgroundFlow) "背景流动：开" else "背景流动：关","返回主菜单") else if(spacePage) listOf(if(followPending) "跟随：切换中" else if(following) "跟随视角：开" else "跟随视角：关","重置到当前视角","跟随视角","固定窗口","窗口可独立拖动","窗口最大100英寸",if(focusDots) "焦点圆点：开" else "焦点圆点：关（默认）",when(controllerHand) {0->"主菜单：左手柄";1->"主菜单：右手柄";else->"主菜单：悬浮"},"","","","返回主菜单") else if(pendingTakeover!=null) listOf("确认抢占","取消抢占") else if(devicePage) (0..11).map {i -> when(i) {
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
    }
    private data class RingEntry(val label: String,val icon: android.graphics.Bitmap?=null,val run: ()->Unit)
    private var ringPage=0
    private var ringKey=""
    private fun ringEntries(): List<RingEntry> {
        fun legacy(label: String,u: Float,v: Float)=RingEntry(label) {activateLegacy(u,v)}
        val entries=when {
            optionTitle=="自定义码率" -> listOf(-10,-1,1,10).map {delta->RingEntry("${if(delta>0) "+" else ""}$delta Mbps") {bitrateDraft=(bitrateDraft+delta).coerceIn(1,500);draw()}}+
                listOf(RingEntry("应用 $bitrateDraft Mbps") {val apply=optionApply;optionTitle=null;optionApply=null;apply?.invoke(bitrateDraft);draw()},RingEntry("取消") {optionTitle=null;draw()})
            optionTitle!=null -> optionLabels.mapIndexed {index,label->RingEntry("${if(index==optionSelected) "✓ " else ""}$label",optionIcons.getOrNull(index)) {val apply=optionApply;optionTitle=null;optionApply=null;apply?.invoke(index);draw()}}
            (devicePage || screenPage) && pendingTakeover==null -> {
                val rows=if(devicePage) devices.mapIndexed {i,d->RingEntry("${if(d.id in connectedIds()) "✓ " else ""}${d.alias} · ${if(d.online) "在线" else "离线"}") {deviceOffset=i;click(0)}}
                    else menuScreens.mapIndexed {i,d->RingEntry("${if(d.id==session()?.selectedScreen()) "✓ " else ""}${d.name}") {screenOffset=i;click(0)}}
                rows+RingEntry("刷新") {click(10)}
            }
            qualityPage() -> PicoXrQualityLayout.items.filter {it.action!=11}.map {item->
                val label=when(item.action) {3->"编码偏好";105->"自动（原画）";104->"原画";103->"超清";102->"高清";2->"自定义码率";98->"分辨率与 DPI";99->"高级设置";else->"${item.action-200} FPS"}
                legacy(label,(item.x+item.w/2)/600,(item.y+item.h/2)/1000)
            }
            backgroundPage -> PicoBackgroundPattern.presets.map {preset->RingEntry(preset.label) {backgroundAction(preset.id);draw()}}
            else -> settingsLabels().mapIndexedNotNull {i,label->if(label.isBlank()) null else RingEntry(label) {click(i)}}
        }
        val key="${optionTitle}:${devicePage}:${screenPage}:${qualityPage()}:${environmentPage}:${spacePage}:${backgroundPage}:${pendingTakeover!=null}:${confirmingVirtual}:${targetScreen}:"+entries.map {it.label}.joinToString("|")
        // The bitrate's draft is a changing value, not a navigation transition.
        val stable=if(optionTitle=="自定义码率") "bitrate" else key
        if(stable!=ringKey) {ringKey=stable;ringPage=0}
        ringPage=PicoXrRingPage.clamp(ringPage,entries.size)
        return entries
    }
    private fun ringTitle()=optionTitle ?: when {devicePage->"设备";screenPage->"显示器";backgroundPage->"背景库";environmentPage->"环境";spacePage->"空间";qualityPage()->"画质";else->"串流设置"}
    private fun ringBack() {
        when {optionTitle!=null->{optionTitle=null;optionApply=null};backgroundPage->{backgroundPage=false;environmentPage=true};else->click(11)}
        draw()
    }
    private fun ringHit(u: Float,v: Float): Int? {
        val entries=ringEntries()
        return PicoXrMenuHit.toolbar(u,v)?.takeIf {PicoXrRingPage.enabled(it,ringPage,entries.size)}
    }
    fun draw() {
        val nextPortrait=false
        val layout=if(!menuVisible) 4 else if(nextPortrait) 2 else if(isRoot() && collapsed) 3 else if(!keyboardPage) 0 else if(keyboardMode==PicoKeyboardMode.FULL) 5 else 1
        if(previousLayout!=layout) {previousLayout=layout;controllerLayout(layout,controllerHand)}
        if(portrait!=nextPortrait) {portrait=nextPortrait;menuShape(portrait)}
        if (!surface.isValid) return
        var canvas: Canvas? = null
        try {
            canvas=surface.lockCanvas(null)
            canvas.scale(1f,canvas.height/(if(keyboardPage) PicoKeyboardLayout.height(keyboardMode) else if(!collapsed) 800f else 400f)) // Preserve logical hit coordinates on the high-resolution menu Surface.
            terminalTarget?.let {
                PicoXrMaterial.panel(canvas,1200f,800f)
                PicoXrMaterial.label(canvas,notice,24f,40f,1152f,24f)
                val p=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=PicoTheme.TEXT;textSize=21f;typeface=android.graphics.Typeface.MONOSPACE}
                terminalScreen.text().split('\n').take(24).forEachIndexed {row,text->canvas.drawText(text,20f,75f+row*24f,p)}
                listOf("打开 Shell","输入","Ctrl+C","Tab","↑","↓","Esc","关闭").forEachIndexed {i,label->
                    val box=RectF(i*150+4f,648f,i*150+146f,712f)
                    PicoXrMaterial.control(canvas,box,false,hoverU*1200 in box.left..box.right && hoverV*800 in box.top..box.bottom,pressed)
                    PicoXrMaterial.label(canvas,label,box.left+8,690f,box.width()-16,22f)
                }
                return
            }
            if(isRoot()) {
                if(collapsed) PicoXrControllerCanvas.collapsed(canvas,controllerHand)
                else PicoXrControllerCanvas.draw(canvas,listOf(if(usingTouch) "触摸" else "鼠标","键盘","屏幕","空间","环境","主页面","应用","断开"),following,followPending,controllerHand,hoverU,hoverV,notice)
                return
            }
            if(!keyboardPage) {
                val entries=ringEntries()
                val labels=(0..5).map {entries.getOrNull(ringPage*6+it)?.label ?: ""}+listOf(if(ringPage>0) "‹ 上一页" else "",if((ringPage+1)*6<entries.size) "下一页 ›" else "")
                PicoXrControllerCanvas.draw(canvas,labels,false,false,controllerHand,hoverU,hoverV,notice,
                    icons=false,title="${ringTitle()} · ${ringPage+1}/${PicoXrRingPage.pages(entries.size)}",
                    previous=ringPage>0,next=(ringPage+1)*6<entries.size,appIcons=(0..5).map {entries.getOrNull(ringPage*6+it)?.icon})
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
            PicoXrMaterial.panel(canvas,1200f,if(keyboardPage) PicoKeyboardLayout.height(keyboardMode) else 400f)
            PicoXrMaterial.label(canvas,notice,24f,32f,1152f,24f,medium=true)
            val state=session()?.settingsSnapshot(targetScreen); val c=state?.current
            if(keyboardPage) PicoKeyboardCanvas.draw(canvas,keyboardMode,keyboardKeys,hoverU,hoverV,voiceActive)
            val labels=settingsLabels()
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
    fun close() { closeTerminal();voiceCancel();optionApply=null;keyboardKeys.release();surface.release() }
}

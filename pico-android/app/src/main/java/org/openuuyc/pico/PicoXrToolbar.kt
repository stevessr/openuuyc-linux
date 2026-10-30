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
    private var codexVoice: ((()->Boolean,(String)->Unit)->Unit)?=null
    fun setCodexVoice(action: (()->Boolean,(String)->Unit)->Unit) {codexVoice=action}
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
        optionTitle=null;optionApply=null;devicePage=false;screenPage=false;spacePage=false;environmentPage=false;backgroundPage=false;keyboardPage=false;targetScreen=null;targetPaneHint=null;collapsed=false;dropdownOffset=0;sliderDrag=-1
        notice="左 X / 右 A 唤起 · 摇杆选择、松开执行 · 内侧扳机拖动"
        draw();if(menuVisible) navigate(0,false)
    }
    fun navigate(direction: Int,confirm: Boolean) {
        if(direction==9) {stickFocused=false;stickSelectionValid=false;draw();return}
        if(!menuVisible || keyboardPage || terminalTarget!=null) return
        if(direction>=100 && keyboardPage) return
        if(confirm && !stickSelectionValid) return
        val dropdown=!isRoot() && !keyboardPage
        val rows=when {
            optionTitle!=null->optionRows()
            qualityPage()->qualityRows()
            else->dropdownRows()
        }
        val selectionDirection=if(dropdown) PicoXrDropdownLayout.navigationStep(direction) else direction
        if(dropdown && !confirm && selectionDirection in setOf(-1,1)) {
            val offset=if(optionTitle!=null) optionOffset else dropdownOffset
            val next=PicoXrDropdownLayout.navigationOffset(offset,navigationCell,selectionDirection,rows.size)
            if(optionTitle!=null) optionOffset=next else dropdownOffset=next
        }
        val hit: (Float,Float)->Int? = if(dropdown) {
            val offset=if(optionTitle!=null) optionOffset else dropdownOffset
            {u,v->PicoXrDropdownLayout.hit(u,v,rows.size,offset)}
        } else ::hoverCell
        val selected=PicoMenuNavigation.select(navigationCell,selectionDirection,hit) ?: run {stickSelectionValid=false;return}
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
    fun showHome(list: List<org.openuuyc.pico.uu.UuDevice>) {optionTitle=null;optionApply=null;pendingTakeover=null;confirmingVirtual=false;devices=list;deviceOffset=deviceOffset.coerceAtMost(PicoXrDropdownLayout.pageOf((list.size-1).coerceAtLeast(0)));backgroundPage=false;environmentPage=false;spacePage=false;devicePage=true;screenPage=false;keyboardPage=false;targetScreen=null;targetPaneHint=null;dropdownOffset=0;notice="主页面 · 仅沉浸式连接 · 当前串流保持运行";draw()}
    private var targetPaneHint: Int?=null
    private var targetScreen: Int?=null
    private var advanced=false
    private var portrait=false
    private fun qualityPage()=!keyboardPage && terminalTarget==null && targetScreen!=null && !spacePage && !advanced && !confirmingVirtual && optionTitle==null

    private var optionTitle: String?=null
    private var optionLabels=emptyList<String>()
    private var optionIcons=emptyList<android.graphics.Bitmap?>()
    private var optionSelected=-1
    private var optionOffset=0
    private var optionApply: ((Int)->Unit)?=null
    private var bitrateDraft=30
    private fun options(title: String, labels: List<String>, selected: Int, apply: (Int)->Unit) {
        optionIcons=emptyList();optionTitle=title;optionLabels=labels;optionSelected=selected;optionOffset=PicoXrDropdownLayout.pageOf(selected);optionApply=apply
    }
    private fun bitrate(current: org.openuuyc.pico.uu.UuCaptureSettings, direct: UuDirectSession) {
        bitrateDraft=current.bitrateMbps.coerceIn(1,500)
        options("自定义码率",emptyList(),-1) {
            check(direct.applySettings(current.copy(quality=6,bitrateMbps=bitrateDraft),targetScreen=targetScreen)) {"控制通道未就绪"}
        }
    }
    private fun optionRows(): List<PicoXrDropdownRow> = if(optionTitle=="自定义码率") listOf(
        PicoXrDropdownRow("视频码率","$bitrateDraft / 500 Mbps",PicoXrDropdownRow.SLIDER,sliderFraction=bitrateDraft/500f),
        PicoXrDropdownRow("取消",kind=PicoXrDropdownRow.ACTION),
        PicoXrDropdownRow("应用 $bitrateDraft Mbps",kind=PicoXrDropdownRow.ACTION,selected=true)
    ) else optionLabels.mapIndexed {index,label ->
        PicoXrDropdownRow(label,"",selected=index==optionSelected)
    }
    private fun optionClick(u: Float,v: Float) {
        try {
            if(optionTitle=="自定义码率") {
                val rows=optionRows().size
                when(PicoXrDropdownLayout.hit(u,v,rows,optionOffset)) {
                    -1 -> {optionTitle=null;optionApply=null}
                    0 -> bitrateDraft=PicoXrDropdownLayout.sliderValue(PicoXrDropdownLayout.sliderFraction(u,v,0,rows,optionOffset) ?: return,1,500)
                    1 -> {optionTitle=null;optionApply=null}
                    2 -> {val apply=optionApply;optionTitle=null;optionApply=null;apply?.invoke(bitrateDraft)}
                }
            } else PicoXrDropdownLayout.hit(u,v,optionLabels.size,optionOffset)?.let {i ->
                when(i) {
                    -1 -> {optionTitle=null;optionApply=null}
                    else -> if(optionOffset+i<optionLabels.size) {val apply=optionApply;optionTitle=null;optionApply=null;apply?.invoke(i);draw();return}
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
    private var windowScale: (Int?,Int) -> String = {_,_-> "请先选中一个串流窗口" }
    fun setWindowScale(action: (Int?,Int) -> String) {windowScale=action}
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
    fun openScreenSettings(screen: Int,paneHint: Int?=null) {optionTitle=null;optionApply=null;pendingTakeover=null;confirmingVirtual=false;targetScreen=screen;targetPaneHint=paneHint;advanced=false;backgroundPage=false;environmentPage=false;spacePage=false;devicePage=false;screenPage=false;keyboardPage=false;dropdownOffset=0;notice="屏幕设置 · ${session()?.screenSnapshot()?.firstOrNull {it.id==screen} ?: screen}";draw()}
    fun openScreens() {optionTitle=null;optionApply=null;pendingTakeover=null;confirmingVirtual=false;keyboardPage=false;spacePage=false;screenOffset=0;targetScreen=null;targetPaneHint=null;advanced=false;devicePage=false;environmentPage=false;backgroundPage=false;screenPage=true;menuScreens=session()?.screenSnapshot().orEmpty();screenOffset=PicoXrDropdownLayout.pageOf(menuScreens.indexOfFirst {it.id==session()?.selectedScreen()});dropdownOffset=0;notice="选择显示器";draw()}
    fun confirmVirtualScreen() {optionTitle=null;
        if((session()?.settingsSnapshot()?.captureLevel ?: 0)<5) {status("远端未开放虚拟屏能力");return}
        confirmingVirtual=true;pendingTakeover=null;keyboardPage=false;environmentPage=false;backgroundPage=false;spacePage=false;devicePage=false;screenPage=false;dropdownOffset=0;notice="确认在远端创建1920×1080虚拟屏？";draw()
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
        optionTitle!=null -> PicoXrDropdownLayout.hit(u,v,optionRows().size,optionOffset)
        !isRoot() && !keyboardPage -> PicoXrDropdownLayout.hit(u,v,dropdownRows().size,dropdownOffset)
        isRoot() -> PicoXrMenuHit.toolbar(u,v)
        else -> PicoXrDropdownLayout.hit(u,v,dropdownRows().size,dropdownOffset)
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
        if(sliderDrag>=0) {
            pressed=down
            if(down) dragSlider(u,v) else endSliderDrag()
            return
        }
        if(down) {
            // Rising press edge: entering a slider track starts a drag immediately,
            // because the native menu click only reports on release.
            val rows=dropdownRowsForDrag()
            if(rows!=null) rows.forEachIndexed {index,row ->
                if(row.kind==PicoXrDropdownRow.SLIDER && PicoXrDropdownLayout.sliderFraction(u,v,index,rows.size,dropdownOffset)!=null) {
                    sliderDrag=index;applySlider(index,PicoXrDropdownLayout.sliderFraction(u,v,index,rows.size,dropdownOffset)!!,rows);pressed=true;return
                }
            }
        }
        if(!down && pressed) endSliderDrag()
        val previousCell=hoverCell(hoverU,hoverV)
        val nextCell=hoverCell(u,v)
        val continuous=keyboardPage || sliderDrag>=0
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
                when {
                    optionTitle!=null->optionClick(u,v)
                    (devicePage || screenPage) && pendingTakeover==null->dropdownClick(u,v,selectionRows())
                    qualityPage()->dropdownClick(u,v,qualityRows())
                    else->dropdownClick(u,v,dropdownRows())
                }
            } catch(e: Exception) {notice=e.message ?: "操作未完成";draw()}
            return
        }
        activateLegacy(u,v)
    }
    private fun activateLegacy(u: Float, v: Float) {
        if(isRoot() && collapsed) {collapsed=false;draw();return}
        if(optionTitle!=null) {optionClick(u,v);return}
        if((devicePage || screenPage) && pendingTakeover==null) {dropdownClick(u,v,selectionRows());return}
        if(qualityPage()) {dropdownClick(u,v,qualityRows());return}
        if(keyboardPage) {
            when(PicoKeyboardLayout.headerAt(keyboardMode,u,v)) {
                PicoKeyboardLayout.Header.MODE->{keyboardKeys.release();voiceCancel();keyboardOptions()}
                PicoKeyboardLayout.Header.BACK->{keyboardKeys.release();voiceCancel();keyboardPage=false;keyboardModeChanged(PicoKeyboardMode.CUSTOM)}
                PicoKeyboardLayout.Header.CODEX_VOICE->{
                    voiceCancel()
                    codexVoice?.invoke(keyboardKeys::codexVoice) {message->notice=message;draw()} ?: run {notice="Codex 语音控制未就绪"}
                }
                null->if(keyboardMode!=PicoKeyboardMode.VOICE) PicoKeyboardLayout.keyAt(keyboardMode,u,v)?.let {if(!keyboardKeys.pressed(it)) notice="键盘发送未完成，检查控制通道"}
            }
            draw();return
        }
        if(isRoot()) PicoXrMenuHit.toolbar(u,v)?.let {index ->
            when(index) {
                0 -> inputOptions()
                3 -> {spacePage=true;dropdownOffset=0;notice="空间布局 · 跟随与固定使用菜单按钮";draw()}
                8 -> {setFollowing(true);draw()}
                9 -> {setFollowing(false);draw()}
                10 -> {collapsed=true;draw()}
                4 -> {environmentPage=true;dropdownOffset=0;notice="环境 · MR 透视与背景";draw()}
                else -> click(listOf(10,8,2,0,4,6,7,11)[index])
            }
        } else dropdownClick(u,v,dropdownRows())
    }
    /** --- Dropdown model: one page = one flat list; two levels max, sliders for deep knobs. --- */
    private var dropdownOffset=0
    private var sliderDrag=-1

    private fun dropdownTitle()=when {
        pendingTakeover!=null->"抢占确认"
        backgroundPage->"背景库"
        environmentPage->"环境"
        spacePage->"空间布局"
        confirmingVirtual->"创建虚拟屏"
        targetScreen!=null->"屏幕设置 · ${session()?.screenSnapshot()?.firstOrNull {it.id==targetScreen} ?: targetScreen}"
        devicePage->"设备"
        screenPage->"显示器"
        else->"串流设置"
    }

    private fun selectionRows(): List<PicoXrDropdownRow> {
        val rows=if(devicePage) devices.map {device ->
            PicoXrDropdownRow(device.alias,if(device.online && device.controllable) "在线" else "不可连接",selected=device.id in connectedIds(),enabled=device.online && device.controllable)
        } else menuScreens.map {screen ->
            PicoXrDropdownRow(screen.name.ifBlank {"屏幕 ${screen.id+1}"},"${screen.current ?: ""}",selected=screen.id==session()?.selectedScreen(),enabled=true)
        }
        return rows+if(devicePage) listOf(PicoXrDropdownRow("刷新设备列表",kind=PicoXrDropdownRow.ACTION),PicoXrDropdownRow("返回主菜单",kind=PicoXrDropdownRow.ACTION))
        else listOf(PicoXrDropdownRow("刷新屏幕",kind=PicoXrDropdownRow.ACTION),PicoXrDropdownRow("返回串流设置",kind=PicoXrDropdownRow.ACTION))
    }

    private fun windowScaleRows(): List<PicoXrDropdownRow> = listOf(
        PicoXrDropdownRow("缩小 XR 窗口（−6%）","保持比例",PicoXrDropdownRow.ACTION),
        PicoXrDropdownRow("放大 XR 窗口（＋6%）","保持比例",PicoXrDropdownRow.ACTION)
    )

    private fun qualityRows(): List<PicoXrDropdownRow> {
        val scaleRows=windowScaleRows()
        val state=session()?.settingsSnapshot(targetScreen)
            ?: return scaleRows+listOf(PicoXrDropdownRow("等待串流就绪…",enabled=false),PicoXrDropdownRow("返回",kind=PicoXrDropdownRow.ACTION))
        val c=state.current
        val source=state.screens.firstOrNull {it.id==state.selected}
        val ceiling=minOf(source?.fps ?: 0,state.profiles.filter {c.codec==0 || it.codec==c.codec}.maxOfOrNull {it.fps} ?: 0)
        return listOf(
            PicoXrDropdownRow("画质",when(c.quality) {5->"自动（原画）";4->"原画";3->"超清";2->"高清";else->"${c.quality}"}),
            PicoXrDropdownRow("帧率上限","${c.fps} FPS",enabled=ceiling>0),
            PicoXrDropdownRow("视频码率","${c.bitrateMbps} / 500 Mbps",PicoXrDropdownRow.SLIDER,sliderFraction=c.bitrateMbps/500f),
            PicoXrDropdownRow("编码偏好",when(c.codec) {1->"H.264";2->"H.265";5->"AV1";else->"自动"})
        )+scaleRows+listOf(
            PicoXrDropdownRow("显示器分辨率与 DPI","当前屏幕 ›"),
            PicoXrDropdownRow("高级设置","›"),
            PicoXrDropdownRow("返回串流设置",kind=PicoXrDropdownRow.ACTION)
        )
    }

    private fun dropdownRows(): List<PicoXrDropdownRow> = when {
        pendingTakeover!=null->listOf(
            PicoXrDropdownRow("确认抢占 · 断开现有会话",kind=PicoXrDropdownRow.ACTION,danger=true),
            PicoXrDropdownRow("取消",kind=PicoXrDropdownRow.ACTION)
        )
        confirmingVirtual->listOf(
            PicoXrDropdownRow("确认创建 1920×1080 虚拟屏",kind=PicoXrDropdownRow.ACTION,selected=true),
            PicoXrDropdownRow("取消",kind=PicoXrDropdownRow.ACTION)
        )
        backgroundPage->PicoBackgroundPattern.presets.map {preset ->
            PicoXrDropdownRow(preset.label,if(preset.procedural) "程序生成" else "",selected=!customImage && selectedPreset==preset.id)
        }+listOf(PicoXrDropdownRow("返回环境设置",kind=PicoXrDropdownRow.ACTION))
        environmentPage->listOf(
            PicoXrDropdownRow("MR 透视",if(mr) "开启" else "关闭",PicoXrDropdownRow.TOGGLE),
            PicoXrDropdownRow("透视可见度","$mrVisibility%",PicoXrDropdownRow.SLIDER,sliderFraction=mrVisibility/100f),
            PicoXrDropdownRow("图片背景",if(customImage) "开启" else "关闭",PicoXrDropdownRow.TOGGLE),
            PicoXrDropdownRow("选择图片 / 全景图…",kind=PicoXrDropdownRow.ACTION),
            PicoXrDropdownRow("背景颜色",when(backgroundIndex) {0->"全黑";1->"深灰";else->"深蓝"}),
            PicoXrDropdownRow("背景库 · 更多图案","›"),
            PicoXrDropdownRow("重新生成图案",kind=PicoXrDropdownRow.ACTION),
            PicoXrDropdownRow("背景亮度","$brightness%",PicoXrDropdownRow.SLIDER,sliderFraction=brightness/100f),
            PicoXrDropdownRow("背景流动",if(backgroundFlow) "开启" else "关闭",PicoXrDropdownRow.TOGGLE),
            PicoXrDropdownRow("返回主菜单",kind=PicoXrDropdownRow.ACTION)
        )
        spacePage->listOf(
            PicoXrDropdownRow("跟随视角",if(followPending) "切换中…" else if(following) "开启" else "关闭",PicoXrDropdownRow.TOGGLE,enabled=!followPending),
            PicoXrDropdownRow("固定窗口",kind=PicoXrDropdownRow.ACTION),
            PicoXrDropdownRow("重置到当前视角",kind=PicoXrDropdownRow.ACTION),
            PicoXrDropdownRow("缩小 XR 窗口（−6%）","保持比例",PicoXrDropdownRow.ACTION),
            PicoXrDropdownRow("放大 XR 窗口（＋6%）","保持比例",PicoXrDropdownRow.ACTION),
            PicoXrDropdownRow("焦点圆点",if(focusDots) "开启" else "关闭（默认）",PicoXrDropdownRow.TOGGLE),
            PicoXrDropdownRow("主菜单位置",when(controllerHand) {0->"左手柄";1->"右手柄";else->"悬浮（推荐）"}),
            PicoXrDropdownRow("窗口尺寸范围","宽度 ≥ 40 cm · 对角线 ≤ 100 英寸",enabled=false),
            PicoXrDropdownRow("返回主菜单",kind=PicoXrDropdownRow.ACTION)
        )
        targetScreen!=null->screenSettingRows()
        devicePage || screenPage->selectionRows()
        else->streamSettingRows()
    }

    private fun screenSettingRows(): List<PicoXrDropdownRow> {
        val direct=session() ?: return listOf(PicoXrDropdownRow("请先连接设备",enabled=false),PicoXrDropdownRow("返回",kind=PicoXrDropdownRow.ACTION))
        val state=direct.settingsSnapshot(targetScreen)
        val source=state.screens.firstOrNull {it.id==targetScreen} ?: return listOf(PicoXrDropdownRow("屏幕已移除",enabled=false),PicoXrDropdownRow("返回",kind=PicoXrDropdownRow.ACTION))
        val c=state.current
        return listOf(
            PicoXrDropdownRow("帧率上限","${c.fps} FPS"),
            PicoXrDropdownRow("画质",when(c.quality) {5->"自动（原画）";4->"原画";3->"超清";2->"高清";else->"${c.quality}"}),
            PicoXrDropdownRow("视频码率","${c.bitrateMbps} / 500 Mbps",PicoXrDropdownRow.SLIDER,sliderFraction=c.bitrateMbps/500f),
            PicoXrDropdownRow("编码偏好",when(c.codec) {1->"H.264";2->"H.265";5->"AV1";else->"自动"}),
            PicoXrDropdownRow("串流尺寸","${c.maxWidth} × ${c.maxHeight}"),
            PicoXrDropdownRow("屏幕分辨率",source.current?.toString() ?: "未知"),
            PicoXrDropdownRow("DPI 缩放","${source.dpi}%"),
            PicoXrDropdownRow("远端光标",if(c.cursor) "开启" else "关闭",PicoXrDropdownRow.TOGGLE),
            PicoXrDropdownRow("创建虚拟屏",kind=PicoXrDropdownRow.ACTION,enabled=state.captureLevel>=5),
            PicoXrDropdownRow("屏幕信息","${source.name} · ${source.current ?: "?"} · ${source.dpi}%"),
            PicoXrDropdownRow("收起屏幕设置",kind=PicoXrDropdownRow.ACTION)
        )
    }

    private fun streamSettingRows(): List<PicoXrDropdownRow> = listOf(
        PicoXrDropdownRow("跟随视角",if(followPending) "切换中…" else if(following) "开启" else "关闭",PicoXrDropdownRow.TOGGLE,enabled=!followPending),
        PicoXrDropdownRow("重置空间布局",kind=PicoXrDropdownRow.ACTION),
        PicoXrDropdownRow("切换屏幕","›"),
        PicoXrDropdownRow("创建虚拟屏",kind=PicoXrDropdownRow.ACTION),
        PicoXrDropdownRow("MR 透视",if(mr) "开启" else "关闭",PicoXrDropdownRow.TOGGLE),
        PicoXrDropdownRow("环境与背景","›"),
        PicoXrDropdownRow("主页面",kind=PicoXrDropdownRow.ACTION),
        PicoXrDropdownRow("键盘与输入","›"),
        PicoXrDropdownRow("应用与 VR 游戏","›"),
        PicoXrDropdownRow("输入模式",if(usingTouch) "触摸" else "鼠标"),
        PicoXrDropdownRow("断开全部连接",kind=PicoXrDropdownRow.ACTION,danger=true)
    )

    /** Slider drag: while pressed over a slider row, the knob follows the pointer. */
    fun dragSlider(u: Float,v: Float) {
        if(sliderDrag<0) return
        val rows=dropdownRowsForDrag() ?: return
        val fraction=PicoXrDropdownLayout.sliderFraction(u,v,sliderDrag,rows.size,dropdownOffset) ?: return
        applySlider(sliderDrag,fraction,rows)
    }
    fun endSliderDrag() {sliderDrag=-1;draw()}
    private fun dropdownRowsForDrag()=when {
        optionTitle!=null->if(optionTitle=="自定义码率") optionRows() else null
        qualityPage()->qualityRows()
        devicePage || screenPage->selectionRows()
        else->dropdownRows()
    }
    private fun applySlider(index: Int,fraction: Float,rows: List<PicoXrDropdownRow>) {
        val row=rows.getOrNull(index) ?: return
        when {
            optionTitle=="自定义码率" && index==0 -> bitrateDraft=PicoXrDropdownLayout.sliderValue(fraction,1,500)
            (qualityPage() || targetScreen!=null) && row.label=="视频码率" -> {
                val direct=session() ?: return
                val current=direct.settingsSnapshot(targetScreen).current
                val value=PicoXrDropdownLayout.sliderValue(fraction,1,500)
                if(value!=current.bitrateMbps) try {
                    check(direct.applySettings(current.copy(quality=current.quality,bitrateMbps=value),targetScreen=targetScreen)) {"控制通道未就绪"}
                } catch(e: Exception) {notice=e.message ?: "设置未完成"}
            }
            environmentPage && row.label=="透视可见度" -> {mrVisibility=PicoXrDropdownLayout.sliderValue(fraction,0,100);appearance(mr,selectedColor,mrVisibility)}
            environmentPage && row.label=="背景亮度" -> {brightness=PicoXrDropdownLayout.sliderValue(fraction,0,100);backgroundAction("brightness:$brightness")}
        }
        draw()
    }

    /** One click handler for every dropdown page (device/screen/quality/space/environment/...). */
    private fun dropdownClick(u: Float,v: Float,rows: List<PicoXrDropdownRow>) {
        try {
            if(sliderDrag>=0) return // hover() owns the active slider drag; release already ended it.
            val hit=PicoXrDropdownLayout.hit(u,v,rows.size,dropdownOffset)
            if(hit==null) return
            if(hit==-1) { // header: back/close
                closeDropdownPage();draw();return
            }
            val row=rows.getOrNull(hit) ?: return
            if(!row.enabled) return
            dropdownAction(hit,row)
        } catch(e: Exception) {notice=e.message ?: "操作未完成"}
        draw()
    }

    private fun closeDropdownPage() {
        when {
            optionTitle!=null->{optionTitle=null;optionApply=null}
            backgroundPage->{backgroundPage=false}
            pendingTakeover!=null->{pendingTakeover=null;notice="已取消抢占"}
            confirmingVirtual->confirmingVirtual=false
            devicePage->{devicePage=false;deviceOffset=0;home()}
            screenPage->{screenPage=false;screenOffset=0;closeScreenMenu();notice="已返回串流设置"}
            qualityPage()->{advanced=false;if(targetScreen!=null) {targetScreen=null;targetPaneHint=null}}
            targetScreen!=null->{targetScreen=null;targetPaneHint=null;closeScreenMenu();notice="串流设置请从各窗口顶部展开"}
            environmentPage || spacePage->{environmentPage=false;spacePage=false;backgroundPage=false}
            else->{}
        }
        dropdownOffset=0
    }

    private fun dropdownAction(index: Int,row: PicoXrDropdownRow) {
        val label=row.label
        when {
            pendingTakeover!=null->{
                val device=pendingTakeover
                pendingTakeover=null
                if(label.startsWith("确认") && device!=null) connectImmersive(device,true) else notice="已取消抢占"
            }
            confirmingVirtual->{
                confirmingVirtual=false
                if(label.startsWith("确认")) {
                    val direct=session() ?: return
                    check(direct.createVirtualDisplay()) {"控制通道未就绪"}
                    notice="已请求创建；等待远端屏幕列表，不代表创建成功"
                } else notice="已取消创建"
            }
            backgroundPage->{
                if(label=="返回环境设置") {backgroundPage=false}
                else PicoBackgroundPattern.presets.firstOrNull {it.label==label}?.let {backgroundAction(it.id);notice="正在准备 ${it.label}…"}
            }
            environmentPage->when(label) {
                "MR 透视"->mr=!mr
                "图片背景"->{customImage=!customImage;useImage(customImage)}
                "选择图片 / 全景图…"->{pickBackground();notice="请在系统选择器中选择图片"}
                "背景颜色"->{backgroundIndex=(backgroundIndex+1)%3;backgroundAction(PicoBackgroundPattern.presets.getOrNull(backgroundIndex)?.id ?: "black")}
                "背景库 · 更多图案"->{backgroundPage=true;dropdownOffset=0}
                "重新生成图案"->{backgroundAction("regenerate");return}
                "背景流动"->{backgroundFlow=!backgroundFlow;backgroundAction("flow");return}
                "返回主菜单"->{environmentPage=false;backgroundPage=false}
            }
            spacePage->when(label) {
                "跟随视角"->{if(!followPending) {session()?.releaseInputs();followPending=true;followView(!following)};return}
                "固定窗口"->setFollowing(false)
                "重置到当前视角"->{reset();return}
                "焦点圆点"->{focusDots=!focusDots;focusDotsChanged(focusDots);notice=if(focusDots) "焦点圆点已开启" else "焦点圆点已隐藏；射线与点击保持可用"}
                "缩小 XR 窗口（−6%）"->notice=windowScale(null,-1)
                "放大 XR 窗口（＋6%）"->notice=windowScale(null,1)
                "主菜单位置"->{controllerHand=when(controllerHand) {2->0;0->1;else->2};previousLayout=-1}
                "返回主菜单"->spacePage=false
            }
            qualityPage()->qualityAction(label)
            devicePage->when {
                label=="刷新设备列表"->{home();return}
                label=="返回主菜单"->{devicePage=false;deviceOffset=0;return}
                else->devices.firstOrNull {it.alias==label}?.let {device ->
                    check(device.online && device.controllable) {"设备离线或未开放控制"}
                    if(device.occupants>0) {pendingTakeover=device;notice="抢占 ${device.alias} 会断开现有会话，是否确认？"}
                    else connectImmersive(device,false)
                }
            }
            screenPage->when {
                label=="刷新屏幕"->{menuScreens=session()?.screenSnapshot().orEmpty();screenOffset=0;return}
                label=="返回串流设置"->{screenPage=false;closeScreenMenu();notice="已返回串流设置";return}
                else->menuScreens.firstOrNull {it.name.ifBlank {"屏幕 ${it.id+1}"}==label}?.let {
                    val direct=session() ?: return
                    check(direct.switchScreen(it.id)) {"切屏未发送：屏幕已变化或通道未就绪"}
                }
            }
            targetScreen!=null->screenSettingAction(label)
            else->streamSettingAction(label)
        }
    }

    /** Quality dropdown actions: direct apply or one submenu deep (lists stay single-level). */
    private fun qualityAction(label: String) {
        if(label=="缩小 XR 窗口（−6%）") {notice=windowScale(targetPaneHint,-1);return}
        if(label=="放大 XR 窗口（＋6%）") {notice=windowScale(targetPaneHint,1);return}
        val direct=session() ?: return
        val current=direct.settingsSnapshot(targetScreen).current
        fun set(next: org.openuuyc.pico.uu.UuCaptureSettings) {check(direct.applySettings(next,targetScreen=targetScreen)) {"控制通道未就绪"}}
        when(label) {
            "画质"->options("画质",listOf("高清","超清","原画","自动（原画）"),listOf(2,3,4,5).indexOf(current.quality).coerceAtLeast(0)) {set(current.copy(quality=listOf(2,3,4,5)[it]))}
            "帧率上限"->{
                val state=direct.settingsSnapshot(targetScreen)
                val source=state.screens.firstOrNull {it.id==state.selected}
                val ceiling=minOf(source?.fps ?: 0,state.profiles.filter {current.codec==0 || it.codec==current.codec}.maxOfOrNull {it.fps} ?: 0)
                val values=listOf(30,60,90,120,144,165,240).filter {it<=ceiling}
                options("帧率上限",values.map {"$it FPS"},values.indexOf(current.fps)) {set(current.copy(fps=values[it]))}
            }
            "编码偏好"->{
                val values=direct.settingsSnapshot(targetScreen).selectableProfiles()
                options("编码偏好",values.map {when(it.codec) {0->"自动";1->"H.264";2->"H.265";else->"编码 ${it.codec}"}},values.indexOfFirst {it.codec==current.codec}) {set(current.copy(codec=values[it].codec,fps=minOf(current.fps,values[it].fps),maxWidth=minOf(current.maxWidth,values[it].width),maxHeight=minOf(current.maxHeight,values[it].height)))}
            }
            "显示器分辨率与 DPI"->{
                val state=direct.settingsSnapshot(targetScreen)
                val source=state.screens.firstOrNull {it.id==state.selected} ?: return
                options("显示器分辨率与 DPI",source.modes.map {it.toString()}+source.dpis.map {"DPI $it%"},-1) {choice ->
                    if(choice<source.modes.size) check(direct.applySettings(current,physical=source.modes[choice],targetScreen=targetScreen)) {"控制通道未就绪"}
                    else check(direct.applySettings(current,dpi=source.dpis[choice-source.modes.size],targetScreen=targetScreen)) {"控制通道未就绪"}
                }
            }
            "高级设置"->{
                val state=direct.settingsSnapshot(targetScreen)
                val sizes=listOf(1280 to 720,1920 to 1080,2560 to 1440,3840 to 2160).filter {v->state.profiles.any {(current.codec==0 || current.codec==it.codec) && v.first<=it.width && v.second<=it.height}}
                options("高级设置 · 串流尺寸",sizes.map {"${it.first} × ${it.second}"},sizes.indexOf(current.maxWidth to current.maxHeight)) {val next=sizes[it];set(current.copy(maxWidth=next.first,maxHeight=next.second))}
            }
            "返回串流设置"->{if(targetScreen!=null) {targetScreen=null;targetPaneHint=null};advanced=false}
        }
    }

    private fun screenSettingAction(label: String) {
        val direct=session() ?: return
        val screen=targetScreen ?: return
        val state=direct.settingsSnapshot(screen)
        val source=state.screens.firstOrNull {it.id==screen} ?: return
        val current=state.current
        fun set(next: org.openuuyc.pico.uu.UuCaptureSettings) {check(direct.applySettings(next,targetScreen=screen)) {"控制通道未就绪"}}
        when(label) {
            "帧率上限"->{
                val ceiling=minOf(source.fps,state.profiles.filter {current.codec==0 || it.codec==current.codec}.maxOfOrNull {it.fps} ?: 0)
                val values=listOf(30,60,90,120,144,165,240).filter {it<=ceiling}
                options("帧率上限",values.map {"$it FPS"},values.indexOf(current.fps).coerceAtLeast(0)) {set(current.copy(fps=values[it]))}
            }
            "画质"->options("画质",listOf("高清","超清","原画","自动（原画）"),listOf(2,3,4,5).indexOf(current.quality).coerceAtLeast(0)) {set(current.copy(quality=listOf(2,3,4,5)[it]))}
            "编码偏好"->{
                val values=state.selectableProfiles()
                options("编码偏好",values.map {when(it.codec) {0->"自动";1->"H.264";2->"H.265";else->"编码 ${it.codec}"}},values.indexOfFirst {it.codec==current.codec}.coerceAtLeast(0)) {set(current.copy(codec=values[it].codec))}
            }
            "串流尺寸"->{
                val values=listOf(1280 to 720,1920 to 1080,2560 to 1440,3840 to 2160).filter {v->state.profiles.any {(current.codec==0 || current.codec==it.codec) && v.first<=it.width && v.second<=it.height}}
                options("串流尺寸",values.map {"${it.first} × ${it.second}"},values.indexOf(current.maxWidth to current.maxHeight).coerceAtLeast(0)) {val next=values[it];set(current.copy(maxWidth=next.first,maxHeight=next.second))}
            }
            "屏幕分辨率"->options("屏幕分辨率",source.modes.map {it.toString()},source.modes.indexOf(source.current)) {check(direct.applySettings(current,physical=source.modes[it],targetScreen=screen)) {"控制通道未就绪"}}
            "DPI 缩放"->options("DPI 缩放",source.dpis.map {"$it%"},source.dpis.indexOf(source.dpi)) {check(direct.applySettings(current,dpi=source.dpis[it],targetScreen=screen)) {"控制通道未就绪"}}
            "远端光标"->set(current.copy(cursor=!current.cursor))
            "创建虚拟屏"->{confirmingVirtual=true;notice="确认创建 1920×1080 虚拟屏？"}
            "收起屏幕设置"->{targetScreen=null;targetPaneHint=null;closeScreenMenu();notice="串流设置请从各窗口顶部展开"}
        }
    }

    private fun streamSettingAction(label: String) {
        when(label) {
            "跟随视角"->{if(!followPending) {session()?.releaseInputs();followPending=true;followView(!following);notice="正在切换跟随模式…"}}
            "重置空间布局"->{reset();notice="已重置六个桌面的空间布局"}
            "切换屏幕"->openScreens()
            "创建虚拟屏"->confirmVirtualScreen()
            "MR 透视"->mr=!mr
            "环境与背景"->{environmentPage=true;dropdownOffset=0;notice="环境 · MR 与背景";return}
            "主页面"->{closeScreenMenu();home()}
            "键盘与输入"->keyboardOptions()
            "应用与 VR 游戏"->click(7)
            "输入模式"->inputOptions()
            "断开全部连接"->disconnect()
        }
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
                    4 -> notice=windowScale(null,-1)
                    5 -> notice=windowScale(null,1)
                    6 -> {focusDots=!focusDots;focusDotsChanged(focusDots);notice=if(focusDots) "焦点圆点已开启" else "焦点圆点已隐藏；射线与点击保持可用"}
                    11 -> {spacePage=false;notice=if(targetScreen!=null) "已返回窗口设置" else "已返回主菜单"}
                }
                draw();return
            }
            if(targetScreen!=null && cell==5) {
                spacePage=true;notice="窗口尺寸调节 · 每次调整6%，保持比例；最小宽40cm，最大对角线100英寸";draw();return
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
                                    options("附加已有 VR 应用",listOf("附加当前应用 · 不启动或重启游戏","停止连接 · 不关闭游戏","状态：${direct.vr.status}","附加 SteamVR 运行中场景 · 双眼镜像"),-1) {choice ->
                                        if(choice==0) direct.quickApps.runningVr {apps,error->
                                            if(apps.isNullOrEmpty()) {
                                                val reason=error ?: "未检测到运行中的内置 OpenXR 应用；本操作不会自动启动游戏"
                                                status(reason);options("没有可附加的应用",listOf(reason,"返回"),-1) {};draw()
                                            } else direct.vr.prepareStereo {ready,message->
                                                status(if(ready) "PCVR 会话已连接，等待已有应用的真实双眼帧：${apps.joinToString()}；未启动新游戏" else message)
                                                draw()
                                            }
                                        } else if(choice==1) {direct.vr.request(false);status("已停止 PCVR 连接，未关闭游戏")}
                                        else if(choice==3) direct.vr.prepareSteamVr {ready,message->
                                            status(if(ready) "已附加 SteamVR 运行中场景；等待真实双眼帧，不会启动游戏" else message)
                                            draw()
                                        }
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
                    10 -> { menuScreens=direct.screenSnapshot();screenOffset=screenOffset.coerceAtMost(PicoXrDropdownLayout.pageOf((menuScreens.size-1).coerceAtLeast(0)));notice="选择远端屏幕 · 当前项显示勾选" }
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
                    9 -> {val values=listOf(1280 to 720,1920 to 1080,2560 to 1440,3840 to 2160).filter {v->state.profiles.any {(current.codec==0 || current.codec==it.codec) && v.first<=it.width && v.second<=it.height}};options("串流尺寸",values.map {"${it.first} × ${it.second}"},values.indexOf(current.maxWidth to current.maxHeight)) {val next=values[it];set(current.copy(maxWidth=next.first,maxHeight=next.second))}}
                    6 -> options("屏幕分辨率",source.modes.map {it.toString()},source.modes.indexOf(source.current)) {check(direct.applySettings(current,physical=source.modes[it],targetScreen=screen)) {"控制通道未就绪"}}
                    7 -> options("DPI 缩放",source.dpis.map {"$it%"},source.dpis.indexOf(source.dpi)) {check(direct.applySettings(current,dpi=source.dpis[it],targetScreen=screen)) {"控制通道未就绪"}}
                    8 -> {confirmingVirtual=true;notice="确认创建 1920×1080 虚拟屏？"}
                    10 -> notice="${source.name} · ${source.current} · DPI ${source.dpi}%"
                    11 -> {targetScreen=null;targetPaneHint=null;closeScreenMenu();notice="串流设置请从各窗口顶部展开"}
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
        return if(backgroundPage) PicoBackgroundPattern.presets.map {"${if(!customImage && selectedPreset==it.id) "✓ " else ""}${it.label}${if(it.procedural) " · 程序生成" else ""}"}+listOf("","返回环境设置") else if(environmentPage) listOf(if(mr) "MR 透视：开启" else "MR 透视：关闭","透视可见度：${mrVisibility}%","选择图片 / 全景图…",if(customImage) "图片背景：开启" else "图片背景：关闭","背景：全黑","背景：深灰","背景：深蓝","背景库 · 更多…","重新生成图案","背景亮度：${brightness}%",if(backgroundFlow) "背景流动：开" else "背景流动：关","返回主菜单") else if(spacePage) spaceLabels() else if(pendingTakeover!=null) listOf("确认抢占","取消抢占") else if(devicePage) (0..11).map {i -> when(i) {
                8 -> "上一页";9 -> "下一页";10 -> "刷新设备";11 -> "返回串流"
                else -> devices.getOrNull(deviceOffset+i)?.let { "${it.alias} · ${if(it.online && it.controllable) "沉浸式连接" else "不可连接"}" } ?: ""
            }} else if(keyboardPage) emptyList() else if(screenPage) (0..11).map { index -> when(index) {
                10 -> "刷新屏幕";11 -> "返回设置";else -> menuScreens.getOrNull(index)?.let {
                    "${if(it.id==session()?.selectedScreen()) "✓ " else ""}${it.name.ifBlank { "屏幕 ${it.id+1}" }} ${it.current ?: ""}"
                } ?: ""
            }} else if(confirmingVirtual) listOf("确认创建","取消","取消","取消") else if(targetScreen!=null) listOf("帧率 ${c?.fps}","画质 ${c?.quality}","码率 ${c?.bitrateMbps}M","编码：自动","远端光标","窗口大小调整","屏幕分辨率","DPI 缩放","创建虚拟屏","串流尺寸","屏幕信息","收起设置") else listOf(
                if(followPending) "跟随：切换中" else if(following) "跟随视角：开" else "跟随视角：关", "重置布局", "切换屏幕", "创建虚拟屏",
                if(mr) "MR：开" else "MR：关", "背景：${listOf("全黑","深灰","深蓝")[backgroundIndex]}", "主页面", "操作帮助",
                "键盘", "最大100英寸", if(usingTouch) "输入：触摸" else "输入：鼠标", "断开全部")
    }

    private fun spaceLabels(): List<String> = listOf(
        if(followPending) "跟随：切换中" else if(following) "跟随视角：开" else "跟随视角：关",
        "重置到当前视角",
        "跟随视角",
        "固定窗口",
        "缩小窗口（−6%）",
        "放大窗口（＋6%）",
        if(focusDots) "焦点圆点：开" else "焦点圆点：关（默认）",
        when(controllerHand) {0->"主菜单：左手柄";1->"主菜单：右手柄";else->"主菜单：悬浮"},
        "", "", "",
        if(targetScreen!=null) "返回窗口设置" else "返回主菜单"
    )
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
                // All sub pages are dropdown panels now (except keyboard pages).
                val rows=when {
                    optionTitle!=null->optionRows()
                    (devicePage || screenPage) && pendingTakeover==null->selectionRows()
                    qualityPage()->qualityRows()
                    else->dropdownRows()
                }
                val title=if(optionTitle!=null) optionTitle!! else dropdownTitle()
                PicoXrDropdownCanvas.draw(canvas,title,rows,if(optionTitle!=null) optionOffset else dropdownOffset,hoverU,hoverV,pressed,notice)
                return
            }
            // Keyboard pages keep their dedicated full-panel layout.
            PicoKeyboardCanvas.draw(canvas,keyboardMode,keyboardKeys,hoverU,hoverV,voiceActive)

        } catch (_: IllegalArgumentException) { /* runtime has destroyed the swapchain */ }
        catch (_: IllegalStateException) { /* Activity teardown */ }
        finally { canvas?.let { runCatching { surface.unlockCanvasAndPost(it) } } }
    }
    fun releaseKeyboardKeys() {keyboardKeys.release();draw()}
    fun close() { closeTerminal();voiceCancel();optionApply=null;keyboardKeys.release();surface.release() }
}

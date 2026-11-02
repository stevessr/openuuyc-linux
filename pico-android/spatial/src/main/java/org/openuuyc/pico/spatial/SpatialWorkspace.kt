package org.openuuyc.pico.spatial

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SearchField
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.windows.Subwindow
import com.pico.spatial.ui.design.windows.Toolbar
import com.pico.spatial.ui.design.windows.SpatialPopup
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import org.openuuyc.pico.PicoPanelVideoView
import org.openuuyc.pico.R
import org.openuuyc.pico.uu.UuDevice

/** Official SDK windows/controls; no manual OpenXR menu atlas or hand pose logic. */
fun openUuSpatialApp(scope: SpatialAppScope)=with(scope) {
    DefaultWindowContainer {
        PicoTheme {
            val context=LocalContext.current
            val controller=remember {SpatialSessionController(context)}
            DisposableEffect(controller) {onDispose {controller.close()}}
            var page by remember {mutableIntStateOf(0)}
            var takeover by remember {mutableStateOf<UuDevice?>(null)}
            Row(Modifier.windowConstraints(minWidth=1280.dp,minHeight=720.dp).fillMaxSize().background(WorkspaceColors.background)) {
                Column(Modifier.width(240.dp).fillMaxHeight().background(WorkspaceColors.panel).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    Image(painterResource(R.drawable.openuuyc_brand),"OpenUUYC",Modifier.size(64.dp))
                    SectionLabel("OpenUUYC","PICO Spatial · OS 6")
                    Spacer(Modifier.height(16.dp))
                    listOf("设备","已打开窗口","关于与帮助").forEachIndexed {index,label ->
                        ChoiceButton(label,page==index,modifier=Modifier.fillMaxWidth()) {page=index}
                    }
                    Spacer(Modifier.weight(1f))
                    Text(if(controller.initializing) "恢复登录中…" else if(controller.signedIn) controller.nickname.ifBlank {"UU 账号已登录"} else "尚未登录",color=WorkspaceColors.muted)
                    Text("${controller.windows.size} / 6 个窗口",color=WorkspaceColors.muted)
                    Text("Spatial Preview",style=PicoTheme.typography.bodyMedium,color=WorkspaceColors.muted)
                }
                Column(Modifier.weight(1f).fillMaxHeight().padding(32.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        SectionLabel(listOf("我的设备","已打开窗口","关于与帮助")[page],if(page==0) "选择设备，打开独立空间串流" else if(page==1) "各窗口拥有独立工具栏与画质菜单" else "官方 PICO Spatial SDK 后端")
                        if(controller.signedIn) Button(onClick={controller.refresh()},enabled=!controller.refreshing && !controller.initializing) {Text(if(controller.refreshing) "正在刷新…" else "刷新设备")}
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when {
                            page==2 -> WorkspaceAbout()
                            controller.initializing -> WorkspaceCard(Modifier.fillMaxWidth()) {SectionLabel("正在恢复登录","读取本机加密凭证并验证账号，请稍候。")}
                            !controller.signedIn -> WorkspaceLogin(controller)
                            page==1 -> WorkspaceWindows(controller)
                            else -> WorkspaceDevices(controller) {device -> if(device.occupants>0) takeover=device else controller.connect(device)}
                        }
                    }
                    Text(controller.status,style=PicoTheme.typography.bodyMedium,color=if(controller.lastError.isNotBlank()) WorkspaceColors.warning else WorkspaceColors.muted)
                }
            }
            takeover?.let {device -> SpatialPopup(onDismissRequest={takeover=null},defaultMinWidth=520.dp) {
                WorkspaceCard(Modifier.width(520.dp)) {
                    MenuHeader("确认抢占",device.alias) {takeover=null}
                    Text("此设备正被使用。继续连接会断开现有会话。",color=WorkspaceColors.warning)
                    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Button(onClick={controller.connect(device,true);takeover=null}) {Text("确认抢占")}
                        ChoiceButton("取消") {takeover=null}
                    }
                }
            }}
            controller.windows.forEach {window -> key(window.id) {
                StreamSubwindow(controller,window) {page=0}
            }}
        }
    }
}

@Composable
private fun WorkspaceLogin(controller: SpatialSessionController) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(24.dp)) {
        WorkspaceCard(Modifier.weight(1f)) {
            SectionLabel("连接你的远程设备","使用手机 UU 扫码，在手机上确认登录。")
            Spacer(Modifier.height(16.dp))
            Text("登录信息加密保存在本机，下次启动自动恢复。",color=WorkspaceColors.muted)
            Text("空间串流直接连接 UU 设备，不需要桌面中转服务。",color=WorkspaceColors.muted)
            Spacer(Modifier.height(20.dp))
            if(controller.canRetryRestore) ChoiceButton("重试恢复已有登录",enabled=!controller.loginPending) {controller.retryRestore()}
            Button(onClick={controller.login()},enabled=!controller.loginPending) {Text(if(controller.loginPending) "等待手机确认…" else "生成登录二维码")}
            if(controller.loginPending) ChoiceButton("取消登录") {controller.cancelLogin()}
        }
        WorkspaceCard(Modifier.width(368.dp)) {
            Box(Modifier.size(320.dp).background(Color.White)) {
                controller.qr?.let {Image(it.asImageBitmap(),"用手机 UU 扫描此二维码",Modifier.fillMaxSize())}
                    ?: Text(if(controller.loginPending) "正在生成二维码…" else "点击左侧生成二维码",modifier=Modifier.padding(24.dp),color=Color.DarkGray)
            }
            Text("二维码有效期约 5 分钟",color=WorkspaceColors.muted)
        }
    }
}

@Composable
private fun WorkspaceDevices(controller: SpatialSessionController,connect: (UuDevice)->Unit) {
    var query by remember {mutableStateOf("")}
    var filter by remember {mutableStateOf(DeviceFilter.ALL)}
    val connected=controller.windows.map {it.device.id}.toSet()
    val shown=SpatialUiModel.devices(controller.devices,query,filter,connected)
    Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
        SearchField(value=query,onValueChange={query=it},onSearch={},placeholder={Text("搜索设备名称或 ID")},modifier=Modifier.fillMaxWidth())
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            DeviceFilter.entries.forEach {choice -> ChoiceButton(choice.label,choice==filter) {filter=choice}}
            Text("${shown.size} 台",modifier=Modifier.padding(12.dp),color=WorkspaceColors.muted)
        }
        if(shown.isEmpty()) WorkspaceCard(Modifier.fillMaxWidth()) {
            SectionLabel(if(controller.refreshing) "正在刷新设备…" else if(query.isNotBlank()) "没有匹配设备" else "这里还没有设备",if(query.isNotBlank()) "尝试其他名称，或清空搜索与筛选。" else "请确认主机在线并登录同一个 UU 账号，然后刷新设备。")
            ChoiceButton("清除搜索与筛选") {query="";filter=DeviceFilter.ALL}
        }
        LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            items(shown,key={it.id}) {device ->
                WorkspaceCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(20.dp)) {
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text(device.alias,style=PicoTheme.typography.titleMedium)
                            Text(when {device.id in connected -> "● 已打开空间窗口";!device.online -> "● 离线";!device.controllable -> "● 未开放控制";device.occupants>0 -> "● 使用中 · 连接需确认抢占";else -> "● 在线 · 可连接"},color=when {device.id in connected -> WorkspaceColors.accent;!device.online || !device.controllable -> WorkspaceColors.muted;device.occupants>0 -> WorkspaceColors.warning;else -> WorkspaceColors.online})
                            Text("设备 ID · ${device.id}",style=PicoTheme.typography.bodyMedium,color=WorkspaceColors.muted)
                        }
                        val opening=device.id in controller.opening
                        Button(onClick={connect(device)},enabled=device.online && device.controllable && device.id !in connected && !opening && controller.windows.size+controller.opening.size<6) {
                            Text(when {opening -> "正在连接…";device.id in connected -> "已打开";device.occupants>0 -> "抢占连接…";else -> "打开串流"})
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceWindows(controller: SpatialSessionController) {
    if(controller.windows.isEmpty()) WorkspaceCard(Modifier.fillMaxWidth()) {SectionLabel("尚未打开串流","在设备页面选择一台在线设备，即可创建空间窗口。")}
    else LazyColumn(verticalArrangement=Arrangement.spacedBy(16.dp)) {items(controller.windows,key={it.id}) {window ->
        WorkspaceCard(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                SectionLabel("${window.device.alias} - ${window.screenName}","${window.width} × ${window.height}")
                ChoiceButton("断开并关闭") {controller.remove(window.id)}
            }
            Text(window.status,color=WorkspaceColors.muted)
        }
    }}
}

@Composable
private fun WorkspaceAbout() {
    Column(verticalArrangement=Arrangement.spacedBy(20.dp)) {
        WorkspaceCard(Modifier.fillMaxWidth()) {
            SectionLabel("原生空间工作台","PICO Spatial SDK 6.1.9 · Android 15 / PICO OS 6")
            Text("设备列表始终保留；各串流窗口拥有独立的设置弹窗和关闭操作。")
            Text("用窗口系统提供的空间交互移动和调整窗口，远程桌面保持真实比例。",color=WorkspaceColors.muted)
        }
        WorkspaceCard(Modifier.fillMaxWidth()) {
            SectionLabel("预览版验证边界")
            Text("OS 5 兼容版继续保留。OS 6 出画、原生拖动与输入需要对应设备验证。",color=WorkspaceColors.muted)
            Text("窗口单层合成和作为 UU 被控端尚未实现。",color=WorkspaceColors.muted)
        }
    }
}

@Composable
private fun StreamSubwindow(controller: SpatialSessionController,window: SpatialSessionController.StreamWindow,home: ()->Unit) {
    var popup by remember(window.id) {mutableIntStateOf(0)}
    var touch by remember(window.id) {mutableStateOf(false)}
    DisposableEffect(window.id) {onDispose {window.session.releaseInputs()}}
    Subwindow {
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            Row(Modifier.fillMaxWidth().background(WorkspaceColors.background).padding(12.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                Text("${window.device.alias} - ${window.screenName}",style=PicoTheme.typography.titleMedium)
                Text("${window.width}×${window.height}",color=WorkspaceColors.muted)
            }
            AndroidView(factory={context -> PicoPanelVideoView(context,
                attach={controller.attach(window,it)},detach={window.session.releaseInputs()},
                pointer={u,v,down -> window.session.pointer(0,u,v,down)},scroll={window.session.scroll(it)},
                touch={kind,contacts -> window.session.touch(kind,contacts)})},
                modifier=Modifier.fillMaxWidth().weight(1f),
                update={it.touchMode=touch;it.setVideoSize(window.width,window.height);it.showStatus(window.status)})
        }
        Toolbar {
            ChoiceButton("设备列表",onClick=home)
            ChoiceButton(if(touch) "触摸" else "鼠标",touch) {window.session.releaseInputs();touch=!touch}
            var micRequested by remember {mutableStateOf(window.session.microphoneEnabled)}
            var micActive by remember {mutableStateOf(window.session.microphoneActive)}
            var clipboard by remember {mutableStateOf(window.session.clipboardEnabled)}
            LaunchedEffect(window.id) {while(true) {
                micRequested=window.session.microphoneEnabled;micActive=window.session.microphoneActive;clipboard=window.session.clipboardEnabled
                kotlinx.coroutines.delay(500)
            }}
            ChoiceButton(if(micActive) "麦克风：开" else if(micRequested) "麦克风…" else "麦克风",micRequested) {
                if(micRequested) window.session.setMicrophoneEnabled(false)
                else org.openuuyc.pico.uu.UuMicrophonePermissionActivity.request(controller.context()) {granted -> if(granted) window.session.setMicrophoneEnabled(true)}
            }
            ChoiceButton("剪贴板",clipboard) {window.session.setClipboardEnabled(!window.session.clipboardEnabled)}
            Box {
                ChoiceButton("显示器 ▾",popup==2) {popup=if(popup==2) 0 else 2}
                if(popup==2) SpatialPopup(onDismissRequest={popup=0},defaultMinWidth=500.dp) {SpatialScreenMenu(window) {popup=0}}
            }
            Box {
                ChoiceButton("画质 ▾",popup==1) {popup=if(popup==1) 0 else 1}
                if(popup==1) SpatialPopup(onDismissRequest={popup=0},defaultMinWidth=560.dp) {SpatialStreamSettings(window) {popup=0}}
            }
            ChoiceButton("断开并关闭") {controller.remove(window.id)}
        }
    }
}

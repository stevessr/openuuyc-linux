package org.openuuyc.pico.spatial

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.Switch
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.TextField
import org.openuuyc.pico.uu.UuCaptureSettings
import org.openuuyc.pico.uu.UuDisplayMode
import kotlinx.coroutines.delay

private enum class SettingsPage(val title: String) { QUALITY("画质"), DISPLAY("显示器分辨率与 DPI"), ADVANCED("高级设置"), CODEC("编码偏好"), BITRATE("自定义码率") }

/** Each official Subwindow owns this popup, including navigation and pending state. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SpatialStreamSettings(window: SpatialSessionController.StreamWindow,onClose: ()->Unit) {
    val session=window.session
    var state by remember(window.id) {mutableStateOf(session.settingsSnapshot(session.windowScreen(0)))}
    var page by remember {mutableStateOf(SettingsPage.QUALITY)}
    var result by remember {mutableStateOf("")}
    var bitrate by remember {mutableStateOf(state.current.bitrateMbps.toString())}
    LaunchedEffect(window.id) {while(true) {state=session.settingsSnapshot(session.windowScreen(0));delay(700)}}
    val source=state.screens.firstOrNull {it.id==state.selected}
    val ready=state.profiles.isNotEmpty() && source!=null && state.pending==null
    fun apply(next: UuCaptureSettings=state.current,mode: UuDisplayMode?=null,dpi: Int=0) {
        result=runCatching {
            val target=session.windowScreen(0) ?: error("等待屏幕映射")
            check(session.applySettings(next,physical=mode,dpi=dpi,targetScreen=target)) {"控制通道未就绪"}
            state=session.settingsSnapshot(target)
            "已发送，等待远端确认"
        }.getOrElse {it.message ?: "设置未完成"}
    }
    Column(Modifier.width(560.dp).heightIn(max=780.dp).padding(24.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        MenuHeader(page.title,"${window.device.alias} - ${window.screenName}",onClose)
        if(page!=SettingsPage.QUALITY) ChoiceButton("‹ 返回画质") {page=SettingsPage.QUALITY}
        when(page) {
            SettingsPage.QUALITY -> {
                val codecLabel=when(state.current.codec) {0 -> "自动";1 -> "H.264";2 -> "H.265";5 -> "AV1";else -> "未知"}
                ChoiceButton("此设备编码偏好                     $codecLabel ›",modifier=Modifier.fillMaxWidth()) {page=SettingsPage.CODEC}
                listOf(5 to "自动（原画）",4 to "原画",3 to "超清",2 to "高清").forEach {(quality,label) ->
                    ChoiceButton("${if(state.current.quality==quality) "✓  " else "     "}$label",state.current.quality==quality,ready,Modifier.fillMaxWidth()) {apply(state.current.copy(quality=quality))}
                }
                ChoiceButton("自定义码率                   ${state.current.bitrateMbps} Mbps ›",state.current.quality==6,ready,Modifier.fillMaxWidth()) {bitrate=state.current.bitrateMbps.toString();page=SettingsPage.BITRATE}
                ChoiceButton("显示器分辨率与 DPI                     ›",modifier=Modifier.fillMaxWidth()) {page=SettingsPage.DISPLAY}
                SectionLabel("帧率上限","当前 ${state.current.fps} FPS · 仅列出双方可用预设")
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    SpatialUiModel.fpsChoices(state).forEach {fps -> ChoiceButton("$fps",state.current.fps==fps,ready) {apply(state.current.copy(fps=fps))}}
                }
                SectionLabel("色度与动态范围","YUV 4:2:0 · HDR / 4:4:4 尚未开放")
                ChoiceButton("高级设置                                  ›",modifier=Modifier.fillMaxWidth()) {page=SettingsPage.ADVANCED}
            }
            SettingsPage.CODEC -> {
                SectionLabel("选择编码","只显示双方支持的编码；默认自动协商。")
                if(state.profiles.isEmpty()) Text("等待双方编码能力…",color=WorkspaceColors.muted)
                state.selectableProfiles().forEach {profile ->
                    ChoiceButton(profile.toString(),state.current.codec==profile.codec,ready,Modifier.fillMaxWidth()) {
                        apply(state.current.copy(codec=profile.codec,
                            maxWidth=minOf(state.current.maxWidth,profile.width),maxHeight=minOf(state.current.maxHeight,profile.height),
                            fps=minOf(state.current.fps,profile.fps,source?.fps ?: profile.fps)))
                    }
                }
            }
            SettingsPage.DISPLAY -> {
                SectionLabel(source?.name ?: "等待显示器信息","当前 ${source?.current ?: "未知"} · DPI ${source?.dpi ?: 0}%")
                if(state.captureLevel<5) Text("远端未开放物理分辨率 / DPI 修改。",color=WorkspaceColors.warning)
                SectionLabel("物理分辨率")
                if(source?.modes.isNullOrEmpty()) Text("远端没有提供可切换模式",color=WorkspaceColors.muted)
                source?.modes.orEmpty().forEach {mode ->
                    ChoiceButton(mode.toString(),source?.current==mode,ready && state.captureLevel>=5,Modifier.fillMaxWidth()) {apply(mode=mode)}
                }
                SectionLabel("显示缩放（DPI）")
                if(source?.dpis.isNullOrEmpty()) Text("远端没有提供缩放档位",color=WorkspaceColors.muted)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    source?.dpis.orEmpty().forEach {dpi -> ChoiceButton("$dpi%",source?.dpi==dpi,ready && state.captureLevel>=5) {apply(dpi=dpi)}}
                }
            }
            SettingsPage.BITRATE -> {
                SectionLabel("自定义码率","Mbps · 实际可用上限以远端确认结果为准")
                TextField(value=bitrate,onValueChange={bitrate=it.filter(Char::isDigit).take(3)},modifier=Modifier.fillMaxWidth(),placeholder={Text("输入 1–500 Mbps")})
                Button(onClick={apply(state.current.copy(quality=6,bitrateMbps=bitrate.toIntOrNull() ?: 0))},enabled=ready && bitrate.toIntOrNull() in 1..500) {Text("应用码率")}
            }
            SettingsPage.ADVANCED -> {
                SectionLabel("串流尺寸上限","仅列出双方编码能力支持的尺寸")
                val sizes=state.profiles.filter {state.current.codec==0 || it.codec==state.current.codec}.map {it.width to it.height}.distinct().sortedBy {it.first*it.second}
                sizes.forEach {(w,h) -> ChoiceButton("$w × $h",state.current.maxWidth==w && state.current.maxHeight==h,ready,Modifier.fillMaxWidth()) {apply(state.current.copy(maxWidth=w,maxHeight=h))}}
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    SectionLabel("显示远端光标")
                    Switch(checked=state.current.cursor,enabled=ready,onCheckedChange={if(ready) apply(state.current.copy(cursor=it))})
                }
                Text("虚拟屏与切屏请使用工具栏的“显示器”菜单。",color=WorkspaceColors.muted)
            }
        }
        Text(if(state.pending!=null) "正在等待远端确认…" else window.settingsStatus.ifBlank {result.ifBlank {"修改仅作用于当前屏幕"}},color=WorkspaceColors.muted)
    }
}

@Composable
internal fun SpatialScreenMenu(window: SpatialSessionController.StreamWindow,onClose: ()->Unit) {
    val session=window.session
    var state by remember(window.id) {mutableStateOf(session.settingsSnapshot())}
    var confirmCreate by remember {mutableStateOf(false)}
    var result by remember {mutableStateOf("")}
    LaunchedEffect(window.id) {while(true) {state=session.settingsSnapshot();delay(700)}}
    Column(Modifier.width(500.dp).heightIn(max=720.dp).padding(24.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        MenuHeader("显示器",window.device.alias,onClose)
        if(state.screens.isEmpty()) Text("等待远端屏幕列表…",color=WorkspaceColors.muted)
        state.screens.forEach {screen ->
            ChoiceButton("${if(screen.id==state.selected) "✓ " else ""}${screen.name} · ${screen.current ?: "未知尺寸"}",screen.id==state.selected,modifier=Modifier.fillMaxWidth()) {
                result=if(session.switchScreen(screen.id)) "正在切换，等待目标画面…" else "通道未就绪，切屏未发送"
            }
        }
        ChoiceButton("＋ 创建虚拟显示器…",enabled=state.captureLevel>=5,modifier=Modifier.fillMaxWidth()) {confirmCreate=true}
        if(state.captureLevel<5) Text("此主机未开放虚拟显示器能力",color=WorkspaceColors.muted)
        if(confirmCreate) WorkspaceCard(Modifier.fillMaxWidth()) {
            SectionLabel("创建虚拟显示器？","将在远端创建 1920 × 1080 显示器。")
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick={confirmCreate=false;result=runCatching {check(session.createVirtualDisplay());"请求已发出，等待远端屏幕列表"}.getOrElse {it.message ?: "创建未完成"}}) {Text("确认创建")}
                ChoiceButton("取消") {confirmCreate=false}
            }
        }
        if(result.isNotBlank()) Text(result,color=WorkspaceColors.muted)
    }
}

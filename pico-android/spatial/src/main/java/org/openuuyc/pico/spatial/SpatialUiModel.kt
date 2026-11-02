package org.openuuyc.pico.spatial

import org.openuuyc.pico.uu.UuDevice
import org.openuuyc.pico.uu.UuRemoteSettingsState

internal enum class DeviceFilter(val label: String) { ALL("全部设备"), ONLINE("在线设备"), CONNECTED("已打开") }
internal object SpatialUiModel {
    fun devices(source: List<UuDevice>,query: String,filter: DeviceFilter,connected: Set<String>): List<UuDevice> {
        val text=query.trim()
        return source.filter {device ->
            (text.isBlank() || device.alias.contains(text,true) || device.id.contains(text,true)) &&
                when(filter) {DeviceFilter.ALL -> true;DeviceFilter.ONLINE -> device.online;DeviceFilter.CONNECTED -> device.id in connected}
        }.sortedWith(compareByDescending<UuDevice> {it.id in connected}.thenByDescending {it.online}.thenBy {it.alias.lowercase()})
    }
    fun fpsChoices(state: UuRemoteSettingsState): List<Int> {
        val screen=state.screens.firstOrNull {it.id==state.selected} ?: return emptyList()
        val ceiling=minOf(screen.fps,state.profiles.filter {state.current.codec==0 || it.codec==state.current.codec}.maxOfOrNull {it.fps} ?: 0)
        return listOf(240,165,144,120,90,60,30).filter {it<=ceiling}
    }
}

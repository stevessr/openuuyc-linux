package org.openuuyc.pico

import org.openuuyc.pico.uu.UuDevice

/** Presentation-only state: no account credentials, requests or XR ownership. */
internal class PicoPanelState {
    enum class Page { DEVICES, SETTINGS, ABOUT }
    enum class Filter { ALL, ONLINE, OFFLINE }
    var page = Page.DEVICES
    var filter = Filter.ALL
    var query = ""
    var sortByName = false
    var devices: List<UuDevice> = emptyList()
    fun visibleDevices(): List<UuDevice> {
        val matching = devices.filter { device ->
            device.alias.contains(query.trim(), ignoreCase = true) && when (filter) {
                Filter.ALL -> true
                Filter.ONLINE -> device.online
                Filter.OFFLINE -> !device.online
            }
        }
        return if (sortByName) matching.sortedBy { it.alias.lowercase() }
        else matching.sortedWith(compareByDescending<UuDevice> { it.online }.thenBy { it.alias.lowercase() })
    }
    fun clear() {
        devices = emptyList(); page = Page.DEVICES; filter = Filter.ALL; query = ""; sortByName = false
    }
    companion object {
        fun canConnect(device: UuDevice) = device.online && device.controllable && device.occupants == 0
    }
}

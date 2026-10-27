package org.openuuyc.pico.uu

/**
 * Ordered UU signaling-server failover. A room token may authorize multiple
 * endpoints. Changing endpoints is permitted only *before* namespace login;
 * after room control begins it would risk resurrecting an invalid session.
 */
internal class UuSignalEndpoints(raw: List<String>) {
    private val endpoints = raw.map(UuWire::signalEndpoint).distinct()
    private var index = 0
    init { require(endpoints.isNotEmpty()) }

    val current: String get() = endpoints[index]
    val position: Int get() = index + 1
    val count: Int get() = endpoints.size

    fun nextBeforeNamespace(): Boolean {
        if (index + 1 >= endpoints.size) return false
        index++
        return true
    }
}

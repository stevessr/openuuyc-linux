package org.openuuyc.pico.uu

/**
 * Ordered UU signaling-server failover. A room token may authorize multiple
 * endpoints. Changing endpoints is permitted only *before* namespace login;
 * after room control begins it would risk resurrecting an invalid session.
 */
internal class UuSignalEndpoints(raw: List<String>) {
    // Deduplicate raw authorized room endpoints before adding the ephemeral
    // Engine.IO t query parameter. Generating t during construction would
    // make duplicates nondeterministic across millisecond boundaries.
    private val endpoints = raw.distinct()
    private var index = 0
    init {
        require(endpoints.isNotEmpty())
        // Fail closed on insecure or invalid roster entries before opening any
        // sockets; generated t values are not used for endpoint identity.
        endpoints.forEach(UuWire::signalEndpoint)
    }

    val current: String get() = UuWire.signalEndpoint(endpoints[index])
    val position: Int get() = index + 1
    val count: Int get() = endpoints.size

    fun nextBeforeNamespace(): Boolean {
        if (index + 1 >= endpoints.size) return false
        index++
        return true
    }
}

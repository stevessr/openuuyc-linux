package org.openuuyc.pico.uu

/** Correlated start/stop acknowledgements; stale/unsolicited status cannot grant consent. */
internal class UuPcvrControlState {
    private var serial=0
    var pending=0;private set
    var desired=false;private set
    var active=false;private set
    fun request(start: Boolean): Int {
        desired=start;active=false
        serial=if(serial==Int.MAX_VALUE) 1 else serial+1
        pending=serial;return pending
    }
    fun receive(id: Int,tracking: Boolean,error: Boolean): Boolean {
        if(pending!=0) {
            if(id!=pending) return false
            pending=0
            active=desired && tracking && !error
            if(!active) desired=false
        } else if(id==0) {
            // A heartbeat can revoke consent/readiness, never create it.
            active=active && desired && tracking && !error
            if(!active) desired=false
        } else return false
        return true
    }
    fun timeout(id: Int): Boolean {
        if(id!=pending) return false
        reset();return true
    }
    fun reset() {pending=0;desired=false;active=false}
}

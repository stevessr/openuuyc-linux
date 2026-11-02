package org.openuuyc.pico.uu

/** No samples may leave until permission, foreground, policy ACK and MicOpened agree. */
internal class UuMicrophoneState {
    @Volatile var requested=false;private set
    @Volatile var generation=0L;private set
    private var sequence: Long?=null
    private var acknowledged=false
    private var opened=false
    @Synchronized fun request(id: Long,enabled: Boolean) {generation++;requested=enabled;sequence=id;acknowledged=false;opened=false}
    @Synchronized fun response(id: Long,code: Int) {
        if(sequence!=id) return
        sequence=null;acknowledged=code==0 && requested
        if(code!=0) requested=false
    }
    @Synchronized fun event(action: Int) {when(action) {23 -> if(requested) opened=true;24 -> opened=false;27 -> stop()}}
    @Synchronized fun mayCapture(permission: Boolean,focused: Boolean,negotiated: Boolean)=requested && acknowledged && opened && permission && focused && negotiated
    @Synchronized fun stop() {generation++;requested=false;sequence=null;acknowledged=false;opened=false}
}

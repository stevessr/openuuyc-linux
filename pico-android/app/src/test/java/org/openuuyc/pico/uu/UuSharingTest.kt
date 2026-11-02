package org.openuuyc.pico.uu
import org.junit.Assert.*
import org.junit.Test

class UuSharingTest {
    private fun Map<Int,List<UuWire.Field>>.data(n: Int)=get(n)?.firstOrNull()?.data
    private fun Map<Int,List<UuWire.Field>>.num(n: Int)=get(n)?.firstOrNull()?.number ?: 0
    private fun field(bytes: ByteArray,n: Int)=UuWire.fields(bytes).data(n)!!
    private fun msg(tag: Int,payload: ByteArray)=UuWire.message {UuWire.bytes(it,tag,payload)}
    private fun num(tag: Int,value: Long)=UuWire.message {UuWire.integer(it,tag,value)}
    @Test fun microphoneNeverCapturesBeforeAllGatesAndIgnoresStaleReplies() {
        val s=UuMicrophoneState();s.request(1,true)
        assertFalse(s.mayCapture(true,true,true));s.event(23)
        assertFalse(s.mayCapture(true,true,true));s.response(2,0)
        assertFalse(s.mayCapture(true,true,true));s.response(1,0)
        assertTrue(s.mayCapture(true,true,true))
        assertFalse(s.mayCapture(false,true,true));assertFalse(s.mayCapture(true,false,true));assertFalse(s.mayCapture(true,true,false))
        val generation=s.generation;s.stop();assertTrue(s.generation>generation)
        s.response(1,0);s.event(23);assertFalse(s.mayCapture(true,true,true))
    }
    @Test fun microphoneRejectAndDriverCloseStopSamples() {
        val s=UuMicrophoneState();s.request(1,true);s.response(1,0);s.event(23);assertTrue(s.mayCapture(true,true,true))
        s.event(24);assertFalse(s.mayCapture(true,true,true));s.event(23);s.event(27);assertFalse(s.requested)
        s.request(2,true);s.event(23);s.response(2,123);assertFalse(s.requested)
    }
    @Test fun microphonePolicyAndAckUseOfficialRpcFields() {
        assertEquals(1L,UuWire.fields(field(field(UuWire.microphonePolicy(17,true),21),21)).num(1))
        val reply=msg(22,msg(1,num(1,17))+msg(17,num(1,123)))
        assertEquals(17L to 123,UuWire.decodeEnvelope(reply).microphoneAck)
        assertEquals(23,UuWire.decodeEnvelope(msg(3,num(1,23))).simpleAction)
    }
    @Test fun clipboardPublishesTextNotKeystrokesAndRejectsOversizedData() {
        val sent=mutableListOf<ByteArray>();val c=UuClipboardWire({7},{sent.add(it);true},{_,done -> done(true)})
        assertTrue(c.text("你好 clipboard"))
        val text=UuWire.fields(field(field(sent.single(),21),10))
        assertEquals(13L,text.num(1));assertEquals("你好 clipboard",text.data(2)!!.toString(Charsets.UTF_8))
        assertFalse(c.text("x".repeat(40000)));assertFalse(c.text("a\u0000b"))
    }
    @Test fun disabledClipboardRejectsLegacyRemoteTextWithoutWriting() {
        var writes=0;val sent=mutableListOf<ByteArray>()
        val c=UuClipboardWire({1},{sent.add(it);true},{_,done -> writes++;done(true)})
        assertTrue(c.receive(UuWire.rpc(4,10,num(1,13)+msg(2,"secret fixture".toByteArray())),false))
        assertEquals(0,writes);assertEquals(2L,UuWire.fields(field(field(sent.single(),22),6)).num(1))
    }
    @Test fun unicodeFormatTransferHasCorrelatedKeyAndSequentialBlocks() {
        val sent=mutableListOf<ByteArray>();val values=mutableListOf<String>()
        val c=UuClipboardWire({50},{sent.add(it);true},{text,done -> values.add(text);done(true)})
        val offer=UuWire.rpc(7,9,msg(1,msg(1,num(1,13))))
        assertTrue(c.receive(offer,true))
        val key=UuWire.fields(field(field(field(sent.last(),21),9),2)).data(2)!!
        val confirm=msg(22,msg(5,msg(2,num(1,1)+msg(2,key)+num(3,1))))
        c.receive(confirm,true)
        val text="远程文本✓"
        val block=UuWire.rpc(8,9,msg(3,msg(1,key)+num(2,1)+msg(3,(text+"\u0000").toByteArray(Charsets.UTF_16LE))))
        assertTrue(c.receive(block,true));assertEquals(listOf(text),values)
        c.reset();c.receive(block,true);assertEquals(1,values.size)
    }
}

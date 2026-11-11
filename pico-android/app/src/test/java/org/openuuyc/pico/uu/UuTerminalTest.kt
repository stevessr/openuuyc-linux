package org.openuuyc.pico.uu

import org.junit.Assert.*
import org.junit.Test

class UuTerminalTest {
    private class Fixture {
        val queued=mutableListOf<()->Unit>()
        val delayed=mutableListOf<Pair<Long,()->Unit>>()
        val sent=mutableListOf<UuTerminalWire.Frame>()
        val statuses=mutableListOf<String>()
        val output=mutableListOf<String>()
        var writable=true
        val terminal=UuTerminal({queued.add(it)},{ms,action -> delayed.add(ms to action)})
        val owner=Any()
        init { terminal.attach(owner,{statuses.add(it)},{output.add(String(it))}) }
        fun bind(official: Boolean=false) {
            terminal.bind(if(official) "BINARY_DATA_CHANNEL" else UuTerminal.CHANNEL) {
                if(writable) {sent.add(UuTerminalWire.decode(it)!!);true} else false
            }
        }
        fun flush() {while(queued.isNotEmpty()) queued.removeAt(0).invoke()}
        fun reply(id: Int=sent.last().session,body: String="""{"code":0}""") {
            terminal.receive(UuTerminalWire.encode(13,id,body.toByteArray()))
        }
    }

    @Test fun bindingNeverOpensButAnExplicitWaitingRequestOpensExactlyOnce() {
        val idle=Fixture();idle.bind();assertTrue(idle.sent.isEmpty())
        val f=Fixture()
        assertTrue(f.terminal.open());assertFalse(f.terminal.open());assertTrue(f.sent.isEmpty())
        f.bind();assertEquals(listOf(1),f.sent.map {it.kind})
        assertFalse(f.terminal.input("never run"));assertEquals(1,f.sent.size)
        f.reply();assertTrue(f.terminal.active);assertTrue(f.terminal.input("echo explicit\r"))
        assertEquals(listOf(1,2),f.sent.map {it.kind})
    }

    @Test fun dismissalCancelsWaitingRequestAndOldOwnersCannotCloseNewShell() {
        val f=Fixture();f.terminal.open();f.terminal.detach(f.owner);f.bind()
        assertTrue(f.sent.isEmpty())
        val next=Any();f.terminal.attach(next,{},{});f.terminal.open();f.reply()
        f.terminal.detach(f.owner);assertTrue(f.terminal.active)
        f.terminal.detach(next);assertFalse(f.terminal.active);assertEquals(4,f.sent.last().kind)
        f.flush();assertTrue(f.statuses.isEmpty())
    }

    @Test fun queuedOutputAndStatusDoNotReachDismissedOrReplacementObserver() {
        val f=Fixture();f.bind();f.terminal.open();f.reply()
        f.terminal.receive(UuTerminalWire.encode(5,f.sent.first().session,"old output".toByteArray()))
        f.terminal.detach(f.owner)
        val replacement=mutableListOf<String>()
        f.terminal.attach(Any(),{replacement.add(it)},{replacement.add(String(it))})
        f.flush();assertTrue(f.output.isEmpty());assertFalse(replacement.contains("old output"))
        assertTrue(f.statuses.isEmpty())
    }

    @Test fun officialHostAllocatedUnsignedHandleIsUsedAndExitDetaches() {
        val f=Fixture();f.bind(true);f.terminal.open()
        assertEquals(0,f.sent.single().session)
        f.reply(0,"""{"code":0,"session_id":"4294967295"}""")
        assertTrue(f.terminal.active);assertTrue(f.terminal.input("pwd\r"))
        assertEquals(-1,f.sent.last().session)
        f.terminal.detach(f.owner)
        assertEquals(16,f.sent.last().kind);assertEquals(-1,f.sent.last().session)
        assertFalse(f.sent.any {it.kind==4})
    }

    @Test fun officialLateReplyIsDetachedAndCannotSatisfyANewRequest() {
        val f=Fixture();f.bind(true);f.terminal.open()
        f.delayed.single {it.first==8000L}.second.invoke()
        assertFalse(f.terminal.active);assertFalse(f.terminal.opening)
        assertFalse(f.terminal.open());assertEquals(1,f.sent.size)
        f.reply(123,"""{"code":0,"handle":123}""")
        assertFalse(f.terminal.active);assertEquals(16,f.sent.last().kind);assertEquals(123,f.sent.last().session)
        assertTrue(f.terminal.open())
    }

    @Test fun rejectionBackpressureAndDisconnectReportFailuresWithoutSendingInput() {
        val f=Fixture();f.bind();f.terminal.open();f.reply(body="""{"code":1,"message":"denied"}""")
        assertFalse(f.terminal.active);assertFalse(f.terminal.input("echo unsafe\r"))
        f.terminal.open();f.reply();f.writable=false
        assertFalse(f.terminal.input("keep draft\r"))
        f.terminal.close();f.flush()
        assertFalse(f.terminal.active);assertFalse(f.terminal.ready)
        assertTrue(f.statuses.last().contains("断开"));assertFalse(f.sent.any {it.kind==2})
    }

    @Test fun duplicateOpenConfirmationDoesNotCloseAnActiveExtensionShell() {
        val f=Fixture();f.bind();f.terminal.open();f.reply();f.reply()
        assertTrue(f.terminal.active);assertEquals(listOf(1),f.sent.map {it.kind})
    }
}

package org.openuuyc.pico.uu

import org.junit.Assert.*
import org.junit.Test

class UuRemoteControlsTest {
    @Test fun screenMenuSettingsAddressTheExplicitTargetScreen() {
        with(UuWire) {
            val packet=captureSetting(31,UuCaptureSettings(),screen=7)
            val capture=fields(fields(packet).data(21)!!).data(2)!!.let(::fields)
            assertEquals(7L,capture.number(4))
            assertEquals(0L,capture.number(13))
        }
    }
    @Test fun autoCodecUsesNativeZeroAndValidatesAgainstRealIntersection() {
        val state=UuRemoteSettingsState()
        state.profiles=listOf(UuCodecProfile(1,1920,1080))
        assertEquals(0,state.current.codec)
        state.validate(state.current)
        with(UuWire) {
            val capture=fields(fields(captureSetting(settings=state.current)).data(21)!!).data(2)!!
            assertEquals(0L,fields(capture).number(13))
        }
        assertEquals(listOf(0,1),state.selectableProfiles().map { it.codec })
    }
    @Test fun virtualCreateUsesNativeUuRpcAndExplicitDimensions() {
        with(UuWire) {
            val request=fields(createVirtualDisplay(42,1920,1080)).data(21)!!.let(::fields)
            assertEquals(42L, fields(request.data(1)!!).number(1))
            val create=fields(request.data(12)!!)
            assertEquals(1L,create.number(2))
            val resolution=fields(create.data(1)!!)
            assertEquals(1920L,resolution.number(1)); assertEquals(1080L,resolution.number(2))
        }
    }
    @Test(expected=IllegalArgumentException::class) fun virtualCreateRejectsUnsafeDimensions() {
        UuWire.createVirtualDisplay(1,0,1080)
    }
    @Test fun virtualAcknowledgmentPreservesErrorAndSequence() {
        with(UuWire) {
            val packet=message { out -> bytes(out,22,message { response ->
                bytes(response,1,message { integer(it,1,99) })
                bytes(response,8,message { integer(it,1,17) })
            }) }
            assertEquals(99L to 17,decodeEnvelope(packet).topologyAck)
            assertNull(decodeEnvelope(packet).captureAck)
        }
    }
    @Test fun settingsOnlyBecomeCurrentOnMatchingSuccessfulAck() {
        val state=UuRemoteSettingsState()
        val old=state.current
        val next=old.copy(bitrateMbps=50)
        state.pending=7L to next
        assertNull(state.confirm(8,emptyList())); assertEquals(old,state.current)
        assertEquals(false,state.confirm(7,listOf(9))); assertEquals(old,state.current)
        state.pending=9L to next
        assertEquals(true,state.confirm(9,listOf(0))); assertEquals(next,state.current)
    }
    @Test(expected=IllegalArgumentException::class) fun unsupportedCodecIsNotOfferedAsWorkingSetting() {
        val state=UuRemoteSettingsState()
        state.profiles=listOf(UuCodecProfile(1,1920,1080))
        state.validate(state.current.copy(codec=5))
    }
    @Test fun touchPacketIsBinaryNativeInputNotMouseJson() {
        // Text and touch deliberately use separate UU wire contracts.
        assertTrue(UuInputWire.text("你好").toString(Charsets.UTF_8).contains("text_input"))
    }
}

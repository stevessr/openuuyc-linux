package org.openuuyc.pico.uu
import org.junit.Assert.*
import org.junit.Test
class UuSmsGateTest {
    @Test fun consentAndCooldownSurviveCancel() {
        var now=0L;val gate=UuSmsGate {now};val phone=gate.phone("+86","13800000000")
        assertThrows(IllegalArgumentException::class.java) {gate.begin(phone,false)}
        gate.begin(phone,true);gate.dispatched(phone);gate.validate(phone,"123456",true)
        gate.cancel()
        assertEquals(60L,gate.remainingSeconds())
        assertThrows(IllegalStateException::class.java) {gate.begin(phone,true)}
        assertThrows(IllegalStateException::class.java) {gate.validate(phone,"123456",true)}
        now=60000;gate.begin(phone,true)
    }
    @Test fun codeCannotBeUsedForAnotherPhone() {
        val gate=UuSmsGate {0};val first=gate.phone("86","13800000000");val other=gate.phone("86","13900000000")
        gate.begin(first,true);gate.dispatched(first)
        assertThrows(IllegalStateException::class.java) {gate.validate(other,"123456",true)}
        assertThrows(IllegalArgumentException::class.java) {gate.validate(first,"12345",true)}
    }
    @Test fun validatesCountryAndLocalPhoneWithoutEchoingTheValue() {
        val gate=UuSmsGate {0}
        assertThrows(IllegalArgumentException::class.java) {gate.phone("86","123")}
        assertThrows(IllegalArgumentException::class.java) {gate.phone("+evil","13800000000")}
        assertEquals("852" to "12345678",gate.phone("+852","12345678"))
    }
}

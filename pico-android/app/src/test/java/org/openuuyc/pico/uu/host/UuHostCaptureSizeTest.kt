package org.openuuyc.pico.uu.host
import org.junit.Assert.*
import org.junit.Test
class UuHostCaptureSizeTest {
    @Test fun orientationAndBudget() {
        assertEquals(1920 to 1080,UuHostCaptureSize.fit(1920,1080))
        assertEquals(1080 to 1920,UuHostCaptureSize.fit(1080,1920))
        assertEquals(2160 to 1080,UuHostCaptureSize.fit(4320,2160))
        assertEquals(1080 to 2160,UuHostCaptureSize.fit(2160,4320))
    }
    @Test fun evenAndNonzero() {assertEquals(1080 to 1920,UuHostCaptureSize.fit(1081,1921));assertEquals(2 to 2,UuHostCaptureSize.fit(1,1))}
    @Test(expected=IllegalArgumentException::class) fun rejectInvalid() {UuHostCaptureSize.fit(0,100)}
}

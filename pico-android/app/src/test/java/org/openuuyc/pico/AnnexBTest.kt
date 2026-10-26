package org.openuuyc.pico

import org.junit.Assert.assertEquals
import org.junit.Test

class AnnexBTest {
    @Test fun parsesThreeAndFourByteStartCodes() {
        val data = byteArrayOf(0,0,0,1,0x67,0x21,0,0,1,0x68,0x22)
        val nals = AnnexB.nals(data, 0, data.size)
        assertEquals(2, nals.size)
        assertEquals(4, nals[0].startCode)
        assertEquals(3, nals[1].startCode)
        assertEquals(0x67, data[nals[0].first].toInt())
    }

    @Test fun ignoresMalformedAndEmptyNals() {
        assertEquals(0, AnnexB.nals(byteArrayOf(0,0,1), 0, 3).size)
        assertEquals(0, AnnexB.nals(byteArrayOf(1,2,3,4), 0, 4).size)
    }
}

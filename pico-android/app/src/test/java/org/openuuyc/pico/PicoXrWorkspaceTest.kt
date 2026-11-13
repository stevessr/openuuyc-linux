package org.openuuyc.pico
import org.junit.Assert.*
import org.junit.Test
class PicoXrWorkspaceTest {
    @Test fun distanceLadderIsNearStandardFarAndAscending() {
        assertEquals(listOf("近","标准","远"),PicoXrWorkspace.distances.map {it.label})
        assertEquals(listOf(1.6f,2.2f,3.0f),PicoXrWorkspace.distances.map {it.meters})
        assertEquals(PicoXrWorkspace.distances[1],PicoXrWorkspace.default)
        assertEquals("标准 2.2 m",PicoXrWorkspace.default.text)
        assertEquals("近 1.6 m",PicoXrWorkspace.distanceText(1.6f))
        assertEquals("远 3.0 m",PicoXrWorkspace.distanceText(3.0f))
    }
    @Test fun clampsDistanceIntoSupportedRange() {
        assertEquals(1.2f,PicoXrWorkspace.clampDistance(.1f),.0001f)
        assertEquals(1.2f,PicoXrWorkspace.clampDistance(1.2f),.0001f)
        assertEquals(4f,PicoXrWorkspace.clampDistance(9f),.0001f)
        assertEquals(2.5f,PicoXrWorkspace.clampDistance(2.5f),.0001f)
        assertEquals(2.2f,PicoXrWorkspace.clampDistance(Float.NaN),.0001f)
        assertEquals(2.2f,PicoXrWorkspace.clampDistance(Float.POSITIVE_INFINITY),.0001f)
        assertEquals(2.2f,PicoXrWorkspace.clampDistance(Float.NEGATIVE_INFINITY),.0001f)
    }
    @Test fun nearestDistanceSnapsToClosestPreset() {
        assertEquals("近",PicoXrWorkspace.nearestDistance(1.6f).label)
        assertEquals("近",PicoXrWorkspace.nearestDistance(1.8f).label)
        assertEquals("标准",PicoXrWorkspace.nearestDistance(2.4f).label)
        assertEquals("远",PicoXrWorkspace.nearestDistance(2.7f).label)
        assertEquals("远",PicoXrWorkspace.nearestDistance(9f).label)
        assertEquals("近",PicoXrWorkspace.nearestDistance(.2f).label)
        assertEquals("标准",PicoXrWorkspace.nearestDistance(Float.NaN).label)
    }
    @Test fun nextDistanceCyclesNearStandardFar() {
        assertEquals("标准",PicoXrWorkspace.nextDistance(1.6f).label)
        assertEquals("远",PicoXrWorkspace.nextDistance(2.2f).label)
        assertEquals("近",PicoXrWorkspace.nextDistance(3.0f).label)
        assertEquals("标准",PicoXrWorkspace.nextDistance(.5f).label)
        assertEquals("远",PicoXrWorkspace.nextDistance(2.4f).label)
        assertEquals("近",PicoXrWorkspace.nextDistance(9f).label)
        assertEquals(2.2f,PicoXrWorkspace.nextDistance(1.6f).meters,.0001f)
    }
    @Test fun modeMapsNativeValuesAndFallsBackToAuto() {
        assertEquals(listOf(0,1,2),PicoXrWorkspace.Mode.entries.map {it.native})
        assertEquals(listOf("自动","并排","环绕"),PicoXrWorkspace.Mode.entries.map {it.label})
        assertEquals(PicoXrWorkspace.Mode.AUTO,PicoXrWorkspace.modeOf(0))
        assertEquals(PicoXrWorkspace.Mode.FLAT,PicoXrWorkspace.modeOf(1))
        assertEquals(PicoXrWorkspace.Mode.WRAP,PicoXrWorkspace.modeOf(2))
        for(invalid in listOf(-1,3,99,Int.MIN_VALUE)) assertEquals(PicoXrWorkspace.Mode.AUTO,PicoXrWorkspace.modeOf(invalid))
    }
    @Test fun modeCyclesThroughEveryLayout() {
        assertEquals(PicoXrWorkspace.Mode.FLAT,PicoXrWorkspace.nextMode(PicoXrWorkspace.Mode.AUTO))
        assertEquals(PicoXrWorkspace.Mode.WRAP,PicoXrWorkspace.nextMode(PicoXrWorkspace.Mode.FLAT))
        assertEquals(PicoXrWorkspace.Mode.AUTO,PicoXrWorkspace.nextMode(PicoXrWorkspace.Mode.WRAP))
        assertEquals(PicoXrWorkspace.Mode.AUTO,PicoXrWorkspace.Mode.entries.fold(PicoXrWorkspace.Mode.AUTO) {mode,_->PicoXrWorkspace.nextMode(mode)})
    }
}

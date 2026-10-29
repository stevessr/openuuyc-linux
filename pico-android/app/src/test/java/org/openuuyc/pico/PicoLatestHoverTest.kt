package org.openuuyc.pico
import org.junit.Assert.*
import org.junit.Test
class PicoLatestHoverTest {
    @Test fun hundredsOfFramesUseOnlyOneUiTaskAndNewestPosition() {
        val tasks=mutableListOf<()->Unit>();val consumed=mutableListOf<Int>()
        val hover=PicoLatestHover<Int>({tasks.add(it)},{consumed.add(it)})
        repeat(900) {hover.offer(it)}
        assertEquals(1,tasks.size);tasks.removeAt(0)();assertEquals(listOf(899),consumed)
        hover.offer(1000);assertEquals(1,tasks.size);tasks.removeAt(0)();assertEquals(listOf(899,1000),consumed)
    }
    @Test fun aClickCannotBeStarvedByContinuousHover() {
        val tasks=mutableListOf<()->Unit>();val result=mutableListOf<String>()
        val hover=PicoLatestHover<Int>({tasks.add(it)},{result.add("hover:$it")})
        hover.offer(1);tasks.add {result.add("click")};repeat(500) {hover.offer(2)}
        assertEquals(2,tasks.size);tasks.removeAt(0)();hover.offer(3)
        tasks.removeAt(0)();assertEquals(listOf("hover:2","click"),result)
    }
    @Test fun stoppedSessionCannotApplyAnOldHover() {
        val tasks=mutableListOf<()->Unit>();val result=mutableListOf<Int>()
        val hover=PicoLatestHover<Int>({tasks.add(it)},{result.add(it)})
        hover.offer(1);hover.clear();tasks.removeAt(0)();assertTrue(result.isEmpty())
    }
}

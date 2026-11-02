package org.openuuyc.pico
import org.json.JSONObject
import kotlin.math.sqrt
/** Aggregated native cursor motion only; does not persist raw controller poses. */
internal class PicoPointerProbeStats {
    private val minimum=FloatArray(6) {Float.POSITIVE_INFINITY}
    private val maximum=FloatArray(6) {Float.NEGATIVE_INFINITY}
    private val valid=LongArray(2)
    private val gripActive=LongArray(2)
    private val gripHeld=LongArray(2)
    private var dragFrames=0L
    private val menuMin=FloatArray(3) {Float.POSITIVE_INFINITY}
    private val menuMax=FloatArray(3) {Float.NEGATIVE_INFINITY}
    @Synchronized fun menu(flags: Int,x: Float,y: Float,z: Float) {
        for(hand in 0..1) {
            if(flags and (1 shl (hand+2))!=0) gripActive[hand]++
            if(flags and (1 shl (hand+4))!=0) gripHeld[hand]++
        }
        if(flags and 64!=0) {
            dragFrames++
            for((i,value) in floatArrayOf(x,y,z).withIndex()) if(value.isFinite()) {menuMin[i]=minOf(menuMin[i],value);menuMax[i]=maxOf(menuMax[i],value)}
        }
    }
    var samples=0L;private set
    @Synchronized fun accept(mask: Int,positions: FloatArray) {
        if(positions.size!=6) return
        samples++
        for(hand in 0..1) if(mask and (1 shl hand)!=0 && (0..2).all {positions[hand*3+it].isFinite()}) {
            valid[hand]++
            for(axis in 0..2) {val i=hand*3+axis;minimum[i]=minOf(minimum[i],positions[i]);maximum[i]=maxOf(maximum[i],positions[i])}
        }
    }
    @Synchronized fun report(): JSONObject {
        fun range(hand: Int): Float {
            if(valid[hand]==0L) return 0f
            return sqrt((0..2).sumOf {val i=hand*3+it;((maximum[i]-minimum[i])*(maximum[i]-minimum[i])).toDouble()}.toFloat())
        }
        val menuRange=if(dragFrames==0L) 0.0 else sqrt((0..2).sumOf {((menuMax[it]-menuMin[it])*(menuMax[it]-menuMin[it])).toDouble()})
        return JSONObject().put("leftGripActive",gripActive[0]).put("rightGripActive",gripActive[1])
            .put("leftGripHeld",gripHeld[0]).put("rightGripHeld",gripHeld[1]).put("menuDragFrames",dragFrames).put("menuRangeMeters",menuRange)
            .put("samples",samples).put("leftValid",valid[0]).put("rightValid",valid[1])
            .put("leftRangeMeters",range(0)).put("rightRangeMeters",range(1)).put("nativePerHand",true)
    }
}

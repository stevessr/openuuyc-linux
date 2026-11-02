package org.openuuyc.pico.uu
import org.json.JSONObject
internal data class UuPcvrPulse(val hand:Int,val amplitude:Float,val frequency:Float,val durationMs:Int)
internal object UuPcvrHaptics {
    private fun integer(value:JSONObject,key:String):Int? {
        val number=value.opt(key) as? Number ?: return null
        val d=number.toDouble()
        if(!d.isFinite() || d<Int.MIN_VALUE.toDouble() || d>Int.MAX_VALUE.toDouble() || d!=d.toInt().toDouble())return null
        return d.toInt()
    }
    fun parse(value:JSONObject,generation:Int):UuPcvrPulse? {
        if(generation<=0 || integer(value,"generation")!=generation) return null
        val hand=integer(value,"hand") ?: return null;val amplitude=value.optDouble("amplitude",Double.NaN)
        val frequency=value.optDouble("frequency",Double.NaN);val duration=integer(value,"duration_ms") ?: return null
        if(hand !in 0..1 || !amplitude.isFinite() || amplitude !in 0.0..1.0 || !frequency.isFinite() || frequency !in 0.0..1000.0 || duration !in 0..1000) return null
        return UuPcvrPulse(hand,amplitude.toFloat(),frequency.toFloat(),duration)
    }
}

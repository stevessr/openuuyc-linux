package org.openuuyc.pico

import kotlin.math.*

/** Deterministic CPU-generated scenery, cached fallback/preview texture; XR motion is rendered on the GPU; no network/assets. */
internal object PicoBackgroundPattern {
    data class Preset(val id: String,val label: String,val color: Int,val procedural: Boolean=false,val mode: Int=0)
    val presets=listOf(
        Preset("black","全黑",0xff000000.toInt()),Preset("gray","深灰",0xff0f141c.toInt()),Preset("navy","深蓝",0xff0c162a.toInt()),
        Preset("twilight","暮色渐变",0xff11162b.toInt(),true,1),Preset("aurora","极光",0xff061321.toInt(),true,2),
        Preset("stars","静谧星空",0xff050a18.toInt(),true,3),Preset("mountains","远山",0xff132338.toInt(),true,4),
        Preset("grid","空间网格",0xff081421.toInt(),true,5),Preset("sunset","日落",0xff31203e.toInt(),true,6),
        Preset("clouds","流云",0xff12233d.toInt(),true,7),
        Preset("ocean","流光海面",0xff073344.toInt(),true,8),Preset("nebula","星云空间",0xff211033.toInt(),true,9),
        Preset("gallery","静谧展厅",0xff252e37.toInt(),true,10),Preset("cyber","霓虹网格",0xff221234.toInt(),true,11)
    )
    fun find(id: String)=presets.firstOrNull {it.id==id} ?: presets[0]
    private fun rgb(r: Int,g: Int,b: Int)=0xff000000.toInt() or (r.coerceIn(0,255) shl 16) or (g.coerceIn(0,255) shl 8) or b.coerceIn(0,255)
    private fun mix(a: Int,b: Int,t: Float): Int {
        val f=t.coerceIn(0f,1f)
        fun c(shift: Int)=(((a ushr shift) and 255)*(1-f)+((b ushr shift) and 255)*f).toInt()
        return rgb(c(16),c(8),c(0))
    }
    private fun noise(x: Int,y: Int,seed: Int): Int {
        var n=x*374761393+y*668265263+seed*1442695041
        n=(n xor (n ushr 13))*1274126177
        return n xor (n ushr 16)
    }
    fun render(id: String,width: Int,height: Int,seed: Int): IntArray {
        require(width in 1..2048 && height in 1..2048)
        val preset=find(id)
        val phase=(seed and 1023)/163f
        return IntArray(width*height) {i ->
            if(!preset.procedural) preset.color else {
                val x=i%width;val y=i/width;val u=x.toFloat()/width;val v=y.toFloat()/height
                val base=mix(preset.color,0xff27334a.toInt(),v*.65f)
                when(id) {
                    "twilight" -> mix(mix(0xff091225.toInt(),0xff4b3156.toInt(),v),0xffbd7772.toInt(),exp(-((v-(.68f+.05f*sin(phase))).pow(2))*25)*.5f)
                    "aurora" -> {
                        val ribbon=.35f+.12f*sin(u*9+phase)+.05f*sin(u*22-phase)
                        val glow=exp(-abs(v-ribbon)*15)*(.3f+.7f*sin(u*5+phase).pow(2))
                        mix(base,mix(0xff36b8a4.toInt(),0xff7561b9.toInt(),u),glow*.65f)
                    }
                    "stars","nebula" -> {
                        val n=noise(x/2,y/2,seed)
                        if((n and 4095)<6 && v<.9f) mix(base,0xffe2e7ff.toInt(),.45f+((n ushr 12) and 255)/464f) else base
                    }
                    "mountains" -> {
                        val ridge1=.56f+.08f*sin(u*12+phase)+.04f*sin(u*29)
                        val ridge2=.72f+.06f*sin(u*17-phase)+.03f*sin(u*41)
                        when {v>ridge2 -> 0xff101b2b.toInt();v>ridge1 -> 0xff25344c.toInt();else -> mix(0xff111e34.toInt(),0xff657389.toInt(),v)}
                    }
                    "grid","cyber","gallery" -> {
                        val horizon=.44f
                        if(v<horizon) base else {
                            val depth=(v-horizon).coerceAtLeast(.005f)
                            val gx=abs(((u-.5f)/depth*3+phase)%1)
                            val gy=abs((1/depth*.45f)%1)
                            if(gx<.035f || gx>.965f || gy<.04f) mix(base,0xff3c7189.toInt(),depth*.8f) else base
                        }
                    }
                    "ocean" -> mix(base,0xff366e86.toInt(),(.3f+.2f*sin(u*40+v*30+phase))*v)
                    "sunset" -> {
                        val sky=mix(0xff121831.toInt(),0xffa16666.toInt(),v)
                        val distance=sqrt(((u-(.5f+.08f*sin(phase)))*1.5f).pow(2)+(v-(.56f+.03f*cos(phase))).pow(2))
                        if(distance<.09f) mix(0xffe6ad79.toInt(),sky,v) else mix(sky,0xffd98d79.toInt(),exp(-distance*8)*.3f)
                    }
                    else -> {
                        val cloud=(sin(u*10+phase+sin(v*8))+sin(u*19-v*11-phase)+sin(v*16+u*5))/3
                        mix(base,0xff748498.toInt(),((cloud-.1f)*.45f).coerceIn(0f,.5f))
                    }
                }
            }
        }
    }
}

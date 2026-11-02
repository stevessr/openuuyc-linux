package org.openuuyc.pico

/** Atlas coordinates shared by the MR chrome drawing and native xr_frame_hit.h. */
internal object PicoXrFrameLayout {
    const val WIDTH=1200f
    const val HEIGHT=800f
    const val SCALE_X=1.06f
    const val SCALE_Y=1.16f
    val device=floatArrayOf(54f,13f,326f,51f)
    val screen=floatArrayOf(348f,13f,782f,51f)
    val microphone=floatArrayOf(800f,13f,842f,51f)
    val clipboard=floatArrayOf(850f,13f,892f,51f)
    val quality=floatArrayOf(910f,13f,956f,51f)
    val add=floatArrayOf(972f,13f,1018f,51f)
    val close=floatArrayOf(1096f,13f,1154f,51f)
    val drag=floatArrayOf(460f,755f,740f,795f)
    fun pixelFromPanel(x: Float,y: Float,width: Float,height: Float)=
        (WIDTH/2+x/width*WIDTH/SCALE_X) to (HEIGHT/2-y/height*HEIGHT/SCALE_Y)
    fun panelFromPixel(x: Float,y: Float,width: Float,height: Float)=
        ((x/WIDTH-.5f)*SCALE_X*width) to ((.5f-y/HEIGHT)*SCALE_Y*height)
    fun videoContainsPixel(x: Float,y: Float)=
        kotlin.math.abs(x-WIDTH/2)<=WIDTH/(2*SCALE_X) && kotlin.math.abs(y-HEIGHT/2)<=HEIGHT/(2*SCALE_Y)
}

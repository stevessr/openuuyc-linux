package org.openuuyc.pico

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import org.openuuyc.pico.uu.UuRemoteSettingsState

internal object PicoXrQualityCanvas {
    fun draw(canvas: Canvas,state: UuRemoteSettingsState?,u: Float,v: Float,pressed: Boolean,notice: String) {
        val p=Paint(Paint.ANTI_ALIAS_FLAG);val text=Color.rgb(231,235,242);val muted=Color.rgb(156,167,184)
        val c=state?.current
        canvas.save();canvas.scale(2f,.4f)
        PicoXrMaterial.panel(canvas,600f,1000f)
        fun label(s: String,x: Float,y: Float,color: Int=text,size: Float=27f) {p.color=color;p.textSize=size;canvas.drawText(s,x,y,p)}
        PicoXrMaterial.label(canvas,"画质",24f,51f,480f,30f,medium=true);label("×",542f,51f,muted,36f)
        val source=state?.screens?.firstOrNull {it.id==state.selected}
        val maxFps=minOf(source?.fps ?: 0,state?.profiles?.filter {c?.codec==0 || it.codec==c?.codec}?.maxOfOrNull {it.fps} ?: 0)
        PicoXrQualityLayout.items.filter {it.action!=11}.forEach {item ->
            val enabled=item.action<200 || item.action-200<=maxFps
            val selected=when(item.action) {in 102..105 -> c?.quality==item.action-100;in 200..500 -> c?.fps==item.action-200;else -> false}
            val hover=enabled && u*600 in item.x..item.x+item.w && v*1000 in item.y..item.y+item.h
            if(selected || hover || item.action>=200) PicoXrMaterial.control(canvas,RectF(item.x,item.y,item.x+item.w,item.y+item.h),selected,hover,pressed,enabled)
            val title=when(item.action) {3 -> "此设备编码偏好";105 -> "自动（原画）";104 -> "原画";103 -> "超清";102 -> "高清";2 -> "自定义码率";98 -> "显示器分辨率与 DPI";99 -> "高级设置";else -> (item.action-200).toString()}
            label(if(selected && item.action<200) "✓  $title" else title,item.x+if(item.action>=200) 22f else 18f,item.y+41f,if(enabled) text else Color.rgb(72,80,91),if(item.action>=200) 25f else 27f)
            val value=when(item.action) {3 -> "${when(c?.codec) {1->"H.264";2->"H.265";5->"AV1";else->"自动"}}  ›";2 -> "${c?.bitrateMbps ?: 0} Mbps  ›";98 -> "当前屏幕  ›";99 -> "›";else -> ""}
            if(value.isNotBlank()) {p.textSize=23f;label(value,570f-p.measureText(value),item.y+41f,muted,23f)}
        }
        p.color=Color.rgb(45,53,66);canvas.drawLine(24f,163f,576f,163f,p);canvas.drawLine(24f,837f,576f,837f,p)
        label("帧率上限",24f,635f,muted,23f);label("FPS",526f,635f,muted,21f)
        label("色度采样",24f,751f,muted,23f);label("YUV 4:2:0 · 当前解码能力",44f,797f,muted,24f)
        label("HDR：当前客户端未开放",24f,830f,Color.rgb(85,94,105),20f)
        p.textSize=17f;label(notice.take(p.breakText(notice,true,550f,null)),24f,944f,muted,17f)
        PicoXrMaterial.handle(canvas,600f,1000f,v>=.95f)
        canvas.restore()
    }
}

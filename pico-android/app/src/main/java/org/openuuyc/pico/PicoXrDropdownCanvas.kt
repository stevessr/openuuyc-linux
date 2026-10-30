package org.openuuyc.pico

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/** One row of a dropdown panel: label + right value, or an inline slider. */
internal class PicoXrDropdownRow(
    val label: String,
    val value: String = "",
    val kind: Int = OPTION,          // OPTION / SLIDER / TOGGLE / ACTION
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val sliderFraction: Float = 0f,
    val danger: Boolean = false
) {
    companion object {
        const val OPTION = 0
        const val SLIDER = 1
        const val TOGGLE = 2
        const val ACTION = 3
    }
}

/** Landscape dropdown page: header (back/title/close) + scrollable rows, drawn per row. */
internal object PicoXrDropdownCanvas {
    fun draw(c: Canvas,title: String,rows: List<PicoXrDropdownRow>,offset: Int,u: Float,v: Float,pressed: Boolean,notice: String,dragIndex: Int=-1) {
        val p=Paint(Paint.ANTI_ALIAS_FLAG)
        PicoXrMaterial.panel(c,1200f,800f)
        PicoXrMaterial.label(c,"‹  $title",24f,66f,1020f,32f,medium=true)
        PicoXrMaterial.label(c,"×",1120f,68f,60f,34f,muted=true)
        p.color=PicoTheme.LINE;p.strokeWidth=1.5f
        c.drawLine(24f,104f,1176f,104f,p)
        val visible=minOf(rows.size-offset,PicoXrDropdownLayout.visibleRows).coerceAtLeast(0)
        if(rows.isEmpty() || visible==0) PicoXrMaterial.label(c,"暂无可调项",32f,220f,1100f,26f,muted=true)
        for(i in 0 until visible) {
            val row=rows[offset+i];val index=offset+i
            val top=PicoXrDropdownLayout.ROW_TOP+i*PicoXrDropdownLayout.ROW_HEIGHT
            val rect=RectF(PicoXrDropdownLayout.ROW_LEFT,top,PicoXrDropdownLayout.ROW_RIGHT,top+PicoXrDropdownLayout.ROW_HEIGHT-8)
            val sliderHit=row.kind==PicoXrDropdownRow.SLIDER && PicoXrDropdownLayout.sliderFraction(u,v,index,rows.size,offset)!=null
            val hover=sliderHit || (PicoXrDropdownLayout.hit(u,v,rows.size,offset)==index)
            if(row.selected || hover) PicoXrMaterial.control(c,rect,row.selected,hover,pressed && hover,row.enabled)
            p.style=Paint.Style.FILL
            val labelColor=when { !row.enabled->Color.rgb(72,80,91);row.danger->PicoTheme.RED;else->PicoTheme.TEXT }
            if(row.kind==PicoXrDropdownRow.SLIDER) {
                PicoXrMaterial.label(c,row.label,rect.left+20,rect.centerY()+10,340f,26f,muted=!(hover||row.selected))
                // Track: base gray then accent fill up to the handle.
                val trackLeft=rect.left+380f;val trackRight=rect.right-80f
                val trackY=rect.centerY()
                p.strokeWidth=10f;p.strokeCap=Paint.Cap.ROUND
                p.color=Color.rgb(58,68,82);c.drawLine(trackLeft,trackY,trackRight,trackY,p)
                val fraction=if(index==dragIndex && PicoXrDropdownLayout.sliderFraction(u,v,index,rows.size,offset)!=null) PicoXrDropdownLayout.sliderFraction(u,v,index,rows.size,offset)!! else row.sliderFraction
                p.color=PicoTheme.ACCENT;c.drawLine(trackLeft,trackY,trackLeft+(trackRight-trackLeft)*fraction,trackY,p)
                p.color=Color.rgb(218,231,251);c.drawCircle(trackLeft+(trackRight-trackLeft)*fraction,trackY,14f,p)
                p.color=PicoTheme.ACCENT;c.drawCircle(trackLeft+(trackRight-trackLeft)*fraction,trackY,7f,p)
                p.strokeWidth=1f;p.strokeCap=Paint.Cap.BUTT
                if(row.value.isNotBlank()) PicoXrMaterial.label(c,row.value,rect.right-260f,rect.centerY()+10,240f,24f,muted=!row.selected)
            } else {
                val check=if(row.selected) "✓  " else ""
                PicoXrMaterial.label(c,"$check${row.label}",rect.left+20,rect.centerY()+10,720f,27f,muted=!row.enabled)
                if(row.kind==PicoXrDropdownRow.TOGGLE && row.value.isNotBlank()) {
                    // Toggle pill: ON filled accent, OFF outline muted.
                    val pillW=96f;val pillH=44f
                    val on=row.value=="开启" || row.value=="开"
                    val pill=RectF(rect.right-pillW-20f,rect.centerY()-pillH/2,rect.right-20f,rect.centerY()+pillH/2)
                    p.style=Paint.Style.FILL;p.color=if(on) PicoTheme.ACCENT else Color.argb(120,58,68,82)
                    c.drawRoundRect(pill,pillH/2,pillH/2,p)
                    p.style=Paint.Style.STROKE;p.strokeWidth=1.5f;p.color=if(on) Color.WHITE else PicoTheme.MUTED
                    c.drawRoundRect(pill,pillH/2,pillH/2,p)
                    p.style=Paint.Style.FILL;p.color=Color.WHITE;p.textSize=22f;p.textAlign=Paint.Align.CENTER
                    c.drawText(if(on) "开" else "关",pill.centerX(),pill.centerY()+8f,p)
                    p.textAlign=Paint.Align.LEFT
                } else if(row.value.isNotBlank()) {
                    val valueColor=when { !row.enabled->Color.rgb(72,80,91);row.selected->PicoTheme.ACCENT;else->PicoTheme.MUTED }
                    p.color=valueColor;p.textSize=24f
                    val width=p.measureText(row.value)
                    c.drawText(row.value,rect.right-24f-width,rect.centerY()+10,p)
                }
            }
        }
        p.textSize=19f;p.color=PicoTheme.MUTED
        val range=if(rows.size>PicoXrDropdownLayout.visibleRows) "${minOf(offset+1,rows.size)}–${minOf(offset+PicoXrDropdownLayout.visibleRows,rows.size)} / ${rows.size} · " else ""
        val actionHint=if(rows.size>PicoXrDropdownLayout.visibleRows) "摇杆翻行 · 下拨返回" else "摇杆选择 · 下拨返回"
        c.drawText(range+actionHint,32f,780f,p)
        PicoXrMaterial.label(c,notice,32f,748f,1100f,18f,muted=true)
    }
}

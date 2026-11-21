package org.openuuyc.pico

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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

/** Landscape dropdown page: header (back/title/paging/close) + scrollable rows. */
internal object PicoXrDropdownCanvas {
    private fun toRect(bounds: FloatArray) = RectF(bounds[0], bounds[1], bounds[2], bounds[3])

    fun draw(
        c: Canvas,
        title: String,
        rows: List<PicoXrDropdownRow>,
        offset: Int,
        u: Float,
        v: Float,
        pressed: Boolean,
        notice: String,
        dragIndex: Int = -1,
        floating: Boolean = true
    ) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        PicoXrMaterial.panel(c, 1200f, 800f)

        val paged = rows.size > PicoXrDropdownLayout.visibleRows
        val headerTarget = PicoXrDropdownLayout.headerHit(u, v, rows.size)

        // Header: Back pill, title, optional paging cluster, and close button.
        val backRect = toRect(PicoXrDropdownLayout.BACK_RECT)
        val backHover = headerTarget == PicoXrDropdownLayout.HEADER_BACK
        PicoXrMaterial.control(c, backRect, hovered = backHover, pressed = pressed && backHover, radius = 14f)
        PicoXrMaterial.label(
            c, "‹ 返回", backRect.centerX(), backRect.centerY() + 9f,
            backRect.width() - 16f, 25f, medium = true,
            color = if (backHover) Color.WHITE else Color.rgb(232, 237, 245), align = Paint.Align.CENTER
        )

        PicoXrMaterial.label(c, title, 176f, 64f, if (paged) 576f else 918f, 30f, medium = true)

        if (paged) {
            val pages = PicoXrDropdownLayout.pageCount(rows.size).coerceAtLeast(1)
            val currentPage = (offset / PicoXrDropdownLayout.visibleRows + 1).coerceIn(1, pages)
            val canPrev = offset > 0
            val canNext = offset + PicoXrDropdownLayout.visibleRows < rows.size

            val prevRect = toRect(PicoXrDropdownLayout.PREV_RECT)
            val prevHover = canPrev && headerTarget == PicoXrDropdownLayout.HEADER_PREV
            PicoXrMaterial.control(c, prevRect, hovered = prevHover, pressed = pressed && prevHover, enabled = canPrev, radius = 14f)
            PicoXrMaterial.label(
                c, "‹ 上页", prevRect.centerX(), prevRect.centerY() + 8f,
                prevRect.width() - 12f, 22f, medium = true,
                color = if (!canPrev) Color.rgb(96, 106, 122) else if (prevHover) Color.WHITE else PicoTheme.TEXT,
                align = Paint.Align.CENTER
            )

            val pagePill = RectF(896f, 24f, 972f, 82f)
            PicoXrMaterial.pill(c, pagePill, accent = true)
            PicoXrMaterial.label(
                c, "$currentPage/$pages", pagePill.centerX(), pagePill.centerY() + 8f,
                pagePill.width() - 8f, 21f, medium = true,
                color = Color.rgb(186, 212, 252), align = Paint.Align.CENTER
            )

            val nextRect = toRect(PicoXrDropdownLayout.NEXT_RECT)
            val nextHover = canNext && headerTarget == PicoXrDropdownLayout.HEADER_NEXT
            PicoXrMaterial.control(c, nextRect, hovered = nextHover, pressed = pressed && nextHover, enabled = canNext, radius = 14f)
            PicoXrMaterial.label(
                c, "下页 ›", nextRect.centerX(), nextRect.centerY() + 8f,
                nextRect.width() - 12f, 22f, medium = true,
                color = if (!canNext) Color.rgb(96, 106, 122) else if (nextHover) Color.WHITE else PicoTheme.TEXT,
                align = Paint.Align.CENTER
            )
        }

        val closeRect = toRect(PicoXrDropdownLayout.CLOSE_RECT)
        val closeHover = headerTarget == PicoXrDropdownLayout.HEADER_CLOSE
        PicoXrMaterial.control(
            c, closeRect, hovered = closeHover, pressed = pressed && closeHover,
            danger = closeHover, radius = 14f
        )
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2.6f
        p.strokeCap = Paint.Cap.ROUND
        p.color = if (closeHover) Color.WHITE else PicoTheme.MUTED
        val cx = closeRect.centerX()
        val cy = closeRect.centerY()
        c.drawLine(cx - 10f, cy - 10f, cx + 10f, cy + 10f, p)
        c.drawLine(cx + 10f, cy - 10f, cx - 10f, cy + 10f, p)
        p.style = Paint.Style.FILL
        p.strokeCap = Paint.Cap.BUTT

        p.color = Color.argb(120, 112, 134, 166)
        p.strokeWidth = 1.2f
        c.drawLine(24f, 104f, 1176f, 104f, p)

        val visible = minOf(rows.size - offset, PicoXrDropdownLayout.visibleRows).coerceAtLeast(0)
        if (rows.isEmpty() || visible == 0) {
            PicoXrMaterial.label(c, "暂无可调项", 600f, 380f, 900f, 28f, muted = true, align = Paint.Align.CENTER)
        }

        for (i in 0 until visible) {
            val index = offset + i
            val row = rows[index]
            val top = PicoXrDropdownLayout.ROW_TOP + i * PicoXrDropdownLayout.ROW_HEIGHT
            val rect = RectF(
                PicoXrDropdownLayout.ROW_LEFT,
                top,
                PicoXrDropdownLayout.ROW_RIGHT,
                top + PicoXrDropdownLayout.ROW_HEIGHT - 8f
            )
            val sliderHit = row.kind == PicoXrDropdownRow.SLIDER &&
                PicoXrDropdownLayout.sliderFraction(u, v, index, rows.size, offset) != null
            val hover = row.enabled && (sliderHit || PicoXrDropdownLayout.hit(u, v, rows.size, offset) == index)

            if (row.selected || hover) {
                PicoXrMaterial.control(
                    c, rect, row.selected, hover, pressed && hover,
                    row.enabled, danger = row.danger, radius = 14f
                )
            } else {
                PicoXrMaterial.rowSurface(c, rect, row.enabled, danger = row.danger, radius = 14f)
            }

            if (row.selected && row.kind != PicoXrDropdownRow.SLIDER) {
                p.style = Paint.Style.FILL
                p.color = if (row.danger) PicoTheme.RED else PicoTheme.ACCENT
                c.drawRoundRect(
                    RectF(rect.left + 8f, rect.centerY() - 18f, rect.left + 12.5f, rect.centerY() + 18f),
                    2.25f, 2.25f, p
                )
            }

            val labelColor = when {
                !row.enabled -> Color.rgb(100, 110, 126)
                row.danger -> if (hover) Color.WHITE else PicoTheme.RED
                row.selected || hover -> Color.WHITE
                else -> PicoTheme.TEXT
            }

            when (row.kind) {
                PicoXrDropdownRow.SLIDER -> drawSliderRow(c, p, rect, row, index, rows.size, offset, u, v, hover, dragIndex, labelColor)
                PicoXrDropdownRow.TOGGLE -> drawToggleRow(c, rect, row, hover, labelColor)
                else -> drawOptionRow(c, p, rect, row, hover, labelColor)
            }
        }

        if (paged && visible > 0) {
            val trackTop = PicoXrDropdownLayout.ROW_TOP
            val trackHeight = PicoXrDropdownLayout.visibleRows * PicoXrDropdownLayout.ROW_HEIGHT - 8f
            p.style = Paint.Style.FILL
            p.color = Color.argb(72, 140, 160, 190)
            c.drawRoundRect(RectF(1184f, trackTop, 1190f, trackTop + trackHeight), 3f, 3f, p)

            val thumbHeight = (trackHeight * (visible.toFloat() / rows.size)).coerceAtLeast(36f)
            val maxOffset = PicoXrDropdownLayout.maxScrollOffset(rows.size).coerceAtLeast(1)
            val thumbTop = trackTop + (trackHeight - thumbHeight) * (offset.toFloat() / maxOffset).coerceIn(0f, 1f)
            p.color = Color.argb(190, 142, 192, 255)
            c.drawRoundRect(RectF(1184f, thumbTop, 1190f, thumbTop + thumbHeight), 3f, 3f, p)
        }

        PicoXrMaterial.label(c, notice, 30f, 720f, 760f, 20f, color = Color.rgb(188, 200, 218))
        val range = if (paged) {
            "${minOf(offset + 1, rows.size)}–${minOf(offset + PicoXrDropdownLayout.visibleRows, rows.size)} / ${rows.size} · "
        } else ""
        val actionHint = if (paged) "摇杆翻行 · 下拨返回" else "摇杆选择 · 下拨返回"
        PicoXrMaterial.label(c, range + actionHint, 1170f, 720f, 370f, 19f, muted = true, align = Paint.Align.RIGHT)

        if (floating) {
            PicoXrMaterial.handle(c, 1200f, 800f, hover = (u in 0f..1f && v >= 0.92f), pressed = pressed)
        }
    }

    private fun drawSliderRow(
        c: Canvas,
        p: Paint,
        rect: RectF,
        row: PicoXrDropdownRow,
        index: Int,
        totalRows: Int,
        offset: Int,
        u: Float,
        v: Float,
        hover: Boolean,
        dragIndex: Int,
        labelColor: Int
    ) {
        PicoXrMaterial.label(
            c, row.label, rect.left + 26f, rect.centerY() + 10f, 342f, 26f,
            medium = row.selected || hover, color = labelColor
        )
        val trackLeft = PicoXrDropdownLayout.SLIDER_TRACK_LEFT
        val trackRight = PicoXrDropdownLayout.SLIDER_TRACK_RIGHT
        val trackY = rect.centerY()
        val liveFraction = if (index == dragIndex) {
            PicoXrDropdownLayout.sliderFraction(u, v, index, totalRows, offset)
        } else null
        val fraction = (liveFraction ?: row.sliderFraction).coerceIn(0f, 1f)
        val knobX = trackLeft + (trackRight - trackLeft) * fraction

        p.style = Paint.Style.FILL
        p.strokeWidth = 12f
        p.strokeCap = Paint.Cap.ROUND
        p.color = Color.argb(195, 52, 63, 79)
        c.drawLine(trackLeft, trackY, trackRight, trackY, p)
        p.color = if (row.enabled) PicoTheme.ACCENT else Color.rgb(86, 102, 126)
        c.drawLine(trackLeft, trackY, knobX, trackY, p)

        if (hover || index == dragIndex) {
            p.color = Color.argb(65, 102, 160, 242)
            c.drawCircle(knobX, trackY, 22f, p)
        }
        p.color = Color.argb(64, 0, 0, 0)
        c.drawCircle(knobX, trackY + 1.5f, 16f, p)
        p.color = Color.rgb(242, 246, 255)
        c.drawCircle(knobX, trackY, 15f, p)
        p.color = PicoTheme.ACCENT
        c.drawCircle(knobX, trackY, 6f, p)
        p.strokeWidth = 1f
        p.strokeCap = Paint.Cap.BUTT

        if (row.value.isNotBlank()) {
            val valueColor = when {
                !row.enabled -> Color.rgb(100, 110, 126)
                hover || index == dragIndex -> Color.WHITE
                else -> Color.rgb(184, 206, 238)
            }
            PicoXrMaterial.label(
                c, row.value, rect.right - 26f, rect.centerY() + 9f, 192f, 23f,
                medium = hover || index == dragIndex, color = valueColor, align = Paint.Align.RIGHT
            )
        }
    }

    private fun drawToggleRow(
        c: Canvas,
        rect: RectF,
        row: PicoXrDropdownRow,
        hover: Boolean,
        labelColor: Int
    ) {
        PicoXrMaterial.label(
            c, row.label, rect.left + 26f, rect.centerY() + 10f, 780f, 27f,
            medium = row.selected || hover, color = labelColor
        )
        val on = row.value == "开启" || row.value == "开"
        val pending = row.value.contains("切换中")
        PicoXrMaterial.toggleSwitch(c, rect.right - 24f, rect.centerY(), on, row.enabled, pending)
        if (row.value.isNotBlank() && row.value !in setOf("开启", "开", "关闭", "关")) {
            PicoXrMaterial.label(
                c, row.value, rect.right - 126f, rect.centerY() + 8f, 220f, 21f,
                color = if (pending) PicoTheme.AMBER else PicoTheme.MUTED, align = Paint.Align.RIGHT
            )
        }
    }

    private fun drawOptionRow(
        c: Canvas,
        p: Paint,
        rect: RectF,
        row: PicoXrDropdownRow,
        hover: Boolean,
        labelColor: Int
    ) {
        val labelWidth = if (row.value.isBlank()) 1020f else 700f
        PicoXrMaterial.label(
            c, row.label, rect.left + 26f, rect.centerY() + 10f, labelWidth, 27f,
            medium = row.selected || hover, color = labelColor
        )
        if (row.value.isNotBlank()) {
            val valueColor = when {
                !row.enabled -> Color.rgb(100, 110, 126)
                row.danger -> if (hover) Color.WHITE else PicoTheme.RED
                row.selected -> Color.rgb(142, 192, 255)
                hover -> Color.rgb(222, 234, 252)
                else -> PicoTheme.MUTED
            }
            PicoXrMaterial.label(
                c, row.value, rect.right - 28f, rect.centerY() + 9f, 340f, 24f,
                medium = row.selected, color = valueColor, align = Paint.Align.RIGHT
            )
        } else if (row.selected && row.kind == PicoXrDropdownRow.OPTION) {
            val cx = rect.right - 40f
            val cy = rect.centerY()
            val check = Path().apply {
                moveTo(cx - 10f, cy + 1f)
                lineTo(cx - 2f, cy + 9f)
                lineTo(cx + 12f, cy - 8f)
            }
            p.style = Paint.Style.STROKE
            p.strokeWidth = 3f
            p.strokeCap = Paint.Cap.ROUND
            p.strokeJoin = Paint.Join.ROUND
            p.color = if (hover) Color.WHITE else Color.rgb(142, 192, 255)
            c.drawPath(check, p)
            p.style = Paint.Style.FILL
            p.strokeCap = Paint.Cap.BUTT
            p.strokeJoin = Paint.Join.MITER
        }
    }
}

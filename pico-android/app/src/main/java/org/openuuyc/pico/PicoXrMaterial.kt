package org.openuuyc.pico

import android.graphics.*

/** Shared MR glass-like surfaces. Premultiplied alpha, not camera-image blur. */
internal object PicoXrMaterial {
    private val text = Color.rgb(232, 237, 245)

    fun panel(c: Canvas, w: Float, h: Float) {
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        sheet(c, RectF(3f, 2f, w - 3f, h - 5f), 24f)
    }

    /** Does not clear its canvas: safe for independent cells in the frame atlas. */
    fun sheet(c: Canvas, r: RectF, radius: Float = 20f) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.argb(30, 0, 0, 0)
        c.drawRoundRect(RectF(r.left, r.top + 4f, r.right, r.bottom + 4f), radius, radius, p)
        p.color = Color.argb(42, 0, 0, 0)
        c.drawRoundRect(RectF(r.left, r.top + 2f, r.right, r.bottom + 2f), radius, radius, p)

        p.shader = LinearGradient(
            r.left, r.top, r.right, r.bottom,
            intArrayOf(Color.argb(232, 34, 43, 56), Color.argb(222, 18, 24, 34)),
            null, Shader.TileMode.CLAMP
        )
        c.drawRoundRect(r, radius, radius, p)

        // Top-edge specular glass sheen clipped inside the rounded sheet.
        val sheenHeight = (r.height() * 0.28f).coerceAtLeast(1f)
        val clipPath = Path().apply { addRoundRect(r, radius, radius, Path.Direction.CW) }
        c.save()
        c.clipPath(clipPath)
        p.shader = LinearGradient(
            0f, r.top, 0f, r.top + sheenHeight,
            Color.argb(28, 220, 236, 255), Color.argb(0, 220, 236, 255),
            Shader.TileMode.CLAMP
        )
        c.drawRect(r.left, r.top, r.right, r.top + sheenHeight, p)
        c.restore()

        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.5f
        p.shader = LinearGradient(
            0f, r.top, 0f, r.bottom,
            Color.argb(132, 208, 226, 252), Color.argb(54, 148, 172, 204),
            Shader.TileMode.CLAMP
        )
        c.drawRoundRect(r, radius, radius, p)
        p.shader = null
        p.style = Paint.Style.FILL
    }

    fun control(
        c: Canvas,
        r: RectF,
        selected: Boolean = false,
        hovered: Boolean = false,
        pressed: Boolean = false,
        enabled: Boolean = true,
        danger: Boolean = false,
        radius: Float = 12f
    ) {
        val (top, bottom, stroke) = controlColors(selected, hovered, pressed, enabled, danger)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, r.top, 0f, r.bottom, top, bottom, Shader.TileMode.CLAMP)
        c.drawRoundRect(r, radius, radius, p)
        p.shader = null

        if (enabled && (hovered || selected)) {
            val inset = (radius * 0.7f).coerceAtMost(r.width() * 0.25f)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.2f
            p.color = Color.argb(if (hovered) 78 else 46, 226, 240, 255)
            c.drawLine(r.left + inset, r.top + 1.5f, r.right - inset, r.top + 1.5f, p)
        }

        p.style = Paint.Style.STROKE
        p.strokeWidth = when {
            hovered -> 2f
            selected -> 1.5f
            else -> 1.1f
        }
        p.color = stroke
        c.drawRoundRect(r, radius, radius, p)
        p.style = Paint.Style.FILL
    }

    private fun controlColors(
        selected: Boolean,
        hovered: Boolean,
        pressed: Boolean,
        enabled: Boolean,
        danger: Boolean
    ): Triple<Int, Int, Int> {
        if (danger) {
            return when {
                !enabled -> Triple(Color.argb(96, 46, 30, 34), Color.argb(96, 36, 24, 28), Color.argb(80, 96, 56, 62))
                pressed && hovered -> Triple(Color.argb(244, 184, 54, 68), Color.argb(244, 138, 36, 48), Color.rgb(255, 168, 176))
                hovered -> Triple(Color.argb(236, 152, 48, 60), Color.argb(236, 114, 34, 44), Color.rgb(248, 142, 152))
                selected -> Triple(Color.argb(226, 128, 44, 54), Color.argb(226, 96, 32, 40), PicoTheme.RED)
                else -> Triple(Color.argb(176, 68, 32, 38), Color.argb(176, 50, 24, 30), Color.argb(130, 212, 92, 104))
            }
        }
        return when {
            !enabled -> Triple(Color.argb(104, 30, 36, 46), Color.argb(104, 25, 30, 39), Color.argb(90, 62, 72, 88))
            pressed && hovered -> Triple(Color.argb(246, 66, 126, 222), Color.argb(246, 48, 98, 184), Color.rgb(164, 206, 255))
            hovered -> Triple(Color.argb(238, 56, 98, 162), Color.argb(238, 38, 68, 116), Color.rgb(122, 180, 252))
            selected -> Triple(Color.argb(232, 42, 74, 122), Color.argb(232, 31, 54, 92), PicoTheme.ACCENT)
            else -> Triple(Color.argb(168, 40, 49, 63), Color.argb(168, 31, 38, 50), Color.argb(112, 86, 102, 126))
        }
    }

    fun rowSurface(
        c: Canvas,
        r: RectF,
        enabled: Boolean = true,
        danger: Boolean = false,
        radius: Float = 14f
    ) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = when {
            danger && enabled -> Color.argb(42, 190, 62, 74)
            enabled -> Color.argb(80, 40, 50, 66)
            else -> Color.argb(46, 26, 32, 42)
        }
        c.drawRoundRect(r, radius, radius, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f
        p.color = when {
            danger && enabled -> Color.argb(82, 224, 96, 108)
            enabled -> Color.argb(46, 178, 200, 230)
            else -> Color.argb(28, 140, 156, 180)
        }
        c.drawRoundRect(r, radius, radius, p)
        p.style = Paint.Style.FILL
    }

    fun label(
        c: Canvas,
        value: String,
        x: Float,
        y: Float,
        width: Float,
        size: Float = 26f,
        muted: Boolean = false,
        medium: Boolean = false,
        color: Int? = null,
        align: Paint.Align = Paint.Align.LEFT
    ) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color ?: if (muted) PicoTheme.MUTED else text
            textSize = size
            textAlign = align
            typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        }
        val count = p.breakText(value, true, width.coerceAtLeast(0f), null)
        val display = if (count < value.length && count > 1) value.take(count - 1) + "…" else value.take(count)
        c.drawText(display, x, y, p)
    }

    fun toggleSwitch(
        c: Canvas,
        rightX: Float,
        centerY: Float,
        on: Boolean,
        enabled: Boolean = true,
        pending: Boolean = false
    ) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val track = RectF(rightX - 86f, centerY - 22f, rightX, centerY + 22f)
        val alphaScale = if (enabled) 255 else 115
        fun scaledColor(rgb: Int, targetAlpha: Int = 255): Int {
            val a = (targetAlpha * alphaScale) / 255
            return (rgb and 0x00FFFFFF) or (a shl 24)
        }
        p.style = Paint.Style.FILL
        p.color = when {
            pending -> scaledColor(PicoTheme.AMBER, 180)
            on -> scaledColor(PicoTheme.ACCENT, 245)
            else -> scaledColor(Color.rgb(42, 51, 65), 165)
        }
        c.drawRoundRect(track, 22f, 22f, p)

        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.4f
        p.color = when {
            pending -> scaledColor(PicoTheme.AMBER, 240)
            on -> scaledColor(Color.rgb(138, 186, 255), 255)
            else -> scaledColor(Color.rgb(116, 132, 156), 180)
        }
        c.drawRoundRect(track, 22f, 22f, p)

        val thumbX = when {
            pending -> track.centerX()
            on -> track.right - 22f
            else -> track.left + 22f
        }
        p.style = Paint.Style.FILL
        p.color = Color.argb(if (enabled) 56 else 24, 0, 0, 0)
        c.drawCircle(thumbX, centerY + 1.5f, 17f, p)
        p.color = scaledColor(if (on || pending) Color.WHITE else Color.rgb(218, 226, 238), if (enabled) 255 else 175)
        c.drawCircle(thumbX, centerY, 16.5f, p)
    }

    fun pill(c: Canvas, r: RectF, accent: Boolean = false) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val radius = r.height() / 2f
        p.color = Color.argb(218, 17, 24, 35)
        c.drawRoundRect(r, radius, radius, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.3f
        p.color = if (accent) Color.argb(185, 92, 154, 245) else Color.argb(95, 178, 200, 230)
        c.drawRoundRect(r, radius, radius, p)
        p.style = Paint.Style.FILL
    }

    fun rootIcon(c: Canvas, kind: Int, x: Float, y: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 2.4f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        c.save()
        c.translate(x, y)
        when (kind) {
            0 -> {
                c.drawRoundRect(RectF(7f, 0f, 29f, 34f), 10f, 10f, p)
                c.drawLine(18f, 0f, 18f, 13f, p)
                c.drawLine(8f, 13f, 28f, 13f, p)
            }
            1 -> {
                c.drawRoundRect(RectF(0f, 5f, 36f, 29f), 4f, 4f, p)
                for (row in 0..1) for (col in 0..4) c.drawPoint(6f + col * 6, 11f + row * 6, p)
                c.drawLine(10f, 24f, 26f, 24f, p)
            }
            2 -> {
                c.drawRoundRect(RectF(0f, 1f, 36f, 25f), 3f, 3f, p)
                c.drawLine(18f, 25f, 18f, 33f, p)
                c.drawLine(10f, 33f, 26f, 33f, p)
            }
            3 -> {
                c.drawOval(RectF(0f, 10f, 36f, 25f), p)
                c.drawOval(RectF(10f, 0f, 26f, 35f), p)
                c.drawCircle(18f, 18f, 3f, p)
            }
            4 -> {
                c.drawCircle(18f, 18f, 17f, p)
                c.drawLine(2f, 23f, 11f, 13f, p)
                c.drawLine(11f, 13f, 22f, 26f, p)
                c.drawLine(22f, 26f, 30f, 18f, p)
            }
            5 -> {
                val path = Path().apply {
                    moveTo(1f, 16f); lineTo(18f, 1f); lineTo(35f, 16f)
                    moveTo(6f, 13f); lineTo(6f, 33f); lineTo(30f, 33f); lineTo(30f, 13f)
                }
                c.drawPath(path, p)
            }
            6 -> {
                for (row in 0..1) for (col in 0..1) {
                    c.drawRoundRect(RectF(col * 21f, row * 21f, col * 21f + 14f, row * 21f + 14f), 3f, 3f, p)
                }
            }
            7 -> {
                c.drawArc(RectF(2f, 2f, 34f, 34f), -55f, 290f, false, p)
                c.drawLine(18f, 0f, 18f, 17f, p)
            }
        }
        c.restore()
    }

    fun handle(c: Canvas, w: Float, h: Float, hover: Boolean, pressed: Boolean = false) {
        val active = hover || pressed
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.argb(120, 10, 14, 20)
        val haloLeft = w * (if (active) 0.34f else 0.36f)
        val haloRight = w * (if (active) 0.66f else 0.64f)
        c.drawRoundRect(RectF(haloLeft, h - 24f, haloRight, h - 11f), 7f, 7f, p)

        p.color = when {
            pressed -> Color.rgb(122, 180, 252)
            hover -> PicoTheme.ACCENT
            else -> Color.argb(226, 186, 198, 216)
        }
        val barLeft = w * (if (active) 0.355f else 0.37f)
        val barRight = w * (if (active) 0.645f else 0.63f)
        c.drawRoundRect(RectF(barLeft, h - 22f, barRight, h - 13f), 4.5f, 4.5f, p)
    }
}

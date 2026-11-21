package org.openuuyc.pico

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.view.Surface

/** One atlas row per window: independent hover states, transparent video area. */
internal class PicoXrWindowFrame(private val surface: Surface) {
    private val titles = Array(6) { "正在连接…" }
    private val microphones = BooleanArray(6)
    private val clipboards = BooleanArray(6)

    fun sharing(pane: Int, mic: Boolean, clip: Boolean) {
        if (pane in 0..5 && (microphones[pane] != mic || clipboards[pane] != clip)) {
            microphones[pane] = mic
            clipboards[pane] = clip
            draw()
        }
    }

    fun title(pane: Int, text: String) {
        if (pane in 0..5 && titles[pane] != text) {
            titles[pane] = text
            draw()
        }
    }

    private var background = Color.BLACK
    private var backgroundImage: Bitmap? = null
    private var backgroundAlpha = 0
    private var brightness = 1f

    fun background(color: Int, image: Bitmap?, alpha: Int, brightness: Int) {
        background = color
        backgroundImage = image
        backgroundAlpha = alpha.coerceIn(0, 255)
        this.brightness = brightness.coerceIn(25, 100) / 100f
        draw()
    }

    private var hover = -1
    private var pointerDown = false

    fun pointer(target: Int, down: Boolean) {
        if (target == hover && down == pointerDown) return
        hover = target
        pointerDown = down
        draw()
    }

    fun hover(target: Int) {
        if (target != hover) {
            hover = target
            draw()
        }
    }

    fun draw() {
        if (!surface.isValid) return
        val c = runCatching { surface.lockCanvas(null) }.getOrNull() ?: return
        try {
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            repeat(6) { pane ->
                c.save()
                c.translate((pane % 2) * 1200f, (pane / 2) * 800f)
                val layout = PicoXrFrameLayout
                val left = layout.WIDTH / 2f - layout.WIDTH / (2f * layout.SCALE_X)
                val right = layout.WIDTH - left
                val bottom = layout.HEIGHT / 2f + layout.HEIGHT / (2f * layout.SCALE_Y)

                val paneHovered = hover == 7 + pane ||
                    hover in (13 + pane * 4)..(16 + pane * 4) ||
                    hover in setOf(37 + pane, 43 + pane, 49 + pane, 55 + pane, 61 + pane, 67 + pane, 73 + pane)

                // Thin frosted connected-device title bar; the video area stays transparent.
                val header = Path().apply {
                    addRoundRect(
                        RectF(left, 8f, right, 55.2f),
                        floatArrayOf(18f, 18f, 18f, 18f, 0f, 0f, 0f, 0f),
                        Path.Direction.CW
                    )
                }
                p.shader = LinearGradient(
                    0f, 8f, 0f, 55.2f,
                    Color.argb(236, 30, 38, 50), Color.argb(228, 19, 25, 34),
                    Shader.TileMode.CLAMP
                )
                c.drawPath(header, p)
                p.shader = null

                p.style = Paint.Style.STROKE
                p.strokeWidth = 1f
                p.color = Color.argb(72, 212, 228, 252)
                c.drawLine(left + 18f, 9.5f, right - 18f, 9.5f, p)

                p.strokeWidth = if (paneHovered) 1.8f else 1.3f
                p.color = if (paneHovered) Color.argb(155, 118, 174, 248) else Color.argb(95, 172, 190, 214)
                c.drawRoundRect(RectF(left, 8f, right, bottom), 18f, 18f, p)
                p.style = Paint.Style.FILL

                fun region(values: FloatArray, target: Int, active: Boolean = false, restingChip: Boolean = false) {
                    val rect = RectF(values[0], values[1], values[2], values[3])
                    if (hover == target || active) {
                        PicoXrMaterial.control(c, rect, active, hover == target, pointerDown, radius = 10f)
                    } else if (restingChip) {
                        p.style = Paint.Style.FILL
                        p.color = Color.argb(72, 44, 54, 70)
                        c.drawRoundRect(rect, 10f, 10f, p)
                        p.style = Paint.Style.STROKE
                        p.strokeWidth = 0.9f
                        p.color = Color.argb(55, 165, 184, 210)
                        c.drawRoundRect(rect, 10f, 10f, p)
                        p.style = Paint.Style.FILL
                    }
                }

                fun chevron(cx: Float, cy: Float, color: Int) {
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = 2.1f
                    p.strokeCap = Paint.Cap.ROUND
                    p.strokeJoin = Paint.Join.ROUND
                    p.color = color
                    val path = Path().apply {
                        moveTo(cx - 6f, cy - 3f)
                        lineTo(cx, cy + 3f)
                        lineTo(cx + 6f, cy - 3f)
                    }
                    c.drawPath(path, p)
                    p.style = Paint.Style.FILL
                    p.strokeCap = Paint.Cap.BUTT
                    p.strokeJoin = Paint.Join.MITER
                }

                region(layout.device, 49 + pane, restingChip = true)
                region(layout.screen, 55 + pane, restingChip = true)

                val deviceHover = hover == 49 + pane
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2.1f
                p.color = if (deviceHover) Color.WHITE else PicoTheme.MUTED
                c.drawRoundRect(RectF(64f, 23f, 85f, 37f), 2f, 2f, p)
                c.drawLine(74.5f, 37f, 74.5f, 41f, p)
                c.drawLine(69f, 41f, 80f, 41f, p)
                p.style = Paint.Style.FILL

                val deviceName = titles[pane].substringBefore(" - ")
                PicoXrMaterial.label(
                    c, deviceName, 96f, 40f, 194f, 22f,
                    medium = true, color = if (deviceHover) Color.WHITE else PicoTheme.TEXT
                )
                chevron(308f, 32f, if (deviceHover) Color.WHITE else PicoTheme.MUTED)

                p.color = Color.argb(65, 182, 194, 210)
                c.drawRect(336f, 21f, 337f, 43f, p)

                val screenHover = hover == 55 + pane
                val screenName = titles[pane].substringAfter(" - ", "屏幕")
                PicoXrMaterial.label(
                    c, screenName, 362f, 40f, 382f, 22f,
                    color = if (screenHover) Color.WHITE else PicoTheme.TEXT
                )
                chevron(764f, 32f, if (screenHover) Color.WHITE else PicoTheme.MUTED)

                for (slot in 0..1) {
                    val active = if (slot == 0) microphones[pane] else clipboards[pane]
                    val rect = if (slot == 0) layout.microphone else layout.clipboard
                    val target = (if (slot == 0) 67 else 73) + pane
                    region(rect, target, active = active)
                    val iconColor = when {
                        hover == target -> Color.WHITE
                        active -> PicoTheme.ACCENT
                        else -> PicoTheme.MUTED
                    }
                    PicoToolbarIcons.draw(
                        c, if (slot == 0) "mic" else "clipboard",
                        rect[0] + 9f, 19.5f, 24f, iconColor
                    )
                    if (slot == 0 && active) {
                        p.style = Paint.Style.FILL
                        p.color = PicoTheme.GREEN
                        c.drawCircle(rect[2] - 7f, rect[1] + 8f, 3.5f, p)
                    }
                }

                region(layout.quality, 43 + pane)
                region(layout.add, 61 + pane)
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2f
                p.strokeCap = Paint.Cap.ROUND
                p.color = if (hover == 43 + pane) Color.WHITE else PicoTheme.MUTED
                for (i in 0..2) {
                    val y = 24f + i * 8f
                    c.drawLine(922f, y, 944f, y, p)
                    c.drawCircle(if (i % 2 == 0) 929f else 939f, y, 2.4f, p)
                }
                p.color = if (hover == 61 + pane) Color.WHITE else PicoTheme.MUTED
                c.drawLine(985f, 32f, 1005f, 32f, p)
                c.drawLine(995f, 22f, 995f, 42f, p)

                val closeHover = hover == 37 + pane
                p.style = Paint.Style.FILL
                p.color = when {
                    closeHover && pointerDown -> Color.argb(242, 184, 54, 68)
                    closeHover -> Color.argb(232, 166, 48, 60)
                    else -> Color.argb(46, 230, 96, 107)
                }
                c.drawCircle(1125f, 32f, 15f, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = 1f
                p.color = if (closeHover) Color.rgb(255, 168, 176) else Color.argb(115, 228, 108, 118)
                c.drawCircle(1125f, 32f, 15f, p)

                p.strokeWidth = 2.4f
                p.color = if (closeHover) Color.WHITE else Color.rgb(235, 118, 128)
                c.drawLine(1119.5f, 26.5f, 1130.5f, 37.5f, p)
                c.drawLine(1130.5f, 26.5f, 1119.5f, 37.5f, p)

                // VisionOS-style bottom rail: subtle halo + luminous pill on hover/drag.
                val dragHover = hover == 7 + pane
                p.style = Paint.Style.FILL
                p.color = Color.argb(145, 12, 16, 23)
                c.drawRoundRect(
                    if (dragHover) RectF(464f, 761f, 736f, 787f) else RectF(474f, 763f, 726f, 785f),
                    13f, 13f, p
                )
                p.color = when {
                    dragHover && pointerDown -> Color.rgb(122, 180, 252)
                    dragHover -> PicoTheme.ACCENT
                    else -> Color.argb(220, 176, 188, 206)
                }
                c.drawRoundRect(
                    if (dragHover) RectF(472f, 767f, 728f, 781f) else RectF(484f, 768f, 716f, 780f),
                    7f, 7f, p
                )

                val tooltip = when (hover) {
                    49 + pane -> "切换或添加设备"
                    55 + pane -> "切换显示器或窗口"
                    67 + pane -> if (microphones[pane]) "麦克风：已开启" else "麦克风：已关闭"
                    73 + pane -> if (clipboards[pane]) "剪贴板同步：已开启" else "剪贴板同步：已关闭"
                    43 + pane -> "画质与窗口设置"
                    61 + pane -> "创建虚拟屏"
                    37 + pane -> "关闭此窗口"
                    7 + pane -> "拖动移动 · 摇杆调远近"
                    in (13 + pane * 4)..(16 + pane * 4) -> "拖拽等比缩放"
                    else -> null
                }
                if (tooltip != null) {
                    val tipRect = RectF(764f, 756f, 1160f, 790f)
                    PicoXrMaterial.pill(c, tipRect, accent = true)
                    PicoXrMaterial.label(
                        c, tooltip, tipRect.centerX(), tipRect.centerY() + 7f,
                        tipRect.width() - 28f, 20f, medium = true,
                        color = Color.rgb(220, 232, 248), align = Paint.Align.CENTER
                    )
                }

                // Curved external corner resize arcs hugging outside the video quad.
                p.style = Paint.Style.STROKE
                p.strokeCap = Paint.Cap.ROUND
                repeat(4) { corner ->
                    val activeCorner = hover == 13 + pane * 4 + corner
                    p.strokeWidth = if (activeCorner) 4.2f else 2.6f
                    p.color = if (activeCorner) PicoTheme.ACCENT else Color.argb(64, 210, 224, 245)
                    val rightCorner = corner % 2 == 1
                    val low = corner >= 2
                    val arcRect = RectF(
                        if (rightCorner) 1128f else 20f,
                        if (low) 706f else 8f,
                        if (rightCorner) 1180f else 72f,
                        if (low) 758f else 60f
                    )
                    val startAngle = when (corner) {
                        0 -> 180f
                        1 -> 270f
                        2 -> 90f
                        else -> 0f
                    }
                    c.drawArc(arcRect, startAngle, 90f, false, p)
                }
                p.style = Paint.Style.FILL
                p.strokeCap = Paint.Cap.BUTT
                c.restore()
            }

            // Atlas-only white tile: reserved outside all window subimages.
            p.style = Paint.Style.FILL
            p.color = Color.WHITE
            c.drawRect(0f, 2400f, 32f, 2432f, p)
            // Transparent circular pointer tiles, sampled by native billboard quads.
            p.color = Color.WHITE
            c.drawCircle(48f, 2416f, 12f, p)
            p.color = PicoTheme.ACCENT
            c.drawCircle(80f, 2416f, 12f, p)
            // Win7 outline ring, Win11 translucent touch target, local arrow.
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2.5f
            p.color = Color.WHITE
            c.drawCircle(112f, 2416f, 11f, p)
            p.style = Paint.Style.FILL
            p.color = Color.argb(100, 180, 220, 255)
            c.drawCircle(144f, 2416f, 13f, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.5f
            p.color = Color.WHITE
            c.drawCircle(144f, 2416f, 12f, p)
            val arrow = Path().apply {
                moveTo(174f, 2416f); lineTo(174f, 2430f); lineTo(180f, 2424f); lineTo(187f, 2424f); close()
            }
            p.style = Paint.Style.FILL
            p.color = Color.WHITE
            c.drawPath(arrow, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.5f
            p.color = Color.BLACK
            c.drawPath(arrow, p)
            p.style = Paint.Style.FILL
            c.save()
            c.clipRect(0, 2432, 1200, 3232)
            p.color = background
            p.alpha = backgroundAlpha
            p.colorFilter = android.graphics.ColorMatrixColorFilter(
                floatArrayOf(brightness, 0f, 0f, 0f, 0f, 0f, brightness, 0f, 0f, 0f, 0f, 0f, brightness, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
            )
            if (backgroundImage == null || backgroundImage?.isRecycled == true) {
                c.drawRect(0f, 2432f, 1200f, 3232f, p)
            }
            backgroundImage?.takeUnless { it.isRecycled }?.let { image ->
                val scale = maxOf(1200f / image.width, 800f / image.height)
                val w = image.width * scale
                val h = image.height * scale
                p.color = Color.WHITE
                p.alpha = backgroundAlpha
                p.isFilterBitmap = true
                c.drawBitmap(image, null, RectF((1200f - w) / 2f, 2432f + (800f - h) / 2f, (1200f + w) / 2f, 2432f + (800f + h) / 2f), p)
            }
            c.restore()
        } finally {
            runCatching { surface.unlockCanvasAndPost(c) }
        }
    }

    fun close() {
        surface.release()
    }
}

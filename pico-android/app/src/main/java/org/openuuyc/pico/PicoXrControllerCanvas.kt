package org.openuuyc.pico

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.sin

/** MR controller dashboard: one radial hub, no independent floating tool cards. */
internal object PicoXrControllerCanvas {
    fun draw(
        c: Canvas,
        labels: List<String>,
        following: Boolean,
        pending: Boolean,
        hand: Int,
        u: Float,
        v: Float,
        notice: String,
        icons: Boolean = true,
        title: String = "快速控制",
        previous: Boolean = false,
        next: Boolean = false,
        appIcons: List<android.graphics.Bitmap?> = emptyList(),
        pressed: Boolean = false,
        mr: Boolean = true
    ) {
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val hovered = PicoXrMenuHit.toolbar(u, v)

        // Subtle outer ambient shadow behind the radial ring for bright MR scenes.
        p.style = Paint.Style.FILL
        p.color = Color.argb(45, 0, 0, 0)
        c.drawCircle(600f, 414f, 314f, p)

        // Top-left mode & anchor status pill balancing the top-right collapse pill.
        val statusPill = RectF(18f, 18f, 326f, 72f)
        PicoXrMaterial.pill(c, statusPill)
        p.style = Paint.Style.FILL
        p.color = if (mr) PicoTheme.GREEN else PicoTheme.ACCENT
        c.drawCircle(statusPill.left + 26f, statusPill.centerY(), 5.5f, p)
        val handLabel = when (hand) {
            0 -> "左手柄"
            1 -> "右手柄"
            else -> "悬浮"
        }
        PicoXrMaterial.label(
            c, "${if (mr) "MR 透视" else "VR 环境"} · $handLabel",
            statusPill.left + 44f, statusPill.centerY() + 7f,
            statusPill.width() - 60f, 21f, medium = true,
            color = Color.rgb(218, 226, 238)
        )

        val outer = RectF(295f, 105f, 905f, 715f)
        val inner = RectF(458f, 268f, 742f, 552f)
        for (i in labels.indices.take(8)) {
            if (labels[i].isBlank()) continue
            val isHover = hovered == i
            val isDanger = i == 7
            val start = -110.5f + i * 45f
            val end = start + 41f
            val a = Math.toRadians(end.toDouble())
            val wedge = Path().apply {
                arcTo(outer, start, 41f)
                lineTo(600f + 142f * cos(a).toFloat(), 410f + 142f * sin(a).toFloat())
                arcTo(inner, end, -41f)
                close()
            }

            p.style = Paint.Style.FILL
            p.color = when {
                isDanger && isHover && pressed -> Color.argb(250, 146, 38, 50)
                isDanger && isHover -> Color.argb(245, 166, 48, 60)
                isDanger -> Color.argb(224, 48, 26, 34)
                isHover && pressed -> Color.argb(250, 44, 96, 178)
                isHover -> Color.argb(246, 56, 114, 204)
                else -> Color.argb(228, 28, 37, 50)
            }
            c.drawPath(wedge, p)

            p.style = Paint.Style.STROKE
            p.strokeWidth = if (isHover) 2.8f else 1.6f
            p.color = when {
                isDanger && isHover -> Color.rgb(255, 168, 176)
                isDanger -> Color.argb(145, 224, 98, 110)
                isHover -> Color.rgb(138, 202, 255)
                else -> Color.argb(115, 140, 172, 212)
            }
            c.drawPath(wedge, p)

            val r = PicoXrMenuHit.toolbarRect(i)
            val x = (r[0] + r[2]) / 2f
            val y = (r[1] + r[3]) / 2f
            val contentColor = when {
                isDanger -> if (isHover) Color.WHITE else PicoTheme.RED
                isHover -> Color.WHITE
                else -> PicoTheme.TEXT
            }
            val appIcon = appIcons.getOrNull(i)
            p.style = Paint.Style.FILL
            if (appIcon != null) c.drawBitmap(appIcon, null, RectF(x - 28f, y - 48f, x + 28f, y + 8f), p)
            if (icons) PicoXrMaterial.rootIcon(c, i, x - 18f, y - 30f, contentColor)
            PicoXrMaterial.label(
                c, labels[i], x, y + if (icons || appIcon != null) 38f else 8f,
                152f, if (icons) 24f else 21f, medium = isHover,
                color = contentColor, align = Paint.Align.CENTER
            )
        }

        // Frosted central hub dial.
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(
            600f, 276f, 600f, 544f,
            Color.argb(242, 22, 30, 42), Color.argb(236, 15, 21, 31),
            Shader.TileMode.CLAMP
        )
        c.drawCircle(600f, 410f, 134f, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.8f
        p.color = if (following) PicoTheme.ACCENT else Color.argb(120, 176, 200, 232)
        c.drawCircle(600f, 410f, 134f, p)
        p.style = Paint.Style.FILL

        PicoXrMaterial.label(
            c, title, 600f, 322f, 220f, 20f,
            muted = true, medium = true, align = Paint.Align.CENTER
        )

        for (i in if (icons) 8..10 else 10..10) {
            val r = PicoXrMenuHit.toolbarRect(i)
            val rect = RectF(r[0], r[1], r[2], r[3])
            val selected = (i == 8 && following) || (i == 9 && !following)
            val isHover = hovered == i
            val btnEnabled = if (icons) !(pending && i in 8..9) else i == 10 || (i == 8 && previous) || (i == 9 && next)
            PicoXrMaterial.control(c, rect, selected, isHover, pressed && isHover, enabled = btnEnabled, radius = 14f)
            if (icons && selected && i in 8..9) {
                p.style = Paint.Style.FILL
                p.color = if (pending) PicoTheme.AMBER else PicoTheme.ACCENT
                c.drawCircle(rect.left + 22f, rect.centerY(), 4.5f, p)
            }
            val btnLabel = when {
                !icons -> when (i) {
                    8 -> if (previous) "‹ 上一页" else "首页"
                    9 -> if (next) "下一页 ›" else "末页"
                    else -> "返回"
                }
                else -> when (i) {
                    8 -> if (pending) "切换中…" else "跟随视角"
                    9 -> "固定窗口"
                    else -> "收起"
                }
            }
            val textX = if (icons && selected && i in 8..9) rect.centerX() + 6f else rect.centerX()
            val textColor = when {
                !btnEnabled -> PicoTheme.MUTED
                isHover || selected -> Color.WHITE
                else -> PicoTheme.TEXT
            }
            PicoXrMaterial.label(
                c, btnLabel, textX, rect.centerY() + 8f, rect.width() - 18f, 23f,
                medium = selected || isHover, color = textColor, align = Paint.Align.CENTER
            )
        }

        // Bottom frosted status pill; keep clear of the v >= 0.92 grab rail.
        val noticePill = RectF(180f, 722f, 1020f, 766f)
        PicoXrMaterial.pill(c, noticePill)
        val footerText = notice.ifBlank { "X / A 唤起 · 摇杆选择 · 快速松开执行" }
        PicoXrMaterial.label(
            c, footerText, 600f, noticePill.centerY() + 7f,
            noticePill.width() - 48f, 20f, color = Color.rgb(206, 216, 232),
            align = Paint.Align.CENTER
        )

        if (hand == 2) {
            PicoXrMaterial.handle(c, 1200f, 800f, hover = (u in 0f..1f && v >= 0.92f), pressed = pressed)
        }
    }

    fun collapsed(c: Canvas, hand: Int, hovered: Boolean = false, pressed: Boolean = false) {
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val pillRect = RectF(32f, 32f, 1168f, 368f)
        if (hovered || pressed) {
            PicoXrMaterial.control(c, pillRect, hovered = hovered, pressed = pressed, radius = 120f)
        } else {
            PicoXrMaterial.sheet(c, pillRect, 120f)
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PicoTheme.ACCENT }
        c.drawCircle(136f, 200f, 14f, p)
        val label = when (hand) {
            0 -> "左手菜单"
            1 -> "右手菜单"
            else -> "悬浮菜单"
        }
        PicoXrMaterial.label(
            c, "UU · $label  ▸", 616f, 224f, 900f, 68f,
            medium = true, color = if (hovered) Color.WHITE else PicoTheme.TEXT,
            align = Paint.Align.CENTER
        )
    }
}

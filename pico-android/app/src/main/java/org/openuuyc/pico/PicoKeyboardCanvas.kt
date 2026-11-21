package org.openuuyc.pico

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

internal object PicoKeyboardCanvas {
    private val specialKeys = setOf(
        "Esc", "PrtSc", "ScrLk", "Pause", "Tab", "Caps", "Backspace", "Enter", "NumEnter",
        "Shift", "RShift", "Ctrl", "RCtrl", "Alt", "RAlt", "Win", "RWin", "Menu",
        "Insert", "Delete", "Home", "End", "PgUp", "PgDn", "↑", "↓", "←", "→", "Num"
    )

    private fun isSpecialKey(label: String): Boolean =
        label in specialKeys || (label.startsWith("F") && label.drop(1).toIntOrNull() in 1..12)

    fun draw(
        c: Canvas,
        mode: PicoKeyboardMode,
        keys: PicoKeyboardKeys,
        u: Float,
        v: Float,
        voiceActive: Boolean = false,
        pressed: Boolean = false,
        notice: String = ""
    ) {
        // This Surface is shared with the XR menus. Repaint the entire frame:
        // translucent keys must never blend with a previous menu or hover frame.
        val height = PicoKeyboardLayout.height(mode)
        PicoXrMaterial.panel(c, 1200f, height)
        val title = when (mode) {
            PicoKeyboardMode.FULL -> "全键盘 · 104 键"
            PicoKeyboardMode.CUSTOM -> "自定义键盘"
            PicoKeyboardMode.SYSTEM -> "系统键盘"
            PicoKeyboardMode.VOICE -> "语音输入"
        }
        PicoXrMaterial.label(c, title, 20f, 32f, if (notice.isBlank()) 1160f else 480f, 22f, medium = true)
        if (notice.isNotBlank()) {
            PicoXrMaterial.label(
                c, notice, 1176f, 32f, 660f, 19f,
                color = Color.rgb(184, 202, 226), align = Paint.Align.RIGHT
            )
        }
        if (mode == PicoKeyboardMode.FULL || mode == PicoKeyboardMode.CUSTOM) {
            PicoXrMaterial.label(c, "点按输入 · Ctrl / Shift / Alt / Win 点按锁定，再点释放", 25f, height - 18f, 1150f, 18f, muted = true)
        }

        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 24f }
        val header = PicoKeyboardLayout.headerAt(mode, u, v)
        val headers = listOf(
            Triple("输入方式 ›", 7f, 383f),
            Triple("Codex 语音 · F8", 393f, 953f),
            Triple("返回", 967f, 1193f)
        )
        headers.forEachIndexed { i, (label, left, right) ->
            val rect = RectF(left, 48f, right, 90f)
            val isHover = header == PicoKeyboardLayout.Header.entries[i]
            PicoXrMaterial.control(c, rect, hovered = isHover, pressed = pressed && isHover, radius = 10f)
            when (i) {
                2 -> PicoXrMaterial.label(
                    c, label, rect.centerX(), 77f, rect.width() - 24f, 24f,
                    medium = isHover, color = if (isHover) Color.WHITE else PicoTheme.TEXT, align = Paint.Align.CENTER
                )
                1 -> {
                    PicoXrMaterial.label(
                        c, label, rect.centerX() - 18f, 77f, rect.width() - 96f, 24f,
                        medium = isHover, color = if (isHover) Color.WHITE else PicoTheme.TEXT, align = Paint.Align.CENTER
                    )
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = 2.5f
                    p.color = if (isHover) Color.WHITE else PicoTheme.ACCENT
                    c.drawRoundRect(RectF(914f, 55f, 926f, 72f), 6f, 6f, p)
                    c.drawArc(RectF(908f, 61f, 932f, 80f), 0f, 180f, false, p)
                    c.drawLine(920f, 80f, 920f, 85f, p)
                    p.style = Paint.Style.FILL
                }
                else -> PicoXrMaterial.label(
                    c, label, left + 24f, 77f, rect.width() - 40f, 24f,
                    medium = isHover, color = if (isHover) Color.WHITE else PicoTheme.TEXT
                )
            }
        }

        if (mode == PicoKeyboardMode.VOICE) {
            val micHover = u.isFinite() && v.isFinite() &&
                ((u - 0.5f) * (u - 0.5f) * 9f + (v - 0.55f) * (v - 0.55f) < 0.16f * 0.16f)
            if (voiceActive) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2.2f
                p.color = Color.argb(110, 241, 125, 132)
                c.drawCircle(600f, 220f, 76f, p)
                p.color = Color.argb(55, 241, 125, 132)
                c.drawCircle(600f, 220f, 90f, p)
                p.style = Paint.Style.FILL
            } else if (micHover) {
                p.style = Paint.Style.FILL
                p.color = Color.argb(55, 75, 136, 235)
                c.drawCircle(600f, 220f, 78f, p)
            }

            p.style = Paint.Style.FILL
            p.color = when {
                voiceActive -> PicoTheme.RED
                micHover && pressed -> Color.rgb(54, 108, 204)
                micHover -> Color.rgb(92, 154, 248)
                else -> PicoTheme.ACCENT
            }
            c.drawCircle(600f, 220f, 64f, p)

            p.style = Paint.Style.STROKE
            p.strokeWidth = 5f
            p.strokeCap = Paint.Cap.ROUND
            p.color = Color.WHITE
            c.drawRoundRect(RectF(587f, 185f, 613f, 226f), 13f, 13f, p)
            c.drawArc(RectF(578f, 202f, 622f, 242f), 0f, 180f, false, p)
            c.drawLine(600f, 242f, 600f, 254f, p)
            c.drawLine(589f, 254f, 611f, 254f, p)
            p.style = Paint.Style.FILL
            p.strokeCap = Paint.Cap.BUTT

            PicoXrMaterial.label(
                c,
                if (voiceActive) "正在聆听 · 松开 / 再次点击结束" else "点击开始再点结束，或按住说话松开结束",
                600f, 336f, 960f, 25f,
                medium = voiceActive,
                color = if (voiceActive) Color.rgb(255, 210, 214) else PicoTheme.TEXT,
                align = Paint.Align.CENTER
            )
            return
        }

        if (mode == PicoKeyboardMode.SYSTEM) {
            val card = RectF(160f, 128f, 1040f, 344f)
            PicoXrMaterial.rowSurface(c, card, enabled = true, radius = 16f)
            PicoXrMaterial.label(
                c, "系统原生键盘已就绪", 600f, 212f, 780f, 30f,
                medium = true, align = Paint.Align.CENTER
            )
            PicoXrMaterial.label(
                c, "使用原生系统键盘输入多语言文字，确认发送后整段传送到远端",
                600f, 266f, 800f, 22f, muted = true, align = Paint.Align.CENTER
            )
            return
        }

        val hover = PicoKeyboardLayout.keyAt(mode, u, v)
        PicoKeyboardLayout.keys(mode).forEach { key ->
            val label = PicoKeyboardLayout.legend(key.label)
            val box = RectF(key.x + 2f, key.y + 2f, key.x + key.width - 2f, key.y + key.height - 2f)
            val isActive = keys.active(key.label)
            val isHover = hover == key.label
            if (isActive || isHover || !isSpecialKey(key.label)) {
                PicoXrMaterial.control(c, box, isActive, isHover, pressed && isHover, radius = 8f)
            } else {
                p.style = Paint.Style.FILL
                p.color = Color.argb(185, 25, 31, 41)
                c.drawRoundRect(box, 8f, 8f, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = 1f
                p.color = Color.argb(105, 76, 92, 114)
                c.drawRoundRect(box, 8f, 8f, p)
                p.style = Paint.Style.FILL
            }
            if (isActive) {
                p.style = Paint.Style.FILL
                p.color = Color.rgb(122, 198, 255)
                c.drawCircle(box.right - 10f, box.top + 10f, 4f, p)
            }
            p.color = if (isHover || isActive) Color.WHITE else PicoTheme.TEXT
            p.textSize = if (label.length > 5) 16f else if (label.length > 3) 18f else 21f
            while (p.measureText(label) > box.width() - 8f && p.textSize > 12f) p.textSize -= 1f
            val secondary = if (mode == PicoKeyboardMode.FULL) PicoKeyboardLayout.shifted(key.label) else null
            val x = key.x + (key.width - p.measureText(label)) / 2f
            c.drawText(label, x, key.y + key.height * (if (secondary != null) 0.8f else 0.63f), p)
            if (secondary != null) {
                p.textSize = 12f
                p.color = PicoTheme.MUTED
                c.drawText(secondary, key.x + 7f, key.y + 15f, p)
            }
        }
    }
}

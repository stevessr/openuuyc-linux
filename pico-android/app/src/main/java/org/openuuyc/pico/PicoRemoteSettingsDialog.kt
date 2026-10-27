package org.openuuyc.pico

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.widget.*
import org.openuuyc.pico.uu.*

internal object PicoRemoteSettingsDialog {
    fun show(activity: Activity, session: UuDirectSession, smartIme: Boolean, onSmartIme: (Boolean) -> Unit, report: (String) -> Unit) {
        val snapshot = session.settingsSnapshot()
        val screen = snapshot.screens.firstOrNull { it.id == snapshot.selected }
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 16, 24, 16) }
        fun title(value: String) { root.addView(TextView(activity).apply { text = value; textSize = 14f; setTextColor(PicoTheme.TEXT); setPadding(0, 16, 0, 6) }) }
        fun <T> select(label: String, values: List<T>, current: T?): Spinner {
            title(label)
            return Spinner(activity).apply {
                adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, values.map { it.toString() })
                setSelection(values.indexOf(current).coerceAtLeast(0)); isEnabled = values.isNotEmpty()
                root.addView(this)
            }
        }
        val profiles = snapshot.selectableProfiles()
        val codec = select("编码格式（双方实际能力交集）", profiles, profiles.firstOrNull { it.codec == snapshot.current.codec })
        val maxFps = minOf(screen?.fps?.coerceAtLeast(30) ?: 60, profiles.firstOrNull { it.codec == snapshot.current.codec }?.fps ?: 60)
        var rates = listOf(30, 60, 90, 120, 144, 165, 240).filter { it <= maxFps }
        val fps = select("帧率", rates, snapshot.current.fps)
        val qualityNames = listOf("流畅", "清晰", "高清", "原画", "自动", "自定义码率")
        val quality = select("画质", qualityNames, qualityNames.getOrNull(snapshot.current.quality - 1))
        title("自定义码率（Mbps，1–500；自定义画质时生效）")
        val bitrate = EditText(activity).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER; setText(snapshot.current.bitrateMbps.toString()); root.addView(this) }
        var sizes = listOf(UuDisplayMode(1280, 720), UuDisplayMode(1920, 1080), UuDisplayMode(2560, 1440), UuDisplayMode(3840, 2160)).filter { size ->
            profiles.any { size.width <= it.width && size.height <= it.height }
        }
        val resolution = select("串流最大尺寸（不改变物理显示器）", sizes, UuDisplayMode(snapshot.current.maxWidth, snapshot.current.maxHeight))
        val cursor = Switch(activity).apply { text = "采集远端光标"; isChecked = snapshot.current.cursor; root.addView(this) }
        val ime = Switch(activity).apply { text = "智能键盘（点击时检测远端文字光标）"; isChecked = smartIme; root.addView(this) }
        title("远端物理屏幕参数（仅对端明确开放时可修改）")
        val physicalChoices = listOf<UuDisplayMode?>(null) + screen?.modes.orEmpty()
        val physical = select("物理分辨率", physicalChoices.map { it?.toString() ?: "保持不变" }, "保持不变").apply { isEnabled = snapshot.captureLevel >= 5 && screen?.modes?.isNotEmpty() == true }
        val dpiChoices = listOf(0) + screen?.dpis.orEmpty()
        val dpi = select("DPI 缩放", dpiChoices.map { if (it == 0) "保持不变" else "$it%" }, "保持不变").apply { isEnabled = snapshot.captureLevel >= 5 && screen?.dpis?.isNotEmpty() == true }
        title("HDR / 10-bit / 4:4:4：当前 Android 显示链路不支持，未开放伪设置。虚拟屏、文件、剪贴板等独立业务尚未完整接入。")
        codec.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val profile = profiles.getOrNull(position) ?: return
                val ceiling = minOf(profile.fps, screen?.fps?.coerceAtLeast(30) ?: 60)
                rates = listOf(30, 60, 90, 120, 144, 165, 240).filter { it <= ceiling }
                fps.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, rates.map { it.toString() })
                fps.setSelection(rates.indexOf(snapshot.current.fps).coerceAtLeast(0))
                sizes = listOf(UuDisplayMode(1280, 720), UuDisplayMode(1920, 1080), UuDisplayMode(2560, 1440), UuDisplayMode(3840, 2160)).filter { it.width <= profile.width && it.height <= profile.height }
                resolution.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, sizes.map { it.toString() })
                resolution.setSelection(sizes.indexOf(UuDisplayMode(snapshot.current.maxWidth, snapshot.current.maxHeight)).coerceAtLeast(0))
            }
        }
        val scroll = ScrollView(activity).apply { addView(root) }
        val dialog = AlertDialog.Builder(activity).setTitle("远端屏幕与串流设置").setView(scroll)
            .setNegativeButton("取消", null).setPositiveButton("应用", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = profiles.isNotEmpty() && snapshot.pending == null
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val profile = profiles[codec.selectedItemPosition]
                    val size = sizes[resolution.selectedItemPosition]
                    val settings = snapshot.current.copy(codec = profile.codec, fps = rates[fps.selectedItemPosition], quality = quality.selectedItemPosition + 1,
                        bitrateMbps = bitrate.text.toString().toInt(), maxWidth = size.width, maxHeight = size.height, cursor = cursor.isChecked)
                    val mode = if (physical.isEnabled) physicalChoices[physical.selectedItemPosition] else null
                    val scale = if (dpi.isEnabled) dpiChoices[dpi.selectedItemPosition] else 0
                    fun apply() {
                        if (session.applySettings(settings, mode, scale)) { onSmartIme(ime.isChecked); dialog.dismiss() }
                        else report("当前控制通道未就绪，设置未发送")
                    }
                    if (mode != null || scale != 0) AlertDialog.Builder(activity).setTitle("修改远端物理屏幕？")
                        .setMessage("这会影响远端电脑本机用户，且可能被合成器或权限拒绝。")
                        .setNegativeButton("取消", null).setPositiveButton("确认修改") { _, _ -> apply() }.show()
                    else apply()
                } catch (error: Exception) { report(error.message?.takeIf { error is IllegalArgumentException || error is IllegalStateException } ?: "设置值无效，未发送") }
            }
        }
        dialog.show()
        dialog.window?.setLayout((activity.resources.displayMetrics.widthPixels * .7).toInt(), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}

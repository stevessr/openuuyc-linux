package org.openuuyc.pico

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.text.Editable
import android.text.TextWatcher
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.openuuyc.pico.uu.UuDevice
import org.openuuyc.pico.PicoTheme as T

/** Linux control-center layout adapted to a resizable PICO Android panel. */
internal class PicoLoginView(
    context: Context,
    private val onLogin: () -> Unit,
    private val onRefresh: () -> Unit,
    private val onLogout: () -> Unit,
    private val onPassthrough: (Boolean) -> Unit,
    private val onCamera: () -> Unit,
    private val cameraPreview: View,
    passthroughEnabled: Boolean,
    private val onConnect: (UuDevice) -> Unit,
    private val onRestore: (() -> Unit)? = null,
    private val onImmersiveConnect: ((UuDevice) -> Unit)? = null,
    private val onSmsLogin: (() -> Unit)? = null
) : FrameLayout(context) {
    private val state = PicoPanelState()
    private var restoreRetry = false
    private var authenticated = false
    private var currentWidth = resources.configuration.screenWidthDp
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private fun box(color: Int, radius: Int = T.PANEL_RADIUS, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        stroke?.let { setStroke(dp(1), it) }
    }
    private fun label(value: String, size: Float = 14f, color: Int = T.TEXT, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color)
        setLineSpacing(dp(3).toFloat(), 1f)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun LinearLayout.gap(n: Int) { addView(View(context), LinearLayout.LayoutParams(1, dp(n))) }
    private fun divider(vertical: Boolean = false) = View(context).apply {
        setBackgroundColor(T.LINE)
        layoutParams = LinearLayout.LayoutParams(if (vertical) dp(1) else -1, if (vertical) -1 else dp(1))
    }
    private fun button(value: String, primary: Boolean = false, action: () -> Unit) = Button(context).apply {
        text = value; textSize = 14f; isAllCaps = false
        setTextColor(if (primary) Color.WHITE else T.TEXT)
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), box(T.LINE, T.CONTROL_RADIUS))
            addState(intArrayOf(android.R.attr.state_pressed), box(T.HOVER, T.CONTROL_RADIUS))
            addState(intArrayOf(android.R.attr.state_focused), box(T.SELECTED, T.CONTROL_RADIUS, T.ACCENT))
            addState(intArrayOf(), box(if (primary) T.ACCENT else T.SURFACE, T.CONTROL_RADIUS, if (primary) null else T.LINE))
        }
        minHeight = dp(44); minimumHeight = dp(44)
        setPadding(dp(16), dp(8), dp(16), dp(8)); setOnClickListener { action() }
    }
    private fun brand(size: Int = 30): LinearLayout = row().apply {
        gravity = Gravity.CENTER
        addView(ImageView(context).apply {
            setImageResource(org.openuuyc.pico.R.drawable.openuuyc_brand)
            contentDescription = "OpenUUYC"
        }, LinearLayout.LayoutParams(dp(size), dp(size)))
        addView(label("OpenUUYC", if (size > 30) 25f else 17f, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
    }

    val status = label("使用 UU 远程手机端扫码", 13f, T.MUTED).apply { gravity = Gravity.CENTER; minHeight = dp(44) }
    val qrImage = ImageView(context).apply {
        visibility = GONE; scaleType = ImageView.ScaleType.FIT_CENTER
        setBackgroundColor(Color.WHITE); setPadding(dp(12), dp(12), dp(12), dp(12))
        contentDescription = "UU 登录二维码"
    }
    private val qrPlaceholder = label("▣\n点击下方按钮获取二维码", 16f, T.MUTED).apply { gravity = Gravity.CENTER }
    private val loginButton = button("扫码登录", true) { if (restoreRetry) onRestore?.invoke() else onLogin() }
    private val switchAccountButton = button("改用扫码登录") { restoreRetry = false; onLogin() }.apply { visibility = GONE }
    private val sidebarBrand = brand()
    private val loginScroll = ScrollView(context).apply { isFillViewport = true; clipToPadding = false }
    private val loginCard = column()
    private val loginColumns = LinearLayout(context)
    private val qrColumn = column()
    private val guideColumn = column()
    private val loginDivider = divider(true)
    private val center = row()
    private val sidebar = column()
    private val navigation = column()
    private val accountLabel = label("已登录", 14f)
    private val main = column()
    private val pageBody = FrameLayout(context)
    private val pageTitle = label("我的设备", 25f, bold = true)
    private val centerStatus = label("选择在线设备，开始远程连接", 13f, T.MUTED)
    private val devicePage = column()
    private val deviceGrid = column()
    private val countLabel = label("0 台设备", 13f, T.MUTED)
    private val filterBar = row()
    private val search = EditText(context).apply {
        setSingleLine(true); textSize = 14f; setTextColor(T.TEXT); setHintTextColor(T.MUTED)
        hint = "搜索设备名称"; contentDescription = "搜索设备"
        background = box(T.SIDEBAR, T.CONTROL_RADIUS, T.LINE)
        setPadding(dp(12), 0, dp(12), 0)
    }
    private val settingsPage = column()
    private val aboutPage = column()

    init {
        setBackgroundColor(T.BG)
        buildLogin()
        buildCenter(passthroughEnabled)
        addView(loginScroll, LayoutParams(-1, -1))
        addView(center, LayoutParams(-1, -1))
        center.visibility = GONE
        reflow(currentWidth)
    }

    private fun buildLogin() {
        val outer = column().apply {
            gravity = Gravity.CENTER; setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        loginCard.apply {
            background = box(T.SURFACE, T.PANEL_RADIUS, T.LINE)
            setPadding(dp(28), dp(30), dp(28), dp(24)); elevation = dp(4).toFloat()
        }
        loginCard.addView(brand(36)); loginCard.gap(28)
        qrColumn.gravity = Gravity.CENTER_HORIZONTAL
        qrColumn.addView(label("扫码登录", 16f, bold = true).apply { gravity = Gravity.CENTER }); qrColumn.gap(18)
        val area = FrameLayout(context).apply { background = box(T.SIDEBAR, T.CONTROL_RADIUS, T.LINE) }
        area.addView(qrPlaceholder, LayoutParams(-1, -1))
        area.addView(qrImage, LayoutParams(-1, -1))
        qrColumn.addView(area, LinearLayout.LayoutParams(dp(T.AUTH_QR_SIZE), dp(T.AUTH_QR_SIZE)))
        qrColumn.gap(10); qrColumn.addView(status, LinearLayout.LayoutParams(-1, -2))
        qrColumn.gap(10); qrColumn.addView(loginButton, LinearLayout.LayoutParams(dp(216), dp(44)))
        onSmsLogin?.let { action -> qrColumn.gap(8);qrColumn.addView(button("手机号登录",action=action),LinearLayout.LayoutParams(dp(216),dp(44))) }
        qrColumn.gap(8); qrColumn.addView(switchAccountButton, LinearLayout.LayoutParams(dp(216), dp(44)))
        guideColumn.apply { setPadding(dp(28), 0, dp(12), 0) }
        guideColumn.addView(label("PICO 空间连接", 16f, bold = true)); guideColumn.gap(24)
        guideColumn.addView(label("登录你的 UU 账号", 17f, bold = true)); guideColumn.gap(10)
        guideColumn.addView(label("使用手机 UU 远程扫描左侧二维码，\n并在手机上确认登录。", 14f, T.MUTED)); guideColumn.gap(22)
        guideColumn.addView(label("连接在线电脑", 17f, bold = true)); guideColumn.gap(10)
        guideColumn.addView(label("在「我的设备」中选择允许控制的电脑。\n默认在普通窗口控制。\n仅点击「沉浸式连接」才进入 OpenXR。", 14f, T.MUTED)); guideColumn.gap(22)
        guideColumn.addView(label("此 PICO 版本暂未提供短信登录。\n扫码和设备发现需访问 UU 服务；\n串流路径以实际 ICE 协商结果为准。", 12f, T.MUTED))
        loginColumns.addView(qrColumn)
        loginColumns.addView(loginDivider)
        loginColumns.addView(guideColumn)
        loginCard.addView(loginColumns)
        outer.addView(loginCard)
        outer.gap(18)
        outer.addView(label("PICO 开发预览版  ·  登录凭证由 Android Keystore 加密保存", 12f, T.MUTED).apply { gravity = Gravity.CENTER })
        loginScroll.addView(outer, ViewGroup.LayoutParams(-1, -1))
    }

    private fun buildCenter(passthroughEnabled: Boolean) {
        sidebar.setBackgroundColor(T.SIDEBAR)
        sidebar.setPadding(dp(12), dp(20), dp(12), dp(16))
        sidebar.addView(sidebarBrand); sidebar.gap(28)
        sidebar.addView(navigation)
        sidebar.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
        sidebar.addView(divider()); sidebar.gap(16)
        accountLabel.maxLines = 1; accountLabel.ellipsize = TextUtils.TruncateAt.END
        sidebar.addView(accountLabel); sidebar.gap(6)
        sidebar.addView(label("●  PICO 客户端", 11f, T.GREEN)); sidebar.gap(12)
        sidebar.addView(button("退出登录", action = onLogout), LinearLayout.LayoutParams(-1, dp(44)))
        center.addView(sidebar, LinearLayout.LayoutParams(dp(T.SIDEBAR_WIDTH), -1))
        center.addView(divider(true))
        main.setPadding(dp(28), dp(24), dp(28), dp(20))
        val heading = row()
        heading.addView(pageTitle, LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(label("PICO / OPENXR", 12f, T.MUTED))
        main.addView(heading); main.gap(8)
        main.addView(centerStatus); main.gap(22)
        main.addView(pageBody, LinearLayout.LayoutParams(-1, 0, 1f))
        center.addView(main, LinearLayout.LayoutParams(0, -1, 1f))

        val tools = row()
        tools.addView(search, LinearLayout.LayoutParams(0, dp(44), 1f))
        tools.addView(button("刷新", action = onRefresh), LinearLayout.LayoutParams(-2, dp(44)).apply { marginStart = dp(12) })
        devicePage.addView(tools); devicePage.gap(14)
        devicePage.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; isFillViewport = true; addView(filterBar) }); devicePage.gap(12)
        devicePage.addView(countLabel); devicePage.gap(14)
        devicePage.addView(ScrollView(context).apply { addView(deviceGrid) }, LinearLayout.LayoutParams(-1, 0, 1f))
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                state.query = s.toString(); renderDevices()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        settingsPage.addView(label("显示与环境", 16f, bold = true)); settingsPage.gap(14)
        val passthrough = Switch(context).apply {
            text = "现实透视背景：" + if (passthroughEnabled) "开启" else "关闭"; textSize = 14f; setTextColor(T.TEXT); isChecked = passthroughEnabled
            thumbTintList = ColorStateList.valueOf(T.TEXT)
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(T.ACCENT, T.LINE))
            setPadding(dp(16), dp(10), dp(16), dp(10)); background = box(T.SURFACE, stroke = T.LINE)
            setOnCheckedChangeListener { _, checked -> text = "现实透视背景：" + if (checked) "开启" else "关闭"; onPassthrough(checked) }
        }
        settingsPage.addView(passthrough, LinearLayout.LayoutParams(-1, dp(60)))
        settingsPage.gap(10)
        settingsPage.addView(label("尝试使用运行时提供的 Alpha Blend 背景；不支持时回退为普通 VR。", 13f, T.MUTED))
        settingsPage.gap(28); settingsPage.addView(label("摄像头诊断", 16f, bold = true)); settingsPage.gap(12)
        settingsPage.addView(label("Camera2 预览仅用于检查系统暴露的相机，并非原始透视图像接口。权限只在点击时申请；进入串流后释放相机。", 13f, T.MUTED))
        settingsPage.gap(14)
        settingsPage.addView(button("打开 / 关闭相机预览", action = onCamera), LinearLayout.LayoutParams(-2, dp(44)))
        settingsPage.gap(12)
        (cameraPreview.parent as? ViewGroup)?.removeView(cameraPreview)
        settingsPage.addView(cameraPreview, LinearLayout.LayoutParams(-1, dp(220)))
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        aboutPage.addView(brand(36)); aboutPage.gap(24)
        aboutPage.addView(label("OpenUUYC PICO  ·  v$version", 21f, bold = true)); aboutPage.gap(12)
        aboutPage.addView(label("与 Linux 控制中心共享配色、品牌与导航结构。", 14f, T.MUTED)); aboutPage.gap(24)
        aboutPage.addView(label("原生 UU 登录与 WebRTC 连接\n普通窗口控制（默认）\n可选 OpenXR 空间窗口 · 最多六个视频面板\n仅在主机确实提供对应视频轨道时显示侧窗", 14f)); aboutPage.gap(24)
        aboutPage.addView(label("开发预览版\n音频、输入、多屏和网络路径需逐项实机验收。登录成功不等于串流通过。", 13f, T.AMBER))
    }

    private fun renderNavigation() {
        navigation.removeAllViews()
        for ((page, title) in listOf(PicoPanelState.Page.DEVICES to "我的设备", PicoPanelState.Page.SETTINGS to "连接设置", PicoPanelState.Page.ABOUT to "关于")) {
            val active = state.page == page
            navigation.addView(button(title) { selectPage(page) }.apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                background = box(if (active) T.SELECTED else T.SIDEBAR, T.CONTROL_RADIUS)
                setTextColor(if (active) T.TEXT else T.MUTED)
                contentDescription = title
            }, LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(6) })
        }
    }

    private fun selectPage(page: PicoPanelState.Page) {
        state.page = page; renderNavigation()
        pageTitle.text = when (page) { PicoPanelState.Page.DEVICES -> "我的设备"; PicoPanelState.Page.SETTINGS -> "连接设置"; PicoPanelState.Page.ABOUT -> "关于" }
        pageBody.removeAllViews()
        when (page) {
            PicoPanelState.Page.DEVICES -> {
                (devicePage.parent as? ViewGroup)?.removeView(devicePage)
                pageBody.addView(devicePage, LayoutParams(-1, -1))
            }
            else -> {
                val content = if (page == PicoPanelState.Page.SETTINGS) settingsPage else aboutPage
                (content.parent as? ViewGroup)?.removeView(content)
                pageBody.addView(ScrollView(context).apply { addView(content) }, LayoutParams(-1, -1))
            }
        }
    }

    private fun renderFilters() {
        filterBar.removeAllViews()
        for ((filter, title) in listOf(PicoPanelState.Filter.ALL to "全部", PicoPanelState.Filter.ONLINE to "在线", PicoPanelState.Filter.OFFLINE to "离线")) {
            filterBar.addView(button(title) { state.filter = filter; renderFilters(); renderDevices() }.apply {
                if (state.filter == filter) background = box(T.SELECTED, T.CONTROL_RADIUS, T.ACCENT)
            }, LinearLayout.LayoutParams(-2, dp(44)).apply { marginEnd = dp(6) })
        }
        filterBar.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        filterBar.addView(button(if (state.sortByName) "名称排序" else "在线优先") {
            state.sortByName = !state.sortByName; renderFilters(); renderDevices()
        }, LinearLayout.LayoutParams(-2, dp(44)))
    }

    private fun renderDevices() {
        val visible = state.visibleDevices()
        countLabel.text = "${visible.size} 台设备  ·  ${state.devices.count { it.online }} 台在线"
        deviceGrid.removeAllViews()
        if (visible.isEmpty()) {
            val empty = column().apply {
                gravity = Gravity.CENTER; setPadding(dp(24), dp(48), dp(24), dp(48))
                background = box(T.SURFACE, stroke = T.LINE)
            }
            empty.addView(label(if (state.devices.isEmpty()) "暂无设备" else "没有匹配的设备", 18f, bold = true))
            empty.gap(10); empty.addView(label("检查电脑是否已登录同一个 UU 账号，或调整搜索条件。", 13f, T.MUTED))
            deviceGrid.addView(empty, LinearLayout.LayoutParams(-1, -2)); return
        }
        val columns = if (currentWidth >= 980) 2 else 1
        for (group in visible.chunked(columns)) {
            val line = row().apply { gravity = Gravity.TOP }
            for ((index, device) in group.withIndex()) {
                line.addView(deviceCard(device), LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (index > 0) marginStart = dp(14)
                })
            }
            if (group.size < columns) line.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(14) })
            deviceGrid.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        }
    }

    private fun deviceCard(device: UuDevice) = column().apply {
        setPadding(dp(18), dp(18), dp(18), dp(16)); background = box(T.SURFACE, stroke = T.LINE)
        val header = row()
        header.addView(MonitorGlyph(context), LinearLayout.LayoutParams(dp(34), dp(34)))
        header.addView(label(device.alias, 16f, bold = true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        addView(header); gap(14)
        val reason = when { !device.online -> "离线"; device.occupants > 0 -> "已占用"; !device.controllable -> "未允许控制"; else -> "在线 · 可连接" }
        addView(label("●  $reason", 13f, if (!device.online) T.MUTED else if (!PicoPanelState.canConnect(device)) T.AMBER else T.GREEN)); gap(18)
        val actions = row()
        actions.addView(button(if (device.occupants > 0) "抢占连接" else "远程连接", true) { if (device.online && device.controllable) onConnect(device) }.apply {
            isEnabled = device.online && device.controllable
            contentDescription = "连接 ${device.alias}，$reason"
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        onImmersiveConnect?.let { immersive ->
            actions.addView(button("沉浸式连接") { if (device.online && device.controllable) immersive(device) }.apply {
                isEnabled = device.online && device.controllable
                contentDescription = "沉浸式连接 ${device.alias}，$reason"
            }, LinearLayout.LayoutParams(-2, dp(44)).apply { marginStart = dp(8) })
        }
        addView(actions)

    }

    fun showQr(bitmap: Bitmap) { qrImage.setImageBitmap(bitmap); qrImage.visibility = VISIBLE; qrPlaceholder.visibility = GONE }
    fun setStatus(text: String) {
        status.text = text; centerStatus.text = text
        val color = if (text.contains("失败") || text.contains("拒绝") || text.contains("错误")) T.AMBER else T.MUTED
        status.setTextColor(color); centerStatus.setTextColor(color)
    }
    fun showDevices(devices: List<UuDevice>) { state.devices = devices.toList(); renderFilters(); renderDevices() }
    fun setLoginState(busy: Boolean, authenticated: Boolean, accountName: String = "") {
        restoreRetry = false
        switchAccountButton.visibility = GONE
        val wasAuthenticated = this.authenticated
        this.authenticated = authenticated
        loginScroll.visibility = if (authenticated) GONE else VISIBLE
        center.visibility = if (authenticated) VISIBLE else GONE
        accountLabel.text = accountName.ifBlank { "已登录" }
        loginButton.text = if (busy) "刷新二维码" else "扫码登录"
        loginButton.isEnabled = !authenticated
        if (!busy && !authenticated) { qrImage.visibility = GONE; qrPlaceholder.visibility = VISIBLE }
        if (!authenticated && wasAuthenticated) { state.clear(); search.setText(""); renderDevices() }
        if (authenticated && !wasAuthenticated) selectPage(PicoPanelState.Page.DEVICES)
    }
    fun setRestoreState(busy: Boolean, retry: Boolean = false) {
        restoreRetry = retry
        loginButton.text = if (busy) "正在恢复登录…" else if (retry) "重试恢复登录" else "扫码登录"
        loginButton.isEnabled = !busy
        switchAccountButton.visibility = if (retry) VISIBLE else GONE
    }
    fun reflow(widthDp: Int) {
        currentWidth = widthDp
        val narrow = widthDp < 720
        loginCard.layoutParams = LinearLayout.LayoutParams(dp(minOf(800, (widthDp - 48).coerceAtLeast(260))), -2)
        loginColumns.orientation = if (narrow) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        qrColumn.layoutParams = LinearLayout.LayoutParams(if (narrow) -1 else 0, -2, if (narrow) 0f else 1f)
        loginDivider.visibility = if (narrow) GONE else VISIBLE
        loginDivider.layoutParams = LinearLayout.LayoutParams(dp(1), dp(338))
        guideColumn.layoutParams = LinearLayout.LayoutParams(if (narrow) -1 else 0, -2, if (narrow) 0f else 1f).apply { topMargin = if (narrow) dp(28) else 0 }
        sidebar.layoutParams = LinearLayout.LayoutParams(dp(if (widthDp < 700) 144 else T.SIDEBAR_WIDTH), -1)
        (sidebarBrand.getChildAt(1) as TextView).textSize = if (widthDp < 700) 14f else 17f
        renderNavigation(); renderFilters(); renderDevices()
    }

    private class MonitorGlyph(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = T.ACCENT; style = Paint.Style.STROKE; strokeWidth = resources.displayMetrics.density * 1.6f }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat()
            canvas.drawRoundRect(w * .08f, h * .1f, w * .92f, h * .7f, w * .07f, w * .07f, paint)
            canvas.drawLine(w * .5f, h * .7f, w * .5f, h * .9f, paint)
            canvas.drawLine(w * .3f, h * .9f, w * .7f, h * .9f, paint)
        }
    }
}

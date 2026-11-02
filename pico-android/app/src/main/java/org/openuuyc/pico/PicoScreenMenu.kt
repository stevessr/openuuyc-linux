package org.openuuyc.pico

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.openuuyc.pico.uu.UuDirectSession

/** App-styled screen picker, shared semantics with the compositor menu. */
internal object PicoScreenMenu {
    fun show(activity: Activity, anchor: View, session: UuDirectSession, beforeSelect: () -> Unit, report: (String) -> Unit) {
        val density=activity.resources.displayMetrics.density
        fun dp(v: Int)=(v*density).toInt()
        val root=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(12),dp(12),dp(12))
            background=GradientDrawable().apply { setColor(PicoTheme.SURFACE);cornerRadius=dp(8).toFloat();setStroke(dp(1),PicoTheme.ACCENT) }
        }
        val popup=PopupWindow(root,minOf(dp(440),activity.resources.displayMetrics.widthPixels-dp(24)),ViewGroup.LayoutParams.WRAP_CONTENT,true).apply {
            elevation=dp(12).toFloat();isOutsideTouchable=true;setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        }
        root.addView(TextView(activity).apply { text="远端屏幕";textSize=18f;setTextColor(PicoTheme.TEXT);setPadding(dp(8),0,0,dp(10)) })
        val screens=session.screenSnapshot()
        if(screens.isEmpty()) root.addView(TextView(activity).apply { text="尚未收到屏幕列表";setTextColor(PicoTheme.MUTED) })
        screens.forEach { screen ->
            root.addView(Button(activity).apply {
                isAllCaps=false;gravity=android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                text="${if(screen.id==session.selectedScreen()) "✓ " else ""}$screen\n${screen.current ?: "等待尺寸"} · ${if(screen.track<0) "未采集" else "视频轨 ${screen.track}"}"
                setTextColor(PicoTheme.TEXT);textSize=15f
                setOnClickListener {
                    beforeSelect()
                    if(session.switchScreen(screen.id)) popup.dismiss() else report("屏幕已变化或连接尚未就绪，未切换")
                }
            },LinearLayout.LayoutParams(-1,dp(68)))
        }
        root.addView(Button(activity).apply { text="刷新列表";setOnClickListener { popup.dismiss();show(activity,anchor,session,beforeSelect,report) } })
        popup.showAsDropDown(anchor)
    }
}

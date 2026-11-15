package org.openuuyc.pico

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.openuuyc.pico.uu.UuDirectSession

/** App-styled screen picker, shared semantics with the compositor menu. */
internal object PicoScreenMenu {
    private const val ROW_HEIGHT_DP=68
    fun show(activity: Activity, anchor: View, session: UuDirectSession, beforeSelect: () -> Unit, report: (String) -> Unit) {
        val density=activity.resources.displayMetrics.density
        fun dp(v: Int)=(v*density).toInt()
        val metrics=activity.resources.displayMetrics
        val root=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(12),dp(12),dp(12))
            background=GradientDrawable().apply { setColor(PicoTheme.SURFACE);cornerRadius=dp(8).toFloat();setStroke(dp(1),PicoTheme.ACCENT) }
        }
        val popup=PopupWindow(root,minOf(dp(440),metrics.widthPixels-dp(24)),ViewGroup.LayoutParams.WRAP_CONTENT,true).apply {
            elevation=dp(12).toFloat();isOutsideTouchable=true;setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        }
        val screens=session.screenSnapshot()
        val selectedIndex=screens.indexOfFirst {it.id==session.selectedScreen()}
        val rowHeight=dp(ROW_HEIGHT_DP)
        // Remote hosts can report many screens (displays + virtual displays + window sources);
        // the list scrolls inside a bounded height so title and 刷新列表 stay visible.
        val listHeight=minOf(screens.size*rowHeight,run {
            val location=IntArray(2);anchor.getLocationOnScreen(location)
            // Leave room for the anchor, the "远端屏幕/窗口" title, the 刷新列表 button and the panel padding.
            val belowAnchor=metrics.heightPixels-location[1]-anchor.height-dp(150)
            minOf(belowAnchor,(metrics.heightPixels*0.62f).toInt()).coerceAtLeast(rowHeight*3)
        })
        root.addView(TextView(activity).apply {
            text=if(screens.size*rowHeight>listHeight) "远端屏幕/窗口 · 共 ${screens.size} 个，可上下滑动" else "远端屏幕/窗口"
            textSize=18f;setTextColor(PicoTheme.TEXT);setPadding(dp(8),0,0,dp(10))
        })
        if(screens.isEmpty()) root.addView(TextView(activity).apply { text="尚未收到屏幕列表";setTextColor(PicoTheme.MUTED) })
        val list=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL}
        val scroll=ScrollView(activity).apply {isFillViewport=false;addView(list)}
        if(screens.isNotEmpty()) root.addView(scroll,LinearLayout.LayoutParams(-1,listHeight))
        screens.forEach { screen ->
            list.addView(Button(activity).apply {
                isAllCaps=false;gravity=android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                text="${if(screen.id==session.selectedScreen()) "✓ " else ""}$screen\n${screen.current ?: "等待尺寸"} · ${if(screen.track<0) "未采集" else "视频轨 ${screen.track}"}"
                setTextColor(PicoTheme.TEXT);textSize=15f
                setOnClickListener {
                    beforeSelect()
                    if(session.switchScreen(screen.id)) popup.dismiss() else report("屏幕已变化或连接尚未就绪，未切换")
                }
            },LinearLayout.LayoutParams(-1,rowHeight))
        }
        root.addView(Button(activity).apply { text="刷新列表";setOnClickListener { popup.dismiss();show(activity,anchor,session,beforeSelect,report) } })
        popup.showAsDropDown(anchor)
        if(selectedIndex>0) scroll.post {
            val viewHeight=if(scroll.height>0) scroll.height else listHeight
            val maxScroll=(screens.size*rowHeight-viewHeight).coerceAtLeast(0)
            val target=(selectedIndex*rowHeight-viewHeight/2+rowHeight/2).coerceIn(0,maxScroll)
            scroll.scrollTo(0,target)
        }
    }
}

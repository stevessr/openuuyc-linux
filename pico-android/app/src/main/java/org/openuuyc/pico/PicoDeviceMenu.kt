package org.openuuyc.pico

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.openuuyc.pico.uu.UuDevice

internal object PicoDeviceMenu {
    fun show(activity: Activity,anchor: View,devices: List<UuDevice>,current: String,refresh: ()->Unit,select: (UuDevice)->Unit) {
        fun dp(n: Int)=(n*activity.resources.displayMetrics.density).toInt()
        val rows=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(12),dp(12),dp(12));background=GradientDrawable().apply {setColor(PicoTheme.BG);cornerRadius=dp(8).toFloat();setStroke(dp(1),PicoTheme.LINE)}}
        val scroll=ScrollView(activity).apply {addView(rows)}
        val popup=PopupWindow(scroll,minOf(dp(390),activity.resources.displayMetrics.widthPixels-dp(24)),ViewGroup.LayoutParams.WRAP_CONTENT,true).apply {elevation=dp(10).toFloat();isOutsideTouchable=true;setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))}
        rows.addView(TextView(activity).apply {text="切换设备                                  ⟳";textSize=16f;setTextColor(PicoTheme.MUTED);setPadding(dp(10),dp(8),dp(10),dp(14));setOnClickListener {popup.dismiss();refresh()}})
        if(devices.isEmpty()) rows.addView(TextView(activity).apply {text="暂无可用设备";setTextColor(PicoTheme.MUTED)})
        devices.forEach {device -> rows.addView(TextView(activity).apply {
            text="▣  ${device.alias}       ${when {device.id==current -> "✓";!device.online -> "离线";!device.controllable -> "不可控";device.occupants>0 -> "使用中";else -> "在线"}}"
            gravity=Gravity.CENTER_VERTICAL;textSize=17f;setTextColor(if(device.online || device.id==current) PicoTheme.TEXT else PicoTheme.MUTED)
            background=GradientDrawable().apply {setColor(if(device.id==current) PicoTheme.SURFACE else PicoTheme.BG);cornerRadius=dp(7).toFloat()}
            setPadding(dp(12),0,dp(10),0);isEnabled=device.online && device.controllable && device.id!=current
            setOnClickListener {popup.dismiss();select(device)}
        },LinearLayout.LayoutParams(-1,dp(56)))}
        popup.showAsDropDown(anchor)
    }
}

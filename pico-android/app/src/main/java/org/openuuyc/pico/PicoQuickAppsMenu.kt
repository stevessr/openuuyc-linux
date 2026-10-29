package org.openuuyc.pico

import android.app.Activity
import android.app.AlertDialog
import org.openuuyc.pico.uu.UuDirectSession

internal object PicoQuickAppsMenu {
    fun showConnections(activity: Activity,connections: List<Pair<String,UuDirectSession>>,status: (String)->Unit) {
        AlertDialog.Builder(activity).setTitle("快速启动 · 选择设备")
            .setItems((connections.map {"远端 · ${it.first}"}+"本机 VR 游戏 / 应用").toTypedArray()) {_,index ->
                if(index==connections.size) showLocal(activity,status)
                else show(activity,connections[index].second,status)
            }.setNegativeButton("关闭",null).show()
    }

    fun showMenu(activity: Activity,session: UuDirectSession?,status: (String)->Unit) {
        AlertDialog.Builder(activity).setTitle("游戏与应用快速启动")
            .setItems(arrayOf("远端游戏 / 应用","本机 VR 游戏 / 应用")) {_,index ->
                if(index==0) {
                    if(session==null) status("请先连接 Linux OpenUUYC，再启动远端游戏")
                    else show(activity,session,status)
                } else showLocal(activity,status)
            }.setNegativeButton("关闭",null).show()
    }

    private fun showLocal(activity: Activity,status: (String)->Unit) {
        status("正在读取本机 VR 游戏…")
        Thread({
            val result=runCatching {PicoLocalVrApps.list(activity)}
            activity.runOnUiThread {
                if(activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                result.fold({apps ->
                    if(apps.isEmpty()) {status("未找到已安装且带 VR 声明的可启动游戏");return@fold}
                    AlertDialog.Builder(activity).setTitle("本机 VR 游戏 / 应用")
                        .setItems(apps.map {it.name}.toTypedArray()) {_,index ->
                            val app=apps[index]
                            AlertDialog.Builder(activity).setTitle("启动 ${app.name}？")
                                .setMessage("将离开当前 OpenUUYC 前台并打开本机 VR 应用。")
                                .setNegativeButton("取消",null).setPositiveButton("启动") {_,_->
                                    runCatching {PicoLocalVrApps.launch(activity,app)}
                                        .onSuccess {status("已请求打开 ${app.name}")}
                                        .onFailure {status(it.message ?: "游戏启动失败，请刷新重试")}
                                }.show()
                        }.setNeutralButton("刷新") {_,_->showLocal(activity,status)}
                        .setNegativeButton("返回",null).show()
                },{status(it.message ?: "本机 VR 游戏列表读取失败")})
            }
        },"pico-local-vr-catalog").start()
    }

    fun show(activity: Activity,session: UuDirectSession,status: (String)->Unit) {
        status("正在读取远端快速应用…")
        session.quickApps.list {apps,error ->
            if(activity.isFinishing || activity.isDestroyed) return@list
            if(apps==null) {status(error ?: "快速应用不可用");return@list}
            if(apps.isEmpty()) {status("远端没有提供可启动应用");return@list}
            AlertDialog.Builder(activity).setTitle("远端快速应用")
                .setItems(apps.map {it.name+if(!it.enabled) " · 暂不可用" else ""}.toTypedArray()) {_,index ->
                    val app=apps[index]
                    if(!app.enabled) {status("内置 VR 尚未就绪，未启动应用；不会跳转外部串流客户端");return@setItems}
                    AlertDialog.Builder(activity).setTitle("启动 ${app.name}？")
                        .setMessage(if(app.vr) "此操作仅在远端启动 VR 应用。OpenUUYC 内置 PCVR 尚未就绪，不会提供双眼游戏画面或头手输入，也不会调用外部串流客户端。" else "在远端启动应用，并继续显示当前桌面串流。")
                        .setNegativeButton("取消",null).setPositiveButton(if(app.vr) "仅启动游戏" else "启动") {_,_->
                            status("正在请求启动 ${app.name}…")
                            session.quickApps.launch(app,launchOnlyAck=app.vr) {_,message ->if(!activity.isDestroyed) status(message)}
                        }.show()
                }.setNeutralButton("刷新") {_,_->show(activity,session,status)}.setNegativeButton("返回",null).show()
        }
    }
}

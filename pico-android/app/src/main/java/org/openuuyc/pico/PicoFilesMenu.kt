package org.openuuyc.pico

import android.app.Activity
import android.app.AlertDialog
import android.widget.*
import org.openuuyc.pico.uu.UuDirectSession

internal object PicoFilesMenu {
    fun show(activity: Activity,session: UuDirectSession,status: (String)->Unit,pick: (Boolean,String,(android.net.Uri)->Unit)->Unit) {
        fun transfer(title: String,start: ((Long,Long)->Unit,(String?)->Unit)->Unit) {
            val text=TextView(activity).apply {setPadding(24,20,24,20);this.text="准备传输…"}
            val progress=AlertDialog.Builder(activity).setTitle(title).setView(text).setNegativeButton("取消") {_,_->session.transfers.cancel()}.create()
            progress.setOnCancelListener {session.transfers.cancel()};progress.show()
            start({bytes,total->activity.runOnUiThread {if(progress.isShowing) text.text="$bytes / $total B"}}, {error->activity.runOnUiThread {
                progress.dismiss();status(error ?: "文件传输完成")
            }})
        }

        val history=mutableListOf<String>()
        var current=":/"
        var epoch=0
        val rows=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL}
        val heading=TextView(activity).apply {setPadding(20,16,20,16)}
        rows.addView(heading)
        val entries=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL}
        rows.addView(ScrollView(activity).apply {addView(entries)},LinearLayout.LayoutParams(-1,450))
        val dialog=AlertDialog.Builder(activity).setTitle("UU 文件管理").setView(rows)
            .setNegativeButton("关闭",null).setNeutralButton("上一级",null).setPositiveButton("刷新",null).create()
        fun browse(path: String) {
            val generation=++epoch;heading.text="正在读取 $path";entries.removeAllViews()
            session.files.request(10,path) {reply,error->
                if(!dialog.isShowing || generation!=epoch || activity.isDestroyed) return@request
                if(reply==null) {heading.text=error;status(error ?: "目录读取失败");return@request}
                current=reply.path.ifBlank {path};heading.text=current
                val create=Button(activity).apply {text="＋ 新建文件夹";isEnabled=current!=":/";setOnClickListener {
                    session.files.request(6,current) {_,failure->if(failure!=null) status(failure) else browse(current)}
                }};entries.addView(create)
                entries.addView(Button(activity).apply {text="↑ 上传文件";isEnabled=current!=":/";setOnClickListener {
                    val destination=current
                    pick(false,"") {uri->
                        val result=runCatching {
                            activity.contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME,android.provider.OpenableColumns.SIZE),null,null,null)?.use {cursor->
                                check(cursor.moveToFirst());val name=cursor.getString(0);check(!cursor.isNull(1)) {"文件提供者未报告大小"};name to cursor.getLong(1)
                            } ?: error("无法读取文件信息")
                        }
                        result.fold({(name,size)->transfer("上传 $name") {notify,done->session.transfers.upload(destination,name,size,{activity.contentResolver.openInputStream(uri) ?: error("无法打开源文件")},notify,done)}},{status(it.message ?: "文件选择失败")})
                    }
                }})
                reply.entries.sortedWith(compareByDescending<org.openuuyc.pico.uu.UuFileWire.Entry> {it.directory}.thenBy {it.name.lowercase()}).forEach {entry->
                    val button=Button(activity).apply {text=(if(entry.directory) "▣ " else "▤ ")+entry.name+(if(entry.directory) "" else " · ${entry.size} B")}
                    fun actions() {
                        AlertDialog.Builder(activity).setTitle(entry.name).setItems((if(entry.directory) listOf("重命名","删除") else listOf("重命名","删除","下载文件")).toTypedArray()) {_,index->
                            if(index==2) {
                                pick(true,entry.name) {uri->
                                    val temp=java.io.File.createTempFile("uuyc-download-",".part",activity.cacheDir)
                                    transfer("下载 ${entry.name}") {notify,done->
                                        session.transfers.download(entry.path,entry.size,{temp.outputStream()},notify) {failure->
                                            val result=if(failure!=null) failure else runCatching {
                                                temp.inputStream().use {input->(activity.contentResolver.openOutputStream(uri,"wt") ?: error("无法保存目标文件")).use {output->input.copyTo(output)}}
                                            }.exceptionOrNull()?.let {"保存失败，目标可能不完整：${it.message}"}
                                            temp.delete();done(result)
                                        }
                                    }
                                }
                            } else if(index==0) {
                                val name=EditText(activity).apply {setText(entry.name);isSingleLine=true}
                                AlertDialog.Builder(activity).setTitle("重命名").setView(name).setNegativeButton("取消",null).setPositiveButton("保存") {_,_->
                                    session.files.request(5,entry.path,name.text.toString()) {_,failure->if(failure!=null) status(failure) else browse(current)}
                                }.show()
                            } else AlertDialog.Builder(activity).setTitle("删除 ${entry.name}？")
                                .setMessage("远端路径：${entry.path}\n${if(entry.directory) "将删除此文件夹及内容。" else "将删除此文件。"}请确认，此操作可能无法撤销。")
                                .setNegativeButton("取消",null).setPositiveButton("删除") {_,_->
                                    session.files.request(if(entry.directory) 8 else 7,entry.path) {_,failure->if(failure!=null) status(failure) else browse(current)}
                                }.show()
                        }.setNegativeButton("取消",null).show()
                    }
                    button.setOnClickListener {if(entry.directory) {history.add(current);browse(entry.path)} else actions()}
                    button.setOnLongClickListener {if(current!=":/") actions();true}
                    entries.addView(button)
                }
            }
        }
        dialog.setOnDismissListener {++epoch}
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {browse(current)}
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {if(history.isNotEmpty()) browse(history.removeAt(history.lastIndex)) else browse(":/")}
            browse(current)
        };dialog.show()
    }
}

package org.openuuyc.pico
import android.app.Activity
import android.widget.*
import org.openuuyc.pico.uu.UuDirectSession
/** UI belongs to exactly one remote connection. No output is persisted. */
internal object PicoTerminalMenu {
 fun show(activity: Activity,direct: UuDirectSession,title: String="远端终端") {
  val terminal=direct.terminal;val screen=PicoTerminalScreen()
  val root=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;setPadding(16,12,16,12)}
  val status=TextView(activity).apply {text="命令在 $title 执行，需要远端单独允许终端"}
  val display=TextView(activity).apply {typeface=android.graphics.Typeface.MONOSPACE;textSize=11f;setTextIsSelectable(true);setTextColor(PicoTheme.TEXT);setBackgroundColor(PicoTheme.BG)}
  val scroller=HorizontalScrollView(activity).apply {addView(display)}
  val input=EditText(activity).apply {hint="输入命令，点发送执行";setSingleLine(true)}
  root.addView(status);root.addView(scroller,LinearLayout.LayoutParams(-1,400));root.addView(input)
  val buttons=LinearLayout(activity)
  fun button(label: String,action: ()->Unit) {buttons.addView(Button(activity).apply {text=label;setOnClickListener {action()}},LinearLayout.LayoutParams(0,-2,1f))}
  button("打开 Shell") {if(!terminal.open()) status.text="终端尚未就绪或已打开；官方 UU 桌面连接可能不支持扩展"}
  button("发送 ↵") {if(terminal.input(input.text.toString()+"\r")) input.text.clear() else status.text="输入未发送"}
  button("Ctrl+C") {terminal.input("\u0003")};button("Tab") {terminal.input("\t")};root.addView(buttons)
  terminal.changed={status.text=it};terminal.output={screen.append(it);display.text=screen.text()}
  val dialog=android.app.AlertDialog.Builder(activity).setTitle(title).setView(root).setNegativeButton("关闭 Shell",null).create()
  dialog.setOnDismissListener {terminal.endShell()};dialog.show()
 }
}

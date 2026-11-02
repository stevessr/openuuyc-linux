package org.openuuyc.pico
import org.junit.Assert.*
import org.junit.Test
class PicoTerminalScreenTest {
 @Test fun splitUtf8AndCursorEraseWork() {
  val s=PicoTerminalScreen(10,3);val bytes="中文".toByteArray();bytes.forEach {s.append(byteArrayOf(it))}
  assertTrue(s.text().startsWith("中文"));s.append("\rabc\u001b[K\rX".toByteArray());assertTrue(s.text().startsWith("Xbc"))
  s.append("\u001b[2J\u001b[Hok".toByteArray());assertEquals("ok\n\n",s.text())
 }
 @Test fun oscClipboardAndAnsiColorsAreNotRenderedOrExecuted() {
  val s=PicoTerminalScreen(20,2);s.append("\u001b]52;c;secret\u0007\u001b[31mhello\u001b[0m".toByteArray());assertEquals("hello\n",s.text())
 }
}

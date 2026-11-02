package org.openuuyc.pico
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
/** Bounded monochrome VT view; OSC clipboard/title/hyperlinks are never acted on. */
internal class PicoTerminalScreen(private val cols: Int=80,private val rows: Int=24) {
 private var cells=Array(rows) {Array(cols) {" "}}
 private var x=0;private var y=0;private var savedX=0;private var savedY=0
 private var mode=0;private var sequence=StringBuilder();private var carry=byteArrayOf()
 private val decoder=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
 fun append(bytes: ByteArray) {
  val input=ByteBuffer.wrap(carry+bytes);val output=CharBuffer.allocate(input.remaining()+2)
  decoder.decode(input,output,false);carry=ByteArray(input.remaining()).also {input.get(it)}
  output.flip();output.toString().codePoints().forEach {accept(it)}
 }
 fun text()=cells.joinToString("\n") {it.joinToString("").trimEnd()}
 private fun scroll() {cells=Array(rows) {if(it==rows-1) Array(cols) {" "} else cells[it+1]};y=rows-1}
 private fun line() {y++;if(y>=rows) scroll()}
 private fun accept(cp: Int) {
  if(mode==3) {if(cp==7) mode=0 else if(cp==27) mode=4;return}
  if(mode==4) {mode=if(cp==92) 0 else 3;return}
  if(mode==1) {when(cp) {91->{mode=2;sequence.clear()};93->mode=3;55->{savedX=x;savedY=y;mode=0};56->{x=savedX;y=savedY;mode=0};else->mode=0};return}
  if(mode==2) {
   if(cp in 0x40..0x7e) {csi(cp.toChar(),sequence.toString());mode=0;sequence.clear()}
   else if(sequence.length<64) sequence.appendCodePoint(cp) else {mode=0;sequence.clear()}
   return
  }
  when(cp) {27->mode=1;13->x=0;10->line();8->x=(x-1).coerceAtLeast(0);9->x=((x/8+1)*8).coerceAtMost(cols-1);in 0..31,127->Unit;else->{
   if(Character.getType(cp) in listOf(Character.NON_SPACING_MARK.toInt(),Character.COMBINING_SPACING_MARK.toInt())) {if(x>0) cells[y][x-1]+=String(Character.toChars(cp));return}
   if(x>=cols) {x=0;line()}
   val wide=cp>=0x1100 && (cp in 0x2e80..0xa4cf || cp in 0xac00..0xd7af || cp in 0xff00..0xff60 || cp>=0x1f300)
   if(wide && x==cols-1) {x=0;line()}
   cells[y][x]=String(Character.toChars(cp));x++
   if(wide && x<cols) {cells[y][x]="";x++}
  }}
 }
 private fun csi(command: Char,value: String) {
  if(value.startsWith("?")) return
  val p=value.split(';').map {it.toIntOrNull() ?: 0};fun arg(i: Int,default: Int=1)=p.getOrNull(i)?.takeIf {it>0} ?: default
  when(command) {
   'H','f'->{y=(arg(0)-1).coerceIn(0,rows-1);x=(arg(1)-1).coerceIn(0,cols-1)}
   'A'->y=(y-arg(0)).coerceAtLeast(0);'B'->y=(y+arg(0)).coerceAtMost(rows-1)
   'C'->x=(x+arg(0)).coerceAtMost(cols-1);'D'->x=(x-arg(0)).coerceAtLeast(0)
   'G'->x=(arg(0)-1).coerceIn(0,cols-1)
   'J'->when(p[0]) {2,3->cells=Array(rows) {Array(cols) {" "}};0->{for(row in y until rows) for(col in if(row==y) x until cols else 0 until cols) cells[row][col]=" "};1->{for(row in 0..y) for(col in if(row==y) 0..x.coerceAtMost(cols-1) else 0 until cols) cells[row][col]=" "}}
   'K'->{val range=when(p[0]) {1->0..x.coerceAtMost(cols-1);2->0 until cols;else->x until cols};for(col in range) cells[y][col]=" "}
   's'->{savedX=x;savedY=y};'u'->{x=savedX;y=savedY}
  }
 }
}

package org.openuuyc.pico

internal enum class PicoKeyboardMode { SYSTEM,CUSTOM,FULL,VOICE }
internal object PicoKeyboardLayout {
    data class Key(val label: String,val x: Float,val y: Float,val width: Float=50f,val height: Float=48f)
    enum class Header { MODE,CODEX_VOICE,BACK }
    fun height(mode: PicoKeyboardMode)=if(mode==PicoKeyboardMode.FULL) 480f else 400f
    fun headerAt(mode: PicoKeyboardMode,u: Float,v: Float): Header? {
        if(!u.isFinite() || !v.isFinite() || u !in 0f..<1f || v*height(mode) !in 48f..90f) return null
        return when(u*1200) {in 7f..383f->Header.MODE;in 393f..953f->Header.CODEX_VOICE;in 967f..1193f->Header.BACK;else->null}
    }
    private val customRows=listOf("1 2 3 4 5 6 7 8 9 0", "Q W E R T Y U I O P", "A S D F G H J K L", "Shift Z X C V B N M Backspace", "Ctrl Alt Tab Space Enter ← ↓ ↑ →").map {it.split(' ')}
    private val fullKeys: List<Key> = buildList {
        fun key(label: String,col: Float,row: Int,w: Float=1f,h: Int=1) {
            val y=if(row==0) 102f else 116f+row*52f
            add(Key(label,25+col*50,y,w*50,if(row==0) 40f else h*52f-4))
        }
        key("Esc",0f,0)
        for(i in 1..12) key("F$i",when(i) {in 1..4->i+1f;in 5..8->i+1.5f;else->i+2f},0)
        listOf("PrtSc","ScrLk","Pause").forEachIndexed {i,k->key(k,15.5f+i,0)}
        fun row(n: Int,entries: List<Pair<String,Float>>) {var col=0f;entries.forEach {(label,w)->key(label,col,n,w);col+=w};check(kotlin.math.abs(col-15)<.001f)}
        fun singles(value: String)=value.split(' ').map {it to 1f}
        row(1,singles("` 1 2 3 4 5 6 7 8 9 0 - =")+listOf("Backspace" to 2f))
        row(2,listOf("Tab" to 1.5f)+singles("Q W E R T Y U I O P [ ]")+listOf("\\" to 1.5f))
        row(3,listOf("Caps" to 1.75f)+singles("A S D F G H J K L ; '")+listOf("Enter" to 2.25f))
        row(4,listOf("Shift" to 2.25f)+singles("Z X C V B N M , . /")+listOf("RShift" to 2.75f))
        row(5,listOf("Ctrl" to 1.25f,"Win" to 1.25f,"Alt" to 1.25f,"Space" to 6.25f,"RAlt" to 1.25f,"RWin" to 1.25f,"Menu" to 1.25f,"RCtrl" to 1.25f))
        listOf("Insert","Home","PgUp").forEachIndexed {i,k->key(k,15.5f+i,1)}
        listOf("Delete","End","PgDn").forEachIndexed {i,k->key(k,15.5f+i,2)}
        key("↑",16.5f,4);listOf("←","↓","→").forEachIndexed {i,k->key(k,15.5f+i,5)}
        listOf("Num","Num/","Num*","Num-").forEachIndexed {i,k->key(k,19f+i,1)}
        listOf("Num7","Num8","Num9").forEachIndexed {i,k->key(k,19f+i,2)};key("Num+",22f,2,h=2)
        listOf("Num4","Num5","Num6").forEachIndexed {i,k->key(k,19f+i,3)}
        listOf("Num1","Num2","Num3").forEachIndexed {i,k->key(k,19f+i,4)};key("NumEnter",22f,4,h=2)
        key("Num0",19f,5,w=2f);key("Num.",21f,5)
        check(size==104)
    }
    fun keys(mode: PicoKeyboardMode): List<Key> = when(mode) {
        PicoKeyboardMode.FULL -> fullKeys
        PicoKeyboardMode.SYSTEM,PicoKeyboardMode.VOICE -> emptyList()
        PicoKeyboardMode.CUSTOM -> customRows.flatMapIndexed {r,row->row.mapIndexed {col,label->
            val w=1200f/row.size;val h=264f/customRows.size
            Key(label,col*w,96+r*h,w,h)
        }}
    }
    fun rows(mode: PicoKeyboardMode): List<List<String>> = keys(mode).groupBy {it.y}.toSortedMap().values.map {row->row.sortedBy {it.x}.map {it.label}}
    fun keyAt(mode: PicoKeyboardMode,u: Float,v: Float): String? {
        if(!u.isFinite() || !v.isFinite() || u !in 0f..<1f || v !in 0f..<1f) return null
        val x=u*1200;val y=v*height(mode)
        return keys(mode).firstOrNull {x>=it.x+2 && x<it.x+it.width-2 && y>=it.y+2 && y<it.y+it.height-2}?.label
    }
    fun legend(label: String)=when(label) {"RShift"->"Shift";"RCtrl"->"Ctrl";"RAlt"->"Alt";"RWin"->"Win";"NumEnter"->"Enter";"Num"->"NumLk";"Backspace"->"Backspace";else->if(label.startsWith("Num")) label.drop(3) else label}
    fun shifted(label: String)=mapOf("`" to "~","1" to "!","2" to "@","3" to "#","4" to "$","5" to "%","6" to "^","7" to "&","8" to "*","9" to "(","0" to ")","-" to "_","=" to "+","[" to "{","]" to "}","\\" to "|",";" to ":","'" to "\"","," to "<","." to ">","/" to "?")[label]
    fun vk(label: String): Int? = when {
        label.length==1 && label[0] in 'A'..'Z' -> label[0].code
        label.length==1 && label[0] in '0'..'9' -> label[0].code
        label.length==4 && label.startsWith("Num") && label.last() in '0'..'9' -> 96+label.last().digitToInt()
        label.startsWith("F") && label.drop(1).toIntOrNull() in 1..12 -> 111+label.drop(1).toInt()
        else -> mapOf("RShift" to 161,"RCtrl" to 163,"RAlt" to 165,"RWin" to 92,"Menu" to 93,"PrtSc" to 44,"ScrLk" to 145,"Pause" to 19,"`" to 192,"\\" to 220,"NumEnter" to 13,"Num" to 144,"Num/" to 111,"Num*" to 106,"Num-" to 109,"Num+" to 107,"Num." to 110,"Esc" to 27,"Tab" to 9,"Caps" to 20,"Shift" to 160,"Ctrl" to 162,"Alt" to 164,"Win" to 91,"Space" to 32,"Enter" to 13,"Backspace" to 8,"Insert" to 45,"Delete" to 46,"Home" to 36,"End" to 35,"PgUp" to 33,"PgDn" to 34,"←" to 37,"↑" to 38,"→" to 39,"↓" to 40,"-" to 189,"=" to 187,"[" to 219,"]" to 221,";" to 186,"'" to 222,"," to 188,"." to 190,"/" to 191)[label]
    }
}
internal class PicoKeyboardKeys(private val send: (Int,Boolean)->Boolean) {
    private val held=mutableSetOf<Int>()
    fun pressed(label: String): Boolean {
        val vk=PicoKeyboardLayout.vk(label) ?: return false
        if(vk in setOf(160,161,162,163,164,165,91,92)) {
            val down=vk !in held
            if(!send(vk,down)) return false
            if(down) held.add(vk) else held.remove(vk)
            return true
        }
        if(!send(vk,true)) return false
        held.add(vk)
        val released=send(vk,false)
        if(released) held.remove(vk)
        return released
    }
    fun active(label: String)=PicoKeyboardLayout.vk(label) in held
    private fun releaseAll(): Boolean {
        var success=true
        held.toList().forEach {if(send(it,false)) held.remove(it) else success=false}
        return success
    }
    fun codexVoice(): Boolean = releaseAll() && pressed("F8")
    fun release() {releaseAll()}
}

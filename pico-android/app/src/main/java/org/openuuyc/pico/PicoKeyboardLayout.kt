package org.openuuyc.pico

internal enum class PicoKeyboardMode { SYSTEM,CUSTOM,FULL }
internal object PicoKeyboardLayout {
    fun rows(mode: PicoKeyboardMode): List<List<String>> = when(mode) {
        PicoKeyboardMode.SYSTEM -> emptyList()
        PicoKeyboardMode.CUSTOM -> listOf("1 2 3 4 5 6 7 8 9 0", "Q W E R T Y U I O P", "A S D F G H J K L", "Shift Z X C V B N M Backspace", "Ctrl Alt Tab Space Enter ← ↓ ↑ →")
        PicoKeyboardMode.FULL -> listOf("Esc F1 F2 F3 F4 F5 F6 F7 F8 F9 F10 F11 F12", "1 2 3 4 5 6 7 8 9 0 - = Backspace", "Tab Q W E R T Y U I O P [ ]", "Caps A S D F G H J K L ; ' Enter", "Shift Z X C V B N M , . / Home End", "Ctrl Win Alt Space Insert Delete PgUp PgDn ← ↓ ↑ →", "Num Num/ Num* Num- Num7 Num8 Num9 Num+ Num4 Num5 Num6 Num1 Num2 Num3 Num0 Num.")
    }.map { it.split(' ') }
    fun keyAt(mode: PicoKeyboardMode,u: Float,v: Float): String? {
        if(!u.isFinite() || !v.isFinite() || u !in 0f..<1f || v*400 !in 96f..<360f) return null
        val rows=rows(mode);if(rows.isEmpty()) return null
        val row=((v*400-96)/(264f/rows.size)).toInt()
        return rows.getOrNull(row)?.let { it.getOrNull((u*it.size).toInt()) }
    }
    fun vk(label: String): Int? = when {
        label.length==1 && label[0] in 'A'..'Z' -> label[0].code
        label.length==1 && label[0] in '0'..'9' -> label[0].code
        label.length==4 && label.startsWith("Num") && label.last() in '0'..'9' -> 96+label.last().digitToInt()
        label.startsWith("F") && label.drop(1).toIntOrNull() in 1..12 -> 111+label.drop(1).toInt()
        else -> mapOf("Num" to 144,"Num/" to 111,"Num*" to 106,"Num-" to 109,"Num+" to 107,"Num." to 110,"Esc" to 27,"Tab" to 9,"Caps" to 20,"Shift" to 160,"Ctrl" to 162,"Alt" to 164,"Win" to 91,"Space" to 32,"Enter" to 13,"Backspace" to 8,"Insert" to 45,"Delete" to 46,"Home" to 36,"End" to 35,"PgUp" to 33,"PgDn" to 34,"←" to 37,"↑" to 38,"→" to 39,"↓" to 40,"-" to 189,"=" to 187,"[" to 219,"]" to 221,";" to 186,"'" to 222,"," to 188,"." to 190,"/" to 191)[label]
    }
}
internal class PicoKeyboardKeys(private val send: (Int,Boolean)->Boolean) {
    private val held=mutableSetOf<Int>()
    fun pressed(label: String): Boolean {
        val vk=PicoKeyboardLayout.vk(label) ?: return false
        if(vk in setOf(160,162,164,91)) {
            val down=vk !in held
            if(!send(vk,down)) return false
            if(down) held.add(vk) else held.remove(vk)
            return true
        }
        if(!send(vk,true)) return false
        return send(vk,false)
    }
    fun active(label: String)=PicoKeyboardLayout.vk(label) in held
    fun release() {held.toList().forEach {send(it,false)};held.clear()}
}

package org.openuuyc.pico

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import java.io.File

/** Private bounded copy: no broad storage permission, no retained external URI. */
internal class PicoXrAppearance(private val context: Context) {
    private val prefs=context.getSharedPreferences("xr_appearance",Context.MODE_PRIVATE)
    private val imageFile=File(context.filesDir,"xr-background.img")
    var mr=prefs.getBoolean("mr",true); private set
    var visibility=prefs.getInt("visibility",100).coerceIn(0,100); private set
    var color=prefs.getInt("color",Color.BLACK); private set
    var custom=prefs.getBoolean("custom",false); private set
    var image: Bitmap?=if(custom) decode(imageFile) else null; private set
    var presetId=prefs.getString("preset",null) ?: PicoBackgroundPattern.presets.firstOrNull {it.color==color}?.id ?: "black"; private set
    var seed=prefs.getInt("seed",1); private set
    var brightness=prefs.getInt("brightness",75).coerceIn(25,100); private set
    private var generated: Bitmap?=null
    private var generatedKey=""
    fun backgroundImage(): Bitmap?=if(custom) image else generated
    fun prepareBackground() {
        val preset=PicoBackgroundPattern.find(presetId)
        if(custom || !preset.procedural) {generated=null;generatedKey="";return}
        val key="${preset.id}:$seed"
        if(generatedKey==key && generated!=null) return
        val pixels=PicoBackgroundPattern.render(preset.id,768,512,seed)
        generated=Bitmap.createBitmap(pixels,768,512,Bitmap.Config.ARGB_8888);generatedKey=key
    }
    fun selectPreset(id: String) {
        val preset=PicoBackgroundPattern.find(id)
        presetId=preset.id;custom=false;color=preset.color;mr=false
        prefs.edit().putString("preset",presetId).putBoolean("custom",false).putInt("color",color).putBoolean("mr",false).apply()
        prepareBackground()
    }
    fun regenerate() {seed+=1;prefs.edit().putInt("seed",seed).apply();prepareBackground()}
    fun cycleBrightness() {brightness=if(brightness>=100) 25 else brightness+25;prefs.edit().putInt("brightness",brightness).apply()}
    fun update(mr: Boolean,color: Int,visibility: Int) {
        this.mr=mr
        if(this.color!=color) {presetId=PicoBackgroundPattern.presets.firstOrNull {it.color==color}?.id ?: "black";generated=null;generatedKey="";prefs.edit().putString("preset",presetId).apply()}
        this.color=color;this.visibility=visibility.coerceIn(0,100)
        prefs.edit().putBoolean("mr",mr).putInt("color",color).putInt("visibility",this.visibility).apply()
    }
    fun useImage(enabled: Boolean) {
        custom=enabled && imageFile.isFile
        if(custom && image==null) image=decode(imageFile)
        prefs.edit().putBoolean("custom",custom).apply()
    }
    fun importImage(uri: Uri) {
        val temp=File(context.filesDir,"xr-background.tmp")
        try {
            context.contentResolver.openInputStream(uri).use {input ->
                requireNotNull(input) {"图片无法读取"}
                temp.outputStream().use {output ->
                    val buffer=ByteArray(8192);var total=0
                    while(true) {val n=input.read(buffer);if(n<0) break;total+=n;require(total<=16*1024*1024) {"图片不能超过16MB"};output.write(buffer,0,n)}
                }
            }
            val decoded=decode(temp) ?: error("无法解析图片，请选择 PNG/JPEG/WebP")
            check(temp.renameTo(imageFile)) {"图片保存失败"}
            image=decoded;useImage(true)
        } finally {temp.delete()}
    }
    private fun decode(file: File): Bitmap?=runCatching {
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
        BitmapFactory.decodeFile(file.path,bounds)
        require(bounds.outWidth in 1..32768 && bounds.outHeight in 1..32768)
        val options=BitmapFactory.Options().apply {
            inSampleSize=1
            while(bounds.outWidth/inSampleSize>2048 || bounds.outHeight/inSampleSize>2048) inSampleSize*=2
        }
        BitmapFactory.decodeFile(file.path,options)
    }.getOrNull()
}

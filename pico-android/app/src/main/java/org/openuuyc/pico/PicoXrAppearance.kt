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
    var menuHand=if(prefs.getBoolean("floating_menu_migrated",false)) prefs.getInt("menu_hand",2).coerceIn(0,2) else 2; private set
    init {if(!prefs.getBoolean("floating_menu_migrated",false)) prefs.edit().putInt("menu_hand",2).putBoolean("floating_menu_migrated",true).apply()}
    fun setMenuHand(value: Int) {menuHand=value.coerceIn(0,2);prefs.edit().putInt("menu_hand",menuHand).apply()}
    var focusDots=prefs.getBoolean("focus_dots",false); private set
    fun setFocusDots(enabled: Boolean) {focusDots=enabled;prefs.edit().putBoolean("focus_dots",enabled).apply()}
    var flow=prefs.getBoolean("background_flow",true); private set
    fun toggleFlow() {flow=!flow;prefs.edit().putBoolean("background_flow",flow).apply()}
    data class EnvironmentImage(val width: Int,val height: Int,val rgba: ByteArray)
    @Volatile private var environmentImage: EnvironmentImage?=null
    private var preparedImage: Bitmap?=null
    fun environmentImage()=environmentImage
    private fun prepareEnvironmentImage() {
        val source=if(custom) image else null
        if(source===preparedImage) return
        preparedImage=source;environmentImage=null
        if(source==null || source.isRecycled) return
        val scale=minOf(1f,1024f/maxOf(source.width,source.height))
        val reduced=if(scale<1f) Bitmap.createScaledBitmap(source,maxOf(1,(source.width*scale).toInt()),maxOf(1,(source.height*scale).toInt()),true) else source
        val pixels=IntArray(reduced.width*reduced.height);reduced.getPixels(pixels,0,reduced.width,0,0,reduced.width,reduced.height)
        val bytes=ByteArray(pixels.size*4)
        for((i,c) in pixels.withIndex()) {bytes[i*4]=(c ushr 16).toByte();bytes[i*4+1]=(c ushr 8).toByte();bytes[i*4+2]=c.toByte();bytes[i*4+3]=(c ushr 24).toByte()}
        environmentImage=EnvironmentImage(reduced.width,reduced.height,bytes)
        if(reduced!==source) reduced.recycle()
    }
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
        prepareEnvironmentImage()
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
    fun setBrightness(value: Int) {brightness=value.coerceIn(25,100);prefs.edit().putInt("brightness",brightness).apply()}
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
            image=decoded;useImage(true);prepareBackground()
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

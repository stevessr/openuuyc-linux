package org.openuuyc.pico
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
internal object PicoAppIcon {
    private val cache=android.util.LruCache<String,Bitmap>(192)
    fun decode(encoded: String): Bitmap? {
        if(encoded.isBlank() || encoded.length>6000) return null
        cache.get(encoded)?.let {return it}
        return runCatching {
            val bytes=Base64.decode(encoded,Base64.DEFAULT)
            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            if(bounds.outWidth !in 1..64 || bounds.outHeight !in 1..64) return null
            BitmapFactory.decodeByteArray(bytes,0,bytes.size)?.also {cache.put(encoded,it)}
        }.getOrNull()
    }
}

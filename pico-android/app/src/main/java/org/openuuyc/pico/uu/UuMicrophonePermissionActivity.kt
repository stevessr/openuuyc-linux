package org.openuuyc.pico.uu

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import java.util.concurrent.atomic.AtomicReference

/** Process-local permission handoff; never put room credentials in an Intent. */
class UuMicrophonePermissionActivity : Activity() {
    companion object {
        private val pending=AtomicReference<((Boolean)->Unit)?>(null)
        fun request(context: Context,done: (Boolean)->Unit): Boolean {
            if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) {done(true);return true}
            if(!pending.compareAndSet(null,done)) return false
            return try {context.startActivity(Intent(context,UuMicrophonePermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));true}
            catch(_: Exception) {pending.compareAndSet(done,null);false}
        }
    }
    private var owner: ((Boolean)->Unit)?=null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state);owner=pending.get()
        if(owner==null) {finish();return}
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),1)
    }
    override fun onRequestPermissionsResult(code: Int,permissions: Array<out String>,results: IntArray) {
        super.onRequestPermissionsResult(code,permissions,results)
        if(code==1) {val done=owner;owner=null;val owns=pending.compareAndSet(done,null);finish();if(owns) done?.invoke(results.firstOrNull()==PackageManager.PERMISSION_GRANTED)}
    }
    override fun onDestroy() {if(isFinishing) {val done=owner;owner=null;if(done!=null && pending.compareAndSet(done,null)) done(false)};super.onDestroy()}
}

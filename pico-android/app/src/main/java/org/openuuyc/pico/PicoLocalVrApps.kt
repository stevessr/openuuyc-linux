package org.openuuyc.pico
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager

internal data class PicoLocalVrApp(val name: String,val packageName: String,val activityName: String)
internal object PicoLocalVrApps {
    fun allowed(packageName: String,own: String,system: Boolean,vr: Boolean): Boolean =
        packageName!=own && !system && vr && packageName !in setOf("org.meumeu.wivrn.github","alvr.client","com.valvesoftware.steamlinkvr") &&
            !packageName.startsWith("org.meumeu.wivrn.")
    @Suppress("DEPRECATION")
    fun list(activity: Activity): List<PicoLocalVrApp> {
        val pm=activity.packageManager
        val queries=listOf(Intent.CATEGORY_LAUNCHER,"org.khronos.openxr.intent.category.IMMERSIVE_HMD","com.oculus.intent.category.VR")
        val candidates=queries.flatMap {category ->pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category),PackageManager.GET_META_DATA)}
        return candidates.mapNotNull {resolved ->
            val info=resolved.activityInfo ?: return@mapNotNull null
            val app=pm.getApplicationInfo(info.packageName,PackageManager.GET_META_DATA)
            val vr=info.metaData?.get("pvr.app.type")=="vr" || app.metaData?.get("pvr.app.type")=="vr" ||
                resolved.filter?.hasCategory("org.khronos.openxr.intent.category.IMMERSIVE_HMD")==true ||
                pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory("org.khronos.openxr.intent.category.IMMERSIVE_HMD").setPackage(info.packageName),0).isNotEmpty() ||
                pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory("com.oculus.intent.category.VR").setPackage(info.packageName),0).isNotEmpty()
            if(!info.exported || !info.enabled || !app.enabled || !allowed(info.packageName,activity.packageName,app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM!=0,vr)) null
            else PicoLocalVrApp(resolved.loadLabel(pm).toString().take(100),info.packageName,info.name)
        }.distinctBy {it.packageName}.sortedBy {it.name.lowercase()}.take(128)
    }
    fun launch(activity: Activity,app: PicoLocalVrApp) {
        check(list(activity).any {it.packageName==app.packageName && it.activityName==app.activityName}) {"VR 应用已变更，请刷新列表"}
        activity.startActivity(Intent(Intent.ACTION_MAIN).setComponent(ComponentName(app.packageName,app.activityName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

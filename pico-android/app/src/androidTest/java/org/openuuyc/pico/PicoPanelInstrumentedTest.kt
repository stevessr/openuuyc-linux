package org.openuuyc.pico

import android.graphics.Bitmap
import android.graphics.Canvas
import android.app.Instrumentation
import android.app.Activity
import android.os.Bundle
import java.util.concurrent.atomic.AtomicReference
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import org.openuuyc.pico.uu.UuDevice
import org.openuuyc.pico.uu.UuAccount
import org.openuuyc.pico.uu.KeystoreSessionVault
import java.io.File

/** On-device Android rendering/interaction fixtures, not live UU interoperability. */
@Suppress("DEPRECATION")
class PicoPanelInstrumentation : Instrumentation() {
    private var phase: String? = null
    override fun onCreate(arguments: Bundle?) { phase = arguments?.getString("vault-phase"); super.onCreate(arguments); start() }
    override fun onStart() {
        val error = AtomicReference<Throwable?>(null)
        runOnMainSync {
            try { when (phase) { "quality" -> verifyQualityCanvas(); "profile" -> { val p=org.openuuyc.pico.uu.UuAndroidDeviceProfile.read(targetContext); check(p.memoryMiB>0 && p.cpu.isNotBlank() && p.screen.contains("x")); sendStatus(0,Bundle().apply {putString("stream","Hardware profile: CPU=${p.cpu}; memory=${p.memoryMiB} MiB; GPU=${p.video}; display=${p.screen}\n")}) }; "runtime" -> { val info=PicoRuntimeProbe.extensions(targetContext); check(!info.startsWith("error:")) { info }; sendStatus(0, Bundle().apply { putString("stream", "PICO runtime extensions:\n$info\n") }) }; "network-denied" -> verifyMissingNetworkPermission(); "network" -> verifyRtcStartup(); null -> verifyPanel(); else -> verifyVaultPhase(phase!!) } } catch (failure: Throwable) { error.set(failure) }
        }
        val result = Bundle()
        val failure = error.get()
        result.putString("stream", if (failure == null) "PICO on-device fixture: 1 test passed" else "PICO UI fixture failed: ${failure.javaClass.simpleName}: ${failure.message}")
        result.putInt("tests", 1); result.putInt("failures", if (failure == null) 0 else 1)
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
    private fun assertEquals(expected: Any?, actual: Any?) { check(expected == actual) { "expected=$expected actual=$actual" } }
    private fun assertTrue(value: Boolean) { check(value) }
    private fun verifyQualityCanvas() {
        val state=org.openuuyc.pico.uu.UuRemoteSettingsState().apply {
            selected=0;current=current.copy(fps=165)
            profiles=listOf(org.openuuyc.pico.uu.UuCodecProfile(1,1920,1080,165))
            screens=listOf(org.openuuyc.pico.uu.UuRemoteScreen(0,0,"显示器1",165,org.openuuyc.pico.uu.UuDisplayMode(1920,1080),emptyList(),100,emptyList()))
        }
        val bitmap=Bitmap.createBitmap(600,1000,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.scale(.5f,2.5f)
        PicoXrQualityCanvas.draw(canvas,state,-1f,-1f,false,"本地画质菜单测试 · 非真实串流")
        File(targetContext.cacheDir,"xr-quality-fixture.png").outputStream().use {check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
        bitmap.recycle()
    }
    private fun verifyMissingNetworkPermission() {
        val context = targetContext
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission(android.Manifest.permission.ACCESS_NETWORK_STATE))
        org.webrtc.PeerConnectionFactory.initialize(org.webrtc.PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        var rejected = false
        try { org.webrtc.NetworkMonitor.getInstance().startMonitoring(context) }
        catch (error: SecurityException) { rejected = error.message.orEmpty().contains("ACCESS_NETWORK_STATE") }
        assertTrue(rejected)
    }

    private fun verifyRtcStartup() {
        val context = targetContext
        assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(android.Manifest.permission.ACCESS_NETWORK_STATE))
        org.webrtc.PeerConnectionFactory.initialize(org.webrtc.PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        repeat(3) {
            val engine = org.webrtc.PeerConnectionFactory.builder().createPeerConnectionFactory()
            val peer = engine.createPeerConnection(org.webrtc.PeerConnection.RTCConfiguration(emptyList()), object : org.webrtc.PeerConnection.Observer {
                override fun onSignalingChange(s: org.webrtc.PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(s: org.webrtc.PeerConnection.IceConnectionState?) {}
                override fun onIceConnectionReceivingChange(r: Boolean) {}
                override fun onIceGatheringChange(s: org.webrtc.PeerConnection.IceGatheringState?) {}
                override fun onIceCandidate(c: org.webrtc.IceCandidate) {}
                override fun onIceCandidatesRemoved(c: Array<out org.webrtc.IceCandidate>?) {}
                override fun onAddStream(s: org.webrtc.MediaStream?) {}
                override fun onRemoveStream(s: org.webrtc.MediaStream?) {}
                override fun onDataChannel(c: org.webrtc.DataChannel) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(r: org.webrtc.RtpReceiver?, s: Array<out org.webrtc.MediaStream>?) {}
            }) ?: error("RTC peer creation failed")
            val channel = peer.createDataChannel("SMOKE_TEST", org.webrtc.DataChannel.Init())
            val done = java.util.concurrent.CountDownLatch(1)
            val success = java.util.concurrent.atomic.AtomicBoolean(false)
            peer.createOffer(object : org.webrtc.SdpObserver {
                override fun onCreateSuccess(s: org.webrtc.SessionDescription?) { success.set(s != null); done.countDown() }
                override fun onCreateFailure(e: String?) { done.countDown() }
                override fun onSetSuccess() {}
                override fun onSetFailure(e: String?) {}
            }, org.webrtc.MediaConstraints())
            check(done.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "RTC offer timeout" }
            assertTrue(success.get())
            channel?.close(); channel?.dispose()
            peer.close(); peer.dispose(); engine.dispose()
        }
    }

    private fun verifyVaultPhase(phase: String) {
        val vault = KeystoreSessionVault(targetContext, "vault-test")
        val prefs = targetContext.getSharedPreferences("uu_secure_vault-test", 0)
        val sample = UuAccount("fixture-not-a-real-token", "fixture-user", "Fixture")
        when (phase) {
            "save" -> {
                vault.clear(); vault.save(sample)
                val first = prefs.getString("encrypted_session", null)!!
                assertTrue(!first.contains(sample.token) && !first.contains(sample.userId))
                vault.save(sample)
                assertTrue(first != prefs.getString("encrypted_session", null))
                assertEquals(sample, vault.load())
            }
            "restore" -> {
                assertEquals(sample, vault.load()) // Separate am instrument invocation/process.
                val original = prefs.getString("encrypted_session", null)!!
                val index = original.length / 2
                val tampered = original.substring(0, index) + (if (original[index] == 'A') "B" else "A") + original.substring(index + 1)
                assertTrue(prefs.edit().putString("encrypted_session", tampered).commit())
                var rejected = false
                try { vault.load() } catch (_: Exception) { rejected = true }
                assertTrue(rejected)
                vault.clear(); assertEquals(null, vault.load())
            }
            else -> error("Unknown vault fixture phase")
        }
    }

    private fun nodes(root: View): List<View> = listOf(root) +
        if (root is ViewGroup) (0 until root.childCount).flatMap { nodes(root.getChildAt(it)) } else emptyList()
    private fun button(root: View, title: String) = nodes(root).filterIsInstance<Button>().first { it.text.toString() == title }
    private fun capture(panel: PicoLoginView, name: String, widthDp: Int = 1280) {
        val density = panel.resources.displayMetrics.density
        val w = (widthDp * density).toInt(); val h = (720 * density).toInt()
        panel.reflow(widthDp)
        panel.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        panel.layout(0, 0, w, h)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        panel.draw(Canvas(bitmap))
        File(targetContext.cacheDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun verifyPanel() {
        run {
            val context = targetContext
            var login = 0; var refresh = 0; var logout = 0; var camera = 0; var restore = 0; var immersive = 0;var sms=0
            val connections = mutableListOf<String>()
            val panel = PicoLoginView(context, { login++ }, { refresh++ }, { logout++ }, {}, { camera++ }, View(context), true, { connections.add(it.id) }, { restore++ }, { immersive++ }, {sms++})
            capture(panel, "pico-ui-login")
            button(panel,"手机号登录").performClick();assertEquals(1,sms)
            button(panel, "扫码登录").performClick(); assertEquals(1, login)
            panel.setLoginState(true, false)
            button(panel, "刷新二维码").performClick(); assertEquals(2, login)
            panel.setRestoreState(true)
            assertTrue(!button(panel, "正在恢复登录…").isEnabled)
            panel.setRestoreState(false, retry = true)
            button(panel, "重试恢复登录").performClick(); assertEquals(1, restore)
            panel.setLoginState(false, true, "UI 测试账号")
            panel.showDevices(listOf(
                UuDevice("linux", "UI TEST · Linux", true, true, 0),
                UuDevice("offline", "UI TEST · Offline", false, true, 0),
                UuDevice("busy", "UI TEST · Occupied", true, true, 1)))
            capture(panel, "pico-ui-devices")
            val connect = nodes(panel).filterIsInstance<Button>().filter { it.text.toString() == "远程连接" }
            assertEquals(2, connect.size); assertEquals(1, connect.count { it.isEnabled })
            assertTrue(button(panel, "抢占连接").isEnabled)
            connect.first { it.isEnabled }.performClick(); assertEquals(listOf("linux"), connections)
            assertEquals(0, immersive)
            nodes(panel).filterIsInstance<Button>().first { it.text.toString() == "沉浸式连接" && it.isEnabled }.performClick()
            assertEquals(1, immersive)
            nodes(panel).filterIsInstance<EditText>().single().setText("no-match")
            assertTrue(nodes(panel).filterIsInstance<TextView>().any { it.text.toString() == "没有匹配的设备" })
            nodes(panel).filterIsInstance<EditText>().single().setText("")
            button(panel, "刷新").performClick(); assertEquals(1, refresh)
            button(panel, "连接设置").performClick(); capture(panel, "pico-ui-settings")
            button(panel, "打开 / 关闭相机预览").performClick(); assertEquals(1, camera)
            button(panel, "关于").performClick(); capture(panel, "pico-ui-about")
            button(panel, "我的设备").performClick(); capture(panel, "pico-ui-narrow", 600)
            button(panel, "退出登录").performClick(); assertEquals(1, logout)
            panel.setLoginState(false, false); capture(panel, "pico-ui-login-narrow", 600)
            val presses = mutableListOf<Boolean>()
            val video = PicoPanelVideoView(context, {}, {}, { _, _, down -> presses.add(down) }, {})
            video.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
            video.layout(0, 0, 1280, 720)
            val surface = video.getChildAt(0)
            val down = android.view.MotionEvent.obtain(0, 1, android.view.MotionEvent.ACTION_DOWN, 640f, 360f, 0)
            surface.dispatchTouchEvent(down); down.recycle()
            video.releasePointer()
            assertEquals(listOf(true, false), presses)
        }
    }
}

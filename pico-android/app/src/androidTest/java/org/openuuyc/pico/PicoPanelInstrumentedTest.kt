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
    private var probeDurationMs=3000L
    override fun onCreate(arguments: Bundle?) { phase = arguments?.getString("vault-phase"); probeDurationMs=(arguments?.getString("probe-seconds")?.toLongOrNull() ?: 3).coerceIn(3,30)*1000; super.onCreate(arguments); start() }
    override fun onStart() {
        sendStatus(0,Bundle().apply {putString("stream","Fixture phase: $phase\n")})
        val error = AtomicReference<Throwable?>(null)
        if(phase=="native-ime") {
            try {verifyNativeIme()} catch(failure: Throwable) {error.set(failure)}
        } else if(phase=="pcvr-stereo" || phase=="xr-pointer") {
            try {verifyPcvrStereo()} catch(failure: Throwable) {error.set(failure)}
        } else runOnMainSync {
            try { when (phase) { "full-keyboard" -> verifyFullKeyboard(); "rounded-video" -> verifyRoundedVideo(); "controller-menu" -> verifyControllerMenu(); "focus-dots" -> verifyFocusDotsSetting(); "xr-menu" -> verifyXrGlobalMenu(); "quality" -> verifyQualityCanvas(); "profile" -> { val p=org.openuuyc.pico.uu.UuAndroidDeviceProfile.read(targetContext); check(p.memoryMiB>0 && p.cpu.isNotBlank() && p.screen.contains("x")); sendStatus(0,Bundle().apply {putString("stream","Hardware profile: CPU=${p.cpu}; memory=${p.memoryMiB} MiB; GPU=${p.video}; display=${p.screen}\n")}) }; "runtime" -> { val info=PicoRuntimeProbe.extensions(targetContext); check(!info.startsWith("error:")) { info }; sendStatus(0, Bundle().apply { putString("stream", "PICO runtime extensions:\n$info\n") }) }; "network-denied" -> verifyMissingNetworkPermission(); "network" -> verifyRtcStartup(); null -> verifyPanel(); else -> verifyVaultPhase(phase!!) } } catch (failure: Throwable) { error.set(failure) }
        }
        val result = Bundle()
        val failure = error.get()
        result.putString("stream", if (failure == null) "PICO on-device fixture: 1 test passed" else "PICO UI fixture failed: ${failure.javaClass.simpleName}: ${failure.message}")
        result.putInt("tests", 1); result.putInt("failures", if (failure == null) 0 else 1)
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
    private fun verifyPcvrStereo() {
        val pointer=phase=="xr-pointer"
        val report=File(targetContext.filesDir,if(pointer) "pointer-probe.json" else "pcvr-probe.json")
        val error=File(targetContext.filesDir,"pcvr-probe-error.txt")
        report.delete();error.delete()
        val intent=android.content.Intent(targetContext,XrActivity::class.java)
            .putExtra("openuuyc.pcvr.probe",true).putExtra("openuuyc.pcvr.pointer",pointer)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val monitor=addMonitor(XrActivity::class.java.name,null,false)
        sendStatus(0,Bundle().apply {putString("stream","Launching internal stereo probe Activity\n")})
        runOnMainSync {targetContext.startActivity(intent)}
        val activity=waitForMonitorWithTimeout(monitor,10000) as? PicoActivity
        removeMonitor(monitor)
        checkNotNull(activity) {"PICO did not launch the internal XR Activity within 10 seconds"}
        try {
            val minimum=android.os.SystemClock.elapsedRealtime()+probeDurationMs
            val deadline=minimum+25000
            var value:org.json.JSONObject?=null
            while(android.os.SystemClock.elapsedRealtime()<deadline) {
                check(!error.exists()) {error.readText()}
                if(report.exists()) {
                    value=runCatching {org.json.JSONObject(report.readText())}.getOrNull()
                    if((value?.optLong(if(pointer) "samples" else "projectionFrames") ?: 0)>=120 && android.os.SystemClock.elapsedRealtime()>=minimum) break
                }
                Thread.sleep(200)
            }
            val result=checkNotNull(value) {"No stereo projection report; wear/wake the headset and keep OpenUUYC focused"}
            if(pointer) {
                check(result.getLong("samples")>=120 && result.getLong("leftValid")>0 && result.getLong("rightValid")>0) {result.toString()}
            } else {
                check(result.getLong("projectionFrames")>=120 && result.getLong("headValid")>0) {result.toString()}
                check(result.getDouble("ipdMeters") in 0.03..0.09) {result.toString()}
                check(result.getInt("leftWidth")>0 && result.getInt("rightWidth")>0)
            }
            sendStatus(0,Bundle().apply {putString("stream","On-device stereo PROBE (not remote video): $result\n")})
        } finally {
            runOnMainSync {activity.finishAndRemoveTask()}
            val deadline=android.os.SystemClock.elapsedRealtime()+5000
            while(!activity.pcvrProbeStopped && android.os.SystemClock.elapsedRealtime()<deadline) Thread.sleep(50)
            check(activity.pcvrProbeStopped) {"XR probe did not release its session within 5 seconds"}
        }
    }

    private fun assertEquals(expected: Any?, actual: Any?) { check(expected == actual) { "expected=$expected actual=$actual" } }
    private fun assertTrue(value: Boolean) { check(value) }
    private fun verifyFullKeyboard() {
        val mode=PicoKeyboardMode.FULL
        val keys=PicoKeyboardLayout.keys(mode)
        check(keys.size==104)
        keys.forEach {key->check(PicoKeyboardLayout.keyAt(mode,(key.x+key.width/2)/1200,(key.y+key.height/2)/480)==key.label)}
        val events=mutableListOf<Pair<Int,Boolean>>()
        val state=PicoKeyboardKeys {key,down->events.add(key to down);true}
        state.pressed("RCtrl");check(state.codexVoice())
        check(events==listOf(163 to true,163 to false,119 to true,119 to false))
        val bitmap=Bitmap.createBitmap(1200,480,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);PicoXrMaterial.panel(canvas,1200f,480f)
        PicoKeyboardCanvas.draw(canvas,mode,state,.5f,70f/480)
        val file=File(targetContext.cacheDir,"physical-keyboard-preview.png")
        file.outputStream().use {check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))};bitmap.recycle()
        sendStatus(0,Bundle().apply {putString("stream","104-key layout rendered; hit geometry, right modifier release and Codex F8 mapping passed; no remote keys or audio sent.\n")})
    }

    private fun verifyNativeIme() {
        val monitor=addMonitor(PicoActivity::class.java.name,null,false)
        runOnMainSync {targetContext.startActivity(android.content.Intent(targetContext,PicoActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))}
        val activity=waitForMonitorWithTimeout(monitor,10000)
        removeMonitor(monitor)
        checkNotNull(activity) {"PICO blocked the native IME fixture Activity"}
        val output=mutableListOf<String>()
        lateinit var keyboard:PicoRemoteKeyboard
        try {
            runOnMainSync {
                keyboard=PicoRemoteKeyboard(activity,{output.add(it);true},{_,_->true})
                activity.setContentView(keyboard);keyboard.show()
            }
            val deadline=android.os.SystemClock.elapsedRealtime()+5000
            var active=false
            while(android.os.SystemClock.elapsedRealtime()<deadline && !active) {
                runOnMainSync {active=keyboard.nativeEditor?.let {(activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).isActive(it)}==true}
                if(!active) Thread.sleep(100)
            }
            check(active) {"System IME did not bind the native EditText"}
            runOnMainSync {
                val editor=checkNotNull(keyboard.nativeEditor)
                val info=android.view.inputmethod.EditorInfo()
                val input=checkNotNull(editor.onCreateInputConnection(info))
                check(info.imeOptions and android.view.inputmethod.EditorInfo.IME_FLAG_FORCE_ASCII==0)
                input.setComposingText("zhongwen",1)
                check(output.isEmpty())
                val value="中文 日本語 한국어 العربية e\u0301 👩🏽‍💻"
                input.commitText(value,1)
                check(output.isEmpty()) {"Composition must not be sent before confirmation"}
                editor.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEND)
                check(output==listOf(value)) {"Unicode text was altered or duplicated"}
                keyboard.show()
                val cancelled=checkNotNull(keyboard.nativeEditor).onCreateInputConnection(android.view.inputmethod.EditorInfo())!!
                cancelled.setComposingText("未确认",1);keyboard.hide()
                check(output.size==1) {"Cancelled text must not be sent"}
                // Opening a voice control does NOT record. Switching back must
                // replace its non-editor dialog instead of swallowing show().
                keyboard.showVoiceInput();keyboard.show()
                checkNotNull(keyboard.nativeEditor) {"Voice dialog swallowed the system keyboard request"}
                keyboard.hide()
            }
            sendStatus(0,Bundle().apply {putString("stream","Native IME bound; multilingual Unicode/composition/confirm/cancel passed; no audio recording or remote transport used.\n")})
        } finally {runOnMainSync {runCatching {keyboard.hide()};activity.finishAndRemoveTask()}}
    }

    private fun verifyRoundedVideo() {
        val egl=org.webrtc.EglBase.create(null,org.webrtc.EglBase.CONFIG_PIXEL_RGBA_BUFFER)
        val drawer=org.webrtc.OpenUuRoundedDrawer()
        val textures=IntArray(4)
        try {
            egl.createPbufferSurface(256,256);egl.makeCurrent()
            android.opengl.GLES20.glGenTextures(4,textures,0)
            fun texture(index: Int,rgba: Boolean,value: Int) {
                val format=if(rgba) android.opengl.GLES20.GL_RGBA else android.opengl.GLES20.GL_LUMINANCE
                val bytes=if(rgba) byteArrayOf(255.toByte(),0,0,255.toByte()) else byteArrayOf(value.toByte())
                val data=java.nio.ByteBuffer.allocateDirect(bytes.size).put(bytes).apply {position(0)}
                android.opengl.GLES20.glBindTexture(android.opengl.GLES20.GL_TEXTURE_2D,textures[index])
                android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D,android.opengl.GLES20.GL_TEXTURE_MIN_FILTER,android.opengl.GLES20.GL_LINEAR)
                android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D,android.opengl.GLES20.GL_TEXTURE_MAG_FILTER,android.opengl.GLES20.GL_LINEAR)
                android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D,android.opengl.GLES20.GL_TEXTURE_WRAP_S,android.opengl.GLES20.GL_CLAMP_TO_EDGE)
                android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D,android.opengl.GLES20.GL_TEXTURE_WRAP_T,android.opengl.GLES20.GL_CLAMP_TO_EDGE)
                android.opengl.GLES20.glTexImage2D(android.opengl.GLES20.GL_TEXTURE_2D,0,format,1,1,0,format,android.opengl.GLES20.GL_UNSIGNED_BYTE,data)
            }
            texture(0,true,0);for(i in 1..3) texture(i,false,128)
            val matrix=FloatArray(16).apply {android.opengl.Matrix.setIdentityM(this,0)}
            val pixels=java.nio.ByteBuffer.allocateDirect(256*256*4)
            for((w,h) in listOf(1920 to 1080,1080 to 1920,256 to 256)) for(yuv in listOf(false,true)) {
                if(yuv) drawer.drawYuv(textures.copyOfRange(1,4),matrix,w,h,0,0,256,256)
                else drawer.drawRgb(textures[0],matrix,w,h,0,0,256,256)
                pixels.position(0)
                android.opengl.GLES20.glReadPixels(0,0,256,256,android.opengl.GLES20.GL_RGBA,android.opengl.GLES20.GL_UNSIGNED_BYTE,pixels)
                check(android.opengl.GLES20.glGetError()==android.opengl.GLES20.GL_NO_ERROR)
                fun alpha(x: Int,y: Int)=pixels.get((y*256+x)*4+3).toInt() and 255
                check(alpha(128,128)>250 && alpha(0,128)>250)
                for((x,y) in listOf(0 to 0,255 to 0,0 to 255,255 to 255)) check(alpha(x,y)<32) {"Rounded alpha not transparent: $w x $h, yuv=$yuv"}
            }
        } finally {
            runCatching {drawer.release()};runCatching {android.opengl.GLES20.glDeleteTextures(4,textures,0)};egl.release()
        }
        sendStatus(0,Bundle().apply {putString("stream","RGB/YUV portrait/landscape rounded alpha probe passed; synthetic pixels, not remote media.\n")})
    }

    private fun verifyControllerMenu() {
        val reader=android.media.ImageReader.newInstance(1200,800,android.graphics.PixelFormat.RGBA_8888,4)
        val following=mutableListOf<Boolean>();val layouts=mutableListOf<Pair<Int,Int>>()
        val menu=PicoXrToolbar(surface=reader.surface,session={null},followView={following.add(it)},reset={},touchMode={},keyboardModeChanged={},
            appearance={_,_,_->},closeScreenMenu={},home={},connectImmersive={_,_->},pickBackground={},useImage={},
            backgroundAction={},menuShape={},connectedIds={emptySet()},disconnect={})
        fun clickRoot(index: Int) {
            val r=PicoXrMenuHit.toolbarRect(index)
            menu.activate((r[0]+r[2])/2400,(r[1]+r[3])/1600)
            reader.acquireLatestImage()?.close()
        }
        try {
            menu.setControllerMenu(0) {layout,hand->layouts.add(layout to hand)}
            reader.acquireLatestImage()?.close();assertEquals(0 to 0,layouts.last())
            clickRoot(8);assertEquals(listOf(true),following)
            menu.followState(true);reader.acquireLatestImage()?.close()
            clickRoot(9);assertEquals(listOf(true,false),following)
            menu.followState(false);reader.acquireLatestImage()?.close()
            clickRoot(10);assertEquals(3 to 0,layouts.last())
            menu.activate(.5f,.5f);reader.acquireLatestImage()?.close();assertEquals(0 to 0,layouts.last())
            clickRoot(3);assertEquals(1 to 0,layouts.last())
            val hand=PicoXrMenuHit.settingsRect(7)
            menu.activate((hand[0]+hand[2])/2400,(hand[1]+hand[3])/800)
            reader.acquireLatestImage()?.close();assertEquals(1 to 1,layouts.last())
        } finally {menu.close();reader.close()}
    }

    private fun verifyFocusDotsSetting() {
        val prefs=targetContext.getSharedPreferences("xr_appearance",android.content.Context.MODE_PRIVATE)
        val hadValue=prefs.contains("focus_dots")
        val settings=PicoXrAppearance(targetContext)
        val initial=settings.focusDots
        if(!hadValue) assertTrue(!initial)
        val reader=android.media.ImageReader.newInstance(1200,400,android.graphics.PixelFormat.RGBA_8888,4)
        val menu=PicoXrToolbar(surface=reader.surface,session={null},followView={},reset={},touchMode={},keyboardModeChanged={},
            appearance={_,_,_->},closeScreenMenu={},home={},connectImmersive={_,_->},pickBackground={},useImage={},
            backgroundAction={},menuShape={},connectedIds={emptySet()},disconnect={})
        var toggles=0
        try {
            menu.setFocusDots(initial) {settings.setFocusDots(it);toggles++}
            reader.acquireLatestImage()?.close()
            val space=PicoXrMenuHit.toolbarRect(3)
            menu.activate((space[0]+space[2])/2400,(space[1]+space[3])/1600)
            reader.acquireLatestImage()?.close()
            val toggle=PicoXrMenuHit.settingsRect(6)
            menu.activate((toggle[0]+toggle[2])/2400,(toggle[1]+toggle[3])/800)
            assertEquals(!initial,PicoXrAppearance(targetContext).focusDots)
            reader.acquireLatestImage()?.close()
            menu.activate((toggle[0]+toggle[2])/2400,(toggle[1]+toggle[3])/800)
            assertEquals(initial,PicoXrAppearance(targetContext).focusDots)
            assertEquals(2,toggles)
        } finally {
            settings.setFocusDots(initial)
            if(!hadValue) prefs.edit().remove("focus_dots").commit()
            menu.close();reader.close()
        }
    }

    private fun verifyXrGlobalMenu() {
        val reader=android.media.ImageReader.newInstance(1200,400,android.graphics.PixelFormat.RGBA_8888,4)
        var home=0;var appearance=0
        val menu=PicoXrToolbar(surface=reader.surface,session={null},followView={},reset={},touchMode={},keyboardModeChanged={},
            appearance={_,_,_->appearance++},closeScreenMenu={},home={home++},connectImmersive={_,_->},pickBackground={},useImage={},
            backgroundAction={},menuShape={},connectedIds={emptySet()},disconnect={})
        try {
            val homeRect=PicoXrMenuHit.toolbarRect(5)
            menu.activate((homeRect[0]+homeRect[2])/2400,(homeRect[1]+homeRect[3])/1600)
            assertEquals(1,home);reader.acquireLatestImage()?.close()
            val env=PicoXrMenuHit.toolbarRect(4)
            menu.activate((env[0]+env[2])/2400,(env[1]+env[3])/1600)
            reader.acquireLatestImage()?.close()
            val toggle=PicoXrMenuHit.settingsRect(0)
            menu.activate((toggle[0]+toggle[2])/2400,(toggle[1]+toggle[3])/800)
            assertEquals(1,appearance);reader.acquireLatestImage()?.close()
        } finally {menu.close();reader.close()}
    }

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
            val monitor=Any()
            val eventRan=java.util.concurrent.CountDownLatch(1)
            val eventError=AtomicReference<Throwable?>(null)
            val eventQueue=org.openuuyc.pico.uu.UuRtcCallbackQueue {eventError.set(it)}
            try {
                synchronized(monitor) {
                    peer.createOffer(object : org.webrtc.SdpObserver {
                        override fun onCreateSuccess(s: org.webrtc.SessionDescription?) {
                            success.set(s!=null)
                            eventQueue.post {synchronized(monitor) {eventRan.countDown()}}
                            done.countDown() // native signaling callback never waits for monitor
                        }
                        override fun onCreateFailure(e: String?) {done.countDown()}
                        override fun onSetSuccess() {}
                        override fun onSetFailure(e: String?) {}
                    },org.webrtc.MediaConstraints())
                    check(done.await(10,java.util.concurrent.TimeUnit.SECONDS)) {"RTC native callback blocked on session lock"}
                    assertTrue(success.get());assertEquals(1L,eventRan.count)
                }
                check(eventRan.await(10,java.util.concurrent.TimeUnit.SECONDS)) {"RTC callback queue did not drain"}
                eventError.get()?.let {throw it}
            } finally {eventQueue.close()}
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

    private fun verifyXrMaterials() {
        val bitmap=Bitmap.createBitmap(1200,2000,Bitmap.Config.ARGB_8888)
        fun render(name: String,draw: (Canvas)->Unit) {
            val c=Canvas(bitmap);c.scale(1f,5f);draw(c)
            assertEquals(0,android.graphics.Color.alpha(bitmap.getPixel(0,0)))
            val alpha=android.graphics.Color.alpha(bitmap.getPixel(600,1000))
            assertTrue(alpha in 180..254) // Real alpha, not opaque painted faux glass.
            File(targetContext.cacheDir,"pico-ui-xr-$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
        render("quality") {PicoXrQualityCanvas.draw(it,null,.4f,.2f,false,"等待真实远端能力")}
        render("options") {PicoXrOptionCanvas.draw(it,"编码偏好",listOf("自动","H.264","H.265"),0,0,30,.4f,.2f,"仅作绘制测试")}
        render("bitrate") {PicoXrOptionCanvas.draw(it,"自定义码率",emptyList(),-1,0,120,.75f,.8f,"尚未应用")}
        bitmap.recycle()
    }

    private fun verifyPanel() {
        verifyXrMaterials()
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
            val connect = nodes(panel).filterIsInstance<Button>().filter { it.text.toString() == "进入桌面" }
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
            capture(panel,"pico-ui-phone",390)
            assertTrue(button(panel,"账号").visibility==View.VISIBLE)
            button(panel,"连接设置").performClick()
            button(panel,"我的设备").performClick()
            capture(panel,"pico-ui-phone-roundtrip",390)
            capture(panel,"pico-ui-wide-roundtrip",1280)
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

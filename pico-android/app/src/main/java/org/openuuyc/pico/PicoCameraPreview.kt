package org.openuuyc.pico

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import android.view.View

/** Camera2 preview, when PICO firmware publishes a usable Android camera. */
internal class PicoCameraPreview(
    private val activity: Activity,
    private val report: (String) -> Unit
) {
    val preview = TextureView(activity).apply { visibility = View.GONE }
    private val handler = Handler(Looper.getMainLooper())
    private val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var surface: Surface? = null
    private var epoch = 0L
    private var firstFrame = false
    var active = false
        private set

    fun start() {
        if (active) return
        if (activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            report("尚未取得相机权限")
            return
        }
        active = true
        epoch++
        firstFrame = false
        preview.visibility = View.VISIBLE
        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) { open(epoch) }
            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                if (active && !firstFrame) {
                    firstFrame = true
                    report("摄像头第一帧已收到")
                }
            }
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean { stop(); return true }
        }
        if (preview.isAvailable) open(epoch)
    }

    private fun open(ticket: Long) {
        if (!active || ticket != epoch || camera != null) return
        try {
            val ids = manager.cameraIdList
            if (ids.isEmpty()) {
                fail("PICO OS 未向 Android Camera2 暴露相机；Ultra 的原生透视相机需要厂商 XR 相机接口")
                return
            }
            // Prefer backward-facing cameras, but skip devices without a usable
            // SurfaceTexture stream instead of failing on the first listed ID.
            val candidates = ids.mapNotNull { candidate ->
                runCatching {
                    val info = manager.getCameraCharacteristics(candidate)
                    val sizes = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        ?.getOutputSizes(SurfaceTexture::class.java)
                    val size = sizes?.minByOrNull {
                        kotlin.math.abs(it.width - 1280) + kotlin.math.abs(it.height - 720)
                    } ?: return@runCatching null
                    Triple(candidate, size, info.get(CameraCharacteristics.LENS_FACING) ==
                        CameraCharacteristics.LENS_FACING_BACK)
                }.getOrNull()
            }.sortedByDescending { it.third }
            val selected = candidates.firstOrNull()
            if (selected == null) { fail("系统列出了 Camera2 ID，但没有可预览的摄像头"); return }
            val (id, size) = selected
            preview.surfaceTexture?.setDefaultBufferSize(size.width, size.height)
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (ticket != epoch || !active) { device.close(); return }
                    camera = device
                    val texture = preview.surfaceTexture
                    if (texture == null) { fail("相机 Surface 已丢失"); return }
                    surface = Surface(texture)
                    try {
                        device.createCaptureSession(listOf(surface!!), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(configured: CameraCaptureSession) {
                                if (ticket != epoch || !active) { configured.close(); return }
                                session = configured
                                try {
                                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                    request.addTarget(surface!!)
                                    configured.setRepeatingRequest(request.build(), null, handler)
                                    report("Camera2 已开始请求相机图像，等待实际画面")
                                    handler.postDelayed({
                                        if (ticket == epoch && active && !firstFrame)
                                            fail("Camera2 会话成功，但 5 秒内无有效画面；PICO 可能限制了相机输出")
                                    }, 5_000)
                                } catch (e: Exception) { fail("Camera2 取流失败：" + e.javaClass.simpleName) }
                            }
                            override fun onConfigureFailed(configured: CameraCaptureSession) {
                                configured.close()
                                fail("PICO OS 拒绝相机输出配置")
                            }
                        }, handler)
                    } catch (e: Exception) { fail("Camera2 会话失败：" + e.javaClass.simpleName) }
                }
                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    if (ticket == epoch) fail("Camera2 相机已断开")
                }
                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    if (ticket == epoch) fail("Camera2 访问错误：" + error)
                }
            }, handler)
        } catch (e: Exception) { fail("Camera2 访问失败：" + e.javaClass.simpleName) }
    }

    private fun fail(reason: String) { stop(); report(reason) }

    fun stop() {
        epoch++
        active = false
        firstFrame = false
        session?.close()
        session = null
        camera?.close()
        camera = null
        surface?.release()
        surface = null
        preview.surfaceTextureListener = null
        preview.visibility = View.GONE
    }
}

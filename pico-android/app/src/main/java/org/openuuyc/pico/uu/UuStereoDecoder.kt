package org.openuuyc.pico.uu

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Hardware decoder into one SBS OpenXR Surface; decoder callbacks carry the render poses. */
internal class UuStereoDecoder(private val rendered: (UuStereoFrames.Frame)->Unit,private val lost: ()->Unit,private val error: (String)->Unit) {
    private val thread=HandlerThread("openuuyc-stereo-decoder").apply {start()}
    private val handler=Handler(thread.looper)
    private val ready=java.util.concurrent.atomic.AtomicBoolean(false)
    fun isReady()=ready.get()
    private var codec: MediaCodec?=null
    private var expectedWidth=0
    private var expectedHeight=0
    private val metadata=linkedMapOf<Long,UuStereoFrames.Frame>()
    private val queued=AtomicInteger()
    @Volatile private var closed=false
    @Volatile private var waitingKeyframe=true
    private val drain=object: Runnable {
        override fun run() {
            val decoder=codec ?: return
            runCatching {
                val info=MediaCodec.BufferInfo()
                repeat(8) {
                    val index=decoder.dequeueOutputBuffer(info,0)
                    if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val format=decoder.outputFormat
                        val width=if(format.containsKey("crop-right")) format.getInteger("crop-right")-format.getInteger("crop-left")+1 else format.getInteger(MediaFormat.KEY_WIDTH)
                        val height=if(format.containsKey("crop-bottom")) format.getInteger("crop-bottom")-format.getInteger("crop-top")+1 else format.getInteger(MediaFormat.KEY_HEIGHT)
                        require(width==expectedWidth && height==expectedHeight) {"双眼码流尺寸与协商格式不符"}
                    }
                    if(index>=0) decoder.releaseOutputBuffer(index,true)
                }
            }.onFailure {fail(it)}
            if(codec!=null && !closed) handler.postDelayed(this,2)
        }
    }
    fun surface(surface: Surface?,width: Int,height: Int,mime: String) {
        if(closed) return
        val done=CountDownLatch(1)
        handler.post {
            release()
            if(surface!=null) runCatching {
                val format=MediaFormat.createVideoFormat(mime,width,height).apply {
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,UuStereoFrames.MAX)
                    setInteger(MediaFormat.KEY_PRIORITY,0)
                    setInteger("low-latency",1)
                }
                val info=MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {candidate->
                    !candidate.isEncoder && candidate.isHardwareAccelerated && candidate.supportedTypes.any {it.equals(mime,true)} && runCatching {candidate.getCapabilitiesForType(mime).isFormatSupported(format)}.getOrDefault(false)
                } ?: kotlin.error("头显不支持此双眼硬件解码格式")
                val name=info.name
                expectedWidth=width;expectedHeight=height
                val decoder=MediaCodec.createByCodecName(name)
                codec=decoder
                decoder.setOnFrameRenderedListener({_,pts,_->metadata.remove(pts)?.let(rendered)},handler)
                decoder.configure(format,surface,null,0);decoder.start();ready.set(true);handler.post(drain)
            }.onFailure {fail(it)}
            done.countDown()
        }
        // Reconfiguration is called by the XR thread before destroying its old Surface.
        if(Thread.currentThread()!==thread && !done.await(3,TimeUnit.SECONDS)) error("双眼解码器释放超时")
    }
    fun frame(frame: UuStereoFrames.Frame) {
        if(closed) return
        if(queued.incrementAndGet()>2) {queued.decrementAndGet();waitingKeyframe=true;lost();return}
        handler.post {
            queued.decrementAndGet()
            val decoder=codec ?: return@post
            runCatching {
                if(waitingKeyframe && !frame.keyframe) return@post
                if(frame.keyframe && waitingKeyframe) {decoder.flush();metadata.clear();waitingKeyframe=false}
                val index=decoder.dequeueInputBuffer(0)
                if(index<0 || metadata.size>=8) {waitingKeyframe=true;lost();return@post}
                val input=checkNotNull(decoder.getInputBuffer(index));input.clear()
                require(input.remaining()>=frame.bytes.size) {"双眼帧超过硬件输入缓冲区"}
                input.put(frame.bytes);metadata[frame.id]=frame
                decoder.queueInputBuffer(index,0,frame.bytes.size,frame.id,if(frame.keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            }.onFailure {fail(it)}
        }
    }
    private fun fail(value: Throwable) {release();lost();error(value.message ?: "双眼解码失败")}
    private fun release() {ready.set(false);handler.removeCallbacks(drain);val old=codec;codec=null;runCatching {old?.stop()};runCatching {old?.release()};metadata.clear();waitingKeyframe=true}
    fun close() {
        if(closed) return
        surface(null,0,0,"");closed=true;handler.post {release();thread.quitSafely()}
    }
}

package org.openuuyc.pico.uu

import org.json.JSONObject
import org.webrtc.DataChannel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A separate capability-negotiated, output-only player. RTP stays warm and muted
 * only after AAudio is primed. Lifecycle/codec work never runs in its RT callback. */
internal class UuNativeAudio(
    private val eligible: ()->Boolean,
    private val switchOutput: (Boolean,()->Unit)->Unit,
    private val report: (String)->Unit,
    private val pcmAllowed: ()->Boolean={false}
): AutoCloseable {
    companion object {
        const val CHANNEL="OPENUUYC_AUDIO_V1"
        private val library=runCatching {System.loadLibrary("openuuyc_pico")}.isSuccess
    }
    private external fun nativeProbe(): LongArray
    internal fun probeForInstrumentation(): LongArray=worker.submit<LongArray> {nativeProbe()}.get(5,TimeUnit.SECONDS)
    private external fun nativeOpen(epoch: Int): Long
    private external fun nativeClose(handle: Long)
    private external fun nativeFeed(handle: Long,data: ByteArray): Boolean
    private external fun nativeArm(handle: Long,value: Boolean)
    private external fun nativeTune(handle: Long,bursts: Int)
    private external fun nativeStats(handle: Long): LongArray?
    private val worker=ScheduledThreadPoolExecutor(1) {r->Thread({runCatching {android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)};r.run()},"uu-native-audio").apply {isDaemon=true}}
    private val queued=AtomicInteger()
    @Volatile private var closed=false
    @Volatile var active=false;private set
    @Volatile var label="WebRTC · 兼容音频";private set
    @Volatile var diagnostic="nativeAudio(active=false)";private set
    private var channel: DataChannel?=null
    private var supported=false
    private var handle=0L
    private var revision=1L
    private var epoch=0
    private var generation=0
    private var handoff=false
    private var policy=UuNativeAudioPolicy()
    private var retryAt=0L
    private var startedAt=0L
    private var lastPacket=0L
    private var lastStart=0L
    private var lastWindow=0L
    private var previous: LongArray?=null
    private var previousArrival=0L
    private var previousTimestamp: Long?=null
    private var jitter=0.0
    init {worker.scheduleAtFixedRate({runCatching {tick()}.onFailure {fallback("原生音频异常，已恢复 WebRTC")}},20,20,TimeUnit.MILLISECONDS)}
    private fun enqueue(action: ()->Unit) {if(!closed) runCatching {worker.execute {if(!closed) action()}}}
    fun bind(value: DataChannel) {enqueue {stop();channel=value;supported=false;retryAt=0}}
    fun receive(bytes: ByteArray) {
        if(closed || bytes.size>1224) return
        if(bytes.size>=24 && bytes[0]=='O'.code.toByte() && bytes[1]=='U'.code.toByte()) {
            if(queued.incrementAndGet()>8) {queued.decrementAndGet();return}
            val arrival=System.nanoTime()
            val accepted=runCatching {worker.execute {
                try {
                    if(closed || handle==0L || System.nanoTime()-arrival>20_000_000L) return@execute
                    if(nativeFeed(handle,bytes)) {
                        lastPacket=System.nanoTime()/1_000_000L
                        if(active) label=if((bytes[5].toInt() and 1)==0) "AAudio · PCM" else "AAudio · Opus 低延迟"
                        val header=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                        val timestamp=header.getInt(16).toLong() and 0xffffffffL
                        val before=previousTimestamp
                        if(before!=null) {
                            val samples=(timestamp-before) and 0xffffffffL
                            if(samples in 1..4800) {
                                val delta=kotlin.math.abs((arrival-previousArrival)/1_000_000.0-samples/48.0)
                                jitter+=(delta-jitter)/16.0
                            }
                        }
                        previousTimestamp=timestamp;previousArrival=arrival
                    }
                } finally {queued.decrementAndGet()}
            }}.isSuccess
            if(!accepted) queued.decrementAndGet()
        } else enqueue {
            val message=runCatching {JSONObject(String(bytes,Charsets.UTF_8))}.getOrNull() ?: return@enqueue
            if(message.optString("type")=="hello" && message.optInt("v")==1 && message.optInt("rate")==48000 && message.optInt("channels")==2 && message.optInt("frames")==120) {
                val codecs=message.optJSONArray("codecs")
                supported=codecs!=null && (0 until codecs.length()).map {codecs.optString(it)}.containsAll(listOf("pcm_s16le","opus_lowdelay"))
            }
        }
    }
    private fun send(type: String) {
        val c=channel ?: return
        if(c.state()!=DataChannel.State.OPEN || c.bufferedAmount()>2048) return
        val message=JSONObject().put("v",1).put("type",type).put("epoch",epoch).put("codec",policy.codec).put("revision",revision)
        c.send(DataChannel.Buffer(ByteBuffer.wrap(message.toString().toByteArray()),false))
    }
    private fun start(now: Long) {
        if(!library) {retryAt=Long.MAX_VALUE;report("原生音频组件不可用，保留 WebRTC");return}
        epoch=((System.nanoTime() ushr 10) and 0x7fffffff).toInt().coerceAtLeast(1)
        handle=runCatching {nativeOpen(epoch)}.getOrDefault(0L)
        if(handle==0L) {fallback("AAudio 低延迟输出不可用，保留 WebRTC");return}
        generation++;revision=1;policy=UuNativeAudioPolicy();previous=null;previousTimestamp=null;previousArrival=0;jitter=0.0
        startedAt=now;lastPacket=now;lastWindow=now;lastStart=now;send("start")
        report("原生音频：等待短包与输出缓冲准备，WebRTC 保持播放")
    }
    private fun tick() {
        if(closed) return
        val now=System.nanoTime()/1_000_000L
        if(!eligible()) {if(handle!=0L) stop();return}
        if(handle==0L) {if(supported && now>=retryAt && channel?.state()==DataChannel.State.OPEN) start(now);return}
        val s=nativeStats(handle) ?: return fallback("原生音频状态失效，已恢复 WebRTC")
        if(s.size<13 || s[5]!=0L) return fallback("AAudio 设备断开，已恢复 WebRTC")
        if(now-lastPacket>500 || (!active && now-startedAt>2000)) return fallback("原生音频中断，已恢复 WebRTC")
        if(!active && now-lastStart>=300) {lastStart=now;send("start")}
        if(!active && !handoff && s[12]>0 && s[0]>=3 && s[4]>=s[6]*2) {
            handoff=true;val token=generation
            switchOutput(true) {enqueue {
                if(token!=generation) return@enqueue
                if(handle!=0L && eligible()) {
                    nativeArm(handle,true);active=true;label="AAudio · Opus 低延迟";report("原生 AAudio 已接管音频输出")
                } else {handoff=false;switchOutput(false) {}}
            }}
        }
        if(now-lastWindow>=1000) {
            lastWindow=now;val p=previous;previous=s
            fun delta(index: Int)=(s[index]-(p?.get(index) ?: s[index])).coerceAtLeast(0)
            val oldCodec=policy.codec
            if(p!=null && active && !policy.update(now,delta(0),delta(9)+delta(3)/120+delta(10),delta(2),delta(11),jitter,pcmAllowed())) return fallback("原生通路持续欠载，已恢复 WebRTC")
            nativeTune(handle,policy.bursts)
            if(oldCodec!=policy.codec) revision++
            send(if(oldCodec!=policy.codec) "start" else "feedback")

            diagnostic="nativeAudio(active=$active,codec=${policy.codec},packets=${s[0]},played=${s[1]},callbacks=${s[12]},burst=${s[6]},deviceBuffer=${s[7]},queue=${s[4]},underruns=${s[2]},xruns=${s[11]},gaps=${s[9]},decodeUs=${s[8]/1000},jitterMs=${jitter.toInt()})"
            if(oldCodec!=policy.codec) report("音频自适应：${if(policy.codec=="pcm_s16le") "PCM" else "Opus LowDelay"}")
        }
    }
    private fun stop() {
        generation++;send("stop")
        val h=handle;handle=0L
        if(h!=0L) {nativeArm(h,false);nativeClose(h)}
        active=false;handoff=false;label="WebRTC · 兼容音频";switchOutput(false) {}
    }
    private fun fallback(reason: String) {stop();retryAt=System.nanoTime()/1_000_000L+15000;diagnostic="nativeAudio(active=false,fallback=true)";report(reason)}
    fun disconnected() {enqueue {stop();channel=null;supported=false}}
    override fun close() {
        if(closed) return
        closed=true
        runCatching {worker.execute {stop();worker.shutdown()}}
    }
}

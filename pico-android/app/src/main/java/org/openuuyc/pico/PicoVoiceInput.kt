package org.openuuyc.pico

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** User-started speech only; partial transcripts never reach the remote peer. */
internal class PicoVoiceInput(private val activity: Activity,private val send: (String)->Boolean,private val changed: (Boolean,String)->Unit) {
    private var recognizer: SpeechRecognizer?=null
    var listening=false;private set
    private var waiting=false
    private var finishRequested=false
    private var transcript: String?=null
    private var generation=0
    fun start() {
        if(listening || waiting) return
        if(activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO),1743)
            changed(false,"请授权麦克风后再次点击开始");return
        }
        if(!SpeechRecognizer.isRecognitionAvailable(activity)) {changed(false,"头显没有可用的系统语音识别服务；可改用系统键盘");return}
        val epoch=++generation
        runCatching {
            val engine=SpeechRecognizer.createSpeechRecognizer(activity);recognizer=engine
            engine.setRecognitionListener(object: RecognitionListener {
                private fun current()=epoch==generation
                override fun onReadyForSpeech(params: Bundle?) {if(current()) changed(listening,"正在聆听…")}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {if(current()) {waiting=true;changed(listening,"识别处理中 · 松开或再次点击结束")}}
                override fun onError(error: Int) {if(current()) {finish();changed(false,"语音识别失败 ($error)，请重试")}}
                override fun onResults(results: Bundle?) {
                    if(!current()) return
                    val value=results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().take(16384)
                    if(finishRequested) deliver(value) else {
                        transcript=value;waiting=false;recognizer?.destroy();recognizer=null
                        changed(true,"已识别 · 松开或再次点击发送")
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {if(current()) changed(listening,partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().take(100))}
                override fun onEvent(eventType: Int,params: Bundle?) {}
            })
            listening=true;changed(true,"正在聆听 · 松开或再次点击结束")
            engine.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE,java.util.Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true)
            })
            activity.window.decorView.postDelayed({if(epoch==generation) cancel()},60000)
        }.onFailure {cancel();changed(false,"无法启动系统语音识别")}
    }
    fun stop() {
        if(!listening) return
        finishRequested=true;listening=false
        val ready=transcript
        if(ready!=null) {deliver(ready);return}
        waiting=true;changed(false,"正在识别…")
        runCatching {recognizer?.stopListening()}.onFailure {cancel()}
    }
    private fun deliver(value: String) {
        finish();changed(false,if(value.isBlank()) "没有识别到文字" else if(send(value)) "语音文字已发送" else "语音文字未发送，连接可能已结束")
    }
    private fun finish() {generation++;listening=false;waiting=false;finishRequested=false;transcript=null;recognizer?.destroy();recognizer=null}
    fun cancel() {generation++;recognizer?.cancel();finish();changed(false,"语音输入已停止")}
}

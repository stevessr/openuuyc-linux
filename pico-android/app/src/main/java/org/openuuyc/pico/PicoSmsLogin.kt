package org.openuuyc.pico

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.WindowManager
import android.widget.*
import org.openuuyc.pico.uu.*
import java.util.concurrent.ExecutorService

/** Sends a real SMS only on an explicit tap after consent; no phone/OTP logging. */
internal object PicoSmsLogin {
    private val gate=UuSmsGate(android.os.SystemClock::elapsedRealtime)
    fun show(activity: Activity,api: UuNrdApi,worker: ExecutorService,isCurrent: ()->Boolean,done: ()->Unit) {
        val ui=Handler(Looper.getMainLooper())
        val root=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;setPadding(28,16,28,16)}
        fun field(hintText: String,type: Int)=EditText(activity).apply {hint=hintText;inputType=type;isSaveEnabled=false;importantForAutofill=android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS;root.addView(this)}
        val country=field("国家/地区区号",InputType.TYPE_CLASS_PHONE).apply {setText("+86")}
        val mobile=field("手机号",InputType.TYPE_CLASS_PHONE)
        val code=field("6位验证码",InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        val consent=CheckBox(activity).apply {text="我已阅读并同意用户协议和隐私政策";root.addView(this)}
        val links=LinearLayout(activity)
        listOf("用户协议" to "licenseandservice","隐私政策" to "privacy").forEach {(label,path)->links.addView(Button(activity).apply {text=label;setOnClickListener {runCatching {activity.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://adl.netease.com/d/g/uuremote/c/$path?type=pc&direct=1")))}}})}
        root.addView(links)
        val status=TextView(activity).apply {setTextColor(PicoTheme.TEXT);text="验证码仅用于 UU 官方登录，不会保存到本机";root.addView(this)}
        val send=Button(activity).apply {text="获取验证码";root.addView(this)}
        val submit=Button(activity).apply {text="登录";root.addView(this)}
        val dialog=AlertDialog.Builder(activity).setTitle("UU 官方手机号登录").setView(root).setNegativeButton("取消",null).create()
        val active=java.util.concurrent.atomic.AtomicBoolean(true)
        var busy=false
        fun failure(error: Exception) {status.text=when(error){is UuNrdResponseException->"登录接口：HTTP ${error.httpStatus} / code ${error.businessCode ?: "未知"}（若需安全验证，请使用官方客户端完成或改用扫码）";is IllegalArgumentException,is IllegalStateException->error.message ?: "参数无效";else->"网络请求未完成，请稍后重试"}}
        fun valid()=active.get() && !activity.isDestroyed && isCurrent()
        val timer=object: Runnable {override fun run(){if(!dialog.isShowing)return;val seconds=gate.remainingSeconds();send.text=if(seconds>0) "${seconds}秒后重发" else "获取验证码";send.isEnabled=!busy && seconds==0L;submit.isEnabled=!busy;ui.postDelayed(this,500)}}
        send.setOnClickListener {
            if(busy) return@setOnClickListener
            try {
                val phone=gate.phone(country.text.toString(),mobile.text.toString());gate.begin(phone,consent.isChecked)
                busy=true;status.text="正在请求验证码…"
                worker.submit {try {api.requestSms(phone.first,phone.second);ui.post {if(valid()){gate.dispatched(phone);status.text="验证码已发送，请在手机查看";busy=false}}}catch(e: Exception){ui.post {if(valid()){failure(e);busy=false}}}}
            } catch(e: Exception){failure(e)}
        }
        submit.setOnClickListener {
            if(busy) return@setOnClickListener
            try {
                val phone=gate.phone(country.text.toString(),mobile.text.toString());val otp=code.text.toString().trim();gate.validate(phone,otp,consent.isChecked)
                busy=true;status.text="正在验证并加密保存登录…"
                worker.submit {try {val account=api.loginSms(phone.first,phone.second,otp);if(valid() && api.acceptLogin(account) {valid()}) ui.post {if(valid()){code.text.clear();dialog.dismiss();done()}}}catch(e: Exception){ui.post {if(valid()){failure(e);busy=false}}}}
            }catch(e: Exception){failure(e)}
        }
        dialog.setOnDismissListener {active.set(false);ui.removeCallbacks(timer);gate.cancel();mobile.text.clear();code.text.clear()}
        dialog.show();dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE);ui.post(timer)
    }
}

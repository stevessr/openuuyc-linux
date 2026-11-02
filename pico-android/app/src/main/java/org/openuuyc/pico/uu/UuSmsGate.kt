package org.openuuyc.pico.uu

/** Process-local only: never serialize phone numbers or codes. */
internal class UuSmsGate(private val now: ()->Long) {
    private var deadline=0L
    private var bound: Pair<String,String>?=null
    @Synchronized fun remainingSeconds()=((deadline-now()).coerceAtLeast(0)+999)/1000
    fun phone(country: String,mobile: String): Pair<String,String> {
        val c=country.trim().removePrefix("+");val p=mobile.trim()
        require(c.length in 1..4 && c.all {it in '0'..'9'}) {"请输入有效区号"}
        require(p.length in 5..20 && p.all {it in '0'..'9'} && (c!="86" || p.length==11)) {"请输入有效手机号"}
        return c to p
    }
    @Synchronized fun begin(phone: Pair<String,String>,agreed: Boolean) {
        require(agreed) {"请先同意用户协议和隐私政策"}
        check(now()>=deadline) {"请等待验证码倒计时结束"}
        deadline=now()+60000;bound=null
    }
    @Synchronized fun dispatched(phone: Pair<String,String>) {bound=phone}
    @Synchronized fun validate(phone: Pair<String,String>,code: String,agreed: Boolean) {
        require(agreed) {"请先同意用户协议和隐私政策"}
        require(code.length==6 && code.all {it in '0'..'9'}) {"请输入6位短信验证码"}
        check(bound==phone) {"请先为当前手机号获取验证码"}
    }
    @Synchronized fun cancel() {bound=null}
}

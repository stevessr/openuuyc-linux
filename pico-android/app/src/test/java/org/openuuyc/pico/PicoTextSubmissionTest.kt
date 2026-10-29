package org.openuuyc.pico
import org.junit.Assert.*
import org.junit.Test
class PicoTextSubmissionTest {
    @Test fun preservesMultilingualUnicodeAndNewlinesExactly() {
        for(value in listOf("中文输入", "かな カナ 日本語", "한국어", "العربية", "हिन्दी", "é e\u0301 👩🏽‍💻\n下一行")) {
            val output=mutableListOf<String>();val s=PicoTextSubmission {output.add(it);true}
            assertTrue(s.submit(value));assertEquals(listOf(value),output)
        }
    }
    @Test fun duplicateImeActionCannotSendTwice() {
        val output=mutableListOf<String>();val s=PicoTextSubmission {output.add(it);true}
        s.submit("中文");s.submit("中文");assertEquals(listOf("中文"),output)
    }
    @Test fun failureKeepsDraftRetryable() {
        var ready=false;var count=0;val s=PicoTextSubmission {count++;ready}
        assertFalse(s.submit("文字"));ready=true;assertTrue(s.submit("文字"));assertEquals(2,count)
    }
    @Test fun cancelNeverSendsDraftAndOversizeIsRejected() {
        var count=0;val s=PicoTextSubmission {count++;true};s.cancel();s.submit("取消草稿");assertEquals(0,count)
        val t=PicoTextSubmission {count++;true};assertFalse(t.submit("中".repeat(30000)));assertEquals(0,count)
    }
}

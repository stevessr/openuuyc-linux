package org.openuuyc.pico.uu

import org.junit.Assert.*
import org.junit.Test
import org.openuuyc.pico.uu.UuWire.data
import org.openuuyc.pico.uu.UuWire.number
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.io.ByteArrayOutputStream

class UuFileTransfersTest {
    private fun fields(p: UuTransferWire.Packet,block: (ByteArrayOutputStream)->Unit={})=UuWire.message {UuWire.bytes(it,1,UuTransferWire.task(p.task,p.index));block(it)}
    @Test fun uploadWaitsForEveryBlockAndSaveAck() {
        val content=ByteArray(140000) {(it%251).toByte()};val saved=ByteArrayOutputStream();val seq=AtomicLong(10)
        lateinit var transfers: UuFileTransfers
        transfers=UuFileTransfers({seq.incrementAndGet()},{bytes,_->
            val p=UuTransferWire.decode(bytes)!!
            val response=when(p.kind) {
                1->1 to fields(p) {UuWire.integer(it,3,1)}
                3->3 to fields(p) {UuWire.integer(it,3,1)}
                4->{val data=p.body.data(3)!!;saved.write(data);4 to fields(p) {UuWire.integer(it,2,p.body.number(2));UuWire.integer(it,3,1);UuWire.integer(it,4,data.size.toLong())}}
                9->if(p.index==1) 6 to fields(p) {UuWire.integer(it,2,1)} else null
                else->null
            }
            response?.let {transfers.receive(UuTransferWire.packet(p.header,true,it.first,it.second))};true
        })
        val done=CountDownLatch(1);var error: String?="not finished"
        transfers.upload("/tmp","测试.bin",content.size.toLong(),{content.inputStream()},{_,_->},{error=it;done.countDown()})
        assertTrue(done.await(5,TimeUnit.SECONDS));assertNull(error);assertArrayEquals(content,saved.toByteArray())
    }
    @Test fun downloadChecksLengthAndAcknowledgesCompletion() {
        val content="多语言文件内容".toByteArray();val saved=ByteArrayOutputStream();val seq=AtomicLong(20)
        lateinit var transfers: UuFileTransfers
        transfers=UuFileTransfers({seq.incrementAndGet()},{bytes,_->
            val p=UuTransferWire.decode(bytes)!!
            if(!p.response && p.kind==2) {
                transfers.receive(UuTransferWire.packet(p.header,true,2,fields(p) {UuWire.integer(it,2,1);UuWire.bytes(it,3,UuTransferWire.file("hello.txt",content.size.toLong()))}))
                fun request(kind: Int,index: Int,block: (ByteArrayOutputStream)->Unit) {
                    transfers.receive(UuTransferWire.packet(900+kind.toLong(),false,kind,UuWire.message {UuWire.bytes(it,1,UuTransferWire.task(p.task,index));block(it)}))
                }
                request(3,1) {UuWire.integer(it,3,content.size.toLong())}
                request(4,1) {UuWire.integer(it,2,1);UuWire.bytes(it,3,content)}
                request(9,1) {UuWire.integer(it,2,1)}
                request(9,-1) {UuWire.integer(it,2,1)}
            };true
        })
        val done=CountDownLatch(1);var error: String?="not finished"
        transfers.download("/remote/hello.txt",content.size.toLong(),{saved},{_,_->},{error=it;done.countDown()})
        assertTrue(done.await(5,TimeUnit.SECONDS));assertNull(error);assertArrayEquals(content,saved.toByteArray())
    }
    @Test fun cancellationTerminatesAnUnresponsivePeer() {
        val seq=AtomicLong(30);val sent=CountDownLatch(1)
        val transfers=UuFileTransfers({seq.incrementAndGet()},{_,_->sent.countDown();true})
        val done=CountDownLatch(1);var error: String?=null
        transfers.upload("/tmp","empty",0,{byteArrayOf().inputStream()},{_,_->},{error=it;done.countDown()})
        assertTrue(sent.await(2,TimeUnit.SECONDS));transfers.cancel()
        assertTrue(done.await(2,TimeUnit.SECONDS));assertNotNull(error)
    }
    @Test fun rejectsRemotePathTraversalNames() {
        listOf("../bad","a/b","a\\b","..","").forEach {assertTrue(runCatching {UuTransferWire.safeName(it)}.isFailure)}
    }
}

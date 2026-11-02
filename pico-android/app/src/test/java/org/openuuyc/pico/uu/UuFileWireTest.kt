package org.openuuyc.pico.uu

import org.junit.Assert.*
import org.junit.Test
import org.openuuyc.pico.uu.UuWire.data
import org.openuuyc.pico.uu.UuWire.number
import java.util.zip.DeflaterOutputStream
import java.io.ByteArrayOutputStream

class UuFileWireTest {
    @Test fun readDirectoryUsesOfficialEnvelope() {
        val packet=UuWire.fields(UuFileWire.request(71,10,":/"))
        val rpc=UuWire.fields(packet.data(21)!!)
        assertEquals(71L,UuWire.fields(rpc.data(1)!!).number(1))
        val read=UuWire.fields(UuWire.fields(rpc.data(8)!!).data(10)!!)
        assertEquals(":/",read.data(2)!!.toString(Charsets.UTF_8))
    }
    private fun response(compressed: Boolean): ByteArray {
        val entry=UuWire.message {UuWire.bytes(it,2,"文档".toByteArray());UuWire.bytes(it,5,"/home/test/文档".toByteArray())}
        return UuWire.message {out->UuWire.bytes(out,22,UuWire.message {rpc->
            UuWire.bytes(rpc,1,UuWire.message {UuWire.integer(it,1,71)})
            UuWire.bytes(rpc,4,UuWire.message {ftp->UuWire.bytes(ftp,7,UuWire.message {dir->
                UuWire.integer(dir,4,1)
                if(compressed) {
                    val packed=ByteArrayOutputStream();DeflaterOutputStream(packed).use {it.write(UuWire.message {UuWire.bytes(it,1,entry)})}
                    UuWire.bytes(dir,5,packed.toByteArray())
                } else UuWire.bytes(dir,3,entry)
            })})
        })}
    }
    @Test fun compressedAndPlainDirectoryMatch() {
        val plain=UuFileWire.response(response(false))!!
        assertEquals(plain,UuFileWire.response(response(true)))
        assertEquals("文档",plain.entries.single().name)
        assertTrue(plain.entries.single().directory)
    }
    @Test fun renameCannotEscapeParent() {
        for(name in listOf("../bad","a\\b","..","")) assertTrue(runCatching {UuFileWire.request(1,5,"/tmp/file",name)}.isFailure)
    }
    @Test fun nonFileReplyIsNotConsumed() {
        assertNull(UuFileWire.response(UuWire.message {UuWire.integer(it,1,7)}))
    }
}

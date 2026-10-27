package org.openuuyc.pico.uu

import java.io.ByteArrayOutputStream
import java.util.UUID

/** Text-only subset of src/features/clipboard/{mod,protocol}.rs. Never file/HTML data. */
internal class UuClipboardWire(private val next: ()->Long,private val send: (ByteArray)->Boolean,private val write: (String,(Boolean)->Unit)->Unit,private val rejected: ()->Unit={}) {
    companion object {const val MAX_BYTES=32768}
    private var key: String?=null
    private var count=0
    private var index=1
    private var data=ByteArrayOutputStream()
    fun reset() {key=null;count=0;index=1;data.reset()}
    fun text(value: String): Boolean {
        val bytes=value.toByteArray(Charsets.UTF_8)
        if(bytes.isEmpty() || bytes.size>MAX_BYTES || '\u0000' in value) return false
        return send(UuWire.rpc(next(),10,UuWire.message {UuWire.integer(it,1,13);UuWire.bytes(it,2,bytes)}))
    }
    private fun response(id: Long,field: Int,payload: ByteArray)=UuWire.message {out ->
        UuWire.bytes(out,22,UuWire.message {r ->
            UuWire.bytes(r,1,UuWire.message {UuWire.integer(it,1,id)})
            UuWire.bytes(r,field,payload)
        })
    }
    private fun clipReply(id: Long,type: Int,payload: ByteArray)=send(response(id,5,UuWire.message {UuWire.bytes(it,type,payload)}))
    private fun blockReply(id: Long,k: String,n: Int,ok: Boolean)=clipReply(id,3,UuWire.message {out ->
        UuWire.bytes(out,1,k.toByteArray());UuWire.integer(out,2,n.toLong());UuWire.integer(out,3,if(ok) 1 else 2)
    })
    fun receive(bytes: ByteArray,allowed: Boolean): Boolean {
        val root=UuWire.fields(bytes)
        val request=root[21]?.firstOrNull()?.data?.let(UuWire::fields)
        val responseFields=root[22]?.firstOrNull()?.data?.let(UuWire::fields)
        fun Map<Int,List<UuWire.Field>>.raw(n: Int)=get(n)?.firstOrNull()?.data
        fun Map<Int,List<UuWire.Field>>.num(n: Int)=get(n)?.firstOrNull()?.number ?: 0L
        if(request!=null) {
            val id=request.raw(1)?.let(UuWire::fields)?.num(1) ?: 0L
            request.raw(10)?.let {raw ->
                val text=UuWire.fields(raw).raw(2)
                if(!allowed || text==null || text.size>MAX_BYTES) {send(response(id,6,UuWire.message {UuWire.integer(it,1,2)}));return true}
                val value=text.toString(Charsets.UTF_8)
                if(value.isEmpty() || '\u0000' in value) {send(response(id,6,UuWire.message {UuWire.integer(it,1,2)}));return true}
                write(value) {ok -> send(response(id,6,UuWire.message {UuWire.integer(it,1,if(ok) 1 else 2)}))}
                return true
            }
            val clip=request.raw(9)?.let(UuWire::fields) ?: return false
            clip.raw(1)?.let {raw ->
                reset();clipReply(id,1,byteArrayOf())
                if(!allowed) return true
                val list=UuWire.fields(raw)
                if(list.num(2)!=0L) return true // never accept drag/drop/file operations
                val unicode=list[1].orEmpty().any {it.data?.let(UuWire::fields)?.num(1)==13L}
                if(unicode) {
                    key=UUID.randomUUID().toString()
                    send(UuWire.rpc(next(),9,UuWire.message {out -> UuWire.bytes(out,2,UuWire.message {ask ->
                        UuWire.integer(ask,1,13);UuWire.bytes(ask,2,key!!.toByteArray())
                    })}))
                }
                return true
            }
            clip.raw(3)?.let {raw ->
                val block=UuWire.fields(raw);val k=block.raw(1)?.toString(Charsets.UTF_8).orEmpty();val n=block.num(2).toInt();val chunk=block.raw(3) ?: byteArrayOf()
                if(!allowed || k!=key || count !in 1..2 || n!=index || data.size()+chunk.size>MAX_BYTES) {blockReply(id,k,n,false);reset();return true}
                data.write(chunk);index++
                if(n==count) {
                    val all=data.toByteArray();reset()
                    if(all.size%2!=0) {blockReply(id,k,n,false);return true}
                    val text=all.toString(Charsets.UTF_16LE).trimEnd('\u0000')
                    if(text.isEmpty() || '\u0000' in text) {blockReply(id,k,n,false);return true}
                    write(text) {ok -> blockReply(id,k,n,ok)}
                } else blockReply(id,k,n,true)
                return true
            }
            return true // unsupported clipboard formats are never imported
        }
        val clip=responseFields?.raw(5)?.let(UuWire::fields)
        clip?.raw(2)?.let {raw ->
            val confirm=UuWire.fields(raw)
            if(!allowed || confirm.raw(2)?.toString(Charsets.UTF_8)!=key || confirm.num(1)!=1L || confirm.num(3) !in 1L..2L) reset()
            else count=confirm.num(3).toInt()
            return true
        }
        responseFields?.raw(6)?.let {if(UuWire.fields(it).num(1)!=1L) rejected();return true}
        return clip!=null
    }
}

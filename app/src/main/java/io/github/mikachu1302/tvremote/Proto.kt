package io.github.mikachu1302.tvremote

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Just enough protobuf for the Android TV Remote v2 protocol: varints and
 * length-delimited fields, nothing else. Messages on the wire are prefixed with
 * their length as a varint.
 */
class PbWriter {
    private val out = ByteArrayOutputStream()

    private fun rawVarint(value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    fun varint(field: Int, value: Long): PbWriter = apply {
        rawVarint((field shl 3).toLong())
        rawVarint(value)
    }

    fun bytes(field: Int, value: ByteArray): PbWriter = apply {
        rawVarint(((field shl 3) or 2).toLong())
        rawVarint(value.size.toLong())
        out.write(value)
    }

    fun string(field: Int, value: String): PbWriter = bytes(field, value.toByteArray())

    fun message(field: Int, build: PbWriter.() -> Unit): PbWriter =
        bytes(field, PbWriter().apply(build).toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()
}

/** A parsed message: every field number maps to the values seen for it. */
class PbMessage(private val fields: Map<Int, List<Any>>) {
    fun has(field: Int) = fields.containsKey(field)
    fun long(field: Int, default: Long = 0): Long = (fields[field]?.firstOrNull() as? Long) ?: default
    fun message(field: Int): PbMessage? = (fields[field]?.firstOrNull() as? ByteArray)?.let(::parsePb)
}

fun parsePb(data: ByteArray): PbMessage {
    val fields = mutableMapOf<Int, MutableList<Any>>()
    var pos = 0

    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            require(pos < data.size) { "truncated varint" }
            val b = data[pos++].toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            require(shift < 64) { "varint too long" }
        }
    }

    while (pos < data.size) {
        val tag = varint()
        val field = (tag ushr 3).toInt()
        val value: Any = when ((tag and 7).toInt()) {
            0 -> varint()
            1 -> { require(pos + 8 <= data.size); pos += 8; continue }
            2 -> {
                val len = varint().toInt()
                require(len >= 0 && pos + len <= data.size) { "bad length" }
                data.copyOfRange(pos, pos + len).also { pos += len }
            }
            5 -> { require(pos + 4 <= data.size); pos += 4; continue }
            else -> throw IllegalArgumentException("unsupported wire type")
        }
        fields.getOrPut(field) { mutableListOf() }.add(value)
    }
    return PbMessage(fields)
}

/** Writes one length-prefixed message. */
fun OutputStream.writeFrame(payload: ByteArray) {
    // The length prefix is a bare varint (no field tag).
    var v = payload.size
    val lenBytes = ByteArrayOutputStream()
    while (v and 0x7F.inv() != 0) {
        lenBytes.write((v and 0x7F) or 0x80)
        v = v ushr 7
    }
    lenBytes.write(v)
    write(lenBytes.toByteArray())
    write(payload)
    flush()
}

/** Reads one length-prefixed message; throws EOFException when the peer closes. */
fun InputStream.readFrame(): ByteArray {
    var len = 0
    var shift = 0
    while (true) {
        val b = read()
        if (b < 0) throw EOFException()
        len = len or ((b and 0x7F) shl shift)
        if (b and 0x80 == 0) break
        shift += 7
        if (shift > 28) throw java.io.IOException("bad frame length")
    }
    if (len < 0 || len > 1 shl 20) throw java.io.IOException("frame too large")
    val buf = ByteArray(len)
    var off = 0
    while (off < len) {
        val n = read(buf, off, len - off)
        if (n < 0) throw EOFException()
        off += n
    }
    return buf
}

package io.github.mikachu1302.tvremote

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/*
 * The TV's own volume, for TVs that run a UPnP/DLNA media renderer (Hisense VIDAA does): the standard
 * RenderingControl service reads and sets volume and mute with plain HTTP + SOAP, no pairing and no
 * certificate. The stick cannot do this: its volume keys go to the TV over HDMI-CEC and nothing comes back.
 * No UI types here, like the other protocol files.
 */

/** A TV's renderer: where its RenderingControl listens ([control] is the path of its control URL). */
data class AudioTv(val name: String, val host: String, val port: Int, val control: String)

data class TvAudio(val level: Int, val muted: Boolean)

object Upnp {
    const val RENDERING = "urn:schemas-upnp-org:service:RenderingControl:1"

    /** A SOAP request body for [action] on instance 0 of the RenderingControl service. */
    fun envelope(action: String, args: String = ""): String =
        """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" """ +
            """s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>""" +
            """<u:$action xmlns:u="$RENDERING"><InstanceID>0</InstanceID>$args</u:$action></s:Body></s:Envelope>"""

    /** The text of the first `<name>…</name>` element, or null. */
    fun tag(xml: String, name: String): String? =
        Regex("<$name(?:\\s[^>]*)?>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim()
            ?.replace("&lt;", "<")?.replace("&gt;", ">")?.replace("&quot;", "\"")?.replace("&apos;", "'")?.replace("&amp;", "&")

    /** The LOCATION header of an SSDP answer. */
    fun location(ssdp: String): String? =
        ssdp.lineSequence().firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }?.substringAfter(':')?.trim()

    /** Friendly name and RenderingControl control path from a device description, or null without that service. */
    fun parseDescription(xml: String): Pair<String, String>? {
        val service = Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL).findAll(xml)
            .map { it.groupValues[1] }.firstOrNull { tag(it, "serviceType") == RENDERING } ?: return null
        val control = tag(service, "controlURL") ?: return null
        return (tag(xml, "friendlyName") ?: "TV") to control
    }
}

/** What an HTTP answer said: the status code and the body. */
data class HttpAnswer(val status: Int, val body: String)

/** Reads one HTTP/1.1 response (Content-Length, chunked, or until the server closes). */
fun readHttp(input: InputStream): HttpAnswer {
    val head = ByteArrayOutputStream()
    var last4 = 0 // the last four header bytes, to spot the blank line
    while (last4 != 0x0D0A0D0A) {
        val b = input.read()
        if (b < 0) break
        head.write(b)
        last4 = (last4 shl 8) or b
        if (head.size() > 16_384) throw IOException("HTTP header too long")
    }
    val lines = String(head.toByteArray(), Charsets.ISO_8859_1).split("\r\n")
    val status = lines.first().split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("Not an HTTP answer")
    fun header(name: String) = lines.firstOrNull { it.startsWith("$name:", ignoreCase = true) }?.substringAfter(':')?.trim()
    val body = when {
        header("Transfer-Encoding")?.contains("chunked", ignoreCase = true) == true -> {
            val out = ByteArrayOutputStream()
            while (true) {
                val size = readLine(input).substringBefore(';').trim().toIntOrNull(16) ?: throw IOException("Bad chunk")
                if (size == 0) break
                out.write(readExactly(input, size))
                readLine(input) // the CRLF after the chunk
            }
            out.toByteArray()
        }
        header("Content-Length") != null -> readExactly(input, header("Content-Length")!!.toInt())
        else -> input.readBytes()
    }
    return HttpAnswer(status, String(body, Charsets.UTF_8))
}

/** [count] bytes, or fewer when the stream ends first (InputStream.readNBytes needs API 33). */
private fun readExactly(input: InputStream, count: Int): ByteArray {
    val buffer = ByteArray(count)
    var read = 0
    while (read < count) {
        val n = input.read(buffer, read, count - read)
        if (n < 0) break
        read += n
    }
    return buffer.copyOf(read)
}

private fun readLine(input: InputStream): String {
    val sb = StringBuilder()
    while (true) {
        val b = input.read()
        if (b < 0 || b == '\n'.code) break
        if (b != '\r'.code) sb.append(b.toChar())
    }
    return sb.toString()
}

/**
 * One HTTP request over a plain socket. Not HttpURLConnection on purpose: Android blocks cleartext
 * HTTP there unless the whole app allows it, and the TV only speaks HTTP.
 */
fun httpRequest(host: String, port: Int, request: String, timeoutMs: Int = 3_000): HttpAnswer =
    Socket().use { socket ->
        socket.connect(InetSocketAddress(host, port), timeoutMs)
        socket.soTimeout = timeoutMs
        socket.getOutputStream().apply { write(request.toByteArray(Charsets.UTF_8)); flush() }
        readHttp(socket.getInputStream())
    }

/** Talks to the TV's RenderingControl. Each call opens its own connection; they are small and rare. */
class AudioClient(private val tv: AudioTv) {
    private fun call(action: String, args: String): String {
        val body = Upnp.envelope(action, args)
        val request = "POST ${tv.control} HTTP/1.1\r\nHOST: ${tv.host}:${tv.port}\r\n" +
            "Content-Type: text/xml; charset=\"utf-8\"\r\nContent-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n" +
            "SOAPACTION: \"${Upnp.RENDERING}#$action\"\r\nConnection: close\r\n\r\n$body"
        val answer = httpRequest(tv.host, tv.port, request)
        if (answer.status !in 200..299) throw IOException("The TV answered ${answer.status}")
        return answer.body
    }

    private val master = "<Channel>Master</Channel>"

    fun read(): TvAudio {
        val volume = Upnp.tag(call("GetVolume", master), "CurrentVolume")?.toIntOrNull() ?: throw IOException("No volume in the answer")
        val mute = Upnp.tag(call("GetMute", master), "CurrentMute")
        return TvAudio(volume, mute == "1" || mute.equals("true", ignoreCase = true))
    }

    fun setVolume(level: Int) { call("SetVolume", "$master<DesiredVolume>${level.coerceIn(0, 100)}</DesiredVolume>") }

    fun setMute(on: Boolean) { call("SetMute", "$master<DesiredMute>${if (on) 1 else 0}</DesiredMute>") }
}

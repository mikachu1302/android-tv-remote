package io.github.mikachu1302.tvremote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// All names, addresses and ids below are made up.
class TvAudioTest {
    private val description = """
        <?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0"><device>
        <friendlyName>Test &amp; Lounge</friendlyName>
        <serviceList>
          <service><serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType><controlURL>/ctl/cm</controlURL></service>
          <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>/ctl/rc</controlURL></service>
        </serviceList></device></root>
    """.trimIndent()

    @Test
    fun descriptionGivesNameAndRenderingControlPath() {
        assertEquals("Test & Lounge" to "/ctl/rc", Upnp.parseDescription(description))
        assertNull(Upnp.parseDescription("<root><device><friendlyName>Lamp</friendlyName></device></root>"))
    }

    @Test
    fun ssdpAnswerGivesLocation() {
        val answer = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLocation: http://192.0.2.7:38400/desc.xml\r\nST: x\r\n\r\n"
        assertEquals("http://192.0.2.7:38400/desc.xml", Upnp.location(answer))
        assertNull(Upnp.location("HTTP/1.1 200 OK\r\n\r\n"))
    }

    @Test
    fun envelopeNamesActionAndInstance() {
        val body = Upnp.envelope("SetVolume", "<Channel>Master</Channel><DesiredVolume>30</DesiredVolume>")
        assertTrue("<u:SetVolume xmlns:u=\"${Upnp.RENDERING}\">" in body)
        assertTrue("<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>30</DesiredVolume>" in body)
        assertEquals("30", Upnp.tag(body, "DesiredVolume"))
    }

    private fun http(text: String) = readHttp(text.toByteArray().inputStream())

    @Test
    fun readsContentLengthChunkedAndCloseDelimitedAnswers() {
        assertEquals(HttpAnswer(200, "hello"), http("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello"))
        assertEquals(HttpAnswer(200, "hello world"), http("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n"))
        assertEquals(HttpAnswer(500, "oops"), http("HTTP/1.1 500 Error\r\nConnection: close\r\n\r\noops"))
    }

    @Test
    fun macAddressesParseInCommonSpellings() {
        val expected = byteArrayOf(0x02, 0x1A, 0x2B, 0x3C, 0x4D, 0x5E)
        assertArrayEquals(expected, Wol.parseMac("02:1a:2b:3c:4d:5e"))
        assertArrayEquals(expected, Wol.parseMac("02-1A-2B-3C-4D-5E"))
        assertArrayEquals(expected, Wol.parseMac("021a2b3c4d5e"))
        assertNull(Wol.parseMac("02:1a:2b:3c:4d"))
        assertNull(Wol.parseMac("02:1a:2b:3c:4d:5g"))
        assertNull(Wol.parseMac(""))
    }

    @Test
    fun magicPacketIsSixFFsAndSixteenCopies() {
        val mac = byteArrayOf(1, 2, 3, 4, 5, 6)
        val packet = Wol.packet(mac)
        assertEquals(102, packet.size)
        assertTrue(packet.take(6).all { it == 0xFF.toByte() })
        repeat(16) { assertArrayEquals(mac, packet.copyOfRange(6 + it * 6, 12 + it * 6)) }
    }

    @Test
    fun savedTvKeepsItsWakeAddress() {
        val tv = AudioTv("Test", "192.0.2.7", 38400, "/ctl/rc", mac = "02:1a:2b:3c:4d:5e")
        assertEquals(tv, parseAudioTv(audioTvToJson(tv)))
        val without = tv.copy(mac = null)
        assertEquals(without, parseAudioTv(audioTvToJson(without)))
    }
}

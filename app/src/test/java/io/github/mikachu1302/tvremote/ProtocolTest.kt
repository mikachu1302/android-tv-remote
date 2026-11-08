package io.github.mikachu1302.tvremote

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.math.BigInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// All values here are made up; nothing comes from a real device.
class ProtocolTest {
    @Test
    fun messageRoundTripsIncludingNestedAndLargeValues() {
        val bytes = PbWriter()
            .varint(1, 2)
            .varint(2, 200)
            .message(10) { string(1, "atvremote"); varint(2, 1L shl 40) }
            .toByteArray()
        val msg = parsePb(bytes)
        assertEquals(2, msg.long(1))
        assertEquals(200, msg.long(2))
        assertEquals(1L shl 40, msg.message(10)!!.long(2))
        assertTrue(msg.has(10))
        assertFalse(msg.has(11))
        assertNull(msg.message(11))
    }

    @Test
    fun keyInjectEncodesLikeTheTvExpects() {
        // RemoteMessage { remote_key_inject { key_code = 19 (DPAD_UP), direction = 3 (SHORT) } }
        val bytes = PbWriter().message(10) { varint(1, 19); varint(2, 3) }.toByteArray()
        assertArrayEquals(byteArrayOf(0x52, 0x04, 0x08, 0x13, 0x10, 0x03), bytes)
    }

    @Test
    fun framesAreLengthPrefixedAndReadBackInOrder() {
        val big = ByteArray(300) { it.toByte() } // length needs a two-byte varint
        val out = ByteArrayOutputStream()
        out.writeFrame(byteArrayOf(1, 2, 3))
        out.writeFrame(big)
        val input = ByteArrayInputStream(out.toByteArray())
        assertArrayEquals(byteArrayOf(1, 2, 3), input.readFrame())
        assertArrayEquals(big, input.readFrame())
        try {
            input.readFrame()
            throw AssertionError("expected EOF")
        } catch (_: EOFException) {
        }
    }

    @Test
    fun parserRejectsTruncatedInput() {
        try {
            parsePb(byteArrayOf(0x0A, 0x05, 0x01)) // claims 5 bytes, has 1
            throw AssertionError("expected failure")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun pairingSecretMatchesAnIndependentImplementation() {
        // Expected value computed with a separate Python script from the same made-up numbers.
        val client = BigInteger("c0ffee".repeat(40), 16)
        val server = BigInteger("8badf00d".repeat(40), 16)
        val exponent = BigInteger.valueOf(65537)
        val secret = pairingSecret(client, exponent, server, exponent, byteArrayOf(0x1A, 0x2B, 0x3C))
        assertEquals(
            "267d45d1e9f24b7a2a525f7a7580d9542c39267f192bf976e328375ec747d662",
            secret.joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun pairingSecretIgnoresBigIntegerSignByte() {
        // A modulus whose top bit is set gets a leading 0x00 from BigInteger.toByteArray().
        val withHighBit = BigInteger("ff".repeat(8), 16)
        val a = pairingSecret(withHighBit, BigInteger.valueOf(3), withHighBit, BigInteger.valueOf(3), byteArrayOf(0, 9))
        val b = MessageDigestHelper.sha256(
            ByteArray(8) { 0xFF.toByte() } + byteArrayOf(3) + ByteArray(8) { 0xFF.toByte() } + byteArrayOf(3) + byteArrayOf(9),
        )
        assertArrayEquals(b, a)
    }
}

private object MessageDigestHelper {
    fun sha256(bytes: ByteArray): ByteArray = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
}

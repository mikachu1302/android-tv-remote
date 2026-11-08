package io.github.mikachu1302.tvremote

import java.io.IOException
import java.math.BigInteger
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket

private const val PAIRING_PORT = 6467
private const val STATUS_OK = 200L
private const val ENCODING_HEXADECIMAL = 3L
private const val ROLE_INPUT = 1L
private const val CODE_LENGTH = 6L

// OuterMessage field numbers (polo.proto).
private const val F_PAIRING_REQUEST = 10
private const val F_PAIRING_REQUEST_ACK = 11
private const val F_OPTIONS = 20
private const val F_CONFIGURATION = 30
private const val F_CONFIGURATION_ACK = 31
private const val F_SECRET = 40
private const val F_SECRET_ACK = 41

private fun outer(build: PbWriter.() -> Unit) = PbWriter()
    .varint(1, 2) // protocol_version
    .varint(2, STATUS_OK)
    .apply(build)
    .toByteArray()

private fun PbWriter.encoding(field: Int) = message(field) {
    varint(1, ENCODING_HEXADECIMAL)
    varint(2, CODE_LENGTH)
}

private fun BigInteger.unsigned(): ByteArray = toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }

/**
 * The pairing secret: SHA-256 over both public keys (unsigned big-endian) and the
 * code without its first byte, which is a checksum of the result.
 */
internal fun pairingSecret(
    clientModulus: BigInteger, clientExponent: BigInteger,
    serverModulus: BigInteger, serverExponent: BigInteger,
    codeBytes: ByteArray,
): ByteArray = MessageDigest.getInstance("SHA-256").run {
    update(clientModulus.unsigned())
    update(clientExponent.unsigned())
    update(serverModulus.unsigned())
    update(serverExponent.unsigned())
    update(codeBytes.copyOfRange(1, codeBytes.size))
    digest()
}

/**
 * One pairing session. [start] makes the TV show a 6-digit code; [finish]
 * takes that code and completes the pairing. On success [serverPin] holds the
 * fingerprint of the TV's certificate, to be pinned for later connections.
 */
class Pairing(private val host: String) {
    private var socket: SSLSocket? = null
    var serverPin: String? = null
        private set

    private fun next(vararg expect: Int): PbMessage {
        val msg = parsePb(socket!!.getInputStream().readFrame())
        if (msg.long(2) != STATUS_OK) throw IOException("TV refused pairing (status ${msg.long(2)})")
        if (expect.none(msg::has)) throw IOException("unexpected message from TV")
        return msg
    }

    private fun send(payload: ByteArray) = socket!!.getOutputStream().writeFrame(payload)

    fun start() {
        val s = openTls(host, PAIRING_PORT, pin = null, timeoutMs = 10_000).also { socket = it }
        try {
            send(outer { message(F_PAIRING_REQUEST) { string(1, "atvremote"); string(2, "TV Remote") } })
            next(F_PAIRING_REQUEST_ACK)
            send(outer { message(F_OPTIONS) { encoding(1); varint(3, ROLE_INPUT) } })
            next(F_OPTIONS)
            send(outer { message(F_CONFIGURATION) { encoding(1); varint(2, ROLE_INPUT) } })
            next(F_CONFIGURATION_ACK)
            // The TV now shows the code. The socket stays open until finish().
            s.soTimeout = 0
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    /** @return false when the code is wrong; throws on connection problems. */
    fun finish(code: String): Boolean {
        val s = socket ?: throw IOException("pairing not started")
        val clean = code.trim()
        if (clean.length != 6 || !clean.all { it in "0123456789abcdefABCDEF" }) return false
        val codeBytes = clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        val client = Identity.certificate().publicKey as RSAPublicKey
        val serverCert: X509Certificate = s.serverCertificate()
        val server = serverCert.publicKey as RSAPublicKey

        val hash = pairingSecret(client.modulus, client.publicExponent, server.modulus, server.publicExponent, codeBytes)
        // The first byte of the code is a checksum: catches typos before the TV does.
        if (hash[0] != codeBytes[0]) return false

        send(outer { message(F_SECRET) { bytes(1, hash) } })
        return try {
            next(F_SECRET_ACK)
            serverPin = sha256Hex(serverCert.encoded)
            true
        } catch (e: IOException) {
            false
        } finally {
            close()
        }
    }

    fun close() {
        runCatching { socket?.close() }
        socket = null
    }
}

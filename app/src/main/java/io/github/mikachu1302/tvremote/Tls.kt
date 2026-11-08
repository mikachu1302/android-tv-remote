package io.github.mikachu1302.tvremote

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/**
 * The phone's identity towards the TV: a self-signed RSA certificate generated
 * inside the Android keystore, so the private key never leaves the device. The
 * TV accepts any client certificate; it just remembers the one it paired with.
 */
object Identity {
    private const val ALIAS = "tvremote-client"

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun ensure() {
        if (keyStore().containsAlias(ALIAS)) return
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setKeySize(2048)
            .setDigests(
                KeyProperties.DIGEST_NONE,
                KeyProperties.DIGEST_SHA256,
                KeyProperties.DIGEST_SHA384,
                KeyProperties.DIGEST_SHA512,
            )
            .setSignaturePaddings(
                KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,
                KeyProperties.SIGNATURE_PADDING_RSA_PSS,
            )
            // Conscrypt signs TLS 1.2 client-auth challenges through a raw RSA
            // operation (RSA/ECB/NoPadding); without this the handshake fails with
            // INCOMPATIBLE_PADDING_MODE.
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setCertificateSubject(X500Principal("CN=tvremote"))
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").run {
            initialize(spec)
            generateKeyPair()
        }
    }

    fun certificate(): X509Certificate = keyStore().getCertificate(ALIAS) as X509Certificate

    /** Always offers our certificate, whatever issuers the server lists (ours is self-signed). */
    val keyManager = object : X509KeyManager {
        override fun getClientAliases(keyType: String?, issuers: Array<out java.security.Principal>?) = arrayOf(ALIAS)
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out java.security.Principal>?, socket: Socket?) = ALIAS
        override fun getServerAliases(keyType: String?, issuers: Array<out java.security.Principal>?): Array<String>? = null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out java.security.Principal>?, socket: Socket?): String? = null
        override fun getCertificateChain(alias: String?) = arrayOf(certificate())
        override fun getPrivateKey(alias: String?): PrivateKey? = keyStore().getKey(ALIAS, null) as? PrivateKey
    }
}

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/**
 * Opens a TLS connection with our client certificate. The TV's certificate is
 * self-signed, so there is no CA to check; instead, once paired, we pin its
 * fingerprint: [pin] null accepts any certificate (pairing, which records it),
 * otherwise only that exact certificate is accepted.
 */
fun openTls(host: String, port: Int, pin: String?, timeoutMs: Int = 5000): SSLSocket {
    Identity.ensure()
    val trust = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val cert = chain?.firstOrNull() ?: throw CertificateException("no certificate")
            if (pin != null && sha256Hex(cert.encoded) != pin) {
                throw PinMismatchException("TV certificate changed - pair again")
            }
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    val context = SSLContext.getInstance("TLS").apply {
        init(arrayOf(Identity.keyManager), arrayOf(trust), null)
    }
    val socket = context.socketFactory.createSocket() as SSLSocket
    try {
        socket.connect(InetSocketAddress(host, port), timeoutMs)
        socket.soTimeout = timeoutMs
        socket.startHandshake()
    } catch (e: Exception) {
        socket.close()
        throw e
    }
    return socket
}

fun SSLSocket.serverCertificate(): X509Certificate = session.peerCertificates[0] as X509Certificate

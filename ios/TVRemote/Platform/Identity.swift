import Foundation
import Security
import TVRemoteCore

enum IdentityError: Error {
    case keyGeneration(String)
    case certificate(String)
    case keychain(OSStatus)
}

/// The phone's identity towards the TV: a self-signed RSA certificate whose private key lives in the
/// Keychain and never leaves the device. The TV accepts any client certificate; it just remembers the one
/// it paired with. (iOS cannot create certificates, so the X.509 structure is built by `DER` and signed here.)
enum Identity {
    private static let label = "tvremote-client"
    private static let tag = Data("io.github.mikachu1302.tvremote.client".utf8)
    private static let lock = NSLock()

    /// The identity (certificate plus private key), created on first use.
    static func identity() throws -> SecIdentity {
        lock.lock()
        defer { lock.unlock() }
        if let existing = find() { return existing }
        try create()
        guard let created = find() else { throw IdentityError.certificate("the new identity was not found in the Keychain") }
        return created
    }

    /// Our public key in PKCS#1 form (what the pairing secret hashes).
    static func publicKeyPKCS1() throws -> [UInt8] {
        var cert: SecCertificate?
        guard SecIdentityCopyCertificate(try identity(), &cert) == errSecSuccess, let cert else {
            throw IdentityError.certificate("no certificate in the identity")
        }
        return try publicKeyPKCS1(of: cert)
    }

    static func publicKeyPKCS1(of cert: SecCertificate) throws -> [UInt8] {
        guard let key = SecCertificateCopyKey(cert) else { throw IdentityError.certificate("no public key") }
        var error: Unmanaged<CFError>?
        guard let data = SecKeyCopyExternalRepresentation(key, &error) as Data? else {
            throw IdentityError.certificate(error?.takeRetainedValue().localizedDescription ?? "no key data")
        }
        return [UInt8](data)
    }

    private static func find() -> SecIdentity? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassIdentity,
            kSecAttrLabel as String: label,
            kSecReturnRef as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess, let item else { return nil }
        return (item as! SecIdentity)
    }

    private static func create() throws {
        var error: Unmanaged<CFError>?
        let attributes: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeRSA,
            kSecAttrKeySizeInBits as String: 2048,
            kSecPrivateKeyAttrs as String: [
                kSecAttrIsPermanent as String: true,
                kSecAttrApplicationTag as String: tag,
                kSecAttrLabel as String: label,
                kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            ],
        ]
        guard let privateKey = SecKeyCreateRandomKey(attributes as CFDictionary, &error) else {
            throw IdentityError.keyGeneration(error?.takeRetainedValue().localizedDescription ?? "unknown")
        }
        guard let publicKey = SecKeyCopyPublicKey(privateKey),
              let publicData = SecKeyCopyExternalRepresentation(publicKey, &error) as Data? else {
            throw IdentityError.keyGeneration(error?.takeRetainedValue().localizedDescription ?? "no public key")
        }

        var serial = (0..<8).map { _ in UInt8.random(in: 0...255) }
        serial[0] = (serial[0] & 0x7F) | 0x01 // positive and not zero
        let now = Date()
        let tbs = DER.tbsCertificate(
            commonName: "tvremote", publicKeyPKCS1: [UInt8](publicData), serial: serial,
            notBefore: now.addingTimeInterval(-3600), notAfter: now.addingTimeInterval(20 * 365 * 24 * 3600)
        )
        guard let signature = SecKeyCreateSignature(privateKey, .rsaSignatureMessagePKCS1v15SHA256, Data(tbs) as CFData, &error) as Data? else {
            throw IdentityError.certificate(error?.takeRetainedValue().localizedDescription ?? "signing failed")
        }
        let der = DER.certificate(tbs: tbs, signature: [UInt8](signature))
        guard let cert = SecCertificateCreateWithData(nil, Data(der) as CFData) else {
            throw IdentityError.certificate("the certificate we built was not accepted")
        }
        let status = SecItemAdd([
            kSecClass as String: kSecClassCertificate,
            kSecValueRef as String: cert,
            kSecAttrLabel as String: label,
        ] as CFDictionary, nil)
        guard status == errSecSuccess || status == errSecDuplicateItem else { throw IdentityError.keychain(status) }
    }
}

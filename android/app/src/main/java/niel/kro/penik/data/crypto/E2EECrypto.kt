package niel.kro.penik.data.crypto

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class E2EEncrypted(
    val ciphertext: ByteArray,
    val salt: ByteArray,
    val nonce: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as E2EEncrypted

        if (!ciphertext.contentEquals(other.ciphertext)) return false
        if (!salt.contentEquals(other.salt)) return false
        if (!nonce.contentEquals(other.nonce)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = ciphertext.contentHashCode()
        result = 31 * result + salt.contentHashCode()
        result = 31 * result + nonce.contentHashCode()
        return result
    }
}

class E2EECrypto {

    private val x509Header = byteArrayOf(
        0x30.toByte(), 0x2A.toByte(), 0x30.toByte(), 0x05.toByte(),
        0x06.toByte(), 0x03.toByte(), 0x2B.toByte(), 0x65.toByte(),
        0x6E.toByte(), 0x03.toByte(), 0x21.toByte(), 0x00.toByte()
    )

    private val pkcs8Header = byteArrayOf(
        0x30.toByte(), 0x2E.toByte(), 0x02.toByte(), 0x01.toByte(),
        0x00.toByte(), 0x30.toByte(), 0x05.toByte(), 0x06.toByte(),
        0x03.toByte(), 0x2B.toByte(), 0x65.toByte(), 0x6E.toByte(),
        0x04.toByte(), 0x22.toByte(), 0x04.toByte(), 0x20.toByte()
    )

    fun generateX25519KeyPair(): Pair<ByteArray, ByteArray> {
        val kpg = KeyPairGenerator.getInstance("X25519")
        val keyPair = kpg.generateKeyPair()
        
        val fullPrivate = keyPair.private.encoded
        val fullPublic = keyPair.public.encoded
        
        val rawPrivate = fullPrivate.copyOfRange(fullPrivate.size - 32, fullPrivate.size)
        val rawPublic = fullPublic.copyOfRange(fullPublic.size - 32, fullPublic.size)
        
        return Pair(rawPrivate, rawPublic)
    }

    fun deriveSharedSecret(myPrivateKey: ByteArray, theirPublicKey: ByteArray): ByteArray {
        val keyFactory = KeyFactory.getInstance("X25519")
        
        val cleanPublicKey = if (theirPublicKey.size == 33 && theirPublicKey[0] == 0x05.toByte()) {
            theirPublicKey.copyOfRange(1, 33)
        } else {
            theirPublicKey
        }

        val fullPrivate = ByteArray(16 + myPrivateKey.size)
        System.arraycopy(pkcs8Header, 0, fullPrivate, 0, 16)
        System.arraycopy(myPrivateKey, 0, fullPrivate, 16, myPrivateKey.size)

        val fullPublic = ByteArray(12 + cleanPublicKey.size)
        System.arraycopy(x509Header, 0, fullPublic, 0, 12)
        System.arraycopy(cleanPublicKey, 0, fullPublic, 12, cleanPublicKey.size)

        val privKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(fullPrivate))
        val pubKey = keyFactory.generatePublic(X509EncodedKeySpec(fullPublic))

        val agreement = KeyAgreement.getInstance("X25519")
        agreement.init(privKey)
        agreement.doPhase(pubKey, true)
        return agreement.generateSecret()
    }

    fun encrypt(plaintext: ByteArray, sharedSecret: ByteArray): E2EEncrypted {
        val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val derivedKeyBytes = hkdfDerive(salt, sharedSecret, "PenikE2EE".toByteArray(Charsets.UTF_8), 32)
        
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val derivedKey = SecretKeySpec(derivedKeyBytes, "ChaCha20")
        
        val cipher = Cipher.getInstance("ChaCha20/Poly1305/NoPadding")
        val spec = IvParameterSpec(nonce)
        cipher.init(Cipher.ENCRYPT_MODE, derivedKey, spec)
        val ciphertext = cipher.doFinal(plaintext)
        
        return E2EEncrypted(ciphertext, salt, nonce)
    }

    fun decrypt(ciphertext: ByteArray, sharedSecret: ByteArray, salt: ByteArray, nonce: ByteArray): ByteArray {
        val derivedKeyBytes = hkdfDerive(salt, sharedSecret, "PenikE2EE".toByteArray(Charsets.UTF_8), 32)
        val derivedKey = SecretKeySpec(derivedKeyBytes, "ChaCha20")
        
        val cipher = Cipher.getInstance("ChaCha20/Poly1305/NoPadding")
        val spec = IvParameterSpec(nonce)
        cipher.init(Cipher.DECRYPT_MODE, derivedKey, spec)
        return cipher.doFinal(ciphertext)
    }

    private fun hkdfDerive(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val macExtract = Mac.getInstance("HmacSHA256")
        val saltKey = if (salt.isEmpty()) {
            SecretKeySpec(ByteArray(32), "HmacSHA256")
        } else {
            SecretKeySpec(salt, "HmacSHA256")
        }
        macExtract.init(saltKey)
        val prk = macExtract.doFinal(ikm)

        val macExpand = Mac.getInstance("HmacSHA256")
        macExpand.init(SecretKeySpec(prk, "HmacSHA256"))
        val okm = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var i = 1
        while (offset < length) {
            macExpand.update(t)
            macExpand.update(info)
            macExpand.update(i.toByte())
            t = macExpand.doFinal()
            val chunkLength = minOf(t.size, length - offset)
            System.arraycopy(t, 0, okm, offset, chunkLength)
            offset += chunkLength
            i++
        }
        return okm
    }
}

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
import javax.crypto.spec.PBEKeySpec
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import java.util.Base64
import java.security.GeneralSecurityException

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

    fun derivePublicKey(privateKey: ByteArray): ByteArray {
        val basepoint = ByteArray(32).also { it[0] = 9 }
        return deriveSharedSecret(privateKey, basepoint)
    }

    fun deriveSharedSecret(myPrivateKey: ByteArray, theirPublicKey: ByteArray): ByteArray {
        val keyFactory = KeyFactory.getInstance("X25519")
        
        var cleanPublicKey = theirPublicKey
        if (cleanPublicKey.size == 44) {
            try {
                val asciiStr = String(cleanPublicKey, Charsets.US_ASCII)
                val decoded = Base64.getDecoder().decode(asciiStr)
                if (decoded.size == 32) {
                    cleanPublicKey = decoded
                }
            } catch (e: Exception) {
                android.util.Log.e("E2EE", "Failed to self-heal 44-byte public key on Android", e)
            }
        }

        if (cleanPublicKey.size == 33 && cleanPublicKey[0] == 0x05.toByte()) {
            cleanPublicKey = cleanPublicKey.copyOfRange(1, 33)
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

    fun buildPairwiseAad(senderUserId: Long, recipientUserId: Long, clientMsgId: String = "", timestamp: Long = 0L): ByteArray {
        val fields = listOf(
            "1",
            senderUserId.toString(),
            recipientUserId.toString(),
            clientMsgId,
            timestamp.toString()
        )
        val bos = java.io.ByteArrayOutputStream()
        for (field in fields) {
            val bytes = field.toByteArray(Charsets.UTF_8)
            val lenBytes = ByteArray(4)
            java.nio.ByteBuffer.wrap(lenBytes).putInt(bytes.size)
            bos.write(lenBytes)
            bos.write(bytes)
        }
        return bos.toByteArray()
    }

    fun encrypt(plaintext: ByteArray, sharedSecret: ByteArray, info: String = "penik-pairwise-message-v1", aad: ByteArray? = null): E2EEncrypted {
        val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val derivedKeyBytes = hkdfDerive(salt, sharedSecret, info.toByteArray(Charsets.UTF_8), 32)
        
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val derivedKey = SecretKeySpec(derivedKeyBytes, "ChaCha20")
        
        val cipher = try {
            Cipher.getInstance("ChaCha20/Poly1305/NoPadding")
        } catch (e: Exception) {
            Cipher.getInstance("ChaCha20-Poly1305")
        }
        val spec = IvParameterSpec(nonce)
        cipher.init(Cipher.ENCRYPT_MODE, derivedKey, spec)
        if (aad != null) {
            cipher.updateAAD(aad)
        }
        val ciphertext = cipher.doFinal(plaintext)
        
        return E2EEncrypted(ciphertext, salt, nonce)
    }

    fun decrypt(ciphertext: ByteArray, sharedSecret: ByteArray, salt: ByteArray, nonce: ByteArray, info: String = "penik-pairwise-message-v1", aad: ByteArray? = null): ByteArray {
        try {
            val derivedKeyBytes = hkdfDerive(salt, sharedSecret, info.toByteArray(Charsets.UTF_8), 32)
            val derivedKey = SecretKeySpec(derivedKeyBytes, "ChaCha20")
            
            val cipher = try {
                Cipher.getInstance("ChaCha20/Poly1305/NoPadding")
            } catch (e: Exception) {
                Cipher.getInstance("ChaCha20-Poly1305")
            }
            val spec = IvParameterSpec(nonce)
            cipher.init(Cipher.DECRYPT_MODE, derivedKey, spec)
            if (aad != null) {
                cipher.updateAAD(aad)
            }
            return cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            if (aad != null) {
                try {
                    return decrypt(ciphertext, sharedSecret, salt, nonce, info, null)
                } catch (_: Exception) {}
            }
            if (info == "penik-pairwise-message-v1") {
                return decrypt(ciphertext, sharedSecret, salt, nonce, "PenikE2EE", aad)
            }
            throw e
        }
    }

    /** Decrypts the browser attachment format: nonce (12 bytes) + ciphertext + Poly1305 tag. */
    fun decryptFileChaCha20(encryptedBytes: ByteArray, keyBytes: ByteArray): ByteArray {
        require(keyBytes.size == 32) { "Invalid attachment key" }
        require(encryptedBytes.size >= 28) { "Invalid encrypted attachment" }

        val nonce = encryptedBytes.copyOfRange(0, 12)
        val ciphertextAndTag = encryptedBytes.copyOfRange(12, encryptedBytes.size)
        val cipher = try {
            Cipher.getInstance("ChaCha20/Poly1305/NoPadding")
        } catch (_: GeneralSecurityException) {
            Cipher.getInstance("ChaCha20-Poly1305")
        }
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "ChaCha20"), IvParameterSpec(nonce))
        return cipher.doFinal(ciphertextAndTag)
    }

    /** Encrypts file payload using ChaCha20-Poly1305 matching the browser format: nonce (12 bytes) + ciphertext + Poly1305 tag. */
    fun encryptFileChaCha20(plaintext: ByteArray): EncryptedFileResult {
        val keyBytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val nonce = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = try {
            Cipher.getInstance("ChaCha20/Poly1305/NoPadding")
        } catch (_: GeneralSecurityException) {
            Cipher.getInstance("ChaCha20-Poly1305")
        }
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "ChaCha20"), IvParameterSpec(nonce))
        val ciphertextAndTag = cipher.doFinal(plaintext)
        val encryptedBytes = ByteArray(12 + ciphertextAndTag.size)
        System.arraycopy(nonce, 0, encryptedBytes, 0, 12)
        System.arraycopy(ciphertextAndTag, 0, encryptedBytes, 12, ciphertextAndTag.size)
        return EncryptedFileResult(encryptedBytes, keyBytes)
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

    data class KeyBackup(
        val encryptedBlob: ByteArray,
        val salt: ByteArray,
        val iv: ByteArray
    )

    fun encryptKeyBackup(privateKeyBytes: ByteArray, passphrase: String): KeyBackup {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val derivedKey = deriveKeyFromPassphrase(passphrase, salt, 600000)
        
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, derivedKey, spec)
        val encrypted = cipher.doFinal(privateKeyBytes)
        
        return KeyBackup(encrypted, salt, iv)
    }

    fun decryptKeyBackup(encryptedBlob: ByteArray, salt: ByteArray, iv: ByteArray, passphrase: String): ByteArray {
        val iterationsList = listOf(600000, 100000)
        var lastException: Exception? = null
        for (iterations in iterationsList) {
            try {
                val derivedKey = deriveKeyFromPassphrase(passphrase, salt, iterations)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                val spec = GCMParameterSpec(128, iv)
                cipher.init(Cipher.DECRYPT_MODE, derivedKey, spec)
                return cipher.doFinal(encryptedBlob)
            } catch (e: Exception) {
                lastException = e
            }
        }
        throw lastException ?: Exception("Decryption failed")
    }

    private fun deriveKeyFromPassphrase(passphrase: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, 256)
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val key = f.generateSecret(spec)
        return SecretKeySpec(key.encoded, "AES")
    }
}

data class EncryptedFileResult(
    val encryptedBytes: ByteArray,
    val keyBytes: ByteArray
)

package niel.kro.penik.data.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Group E2EE crypto, mirroring the web client's groups.js.
 *
 * A group epoch has a 32-byte group key. Each message derives its own message key
 * via HKDF-SHA256 with a random 32-byte salt, so message keys never repeat even
 * if a nonce collides. Message authentication binds an immutable header via AAD.
 *
 * AAD = protocol_version | group_id | key_version | message_id | created_at.
 * sender_user_id is intentionally NOT in the AAD: the server assigns sender
 * authoritatively, so a client-supplied sender cannot be verified. (EasyGroups
 * design decision; matches buildGroupAAD in crypto.js.)
 */
class GroupCrypto(private val e2ee: E2EECrypto = E2EECrypto()) {

    companion object {
        const val PROTOCOL_VERSION = 1
        private const val MESSAGE_INFO = "penik-group-message-v1"
    }

    fun generateGroupKey(): ByteArray =
        ByteArray(32).also { SecureRandom().nextBytes(it) }

    /** AAD header, byte-identical to buildGroupAAD in the web client. */
    fun buildAad(groupId: Long, keyVersion: Long, messageId: String, createdAt: Long): ByteArray {
        val fields = listOf(
            PROTOCOL_VERSION.toString(),
            groupId.toString(),
            keyVersion.toString(),
            messageId,
            createdAt.toString()
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

    fun encryptMessage(
        plaintext: ByteArray,
        groupKey: ByteArray,
        groupId: Long,
        keyVersion: Long,
        messageId: String,
        createdAt: Long,
    ): E2EEncrypted {
        val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val messageKey = hkdf(salt, groupKey, MESSAGE_INFO.toByteArray(Charsets.UTF_8), 32)
        val aad = buildAad(groupId, keyVersion, messageId, createdAt)

        val cipher = Cipher.getInstance("ChaCha20/Poly1305/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(messageKey, "ChaCha20"), IvParameterSpec(nonce))
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plaintext)
        return E2EEncrypted(ciphertext, salt, nonce)
    }

    fun decryptMessage(
        ciphertext: ByteArray,
        groupKey: ByteArray,
        salt: ByteArray,
        nonce: ByteArray,
        groupId: Long,
        keyVersion: Long,
        messageId: String,
        createdAt: Long,
    ): ByteArray {
        val messageKey = hkdf(salt, groupKey, MESSAGE_INFO.toByteArray(Charsets.UTF_8), 32)
        val aad = buildAad(groupId, keyVersion, messageId, createdAt)

        val cipher = Cipher.getInstance("ChaCha20/Poly1305/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(messageKey, "ChaCha20"), IvParameterSpec(nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    /** Wrap a group key for one recipient device using the pairwise shared secret. */
    fun wrapKeyForDevice(groupKey: ByteArray, sharedSecret: ByteArray, groupId: Long, keyVersion: Long): E2EEncrypted {
        val aad = "penik-group-key-wrap-v1|$groupId|$keyVersion".toByteArray(Charsets.UTF_8)
        return e2ee.encrypt(groupKey, sharedSecret, "penik-group-key-wrap-v1", aad)
    }

    /** Unwrap a group key envelope with the pairwise shared secret. */
    fun unwrapKey(encryptedKey: ByteArray, sharedSecret: ByteArray, salt: ByteArray, nonce: ByteArray, groupId: Long, keyVersion: Long): ByteArray {
        val aad = "penik-group-key-wrap-v1|$groupId|$keyVersion".toByteArray(Charsets.UTF_8)
        return e2ee.decrypt(encryptedKey, sharedSecret, salt, nonce, "penik-group-key-wrap-v1", aad)
    }

    // HKDF-SHA256, identical construction to E2EECrypto.hkdfDerive.
    private fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val extract = javax.crypto.Mac.getInstance("HmacSHA256")
        val saltKey = if (salt.isEmpty()) SecretKeySpec(ByteArray(32), "HmacSHA256")
        else SecretKeySpec(salt, "HmacSHA256")
        extract.init(saltKey)
        val prk = extract.doFinal(ikm)

        val expand = javax.crypto.Mac.getInstance("HmacSHA256")
        expand.init(SecretKeySpec(prk, "HmacSHA256"))
        val okm = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var i = 1
        while (offset < length) {
            expand.update(t)
            expand.update(info)
            expand.update(i.toByte())
            t = expand.doFinal()
            val chunk = minOf(t.size, length - offset)
            System.arraycopy(t, 0, okm, offset, chunk)
            offset += chunk
            i++
        }
        return okm
    }
}

package niel.kro.penik.data.crypto

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.security.Security

class GroupCryptoTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun setup() {
            Security.removeProvider("BC")
            Security.addProvider(BouncyCastleProvider())
        }
    }

    private val e2ee = E2EECrypto()
    private val crypto = GroupCrypto(e2ee)

    @Test
    fun generateGroupKeyIs32RandomBytes() {
        val a = crypto.generateGroupKey()
        val b = crypto.generateGroupKey()
        assertEquals(32, a.size)
        assertEquals(32, b.size)
        assertFalse("two keys must not collide", a.contentEquals(b))
    }

    @Test
    fun encryptDecryptRoundTrip() {
        val key = crypto.generateGroupKey()
        val plaintext = "привет группа".toByteArray(Charsets.UTF_8)
        val enc = crypto.encryptMessage(plaintext, key, 7L, 2L, "msg-1", 1_700_000_000L)

        val dec = crypto.decryptMessage(
            enc.ciphertext, key, enc.salt, enc.nonce, 7L, 2L, "msg-1", 1_700_000_000L
        )
        assertArrayEquals(plaintext, dec)
    }

    @Test
    fun ciphertextIsNotPlaintextAndSaltNonceSized() {
        val key = crypto.generateGroupKey()
        val plaintext = "secret".toByteArray()
        val enc = crypto.encryptMessage(plaintext, key, 1L, 1L, "m", 1L)
        assertEquals(32, enc.salt.size)
        assertEquals(12, enc.nonce.size)
        assertFalse(enc.ciphertext.contentEquals(plaintext))
    }

    @Test
    fun eachEncryptionUsesFreshSaltAndNonce() {
        val key = crypto.generateGroupKey()
        val pt = "same".toByteArray()
        val a = crypto.encryptMessage(pt, key, 1L, 1L, "m", 1L)
        val b = crypto.encryptMessage(pt, key, 1L, 1L, "m", 1L)
        assertFalse(a.salt.contentEquals(b.salt))
        assertFalse(a.nonce.contentEquals(b.nonce))
        assertFalse(a.ciphertext.contentEquals(b.ciphertext))
    }

    @Test
    fun wrongGroupKeyFailsToDecrypt() {
        val key = crypto.generateGroupKey()
        val other = crypto.generateGroupKey()
        val enc = crypto.encryptMessage("x".toByteArray(), key, 1L, 1L, "m", 1L)
        assertThrows(Exception::class.java) {
            crypto.decryptMessage(enc.ciphertext, other, enc.salt, enc.nonce, 1L, 1L, "m", 1L)
        }
    }

    @Test
    fun tamperedAadFailsAuthentication() {
        val key = crypto.generateGroupKey()
        val enc = crypto.encryptMessage("x".toByteArray(), key, 1L, 1L, "m", 1L)
        // A different messageId changes the AAD, so the Poly1305 tag must reject it.
        assertThrows(Exception::class.java) {
            crypto.decryptMessage(enc.ciphertext, key, enc.salt, enc.nonce, 1L, 1L, "m-evil", 1L)
        }
    }

    @Test
    fun tamperedCiphertextFailsAuthentication() {
        val key = crypto.generateGroupKey()
        val enc = crypto.encryptMessage("hello".toByteArray(), key, 1L, 1L, "m", 1L)
        enc.ciphertext[0] = (enc.ciphertext[0].toInt() xor 0xFF).toByte()
        assertThrows(Exception::class.java) {
            crypto.decryptMessage(enc.ciphertext, key, enc.salt, enc.nonce, 1L, 1L, "m", 1L)
        }
    }

    @Test
    fun buildAadIsDeterministicAndFieldSensitive() {
        val base = crypto.buildAad(1L, 1L, "m", 1L)
        assertArrayEquals(base, crypto.buildAad(1L, 1L, "m", 1L))
        assertFalse(base.contentEquals(crypto.buildAad(2L, 1L, "m", 1L)))
        assertFalse(base.contentEquals(crypto.buildAad(1L, 2L, "m", 1L)))
        assertFalse(base.contentEquals(crypto.buildAad(1L, 1L, "m2", 1L)))
        assertFalse(base.contentEquals(crypto.buildAad(1L, 1L, "m", 2L)))
    }

    @Test
    fun wrapUnwrapKeyRoundTrip() {
        val (privA, pubA) = e2ee.generateX25519KeyPair()
        val (privB, pubB) = e2ee.generateX25519KeyPair()
        val secretA = e2ee.deriveSharedSecret(privA, pubB)
        val secretB = e2ee.deriveSharedSecret(privB, pubA)

        val groupKey = crypto.generateGroupKey()
        val wrapped = crypto.wrapKeyForDevice(groupKey, secretA, 3L, 5L)
        val unwrapped = crypto.unwrapKey(
            wrapped.ciphertext, secretB, wrapped.salt, wrapped.nonce, 3L, 5L
        )
        assertArrayEquals(groupKey, unwrapped)
    }

    @Test
    fun unwrapWithWrongVersionFails() {
        val (privA, pubA) = e2ee.generateX25519KeyPair()
        val (privB, pubB) = e2ee.generateX25519KeyPair()
        val secretA = e2ee.deriveSharedSecret(privA, pubB)
        val secretB = e2ee.deriveSharedSecret(privB, pubA)

        val wrapped = crypto.wrapKeyForDevice(crypto.generateGroupKey(), secretA, 3L, 5L)
        assertThrows(Exception::class.java) {
            crypto.unwrapKey(wrapped.ciphertext, secretB, wrapped.salt, wrapped.nonce, 3L, 99L)
        }
    }
}

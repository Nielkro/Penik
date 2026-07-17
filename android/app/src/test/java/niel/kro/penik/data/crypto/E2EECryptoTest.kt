package niel.kro.penik.data.crypto

import org.junit.Assert.*
import org.junit.Test

class E2EECryptoTest {

    private val crypto = E2EECrypto()

    @Test
    fun testGenerateX25519KeyPair() {
        val (privateKey, publicKey) = crypto.generateX25519KeyPair()
        assertNotNull(privateKey)
        assertNotNull(publicKey)
        assertTrue(privateKey.isNotEmpty())
        assertTrue(publicKey.isNotEmpty())
    }

    @Test
    fun testDeriveSharedSecret() {
        val (alicePrivate, alicePublic) = crypto.generateX25519KeyPair()
        val (bobPrivate, bobPublic) = crypto.generateX25519KeyPair()

        val aliceSecret = crypto.deriveSharedSecret(alicePrivate, bobPublic)
        val bobSecret = crypto.deriveSharedSecret(bobPrivate, alicePublic)

        assertArrayEquals(aliceSecret, bobSecret)
    }

    @Test
    fun testEncryptDecryptRoundTrip() {
        val (alicePrivate, alicePublic) = crypto.generateX25519KeyPair()
        val (bobPrivate, bobPublic) = crypto.generateX25519KeyPair()

        val aliceSecret = crypto.deriveSharedSecret(alicePrivate, bobPublic)
        val bobSecret = crypto.deriveSharedSecret(bobPrivate, alicePublic)

        val plaintext = "Hello Penik Secure World!".toByteArray(Charsets.UTF_8)
        val encrypted = crypto.encrypt(plaintext, aliceSecret)

        assertNotNull(encrypted.ciphertext)
        assertNotNull(encrypted.salt)
        assertNotNull(encrypted.nonce)
        assertEquals(32, encrypted.salt.size)
        assertEquals(12, encrypted.nonce.size)

        val decrypted = crypto.decrypt(encrypted.ciphertext, bobSecret, encrypted.salt, encrypted.nonce)
        assertArrayEquals(plaintext, decrypted)
        assertEquals("Hello Penik Secure World!", String(decrypted, Charsets.UTF_8))
    }

    @Test
    fun testEncryptionRandomness() {
        val (alicePrivate, alicePublic) = crypto.generateX25519KeyPair()
        val (bobPrivate, bobPublic) = crypto.generateX25519KeyPair()

        val secret = crypto.deriveSharedSecret(alicePrivate, bobPublic)
        val plaintext = "Same plaintext message".toByteArray(Charsets.UTF_8)

        val enc1 = crypto.encrypt(plaintext, secret)
        val enc2 = crypto.encrypt(plaintext, secret)

        assertFalse(enc1.ciphertext.contentEquals(enc2.ciphertext))
        assertFalse(enc1.salt.contentEquals(enc2.salt))
        assertFalse(enc1.nonce.contentEquals(enc2.nonce))
    }
}

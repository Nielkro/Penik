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
import niel.kro.penik.data.network.websocket.E2EDevicePayload

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
        if (RustCryptoCore.isAvailable()) {
            val keyPairBytes = RustCryptoCore.generateKeyPair()
            if (keyPairBytes != null && keyPairBytes.size == 64) {
                val pubKey = keyPairBytes.copyOfRange(0, 32)
                val privKey = keyPairBytes.copyOfRange(32, 64)
                return Pair(privKey, pubKey)
            }
        }
        val kpg = KeyPairGenerator.getInstance("X25519")
        val keyPair = kpg.generateKeyPair()
        
        val fullPrivate = keyPair.private.encoded
        val fullPublic = keyPair.public.encoded
        
        val rawPrivate = fullPrivate.copyOfRange(fullPrivate.size - 32, fullPrivate.size)
        val rawPublic = fullPublic.copyOfRange(fullPublic.size - 32, fullPublic.size)
        
        return Pair(rawPrivate, rawPublic)
    }

    fun derivePublicKey(privateKey: ByteArray): ByteArray {
        if (RustCryptoCore.isAvailable()) {
            val pub = RustCryptoCore.derivePublicKey(privateKey)
            if (pub != null) return pub
        }
        val basepoint = ByteArray(32).also { it[0] = 9 }
        return deriveSharedSecret(privateKey, basepoint)
    }

    fun deriveSharedSecret(myPrivateKey: ByteArray, theirPublicKey: ByteArray): ByteArray {
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

        if (RustCryptoCore.isAvailable()) {
            val shared = RustCryptoCore.diffieHellman(myPrivateKey, cleanPublicKey)
            if (shared != null) return shared
        }

        val keyFactory = KeyFactory.getInstance("X25519")
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
        if (RustCryptoCore.isAvailable()) {
            val aad = RustCryptoCore.buildPairwiseAad(senderUserId, recipientUserId, clientMsgId, timestamp)
            if (aad != null) return aad
        }
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

    fun buildPairwiseAadV2(senderUserId: Long, recipientUserId: Long, clientMsgId: String = ""): ByteArray {
        if (RustCryptoCore.isAvailable()) {
            val aad = RustCryptoCore.buildPairwiseAadV2(senderUserId, recipientUserId, clientMsgId)
            if (aad != null) return aad
        }
        val fields = listOf(
            "2",
            senderUserId.toString(),
            recipientUserId.toString(),
            clientMsgId
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
        if (RustCryptoCore.isAvailable()) {
            val infoBytes = info.toByteArray(Charsets.UTF_8)
            val result = RustCryptoCore.encrypt(plaintext, sharedSecret, infoBytes, aad)
            if (result != null && result.size >= 44) {
                val salt = result.copyOfRange(0, 32)
                val nonce = result.copyOfRange(32, 44)
                val ciphertext = result.copyOfRange(44, result.size)
                return E2EEncrypted(ciphertext, salt, nonce)
            }
        }
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
        if (RustCryptoCore.isAvailable()) {
            val infoBytes = info.toByteArray(Charsets.UTF_8)
            val pt = RustCryptoCore.decrypt(ciphertext, salt, nonce, sharedSecret, infoBytes, aad)
            if (pt != null) return pt
        }
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
            if (info == "penik-pairwise-message-v1") {
                return decrypt(ciphertext, sharedSecret, salt, nonce, "PenikE2EE", aad)
            }
            throw e
        }
    }

    data class DeviceRecipientInfo(
        val deviceId: Long,
        val publicKey: ByteArray,
        val cryptoVersion: Int = 1
    )

    fun encryptPairwiseBatch(
        senderPrivateKey: ByteArray,
        senderUserId: Long,
        recipientUserId: Long,
        clientMsgId: String,
        timestamp: Long,
        plaintext: ByteArray,
        recipients: List<DeviceRecipientInfo>
    ): List<E2EDevicePayload> {
        if (recipients.isEmpty()) return emptyList()

        if (RustCryptoCore.isAvailable()) {
            val deviceIds = LongArray(recipients.size) { recipients[it].deviceId }
            val versions = IntArray(recipients.size) { recipients[it].cryptoVersion }
            val allKeys = ByteArray(recipients.size * 32)
            for (i in recipients.indices) {
                System.arraycopy(recipients[i].publicKey, 0, allKeys, i * 32, 32)
            }
            val packed = RustCryptoCore.encryptPairwiseBatch(
                senderPrivateKey,
                senderUserId,
                recipientUserId,
                clientMsgId,
                timestamp,
                plaintext,
                deviceIds,
                allKeys,
                versions
            )
            if (packed != null && packed.size >= 4) {
                val bb = java.nio.ByteBuffer.wrap(packed)
                val count = bb.getInt()
                val list = ArrayList<E2EDevicePayload>(count)
                for (i in 0 until count) {
                    val devId = bb.getLong()
                    val ver = bb.getInt()
                    val salt = ByteArray(32).also { bb.get(it) }
                    val nonce = ByteArray(12).also { bb.get(it) }
                    val ctLen = bb.getInt()
                    val ct = ByteArray(ctLen).also { bb.get(it) }
                    list.add(E2EDevicePayload(deviceId = devId, ciphertext = ct, salt = salt, nonce = nonce, v = ver))
                }
                return list
            }
        }

        // Fallback: iterate over devices
        return recipients.map { device ->
            val isV2 = device.cryptoVersion >= 2
            val deviceAad = if (isV2) {
                buildPairwiseAadV2(senderUserId, recipientUserId, clientMsgId)
            } else {
                buildPairwiseAad(senderUserId, recipientUserId, clientMsgId, timestamp)
            }
            val secret = deriveSharedSecret(senderPrivateKey, device.publicKey)
            val encrypted = encrypt(plaintext, secret, aad = deviceAad)
            E2EDevicePayload(
                deviceId = device.deviceId,
                ciphertext = encrypted.ciphertext,
                salt = encrypted.salt,
                nonce = encrypted.nonce,
                v = if (isV2) 2 else 1
            )
        }
    }

    /** Decrypts the attachment format: supports both PCK1 chunked files and legacy monolithic ChaCha20-Poly1305. */
    fun decryptFileChaCha20(encryptedBytes: ByteArray, keyBytes: ByteArray): ByteArray {
        require(keyBytes.size == 32) { "Invalid attachment key" }
        require(encryptedBytes.size >= 28) { "Invalid encrypted attachment" }

        if (RustCryptoCore.isAvailable()) {
            val pt = RustCryptoCore.decryptFileChaCha20(encryptedBytes, keyBytes)
            if (pt != null) return pt
        }

        if (isChunkedFile(encryptedBytes)) {
            return decryptFileChunkedFallback(encryptedBytes, keyBytes)
        }

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

    /** Encrypts file payload using PCK1 chunked format with ChaCha20-Poly1305, falling back to monolithic format. */
    fun encryptFileChaCha20(plaintext: ByteArray): EncryptedFileResult {
        if (RustCryptoCore.isAvailable()) {
            val (keyBytes, baseNonce) = generateFileKeyAndNonce()
            val chunkSize = 64 * 1024
            val header = RustCryptoCore.createChunkedFileHeader(baseNonce, chunkSize)
            if (header != null) {
                val bos = java.io.ByteArrayOutputStream(header.size + plaintext.size + 32)
                bos.write(header)
                if (plaintext.isEmpty()) {
                    val enc = RustCryptoCore.encryptFileChunk(keyBytes, baseNonce, 0, true, ByteArray(0))
                    if (enc != null) bos.write(enc)
                } else {
                    val numChunks = (plaintext.size + chunkSize - 1) / chunkSize
                    for (i in 0 until numChunks) {
                        val start = i * chunkSize
                        val end = minOf(plaintext.size, start + chunkSize)
                        val slice = plaintext.copyOfRange(start, end)
                        val isLast = i == numChunks - 1
                        val enc = RustCryptoCore.encryptFileChunk(keyBytes, baseNonce, i, isLast, slice)
                        if (enc != null) bos.write(enc)
                    }
                }
                return EncryptedFileResult(bos.toByteArray(), keyBytes)
            }
        }

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

    fun isChunkedFile(data: ByteArray): Boolean {
        if (data.size < 20) return false
        if (RustCryptoCore.isAvailable()) {
            return RustCryptoCore.isChunkedFile(data)
        }
        return data[0] == 0x50.toByte() && data[1] == 0x43.toByte() &&
               data[2] == 0x4B.toByte() && data[3] == 0x31.toByte()
    }

    fun generateFileKeyAndNonce(): Pair<ByteArray, ByteArray> {
        if (RustCryptoCore.isAvailable()) {
            val res = RustCryptoCore.generateFileKeyAndNonce()
            if (res != null && res.size == 44) {
                return Pair(res.copyOfRange(0, 32), res.copyOfRange(32, 44))
            }
        }
        val key = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val nonce = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        return Pair(key, nonce)
    }

    fun encryptFileChunk(key: ByteArray, baseNonce: ByteArray, chunkIndex: Int, isLast: Boolean, chunk: ByteArray): ByteArray {
        if (RustCryptoCore.isAvailable()) {
            val enc = RustCryptoCore.encryptFileChunk(key, baseNonce, chunkIndex, isLast, chunk)
            if (enc != null) return enc
        }
        return encryptFileChunkFallback(key, baseNonce, chunkIndex, isLast, chunk)
    }

    fun decryptFileChunk(key: ByteArray, baseNonce: ByteArray, chunkIndex: Int, isLast: Boolean, encryptedChunk: ByteArray): ByteArray {
        if (RustCryptoCore.isAvailable()) {
            val pt = RustCryptoCore.decryptFileChunk(key, baseNonce, chunkIndex, isLast, encryptedChunk)
            if (pt != null) return pt
        }
        return decryptFileChunkFallback(key, baseNonce, chunkIndex, isLast, encryptedChunk)
    }

    /**
     * Streaming encryption: reads input in 64 KB chunks, encrypts via PCK1 format, and writes to output.
     * Memory overhead is capped at 64 KB regardless of file size.
     */
    fun encryptFileStream(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        chunkSize: Int = 64 * 1024,
        onProgress: ((loaded: Long, total: Long) -> Unit)? = null
    ): ByteArray {
        val (key, baseNonce) = generateFileKeyAndNonce()
        val header = if (RustCryptoCore.isAvailable()) {
            RustCryptoCore.createChunkedFileHeader(baseNonce, chunkSize)
        } else {
            createChunkedFileHeaderFallback(baseNonce, chunkSize)
        } ?: createChunkedFileHeaderFallback(baseNonce, chunkSize)

        output.write(header)

        val buffer = ByteArray(chunkSize)
        var chunkIndex = 0
        var totalLoaded = 0L

        var bytesRead = input.read(buffer)
        if (bytesRead <= 0) {
            val enc = encryptFileChunk(key, baseNonce, 0, true, ByteArray(0))
            output.write(enc)
            return key
        }

        while (bytesRead > 0) {
            val nextBuffer = ByteArray(chunkSize)
            val nextRead = input.read(nextBuffer)
            val isLast = nextRead <= 0
            val chunkSlice = if (bytesRead == chunkSize) buffer else buffer.copyOfRange(0, bytesRead)

            val enc = encryptFileChunk(key, baseNonce, chunkIndex, isLast, chunkSlice)
            output.write(enc)
            totalLoaded += bytesRead
            onProgress?.invoke(totalLoaded, -1L)

            chunkIndex++
            bytesRead = nextRead
            if (nextRead > 0) {
                System.arraycopy(nextBuffer, 0, buffer, 0, nextRead)
            }
        }

        return key
    }

    private fun createChunkedFileHeaderFallback(baseNonce: ByteArray, chunkSize: Int): ByteArray {
        val header = ByteArray(20)
        header[0] = 0x50.toByte()
        header[1] = 0x43.toByte()
        header[2] = 0x4B.toByte()
        header[3] = 0x31.toByte()
        System.arraycopy(baseNonce, 0, header, 4, 12)
        header[16] = (chunkSize ushr 24).toByte()
        header[17] = (chunkSize ushr 16).toByte()
        header[18] = (chunkSize ushr 8).toByte()
        header[19] = chunkSize.toByte()
        return header
    }

    private fun deriveChunkNonce(baseNonce: ByteArray, chunkIndex: Int): ByteArray {
        val nonce = baseNonce.copyOf()
        nonce[8] = (nonce[8].toInt() xor (chunkIndex ushr 24)).toByte()
        nonce[9] = (nonce[9].toInt() xor (chunkIndex ushr 16)).toByte()
        nonce[10] = (nonce[10].toInt() xor (chunkIndex ushr 8)).toByte()
        nonce[11] = (nonce[11].toInt() xor chunkIndex).toByte()
        return nonce
    }

    private fun buildChunkAad(chunkIndex: Int, isLast: Boolean): ByteArray {
        return byteArrayOf(
            (chunkIndex ushr 24).toByte(),
            (chunkIndex ushr 16).toByte(),
            (chunkIndex ushr 8).toByte(),
            chunkIndex.toByte(),
            if (isLast) 1 else 0
        )
    }

    private fun encryptFileChunkFallback(key: ByteArray, baseNonce: ByteArray, chunkIndex: Int, isLast: Boolean, chunk: ByteArray): ByteArray {
        val chunkNonce = deriveChunkNonce(baseNonce, chunkIndex)
        val aad = buildChunkAad(chunkIndex, isLast)
        val cipher = try {
            Cipher.getInstance("ChaCha20/Poly1305/NoPadding")
        } catch (_: GeneralSecurityException) {
            Cipher.getInstance("ChaCha20-Poly1305")
        }
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(chunkNonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(chunk)
    }

    private fun decryptFileChunkFallback(key: ByteArray, baseNonce: ByteArray, chunkIndex: Int, isLast: Boolean, encryptedChunk: ByteArray): ByteArray {
        val chunkNonce = deriveChunkNonce(baseNonce, chunkIndex)
        val aad = buildChunkAad(chunkIndex, isLast)
        val cipher = try {
            Cipher.getInstance("ChaCha20/Poly1305/NoPadding")
        } catch (_: GeneralSecurityException) {
            Cipher.getInstance("ChaCha20-Poly1305")
        }
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(chunkNonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(encryptedChunk)
    }

    private fun decryptFileChunkedFallback(encryptedBytes: ByteArray, keyBytes: ByteArray): ByteArray {
        require(encryptedBytes.size > 20) { "Payload too short for chunked file" }
        val baseNonce = encryptedBytes.copyOfRange(4, 16)
        val chunkSize = ((encryptedBytes[16].toInt() and 0xFF) shl 24) or
                        ((encryptedBytes[17].toInt() and 0xFF) shl 16) or
                        ((encryptedBytes[18].toInt() and 0xFF) shl 8) or
                        (encryptedBytes[19].toInt() and 0xFF)
        val chunkPayloadMax = chunkSize + 16
        var cur = 20
        var chunkIndex = 0
        val bos = java.io.ByteArrayOutputStream()

        while (cur < encryptedBytes.size) {
            val remaining = encryptedBytes.size - cur
            val currentChunkLen = minOf(remaining, chunkPayloadMax)
            val isLast = cur + currentChunkLen == encryptedBytes.size
            val chunkData = encryptedBytes.copyOfRange(cur, cur + currentChunkLen)
            val pt = decryptFileChunk(keyBytes, baseNonce, chunkIndex, isLast, chunkData)
            bos.write(pt)
            cur += currentChunkLen
            chunkIndex++
        }

        return bos.toByteArray()
    }

    private fun hkdfDerive(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        if (RustCryptoCore.isAvailable()) {
            val okm = RustCryptoCore.hkdfDerive(salt, ikm, info, length)
            if (okm != null) return okm
        }
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
        if (RustCryptoCore.isAvailable()) {
            val keyBytes = RustCryptoCore.deriveKeyPbkdf2(passphrase, salt, iterations, 32)
            if (keyBytes != null) {
                return SecretKeySpec(keyBytes, "AES")
            }
        }
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, 256)
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val key = f.generateSecret(spec)
        return SecretKeySpec(key.encoded, "AES")
    }

    fun zeroize(array: ByteArray) {
        if (RustCryptoCore.isAvailable()) {
            RustCryptoCore.zeroize(array)
        } else {
            array.fill(0)
        }
    }
}

data class EncryptedFileResult(
    val encryptedBytes: ByteArray,
    val keyBytes: ByteArray
)

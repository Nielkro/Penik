package niel.kro.penik.data.crypto

import java.security.MessageDigest

/**
 * Conversation safety number — the human-comparable fingerprint of two identity
 * keys.
 *
 * This is the single Android definition and it must stay byte-for-byte identical
 * to `computeSafetyNumber` in `client/js/crypto.js`. The codebase previously
 * carried three variants; a fingerprint that differs across platforms is worse
 * than none, because the users compare, see different numbers, and conclude they
 * are being intercepted.
 *
 * Shape: strip the legacy 0x05 type prefix, order the two keys by *unsigned* byte
 * value (Kotlin bytes are signed, so the mask is not optional), SHA-256 over the
 * 64 concatenated bytes, then 5 groups of 5 digits.
 */
object SafetyNumber {

    private const val BLOCKS = 5

    fun compute(identityKeysA: List<ByteArray>, identityKeysB: List<ByteArray>): String {
        val allKeys = (identityKeysA + identityKeysB)
            .filter { it.isNotEmpty() }
            .map { normalize(it) }
            .sortedWith { a, b -> compareUnsigned(a, b) }

        require(allKeys.isNotEmpty()) { "safety number: no identity keys provided" }

        val concat = ByteArray(allKeys.size * 32)
        allKeys.forEachIndexed { index, key ->
            System.arraycopy(key, 0, concat, index * 32, 32)
        }

        val hash = MessageDigest.getInstance("SHA-256").digest(concat)

        val digits = StringBuilder()
        var i = 0
        while (i + 1 < hash.size && digits.length < BLOCKS * 5) {
            val value = ((hash[i].toInt() and 0xFF) shl 8) or (hash[i + 1].toInt() and 0xFF)
            digits.append(value.toString().padStart(5, '0').take(5))
            i += 2
        }

        return digits.toString().chunked(5).joinToString(" ")
    }

    fun compute(identityKeyA: ByteArray, identityKeyB: ByteArray): String {
        return compute(listOf(identityKeyA), listOf(identityKeyB))
    }

    private fun normalize(key: ByteArray): ByteArray {
        val clean = if (key.size == 33 && key[0] == 5.toByte()) key.copyOfRange(1, 33) else key
        require(clean.size == 32) { "safety number: expected a 32-byte identity key, got ${clean.size}" }
        return clean
    }

    private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until 32) {
            val diff = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (diff != 0) return diff
        }
        return 0
    }
}

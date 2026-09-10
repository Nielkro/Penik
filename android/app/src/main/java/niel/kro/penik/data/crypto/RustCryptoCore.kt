package niel.kro.penik.data.crypto

object RustCryptoCore {
    private var isLoaded = false

    init {
        try {
            System.loadLibrary("penik_crypto")
            isLoaded = true
        } catch (e: Throwable) {
            isLoaded = false
        }
    }

    fun isAvailable(): Boolean = isLoaded

    @JvmStatic
    external fun generateKeyPair(): ByteArray?

    @JvmStatic
    external fun derivePublicKey(privateKey: ByteArray): ByteArray?

    @JvmStatic
    external fun diffieHellman(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray?

    @JvmStatic
    external fun buildPairwiseAad(
        senderUserId: Long,
        recipientUserId: Long,
        clientMsgId: String,
        timestamp: Long
    ): ByteArray?

    @JvmStatic
    external fun encrypt(
        plaintext: ByteArray,
        sharedSecret: ByteArray,
        info: ByteArray?,
        aad: ByteArray?
    ): ByteArray?

    @JvmStatic
    external fun decrypt(
        ciphertext: ByteArray,
        salt: ByteArray,
        nonce: ByteArray,
        sharedSecret: ByteArray,
        info: ByteArray?,
        aad: ByteArray?
    ): ByteArray?

    @JvmStatic
    external fun computeSafetyNumber(ikA: ByteArray, ikB: ByteArray): String?

    @JvmStatic
    external fun formatSafetyNumber(ikA: ByteArray, ikB: ByteArray): String?

    @JvmStatic
    external fun generateSafetyWords(ikA: ByteArray, ikB: ByteArray): Array<String>?
}

package niel.kro.penik.data.crypto

object RustCryptoCore {
    private var isLoaded = false
    var coreVersion: Int = 0
        private set

    const val MIN_SUPPORTED_CORE_VERSION = 1

    init {
        try {
            System.loadLibrary("penik_crypto")
            isLoaded = true
            try {
                coreVersion = cryptoVersion()
                android.util.Log.i("RustCryptoCore", "Loaded native penik_crypto (version: $coreVersion)")
                if (coreVersion < MIN_SUPPORTED_CORE_VERSION) {
                    android.util.Log.e("RustCryptoCore", "Outdated penik_crypto: version $coreVersion, expected >= $MIN_SUPPORTED_CORE_VERSION")
                }
            } catch (ve: UnsatisfiedLinkError) {
                coreVersion = 0
                android.util.Log.w("RustCryptoCore", "Native penik_crypto loaded but lacks cryptoVersion symbol (legacy/unversioned)")
            }
        } catch (e: Throwable) {
            isLoaded = false
        }
    }

    fun isAvailable(): Boolean = isLoaded

    @JvmStatic
    external fun cryptoVersion(): Int

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
    external fun buildPairwiseAadV2(
        senderUserId: Long,
        recipientUserId: Long,
        clientMsgId: String
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
    external fun generateFileKeyAndNonce(): ByteArray?

    @JvmStatic
    external fun createChunkedFileHeader(baseNonce: ByteArray, chunkSize: Int): ByteArray?

    @JvmStatic
    external fun parseChunkedFileHeader(header: ByteArray): ByteArray?

    @JvmStatic
    external fun isChunkedFile(data: ByteArray): Boolean

    @JvmStatic
    external fun encryptFileChunk(
        key: ByteArray,
        baseNonce: ByteArray,
        chunkIndex: Int,
        isLast: Boolean,
        chunk: ByteArray
    ): ByteArray?

    @JvmStatic
    external fun decryptFileChunk(
        key: ByteArray,
        baseNonce: ByteArray,
        chunkIndex: Int,
        isLast: Boolean,
        encryptedChunk: ByteArray
    ): ByteArray?

    @JvmStatic
    external fun decryptFileChaCha20(encryptedBytes: ByteArray, key: ByteArray): ByteArray?

    @JvmStatic
    external fun encryptPairwiseBatch(
        senderPrivateKey: ByteArray,
        senderUserId: Long,
        recipientUserId: Long,
        clientMsgId: String,
        timestamp: Long,
        plaintext: ByteArray,
        deviceIds: LongArray,
        devicePublicKeys: ByteArray,
        deviceCryptoVersions: IntArray
    ): ByteArray?

    @JvmStatic
    external fun computeSafetyNumber(ikA: ByteArray, ikB: ByteArray): String?

    @JvmStatic
    external fun formatSafetyNumber(ikA: ByteArray, ikB: ByteArray): String?

    @JvmStatic
    external fun generateSafetyWords(ikA: ByteArray, ikB: ByteArray): Array<String>?

    @JvmStatic
    external fun deriveKeyPbkdf2(
        passphrase: String,
        salt: ByteArray,
        iterations: Int,
        length: Int
    ): ByteArray?

    @JvmStatic
    external fun hkdfDerive(
        salt: ByteArray,
        ikm: ByteArray,
        info: ByteArray,
        length: Int
    ): ByteArray?

    @JvmStatic
    external fun zeroize(array: ByteArray)
}

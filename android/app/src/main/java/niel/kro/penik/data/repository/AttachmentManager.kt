package niel.kro.penik.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import niel.kro.penik.data.crypto.E2EECrypto
import niel.kro.penik.data.network.ProgressRequestBody
import niel.kro.penik.data.network.api.ApiConfig
import niel.kro.penik.data.network.api.ApiService
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

data class LocalMediaInfo(
    val rawBytes: ByteArray,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val thumbBase64: String?,
    val optimisticPayload: String
)

class AttachmentManager(
    private val apiService: ApiService,
    private val e2eeCrypto: E2EECrypto
) {
    suspend fun prepareLocalMedia(
        context: Context,
        uri: Uri,
        clientMsgId: String,
        textCaption: String = ""
    ): LocalMediaInfo = withContext(Dispatchers.IO) {
        val contentResolver = context.contentResolver
        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"

        var fileName = "file"
        var fileSize = 0L

        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex != -1) fileName = cursor.getString(nameIndex) ?: "file"
                if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex)
            }
        }

        val rawBytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Не удалось прочитать файл")

        if (fileSize == 0L) fileSize = rawBytes.size.toLong()

        val thumbBase64 = generateThumbnailBase64(context, uri, rawBytes, mimeType)

        // Cache local plaintext by clientMsgId so it renders instantly in UI
        cacheLocalFileById(context, clientMsgId, fileName, rawBytes)

        val fileObj = JSONObject().apply {
            put("upload_msg_id", clientMsgId)
            put("uploading", true)
            put("url", "local:$clientMsgId")
            put("name", fileName)
            put("size", fileSize)
            put("mime", mimeType)
            put("key", "")
            if (!thumbBase64.isNullOrBlank()) {
                put("thumb", thumbBase64)
            }
        }

        val optimisticPayload = JSONObject().apply {
            put("v", 1)
            put("type", "file")
            put("text", textCaption)
            put("file", fileObj)
        }.toString()

        LocalMediaInfo(
            rawBytes = rawBytes,
            fileName = fileName,
            fileSize = fileSize,
            mimeType = mimeType,
            thumbBase64 = thumbBase64,
            optimisticPayload = optimisticPayload
        )
    }

    suspend fun uploadAndEncryptAttachment(
        context: Context,
        info: LocalMediaInfo,
        clientMsgId: String,
        textCaption: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            UploadProgressBus.update(clientMsgId, 0, info.fileSize)

            // 1. Encrypt raw file via ChaCha20-Poly1305
            val encResult = e2eeCrypto.encryptFileChaCha20(info.rawBytes)

            // 2. Upload ciphertext with progress tracking
            val rawBody = encResult.encryptedBytes.toRequestBody("application/octet-stream".toMediaTypeOrNull())
            val progressBody = ProgressRequestBody(rawBody) { loaded, total ->
                UploadProgressBus.update(clientMsgId, loaded, if (total > 0) total else info.fileSize)
            }
            val part = MultipartBody.Part.createFormData("file", info.fileName, progressBody)
            val uploadResponse = apiService.uploadAttachment(part)
            if (!uploadResponse.isSuccessful || uploadResponse.body() == null) {
                throw IllegalStateException("Не удалось загрузить файл на сервер (${uploadResponse.code()})")
            }
            val attachmentUrl = uploadResponse.body()!!.url

            // Pre-cache unencrypted plaintext file in local attachment cache under final URL and key
            cacheLocalPlaintext(context, attachmentUrl, Base64.encodeToString(encResult.keyBytes, Base64.NO_WRAP), info.fileName, info.rawBytes)

            UploadProgressBus.remove(clientMsgId)

            val fileObj = JSONObject().apply {
                put("url", attachmentUrl)
                put("name", info.fileName)
                put("size", info.fileSize)
                put("mime", info.mimeType)
                put("key", Base64.encodeToString(encResult.keyBytes, Base64.NO_WRAP))
                if (!info.thumbBase64.isNullOrBlank()) {
                    put("thumb", info.thumbBase64)
                }
            }

            val payloadObj = JSONObject().apply {
                put("v", 1)
                put("type", "file")
                put("text", textCaption)
                put("file", fileObj)
            }

            payloadObj.toString()
        }.onFailure {
            UploadProgressBus.remove(clientMsgId)
        }
    }

    private fun cacheLocalFileById(context: Context, id: String, filename: String, plaintext: ByteArray) {
        runCatching {
            val cacheDir = File(context.cacheDir, "attachments").apply { mkdirs() }
            val extension = filename.substringAfterLast('.', "").take(16)
            val outputFile = File(cacheDir, "$id${if (extension.isBlank()) "" else ".$extension"}")
            outputFile.writeBytes(plaintext)
        }
    }

    private fun cacheLocalPlaintext(context: Context, cdnUrl: String, keyB64: String, filename: String, plaintext: ByteArray) {
        runCatching {
            val cacheDir = File(context.cacheDir, "attachments").apply { mkdirs() }
            val extension = filename.substringAfterLast('.', "").take(16)
            val fullUrl = when {
                cdnUrl.startsWith("http://") || cdnUrl.startsWith("https://") -> cdnUrl
                cdnUrl.startsWith("/") -> "${ApiConfig.SCHEME}://${ApiConfig.HOST}$cdnUrl"
                else -> "${ApiConfig.SCHEME}://${ApiConfig.HOST}/$cdnUrl"
            }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest((fullUrl + keyB64).toByteArray())
                .joinToString("") { "%02x".format(it) }
            val outputFile = File(cacheDir, "$digest${if (extension.isBlank()) "" else ".$extension"}")
            outputFile.writeBytes(plaintext)
        }
    }

    private fun generateThumbnailBase64(context: Context, uri: Uri, rawBytes: ByteArray, mimeType: String): String? {
        return runCatching {
            var bitmap: Bitmap? = null
            if (mimeType.startsWith("image/")) {
                bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
            } else if (mimeType.startsWith("video/")) {
                val retriever = MediaMetadataRetriever()
                runCatching {
                    retriever.setDataSource(context, uri)
                    bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST)
                        ?: retriever.frameAtTime
                }
                runCatching { retriever.release() }
            }

            if (bitmap != null) {
                val maxSide = 180
                var w = bitmap.width
                var h = bitmap.height
                if (w > maxSide || h > maxSide) {
                    if (w > h) {
                        h = ((h * maxSide) / w.toFloat()).toInt()
                        w = maxSide
                    } else {
                        w = ((w * maxSide) / h.toFloat()).toInt()
                        h = maxSide
                    }
                }
                val scaled = Bitmap.createScaledBitmap(bitmap, w.coerceAtLeast(1), h.coerceAtLeast(1), true)
                val outputStream = ByteArrayOutputStream()
                val compressFormat = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
                scaled.compress(compressFormat, 40, outputStream)
                val b64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
                "data:image/webp;base64,$b64"
            } else {
                null
            }
        }.getOrNull()
    }
}

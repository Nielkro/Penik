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
import niel.kro.penik.data.network.api.ApiConfig
import niel.kro.penik.data.network.api.ApiService
import niel.kro.penik.data.network.api.VkSaveRequest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

class AttachmentManager(
    private val apiService: ApiService,
    private val e2eeCrypto: E2EECrypto,
    private val uploadClient: OkHttpClient
) {
    suspend fun uploadAndPrepareAttachment(
        context: Context,
        uri: Uri,
        textCaption: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
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

            // 1. Encrypt raw file via ChaCha20-Poly1305
            val encResult = e2eeCrypto.encryptFileChaCha20(rawBytes)

            // 2. Upload the ciphertext straight to VK, bypassing our server
            val cdnUrl = uploadDirectToVK(encResult.encryptedBytes, fileName)

            // 3. Generate thumbnail base64
            val thumbBase64 = generateThumbnailBase64(context, uri, rawBytes, mimeType)

            // 4. Pre-cache unencrypted plaintext file in local attachment cache for instant render
            cacheLocalPlaintext(context, cdnUrl, Base64.encodeToString(encResult.keyBytes, Base64.NO_WRAP), fileName, rawBytes)

            // 5. Build JSON payload matching Web client spec
            val fileObj = JSONObject().apply {
                put("url", cdnUrl)
                put("name", fileName)
                put("size", fileSize)
                put("mime", mimeType)
                put("key", Base64.encodeToString(encResult.keyBytes, Base64.NO_WRAP))
                if (!thumbBase64.isNullOrBlank()) {
                    put("thumb", thumbBase64)
                }
            }

            val payloadObj = JSONObject().apply {
                put("v", 1)
                put("type", "file")
                put("text", textCaption)
                put("file", fileObj)
            }

            payloadObj.toString()
        }
    }

    /**
     * Client-side upload flow: the server only issues a one-shot VK upload URL
     * and later commits the resulting token, so the encrypted bytes travel
     * directly from the device to VK. The upload request goes through a client
     * without our auth interceptor — the session token must not reach VK.
     */
    private suspend fun uploadDirectToVK(encryptedBytes: ByteArray, fileName: String): String {
        val urlResponse = apiService.getVKUploadUrl()
        val uploadUrl = urlResponse.body()?.uploadUrl
        if (!urlResponse.isSuccessful || uploadUrl.isNullOrBlank()) {
            throw IllegalStateException("Не удалось получить ссылку для загрузки (${urlResponse.code()})")
        }

        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                fileName,
                encryptedBytes.toRequestBody("application/octet-stream".toMediaTypeOrNull())
            )
            .build()

        val fileToken = uploadClient.newCall(
            Request.Builder().url(uploadUrl).post(multipart).build()
        ).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("VK отклонил загрузку (${response.code})")
            }
            JSONObject(body).optString("file").takeIf { it.isNotBlank() && it != "null" }
                ?: throw IllegalStateException("VK не вернул токен файла")
        }

        val saveResponse = apiService.saveVKAttachment(VkSaveRequest(file = fileToken, name = fileName))
        val cdnUrl = saveResponse.body()?.url
        if (!saveResponse.isSuccessful || cdnUrl.isNullOrBlank()) {
            throw IllegalStateException("Не удалось сохранить файл в VK (${saveResponse.code()})")
        }
        return cdnUrl
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

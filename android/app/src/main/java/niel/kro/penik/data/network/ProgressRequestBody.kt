package niel.kro.penik.data.network

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink

class ProgressRequestBody(
    private val contentType: MediaType?,
    private val data: ByteArray,
    private val onProgress: (bytesWritten: Long, contentLength: Long) -> Unit
) : RequestBody() {

    constructor(
        delegate: RequestBody,
        onProgress: (bytesWritten: Long, contentLength: Long) -> Unit
    ) : this(
        delegate.contentType(),
        run {
            val buffer = okio.Buffer()
            delegate.writeTo(buffer)
            buffer.readByteArray()
        },
        onProgress
    )

    override fun contentType(): MediaType? = contentType

    override fun contentLength(): Long = data.size.toLong()

    override fun writeTo(sink: BufferedSink) {
        val total = data.size.toLong()
        val chunkSize = 32 * 1024 // 32 KB chunk for smooth live progress updates
        var offset = 0

        onProgress(0L, total)

        while (offset < data.size) {
            val toWrite = minOf(chunkSize, data.size - offset)
            sink.write(data, offset, toWrite)
            sink.flush()
            offset += toWrite
            onProgress(offset.toLong(), total)
        }
    }
}

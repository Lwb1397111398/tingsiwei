package com.tingsiwei.app.transcribe

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * 离线识别模型下载/管理。默认从 hf-mirror.com（国内镜像）下载，
 * 支持断点续传：中断后再点下载会从已下载的字节继续。
 */
object ModelManager {

    private const val REPO =
        "https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09/resolve/main"
    const val MODEL_URL = "$REPO/model.int8.onnx"
    const val TOKENS_URL = "$REPO/tokens.txt"
    const val MODEL_BYTES = 237_115_547L

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun finalFile(context: Context, name: String): File =
        File(SherpaTranscriber.modelDir(context), name)

    private fun tmpFile(context: Context, name: String): File =
        File(SherpaTranscriber.modelDir(context), "$name.part")

    fun downloadedBytes(context: Context, name: String): Long {
        val done = finalFile(context, name)
        if (done.exists()) return done.length()
        val part = tmpFile(context, name)
        return if (part.exists()) part.length() else 0
    }

    fun isFileComplete(context: Context, name: String, minBytes: Long): Boolean =
        finalFile(context, name).length() >= minBytes

    /** 下载单个文件（断点续传）。onProgress(已下载, 总大小)。 */
    suspend fun downloadFile(
        context: Context,
        name: String,
        url: String,
        minBytes: Long,
        onProgress: (Long, Long) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        val dst = finalFile(context, name)
        if (dst.length() >= minBytes) {
            onProgress(dst.length(), dst.length())
            return@withContext
        }
        val part = tmpFile(context, name)
        var attempts = 0
        while (true) {
            attempts++
            try {
                var start = if (part.exists()) part.length() else 0
                val reqBuilder = Request.Builder().url(url)
                if (start > 0) reqBuilder.header("Range", "bytes=$start-")
                http.newCall(reqBuilder.build()).execute().use { resp ->
                    if (resp.code != 200 && resp.code != 206) {
                        throw Exception("下载失败 (HTTP ${resp.code})")
                    }
                    val body = resp.body ?: throw Exception("下载失败：空响应")
                    val append = resp.code == 206 && start > 0
                    if (!append) {
                        start = 0
                        if (part.exists()) part.delete()
                    }
                    val total = body.contentLength().let {
                        if (it > 0) it + start else maxOf(start, MODEL_BYTES)
                    }
                    body.byteStream().use { input ->
                        FileOutputStream(part, append).use { fos ->
                            copyWithProgress(input, fos, start, total, onProgress)
                        }
                    }
                }
                if (part.length() < minBytes) throw Exception("下载的文件不完整")
                if (dst.exists()) dst.delete()
                if (!part.renameTo(dst)) {
                    part.copyTo(dst, overwrite = true)
                    part.delete()
                }
                return@withContext
            } catch (e: Exception) {
                if (attempts >= 5) throw e
                kotlinx.coroutines.delay(1500L * attempts)
            }
        }
    }

    private fun copyWithProgress(
        input: InputStream,
        out: FileOutputStream,
        startBytes: Long,
        total: Long,
        onProgress: (Long, Long) -> Unit,
    ) {
        val buf = ByteArray(64 * 1024)
        var read = startBytes
        var sinceReport = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            read += n
            sinceReport += n
            if (sinceReport >= 256 * 1024) {
                sinceReport = 0
                onProgress(read, total)
            }
        }
        out.flush()
        onProgress(read, total)
    }

    fun deleteModel(context: Context) {
        val dir = SherpaTranscriber.modelDir(context)
        dir.listFiles()?.forEach { it.delete() }
    }

    fun totalBytes(context: Context): Long =
        SherpaTranscriber.modelDir(context).listFiles()?.sumOf { it.length() } ?: 0
}

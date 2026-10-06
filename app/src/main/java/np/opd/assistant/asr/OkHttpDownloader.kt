package np.opd.assistant.asr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import np.opd.speech.Downloader
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Downloads a model file with resume support. Used only for models whose checksum is pinned in the manifest:
 * [np.opd.speech.ModelInstaller] verifies the SHA-256 afterwards and discards anything that does not match.
 */
class OkHttpDownloader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)   // per read, not per file: a 600 MB download may take many minutes
        .callTimeout(0, TimeUnit.SECONDS)
        .build()
) : Downloader {

    override suspend fun download(url: String, dest: File, resumeFrom: Long, onProgress: (Long, Long?) -> Unit) {
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url).apply { if (resumeFrom > 0) header("Range", "bytes=$resumeFrom-") }.build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} from the model server")
                val body = resp.body ?: throw IOException("empty response")
                // 206 = the server honoured the range; 200 = it ignored it and is sending the whole file again.
                val append = resumeFrom > 0 && resp.code == 206
                val length = body.contentLength().takeIf { it > 0 }
                val total = length?.let { if (append) it + resumeFrom else it }
                var done = if (append) resumeFrom else 0L
                var lastReport = 0L
                FileOutputStream(dest, append).use { out ->
                    body.byteStream().use { src ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = src.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val now = System.nanoTime()
                            if (now - lastReport > 250_000_000L) { lastReport = now; onProgress(done, total) }
                        }
                    }
                }
                onProgress(done, total)
            }
        }
    }
}

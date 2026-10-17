package de.wolaang.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Plain HTTP downloads for large data files (routing tiles, offline maps). */
class FileDownloader(private val userAgent: String) {

    /**
     * Downloads [url] to [target]. [onProgress] gets 0..1. Writes to a temp file first, so an
     * aborted download never leaves a broken file behind, and replaces an older version only
     * once the new one is complete. The file keeps the server's version date.
     */
    suspend fun download(url: String, target: File, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".part")
        val conn = open(url)
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                error("Download fehlgeschlagen (HTTP ${conn.responseCode})")
            }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
            target.delete()
            if (!tmp.renameTo(target)) error("Datei konnte nicht gespeichert werden")
            conn.lastModified.takeIf { it > 0 }?.let { target.setLastModified(it) }
        } finally {
            conn.disconnect()
            tmp.delete()
        }
    }

    /** Version date of [url] on the server (HEAD request), or null if unknown. */
    suspend fun remoteLastModified(url: String): Long? = withContext(Dispatchers.IO) {
        val conn = open(url)
        conn.requestMethod = "HEAD"
        try {
            if (conn.responseCode == HttpURLConnection.HTTP_OK) conn.lastModified.takeIf { it > 0 } else null
        } finally {
            conn.disconnect()
        }
    }

    // Without timeouts a dropped connection (e.g. WLAN lost) would hang the download forever.
    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", userAgent)
    }
}

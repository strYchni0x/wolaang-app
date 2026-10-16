package de.wolaang.app.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Hands a GPX document to other apps via the Android share sheet. */
object GpxShare {

    fun share(context: Context, name: String, gpx: String) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        // Only one shared file at a time is needed; clear older ones.
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "${safeFileName(name)}.gpx")
        file.writeText(gpx)

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/gpx+xml"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, "Route teilen").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun safeFileName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9ÄÖÜäöüß ._-]"), "_").trim().ifEmpty { "route" }
}

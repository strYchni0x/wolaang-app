package de.wolaang.app.ui.common

import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Wraps large downloads (maps, routing data): on a metered network (mobile data) it first
 * asks; on WLAN the download starts right away. Usage: `guard("ca. 400 MB") { startDownload() }`.
 */
@Composable
fun rememberMeteredDownloadGuard(): (sizeHint: String, start: () -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    pending?.let { (sizeHint, start) ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Mobile Daten verwenden?") },
            text = {
                Text("Das Handy ist gerade nicht im WLAN. Der Download ($sizeHint) würde über mobile Daten laufen.")
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    start()
                }) { Text("Trotzdem laden") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Abbrechen") } },
        )
    }

    return remember {
        { sizeHint, start -> if (isMetered(context)) pending = sizeHint to start else start() }
    }
}

private fun isMetered(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    return cm.isActiveNetworkMetered
}

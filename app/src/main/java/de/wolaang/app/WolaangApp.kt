package de.wolaang.app

import android.app.Application
import android.content.Context
import de.wolaang.app.data.AppContainer
import org.osmdroid.config.Configuration
import java.io.File

class WolaangApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // Global osmdroid configuration. Tile cache stays in app-private storage,
        // so no external-storage permission is needed (works on API 26+).
        Configuration.getInstance().apply {
            load(this@WolaangApp, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }
    }
}

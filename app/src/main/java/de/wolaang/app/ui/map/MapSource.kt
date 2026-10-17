package de.wolaang.app.ui.map

import de.wolaang.app.data.MapTextSize
import org.osmdroid.mapsforge.MapsForgeTileProvider
import org.osmdroid.mapsforge.MapsForgeTileSource
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.views.MapView
import java.io.File

/**
 * Switches the map between the online OSM tiles and the offline Mapsforge maps.
 * With [offlineFiles] non-empty, the map is rendered on the device from those files
 * (areas outside them stay empty); otherwise the online OSM tiles are used.
 * [textSize] sets the label size. Re-applies the dark filter, since a new tile provider
 * brings a new tiles overlay.
 */
fun MapView.useMapSource(offlineFiles: List<File>, dark: Boolean, textSize: MapTextSize) {
    val provider = if (offlineFiles.isNotEmpty()) {
        val source = MapsForgeTileSource.createFromFiles(offlineFiles.toTypedArray())
        source.setUserScaleFactor(textSize.offlineScale)
        MapsForgeTileProvider(SimpleRegisterReceiver(context), source, null)
    } else {
        MapTileProviderBasic(context, TileSourceFactory.MAPNIK)
    }
    // Free the old provider's threads and caches before replacing it.
    tileProvider.detach()
    setTileProvider(provider)
    // Mapsforge already renders for the screen density; scaling its tiles again would blow
    // up text and lines. Online raster tiles need the DPI scaling (plus the extra size) to
    // keep street names readable.
    val offline = offlineFiles.isNotEmpty()
    setTilesScaledToDpi(!offline)
    setTilesScaleFactor(if (offline) 1f else textSize.onlineScale)
    setDarkTiles(dark)
}

/** Zooms to [box]; waits for the first layout if the map was just created and has no size yet. */
fun MapView.zoomToBoxWhenReady(box: org.osmdroid.util.BoundingBox) {
    if (isLayoutOccurred) {
        post { zoomToBoundingBox(box, false, 60) }
    } else {
        addOnFirstLayoutListener(object : MapView.OnFirstLayoutListener {
            override fun onFirstLayout(v: android.view.View?, left: Int, top: Int, right: Int, bottom: Int) {
                removeOnFirstLayoutListener(this)
                zoomToBoundingBox(box, false, 60)
            }
        })
    }
}

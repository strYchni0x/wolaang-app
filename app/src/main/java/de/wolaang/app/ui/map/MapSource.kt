package de.wolaang.app.ui.map

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
 * Re-applies the dark filter, since a new tile provider brings a new tiles overlay.
 */
fun MapView.useMapSource(offlineFiles: List<File>, dark: Boolean) {
    val provider = if (offlineFiles.isNotEmpty()) {
        val source = MapsForgeTileSource.createFromFiles(offlineFiles.toTypedArray())
        MapsForgeTileProvider(SimpleRegisterReceiver(context), source, null)
    } else {
        MapTileProviderBasic(context, TileSourceFactory.MAPNIK)
    }
    // Free the old provider's threads and caches before replacing it.
    tileProvider.detach()
    setTileProvider(provider)
    // Mapsforge already renders for the screen density; scaling its tiles again would blow
    // up text and lines. Online raster tiles need the DPI scaling to stay readable.
    val offline = offlineFiles.isNotEmpty()
    setTilesScaledToDpi(!offline)
    setTilesScaleFactor(if (offline) 1f else ONLINE_TILE_SCALE)
    setDarkTiles(dark)
}

/** Extra size for online raster tiles on top of DPI scaling, so street names stay readable. */
const val ONLINE_TILE_SCALE = 1.4f

package de.wolaang.app.tracking

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import de.wolaang.app.MainActivity
import de.wolaang.app.R
import de.wolaang.app.WolaangApp
import de.wolaang.app.data.GpxWriter
import de.wolaang.app.data.model.Tour
import de.wolaang.app.data.model.TourType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service that records the current GPS track using the plain Android
 * [LocationManager] (no Google Play Services). Controlled via [start] / [stop].
 */
class TrackingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var locationManager: LocationManager? = null

    private val recorder get() = (application as WolaangApp).container.trackRecorder
    private val tourDao get() = (application as WolaangApp).container.tourDao

    private val listener = LocationListener { location ->
        if (location.accuracy <= MAX_ACCURACY_M) {
            recorder.addPoint(location)
            updateNotification()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(
                intent.getStringExtra(EXTRA_TYPE)
                    ?.let { runCatching { TourType.valueOf(it) }.getOrNull() }
                    ?: TourType.HIKE
            )
            ACTION_STOP -> stopRecording()
        }
        return START_STICKY
    }

    private fun startRecording(type: TourType) {
        recorder.start(type)
        createChannel()
        startForegroundCompat()

        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationManager = lm
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, MIN_TIME_MS, MIN_DISTANCE_M, listener)
        } catch (e: SecurityException) {
            // Location permission was revoked — abort gracefully.
            stopSelf()
        }
    }

    private fun stopRecording() {
        locationManager?.removeUpdates(listener)
        locationManager = null
        val finished = recorder.stop()

        serviceScope.launch {
            if (finished.points.size >= 2) {
                val kind = if (finished.type == TourType.BIKE) "Radtour" else "Wanderung"
                val name = "$kind " + DATE_FORMAT.format(Date(finished.startedAt))
                val now = System.currentTimeMillis()
                val id = tourDao.insertTour(
                    Tour(
                        name = name,
                        type = finished.type,
                        startedAt = finished.startedAt,
                        finishedAt = now,
                        distanceMeters = finished.distanceMeters,
                        durationSeconds = (now - finished.startedAt) / 1000,
                        elevationGainMeters = finished.elevationGainMeters,
                        gpxData = GpxWriter.write(name, finished.points),
                    )
                )
                // Show the finished tour with its figures on the map.
                (application as WolaangApp).container.tourToShow.value = id
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    // --- Notification ---------------------------------------------------------

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.tracking_active))
        .setContentText(formatStats())
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        )
        .build()

    private fun formatStats(): String {
        val s = recorder.state.value
        val km = s.distanceMeters / 1000.0
        val kind = if (s.type == TourType.BIKE) "Rad" else "Wandern"
        return String.format(Locale.GERMANY, "%s · %.2f km · %d m ↑", kind, km, s.elevationGainMeters.toInt())
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Aufzeichnung",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "de.wolaang.app.action.START"
        const val ACTION_STOP = "de.wolaang.app.action.STOP"
        private const val EXTRA_TYPE = "type"

        private const val CHANNEL_ID = "tracking"
        private const val NOTIF_ID = 1
        private const val MIN_TIME_MS = 2000L
        private const val MIN_DISTANCE_M = 0f
        private const val MAX_ACCURACY_M = 50f

        private val DATE_FORMAT = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY)

        fun start(context: Context, type: TourType) {
            val intent = Intent(context, TrackingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TYPE, type.name)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TrackingService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}

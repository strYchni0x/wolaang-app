package de.wolaang.app.navigation

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Vibrates in [pattern] (off/on durations in ms, starting with a pause) as a silent hint. */
fun vibrate(context: Context, pattern: LongArray) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    } ?: return
    vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
}

/** Before a turn. */
val VIBRATE_TURN = longArrayOf(0, 250)

/** Left the route. */
val VIBRATE_OFF_ROUTE = longArrayOf(0, 200, 150, 200)

/** Arrived. */
val VIBRATE_ARRIVED = longArrayOf(0, 600)

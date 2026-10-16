package de.wolaang.app.ui.plan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable

/**
 * Round map markers for planned waypoints: green "S" = start, blue numbers = via points,
 * red "Z" = destination. Drawn in code so no image assets are needed.
 */
object WaypointIcons {

    private const val SIZE_DP = 34f
    private const val BORDER_DP = 3f

    private val START_COLOR = Color.parseColor("#2E7D32")
    private val VIA_COLOR = Color.parseColor("#1565C0")
    private val END_COLOR = Color.parseColor("#C62828")

    fun forIndex(context: Context, index: Int, count: Int): BitmapDrawable = when (index) {
        0 -> icon(context, START_COLOR, "S")
        count - 1 -> icon(context, END_COLOR, "Z")
        else -> icon(context, VIA_COLOR, index.toString())
    }

    private fun icon(context: Context, fill: Int, label: String): BitmapDrawable {
        val density = context.resources.displayMetrics.density
        val size = (SIZE_DP * density).toInt()
        val border = BORDER_DP * density
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val center = size / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = Color.WHITE
        canvas.drawCircle(center, center, center, paint)
        paint.color = fill
        canvas.drawCircle(center, center, center - border, paint)

        paint.color = Color.WHITE
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = size * 0.45f
        // Vertically center the text on its visual middle, not its baseline.
        val textY = center - (paint.descent() + paint.ascent()) / 2
        canvas.drawText(label, center, textY, paint)

        return BitmapDrawable(context.resources, bitmap)
    }
}

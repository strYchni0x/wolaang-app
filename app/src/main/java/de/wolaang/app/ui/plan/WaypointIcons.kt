package de.wolaang.app.ui.plan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pin-shaped map markers for planned waypoints (the tip marks the point): green "S" = start, blue numbers = via points,
 * red "Z" = destination. Drawn in code so no image assets are needed.
 */
object WaypointIcons {

    private const val WIDTH_DP = 32f
    private const val HEIGHT_DP = 44f
    private const val BORDER_DP = 3f

    private val START_COLOR = Color.parseColor("#2E7D32")
    private val VIA_COLOR = Color.parseColor("#1565C0")
    private val END_COLOR = Color.parseColor("#C62828")

    fun forIndex(context: Context, index: Int, count: Int): BitmapDrawable = when (index) {
        0 -> icon(context, START_COLOR, "S")
        count - 1 -> icon(context, END_COLOR, "Z")
        else -> icon(context, VIA_COLOR, index.toString())
    }

    /** Small dot with a white ring, marking the spot touched in the elevation profile. */
    fun dot(context: Context, fill: Int): BitmapDrawable {
        val density = context.resources.displayMetrics.density
        val size = (16 * density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val center = size / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        canvas.drawCircle(center, center, center, paint)
        paint.color = fill
        canvas.drawCircle(center, center, center - 3 * density, paint)
        return BitmapDrawable(context.resources, bitmap)
    }

    private fun icon(context: Context, fill: Int, label: String): BitmapDrawable {
        val density = context.resources.displayMetrics.density
        val width = (WIDTH_DP * density).toInt()
        val height = (HEIGHT_DP * density).toInt()
        val border = BORDER_DP * density
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = width / 2f
        val r = width / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // White outline first, then the colored pin slightly inset on top of it.
        paint.color = Color.WHITE
        canvas.drawPath(pinPath(cx, r, r, height.toFloat()), paint)
        paint.color = fill
        canvas.drawPath(pinPath(cx, r, r - border, height - border * 1.8f), paint)

        paint.color = Color.WHITE
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = width * 0.45f
        // Vertically center the text in the round head, not on its baseline.
        val textY = r - (paint.descent() + paint.ascent()) / 2
        canvas.drawText(label, cx, textY, paint)

        return BitmapDrawable(context.resources, bitmap)
    }

    /**
     * Teardrop pin: a circle (center [cx]/[cy], radius [radius]) joined to a tip at
     * ([cx], [tipY]) by the two tangent lines. The tip is the exact map position.
     */
    private fun pinPath(cx: Float, cy: Float, radius: Float, tipY: Float): Path {
        val d = tipY - cy
        val phi = acos(radius / d) // angle between "straight down" and a tangent point
        val dx = radius * sin(phi)
        val y = cy + radius * cos(phi)
        val tip = Path().apply {
            moveTo(cx - dx, y)
            lineTo(cx, tipY)
            lineTo(cx + dx, y)
            close()
        }
        // Union, so the overlap of circle and triangle can't cancel out by winding direction.
        return Path().apply {
            addCircle(cx, cy, radius, Path.Direction.CW)
            op(tip, Path.Op.UNION)
        }
    }
}

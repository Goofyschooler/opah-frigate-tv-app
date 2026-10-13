package app.opah.tv.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Decorative preview of the real camera-grid hierarchy; never contains private camera imagery. */
internal class NativeThemePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private var accent = NativeTheme.palette.focus
    private var backgroundColor = NativeTheme.palette.background
    private var rail = context.nativeGlassPanelBackground()

    init { contentDescription = "Current theme preview, camera grid with floating navigation" }

    fun show(accent: Int, background: Int) {
        this.accent = accent
        backgroundColor = background
        rail = context.nativeGlassPanelBackground()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val unit = height / 142f
        val radius = unit * if (NativeTheme.subtleRoundedCorners) 11f else 6f
        paint.style = Paint.Style.FILL
        paint.color = backgroundColor
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, paint)
        val left = 49f * unit
        val top = 16f * unit
        val gap = 8f * unit
        val tileWidth = ((width - left - 14f * unit - gap) / 2f).coerceAtLeast(1f)
        val tileHeight = (height - top * 2 - gap) / 2f
        repeat(4) { index ->
            val x = left + (index % 2) * (tileWidth + gap)
            val y = top + (index / 2) * (tileHeight + gap)
            bounds.set(x, y, x + tileWidth, y + tileHeight)
            paint.color = NativeTheme.palette.panelSelected
            canvas.drawRoundRect(bounds, radius * 0.7f, radius * 0.7f, paint)
            // A simple landscape silhouette reads as imagery without suggesting a real camera.
            paint.color = NativeTheme.palette.divider
            canvas.drawCircle(x + tileWidth * 0.72f, y + tileHeight * 0.34f, 7f * unit, paint)
            canvas.drawRoundRect(x + 9f * unit, y + tileHeight * 0.48f, x + tileWidth * 0.62f, y + tileHeight * 0.69f, 4f * unit, 4f * unit, paint)
            paint.color = NativeTheme.palette.secondaryText
            canvas.drawRoundRect(x + 8f * unit, bounds.bottom - 11f * unit, x + tileWidth * 0.45f, bounds.bottom - 8f * unit, 2f * unit, 2f * unit, paint)
            if (index == 0) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f * unit
                paint.color = accent
                canvas.drawRoundRect(bounds, radius * 0.7f, radius * 0.7f, paint)
                paint.style = Paint.Style.FILL
            }
        }
        rail.setBounds((10f * unit).toInt(), (12f * unit).toInt(), (37f * unit).toInt(), (height - 12f * unit).toInt())
        rail.draw(canvas)
        repeat(4) { index ->
            paint.color = if (index == 0) accent else NativeTheme.palette.secondaryText
            canvas.drawCircle(23.5f * unit, (33f + index * 24f) * unit, 3.4f * unit, paint)
        }
    }
}

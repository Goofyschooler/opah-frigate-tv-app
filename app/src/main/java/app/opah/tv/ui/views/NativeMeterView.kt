package app.opah.tv.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** A compact, non-interactive bar for proportional information such as storage use. */
internal class NativeMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var segments: List<NativeMeterSegment> = emptyList()

    fun show(updated: List<NativeMeterSegment>) {
        segments = updated.filter { it.amount.isFinite() && it.amount > 0.0 }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = NativeTheme.palette.panelSelected
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        val total = segments.sumOf(NativeMeterSegment::amount).takeIf { it > 0.0 } ?: return
        var left = 0f
        segments.forEachIndexed { index, segment ->
            val right = if (index == segments.lastIndex) {
                width.toFloat()
            } else {
                left + (width * (segment.amount / total)).toFloat()
            }
            paint.color = segment.color
            canvas.drawRect(left, 0f, right.coerceAtMost(width.toFloat()), height.toFloat(), paint)
            left = right
        }
    }
}

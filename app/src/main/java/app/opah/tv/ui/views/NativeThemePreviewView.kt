package app.opah.tv.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** A live, non-interactive preview of Opah's compact presentation language. */
internal class NativeThemePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var accent = NativeTheme.palette.focus
    private var backgroundColor = NativeTheme.palette.background

    fun show(accent: Int, background: Int) {
        this.accent = accent
        backgroundColor = background
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat()
        val height = height.toFloat()
        paint.style = Paint.Style.FILL
        paint.color = backgroundColor
        canvas.drawRect(0f, 0f, width, height, paint)

        val railWidth = context.dp(48).toFloat()
        paint.color = NativeTheme.palette.panel
        canvas.drawRect(0f, 0f, railWidth, height, paint)
        repeat(4) { index ->
            paint.color = if (index == 1) accent else NativeTheme.palette.secondaryText
            canvas.drawCircle(
                railWidth / 2f,
                context.dp(28 + (index * 27)).toFloat(),
                context.dp(if (index == 1) 6 else 4).toFloat(),
                paint,
            )
        }

        val left = railWidth + context.dp(18)
        paint.color = NativeTheme.palette.text
        canvas.drawRect(left, context.dp(18).toFloat(), left + context.dp(132), context.dp(24).toFloat(), paint)
        paint.color = NativeTheme.palette.secondaryText
        canvas.drawRect(left, context.dp(31).toFloat(), left + context.dp(205), context.dp(34).toFloat(), paint)

        val rowTop = context.dp(51)
        repeat(3) { index ->
            val top = rowTop + context.dp(index * 29)
            paint.color = if (index == 0) NativeTheme.palette.panelSelected else Color.TRANSPARENT
            val radius = if (NativeTheme.subtleRoundedCorners) context.dp(4).toFloat() else 0f
            canvas.drawRoundRect(
                left - context.dp(6),
                top.toFloat(),
                width - context.dp(14),
                (top + context.dp(24)).toFloat(),
                radius,
                radius,
                paint,
            )
            if (index == 0) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = context.dp(2).toFloat()
                paint.color = accent
                canvas.drawRoundRect(
                    left - context.dp(6),
                    top.toFloat(),
                    width - context.dp(14),
                    (top + context.dp(24)).toFloat(),
                    radius,
                    radius,
                    paint,
                )
                paint.style = Paint.Style.FILL
            }
            paint.color = if (index == 0) accent else NativeTheme.palette.text
            canvas.drawRect(left, (top + context.dp(7)).toFloat(), left + context.dp(92 + index * 26), (top + context.dp(11)).toFloat(), paint)
            paint.color = NativeTheme.palette.divider
            canvas.drawRect(left, (top + context.dp(23)).toFloat(), width - context.dp(14), (top + context.dp(24)).toFloat(), paint)
        }
    }
}

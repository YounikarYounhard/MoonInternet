package cc.moon.internet.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.view.View

/**
 * Aiming frame over the camera: everything outside a rounded square is dimmed, the square gets
 * accent corner brackets and a hint underneath. Without it the scanner is just a camera and the
 * user has nothing to point at.
 */
class ScannerOverlay(context: Context) : View(context) {

    private val dim = Paint().apply { color = Color.parseColor("#B3000000") }
    private val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val bracket = Paint().apply {
        color = Color.parseColor("#FF9D7BFF")
        style = Paint.Style.STROKE
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }
    private val hint = Paint().apply {
        color = Color.parseColor("#FFECE9F5")
        textSize = 42f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    init {
        // the CLEAR xfermode needs its own layer
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = minOf(width, height) * 0.68f
        val left = (width - side) / 2f
        val top = (height - side) / 2f
        val box = RectF(left, top, left + side, top + side)
        val r = 40f

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        canvas.drawRoundRect(box, r, r, clear)

        val arm = side * 0.14f
        val p = Path()
        // top-left
        p.moveTo(box.left, box.top + arm); p.lineTo(box.left, box.top + r)
        p.quadTo(box.left, box.top, box.left + r, box.top); p.lineTo(box.left + arm, box.top)
        // top-right
        p.moveTo(box.right - arm, box.top); p.lineTo(box.right - r, box.top)
        p.quadTo(box.right, box.top, box.right, box.top + r); p.lineTo(box.right, box.top + arm)
        // bottom-right
        p.moveTo(box.right, box.bottom - arm); p.lineTo(box.right, box.bottom - r)
        p.quadTo(box.right, box.bottom, box.right - r, box.bottom); p.lineTo(box.right - arm, box.bottom)
        // bottom-left
        p.moveTo(box.left + arm, box.bottom); p.lineTo(box.left + r, box.bottom)
        p.quadTo(box.left, box.bottom, box.left, box.bottom - r); p.lineTo(box.left, box.bottom - arm)
        canvas.drawPath(p, bracket)

        canvas.drawText("Наведите камеру на QR-код", width / 2f, box.bottom + 90f, hint)
    }
}

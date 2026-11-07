package tn.eaba.dronedetect

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var results: List<Detection> = emptyList()
    private var srcW = 1
    private var srcH = 1

    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 38f
        isAntiAlias = true
    }
    private val bgPaint = Paint().apply { color = Color.argb(200, 0, 0, 0) }

    private fun colorFor(id: Int): Int = when (id % 4) {
        0 -> Color.rgb(80, 220, 120)
        1 -> Color.rgb(255, 70, 70)
        2 -> Color.rgb(255, 170, 40)
        else -> Color.rgb(90, 170, 255)
    }

    fun setResults(detections: List<Detection>, sourceWidth: Int, sourceHeight: Int) {
        results = detections
        srcW = sourceWidth
        srcH = sourceHeight
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (results.isEmpty()) return

        val scale = min(width.toFloat() / srcW, height.toFloat() / srcH)
        val offX = (width - srcW * scale) / 2f
        val offY = (height - srcH * scale) / 2f

        for (d in results) {
            boxPaint.color = colorFor(d.classId)
            val l = d.box.left * scale + offX
            val t = d.box.top * scale + offY
            val r = d.box.right * scale + offX
            val b = d.box.bottom * scale + offY
            canvas.drawRect(l, t, r, b, boxPaint)

            val text = "${d.label} ${(d.score * 100).toInt()}%"
            val w = textPaint.measureText(text)
            canvas.drawRect(l, t - 48f, l + w + 16f, t, bgPaint)
            canvas.drawText(text, l + 8f, t - 12f, textPaint)
        }
    }
}
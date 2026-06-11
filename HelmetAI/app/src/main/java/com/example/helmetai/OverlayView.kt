package com.example.helmetai

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val labelPaint = Paint().apply {
        color = Color.WHITE
        textSize = 36f
        style = Paint.Style.FILL
        isFakeBoldText = true
    }

    private val labelBgPaint = Paint().apply {
        style = Paint.Style.FILL
    }

    private val targetPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    private var results: List<DetectionResult> = listOf()
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1

    fun setResults(results: List<DetectionResult>, imageWidth: Int, imageHeight: Int) {
        this.results = results
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        drawTargetReticle(canvas)

        val scaleX = width.toFloat() / imageWidth.toFloat()
        val scaleY = height.toFloat() / imageHeight.toFloat()
        val scale = maxOf(scaleX, scaleY)

        val scaledWidth = imageWidth * scale
        val scaledHeight = imageHeight * scale
        
        val offsetX = (width - scaledWidth) / 2f
        val offsetY = (height - scaledHeight) / 2f

        results.forEach { result ->
            val color = when (result.label) {
                "WITH_HELMET", "normal", "with helmet", "rider" -> Color.GREEN
                "number plate" -> Color.BLUE
                else -> Color.RED
            }
            
            boxPaint.color = color
            labelBgPaint.color = color

            val rect = RectF(
                result.rect.left * scaledWidth + offsetX,
                result.rect.top * scaledHeight + offsetY,
                result.rect.right * scaledWidth + offsetX,
                result.rect.bottom * scaledHeight + offsetY
            )
            canvas.drawRect(rect, boxPaint)

            val confidencePercent = (result.confidence * 100).toInt().coerceAtMost(100)
            val labelText = "${result.label} $confidencePercent%"
            
            val textWidth = labelPaint.measureText(labelText)
            val textHeight = 40f
            
            canvas.drawRect(rect.left, rect.top - textHeight - 10f, rect.left + textWidth + 20f, rect.top, labelBgPaint)
            
            canvas.drawText(labelText, rect.left + 10f, rect.top - 15f, labelPaint)
        }
    }

    private fun drawTargetReticle(canvas: Canvas) {
        val centerX = width / 2f
        val centerY = height / 2f
        val size = 200f
        val cornerLen = 50f
        
        canvas.drawLine(centerX - size, centerY - size, centerX - size + cornerLen, centerY - size, targetPaint)
        canvas.drawLine(centerX - size, centerY - size, centerX - size, centerY - size + cornerLen, targetPaint)
        
        canvas.drawLine(centerX + size, centerY - size, centerX + size - cornerLen, centerY - size, targetPaint)
        canvas.drawLine(centerX + size, centerY - size, centerX + size, centerY - size + cornerLen, targetPaint)
        
        canvas.drawLine(centerX - size, centerY + size, centerX - size + cornerLen, centerY + size, targetPaint)
        canvas.drawLine(centerX - size, centerY + size, centerX - size, centerY + size - cornerLen, targetPaint)
        
        canvas.drawLine(centerX + size, centerY + size, centerX + size - cornerLen, centerY + size, targetPaint)
        canvas.drawLine(centerX + size, centerY + size, centerX + size, centerY + size - cornerLen, targetPaint)
    }
}

data class DetectionResult(val rect: RectF, val label: String, val confidence: Float)

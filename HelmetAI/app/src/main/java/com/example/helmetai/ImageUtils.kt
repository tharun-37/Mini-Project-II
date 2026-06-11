package com.example.helmetai

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

fun ImageProxy.toBitmap(): Bitmap {
    val yBuffer = planes[0].buffer
    val uBuffer = planes[1].buffer
    val vBuffer = planes[2].buffer

    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()

    val nv21 = ByteArray(ySize + uSize + vSize)

    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)

    val yuvImage = YuvImage(nv21, ImageFormat.NV21, this.width, this.height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 100, out)
    val imageBytes = out.toByteArray()
    return android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
}

fun Bitmap.crop(rect: RectF): Bitmap {
    val left = (rect.left * width).toInt().coerceIn(0, width)
    val top = (rect.top * height).toInt().coerceIn(0, height)
    val right = (rect.right * width).toInt().coerceIn(0, width)
    val bottom = (rect.bottom * height).toInt().coerceIn(0, height)
    
    val cropWidth = (right - left).coerceAtLeast(1)
    val cropHeight = (bottom - top).coerceAtLeast(1)
    
    return Bitmap.createBitmap(this, left, top, cropWidth, cropHeight)
}

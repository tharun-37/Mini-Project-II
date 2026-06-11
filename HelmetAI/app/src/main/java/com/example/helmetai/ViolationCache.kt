package com.example.helmetai

import android.graphics.Bitmap

object ViolationCache {
    var plateNumber: String = ""
    var violationType: String = ""
    
    var violationBitmap: Bitmap? = null
    var plateBitmap: Bitmap? = null
    var plateCropBitmap: Bitmap? = null
    
    fun clear() {
        plateNumber = ""
        violationType = ""
        violationBitmap = null
        plateBitmap = null
        plateCropBitmap = null
    }
}

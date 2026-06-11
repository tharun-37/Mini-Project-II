package com.example.helmetai

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.helmetai.databinding.ActivityReportBinding
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException

class ReportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReportBinding
    private val okHttpClient = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        setupListeners()
    }

    private fun setupUI() {
        binding.tvPlateNumber.text = "Plate Number: ${ViolationCache.plateNumber}"
        binding.tvViolationType.text = "Violation Detected: ${ViolationCache.violationType}"

        // Display all three distinct evidence sets to the user
        ViolationCache.violationBitmap?.let {
            binding.ivEvidenceViolation.setImageBitmap(it)
        }
        ViolationCache.plateBitmap?.let {
            binding.ivEvidencePlate.setImageBitmap(it)
        }
        ViolationCache.plateCropBitmap?.let {
            binding.ivPlateCrop.setImageBitmap(it)
        }
    }

    private fun setupListeners() {
        binding.btnDiscard.setOnClickListener {
            ViolationCache.clear()
            finish()
        }

        binding.btnSubmit.setOnClickListener {
            submitReport()
        }
    }

    private fun submitReport() {
        val violationBmp = ViolationCache.violationBitmap
        val plateBmp = ViolationCache.plateBitmap
        val cropBmp = ViolationCache.plateCropBitmap

        if (violationBmp == null || plateBmp == null || cropBmp == null) {
            Toast.makeText(this, "Evidence images are missing!", Toast.LENGTH_SHORT).show()
            return
        }

        val requestBodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("plate_number", ViolationCache.plateNumber)
            .addFormDataPart("violation_type", ViolationCache.violationType)

        // Compress and attach all 3 distinct evidence images to the backend multipart request
        val imageList = listOf(violationBmp, plateBmp, cropBmp)
        val imageNames = listOf("violation_frame.jpg", "plate_frame.jpg", "plate_crop.jpg")

        for (i in 0 until 3) {
            val bmp = imageList[i]
            val stream = ByteArrayOutputStream()
            
            val byteArray = if (i == 2) {
                // Do NOT compress or scale down the cropped license plate image to preserve raw original crop details!
                bmp.compress(Bitmap.CompressFormat.JPEG, 100, stream)
                stream.toByteArray()
            } else {
                // Compress full frames for quick upload over local network
                val scaledBitmap = Bitmap.createScaledBitmap(bmp, bmp.width / 2, bmp.height / 2, true)
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream)
                stream.toByteArray()
            }

            requestBodyBuilder.addFormDataPart(
                "image${i + 1}",
                "violation_${ViolationCache.plateNumber}_${imageNames[i]}",
                byteArray.toRequestBody("image/jpeg".toMediaTypeOrNull())
            )
        }

        val request = Request.Builder()
            .url("http://10.62.196.17:5000/api/report")
            .post(requestBodyBuilder.build())
            .build()

        Toast.makeText(this, "Uploading 3 custom evidence files to server...", Toast.LENGTH_SHORT).show()

        okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("ReportActivity", "Server upload failed", e)
                runOnUiThread {
                    Toast.makeText(this@ReportActivity, "Upload failed! Check network connection.", Toast.LENGTH_LONG).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string() ?: ""
                response.close()
                
                runOnUiThread {
                    if (response.isSuccessful) {
                        Toast.makeText(this@ReportActivity, "Violation submitted successfully!", Toast.LENGTH_SHORT).show()
                        ViolationCache.clear()
                        finish()
                    } else {
                        Log.e("ReportActivity", "Server error: $responseBody")
                        Toast.makeText(this@ReportActivity, "Server Error: ${response.code}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }
}

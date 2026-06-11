package com.example.helmetai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.helmetai.databinding.ActivityMainBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var objectDetector: ObjectDetector? = null
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private const val SLIDING_WINDOW_MS = 10000 // 4 seconds detection window
    }

    @Volatile private var isCapturing = false

    // Sliding window states for asynchronous detection matching
    private var lastViolationType: String = "UNKNOWN"
    private var lastViolationTimestamp: Long = 0L
    private var lastViolationBitmap: Bitmap? = null
    private val recentViolations = java.util.concurrent.ConcurrentHashMap<String, Long>()
    
    private var lastPlateNumber: String = ""
    private var lastPlateTimestamp: Long = 0L
    private var lastPlateBitmap: Bitmap? = null
    private var lastPlateCrop: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
        
        try {
            objectDetector = ObjectDetector(this)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error initializing detector", e)
            Toast.makeText(this, "Error initializing detector: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // Reset scanning state and cached matching histories
        isCapturing = false
        ViolationCache.clear()
        binding.captureOverlay.visibility = View.GONE
        
        lastViolationType = "UNKNOWN"
        lastViolationTimestamp = 0L
        lastViolationBitmap = null
        lastPlateNumber = ""
        lastPlateTimestamp = 0L
        lastPlateBitmap = null
        lastPlateCrop = null
        recentViolations.clear()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        val bitmap = imageProxy.toBitmap()
                        val primaryResults = objectDetector?.detect(bitmap) ?: listOf()
                        val anprResults = objectDetector?.detectAnpr(bitmap) ?: listOf()
                        
                        val allResults = primaryResults + anprResults

                        if (isCapturing) {
                            imageProxy.close()
                            return@setAnalyzer
                        }

                        // 1. Extract and update all unique violation classes found in the frame
                        val currentTime = System.currentTimeMillis()
                        
                        // Clean up recent violations history older than 4 seconds
                        recentViolations.entries.removeIf { currentTime - it.value > SLIDING_WINDOW_MS }
                        
                        var frameHasViolation = false
                        primaryResults.forEach { result ->
                            val label = result.label.uppercase()
                            if (label != "NORMAL" && label != "RIDER") {
                                recentViolations[label] = currentTime
                                frameHasViolation = true
                            }
                        }

                        if (frameHasViolation) {
                            lastViolationBitmap = bitmap // Store frame containing violation
                        }

                        val currentViolation = if (recentViolations.isNotEmpty()) {
                            recentViolations.keys.joinToString(", ")
                        } else {
                            null
                        }

                        if (currentViolation != null) {
                            lastViolationType = currentViolation
                            lastViolationTimestamp = recentViolations.values.maxOrNull() ?: currentTime
                        }

                        // 2. Process plate detection and trigger asynchronously if a match occurs
                        anprResults.forEach { result ->
                            if (result.label == "number plate") {
                                val plateBitmap = bitmap.crop(result.rect)
                                performOcr(plateBitmap, bitmap)
                            }
                        }

                        // 3. Double-check: What if the violation is detected shortly AFTER a plate is OCR'd?
                        val hasRecentPlate = lastPlateNumber.isNotEmpty() && (currentTime - lastPlateTimestamp < SLIDING_WINDOW_MS)
                        
                        if (currentViolation != null && hasRecentPlate && !isCapturing && ViolationCache.violationBitmap == null) {
                            isCapturing = true
                            
                            ViolationCache.plateNumber = lastPlateNumber
                            ViolationCache.violationType = currentViolation
                            ViolationCache.violationBitmap = bitmap // Current frame has the violation
                            ViolationCache.plateBitmap = lastPlateBitmap // Cached plate full frame
                            ViolationCache.plateCropBitmap = lastPlateCrop // Cached plate crop
                            
                            Log.i("MainActivity", "TRIGGERED: Violation detected after recent plate OCR")
                            
                            runOnUiThread {
                                binding.captureOverlay.visibility = View.VISIBLE
                            }
                            
                            binding.root.postDelayed({
                                launchReportActivity()
                            }, 2000)
                        }

                        runOnUiThread {
                            binding.overlayView.setResults(allResults, bitmap.width, bitmap.height)
                        }
                        imageProxy.close()
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            } catch (exc: Exception) {
                Log.e("MainActivity", "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun performOcr(plateBitmap: Bitmap, fullBitmap: Bitmap) {
        val image = InputImage.fromBitmap(plateBitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val fullText = visionText.text.replace("\n", " ").uppercase().replace(" ", "").trim()
                Log.d("MainActivity", "OCR Raw Result: $fullText")
                
                if (fullText.length >= 5) {
                    // Update recent plate memory
                    lastPlateNumber = fullText
                    lastPlateTimestamp = System.currentTimeMillis()
                    lastPlateBitmap = fullBitmap
                    lastPlateCrop = plateBitmap
                    
                    // Check if we have a matching recent violation
                    val currentTime = System.currentTimeMillis()
                    val hasRecentViolation = lastViolationType != "UNKNOWN" && (currentTime - lastViolationTimestamp < SLIDING_WINDOW_MS)
                    
                    if (hasRecentViolation && !isCapturing && ViolationCache.violationBitmap == null) {
                        isCapturing = true
                        
                        ViolationCache.plateNumber = fullText
                        ViolationCache.violationType = lastViolationType
                        ViolationCache.violationBitmap = lastViolationBitmap // Cached violation frame
                        ViolationCache.plateBitmap = fullBitmap // Current frame is plate full frame
                        ViolationCache.plateCropBitmap = plateBitmap // Current crop is plate crop
                        
                        Log.i("MainActivity", "TRIGGERED: Plate OCR matched recent violation")
                        
                        runOnUiThread {
                            binding.captureOverlay.visibility = View.VISIBLE
                        }

                        // Transition to ReportActivity after 2 seconds
                        binding.root.postDelayed({
                            launchReportActivity()
                        }, 2000)
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e("MainActivity", "OCR Failed", e)
            }
    }

    private fun launchReportActivity() {
        val intent = Intent(this, ReportActivity::class.java)
        startActivity(intent)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        recognizer.close()
    }
}

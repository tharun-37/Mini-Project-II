package com.example.helmetai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Collections

class ObjectDetector(private val context: Context) {

    private var ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var violationSession: OrtSession? = null
    private var anprSession: OrtSession? = null
    
    private var violationLabels: List<String> = listOf()
    private var anprLabels: List<String> = listOf()

    init {
        loadModels()
        loadLabels()
    }

    private fun loadModels() {
        try {
            val options = OrtSession.SessionOptions()
            // NNAPI removed due to hardware compatibility issues causing black screen.
            // Using CPU Multithreading for performance instead.
            options.setIntraOpNumThreads(4)

            Log.d("ObjectDetector", "Loading violation model...")
            val violationModelBytes = context.assets.open("model.onnx").readBytes()
            violationSession = ortEnv.createSession(violationModelBytes, options)
            Log.d("ObjectDetector", "Violation model loaded successfully")
            
            Log.d("ObjectDetector", "Loading ANPR model...")
            val anprModelBytes = context.assets.open("anpr.onnx").readBytes()
            anprSession = ortEnv.createSession(anprModelBytes, options)
            Log.d("ObjectDetector", "ANPR model loaded successfully")
        } catch (e: Exception) {
            Log.e("ObjectDetector", "Failed to load models", e)
            throw e
        }
    }

    private fun loadLabels() {
        try {
            Log.d("ObjectDetector", "Loading labels...")
            violationLabels = context.assets.open("labels.txt").bufferedReader().readLines()
            Log.d("ObjectDetector", "Labels loaded successfully")
            
            Log.d("ObjectDetector", "Loading ANPR labels...")
            anprLabels = context.assets.open("anpr_labels.txt").bufferedReader().readLines()
            Log.d("ObjectDetector", "ANPR labels loaded successfully")
        } catch (e: Exception) {
            Log.e("ObjectDetector", "Failed to load labels", e)
            throw e
        }
    }

    fun detectViolations(bitmap: Bitmap): List<DetectionResult> {
        val imgSize = 640
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, imgSize, imgSize, true)
        val imgData = bitmapToFloatBuffer(resizedBitmap, imgSize)

        val inputName = violationSession?.inputNames?.firstOrNull() ?: return listOf()
        val inputTensor = OnnxTensor.createTensor(ortEnv, imgData, longArrayOf(1, 3, imgSize.toLong(), imgSize.toLong()))

        val results = violationSession?.run(Collections.singletonMap(inputName, inputTensor))
        val rawOutput = results?.get(0)?.value 
        
        if (rawOutput is Array<*> && rawOutput[0] is Array<*>) {
            val output = rawOutput[0] as Array<FloatArray>
            return processNmsOutput(output, violationLabels)
        }
        
        return listOf()
    }

    fun detectAnpr(bitmap: Bitmap): List<DetectionResult> {
        val imgSize = 320
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, imgSize, imgSize, true)
        val imgData = bitmapToFloatBuffer(resizedBitmap, imgSize)

        val inputName = anprSession?.inputNames?.firstOrNull() ?: return listOf()
        val inputTensor = OnnxTensor.createTensor(ortEnv, imgData, longArrayOf(1, 3, imgSize.toLong(), imgSize.toLong()))

        val results = anprSession?.run(Collections.singletonMap(inputName, inputTensor))
        val rawOutput = results?.get(0)?.value 
        
        if (rawOutput is Array<*> && rawOutput[0] is Array<*>) {
            val output = rawOutput[0] as Array<FloatArray>
            Log.d("ObjectDetector", "ANPR Output shape: [${output.size}, ${output[0].size}]")
            return processYoloV8Output(output, anprLabels, imgSize)
        }
        
        return listOf()
    }

    private fun bitmapToFloatBuffer(bitmap: Bitmap, size: Int): FloatBuffer {
        val buffer = ByteBuffer.allocateDirect(1 * 3 * size * size * 4)
        buffer.order(ByteOrder.nativeOrder())
        val floatBuffer = buffer.asFloatBuffer()

        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)

        for (i in 0 until size * size) {
            val pixel = pixels[i]
            floatBuffer.put(i, ((pixel shr 16) and 0xFF) / 255.0f)
            floatBuffer.put(i + size * size, ((pixel shr 8) and 0xFF) / 255.0f)
            floatBuffer.put(i + 2 * size * size, (pixel and 0xFF) / 255.0f)
        }
        return floatBuffer
    }

    private fun processNmsOutput(output: Array<FloatArray>, labels: List<String>): List<DetectionResult> {
        val detections = mutableListOf<DetectionResult>()
        for (i in output.indices) {
            val confidence = output[i][4]
            val classId = output[i][5].toInt()
            
            if (confidence > 0.45f) {
                val x1 = output[i][0] / 640f
                val y1 = output[i][1] / 640f
                val x2 = output[i][2] / 640f
                val y2 = output[i][3] / 640f
                
                val rect = RectF(x1, y1, x2, y2)
                detections.add(DetectionResult(rect, labels.getOrNull(classId) ?: "Unknown", confidence))
            }
        }
        return detections
    }

    private fun processYoloV8Output(output: Array<FloatArray>, labels: List<String>, imgSize: Int): List<DetectionResult> {
        val detections = mutableListOf<DetectionResult>()
        val numClasses = labels.size
        val numPredictions = output[0].size
        
        for (i in 0 until numPredictions) {
            var maxProb = 0f
            var classId = -1
            
            for (c in 0 until numClasses) {
                val prob = output[4 + c][i]
                if (prob > maxProb) {
                    maxProb = prob
                    classId = c
                }
            }
            
            if (maxProb > 0.45f) {
                val cx = output[0][i]
                val cy = output[1][i]
                val w = output[2][i]
                val h = output[3][i]
                
                val x1 = (cx - w / 2f) / imgSize
                val y1 = (cy - h / 2f) / imgSize
                val x2 = (cx + w / 2f) / imgSize
                val y2 = (cy + h / 2f) / imgSize
                
                detections.add(DetectionResult(RectF(x1, y1, x2, y2), labels[classId], maxProb))
            }
        }
        
        return nms(detections)
    }

    private fun nms(detections: List<DetectionResult>): List<DetectionResult> {
        val sorted = detections.sortedByDescending { it.confidence }.toMutableList()
        val selected = mutableListOf<DetectionResult>()

        while (sorted.isNotEmpty()) {
            val first = sorted.removeAt(0)
            selected.add(first)
            val iterator = sorted.iterator()
            while (iterator.hasNext()) {
                val next = iterator.next()
                if (iou(first.rect, next.rect) > 0.45f) {
                    iterator.remove()
                }
            }
        }
        return selected
    }

    private fun iou(a: RectF, b: RectF): Float {
        val intersection = RectF()
        if (!intersection.setIntersect(a, b)) return 0f
        val interArea = intersection.width() * intersection.height()
        val unionArea = (a.width() * a.height()) + (b.width() * b.height()) - interArea
        return interArea / unionArea
    }

    fun detect(bitmap: Bitmap): List<DetectionResult> = detectViolations(bitmap)
}

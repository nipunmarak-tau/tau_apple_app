package com.example.cameraaccess.processing

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Rect
import android.media.Image
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate

data class DetectionResult(
    val confidenceScore: Float,
    val status: String,
    val boundingBoxes: List<Rect>,
    val tfliteDelegate: TfliteDelegate = TfliteDelegate.UNKNOWN,
    val objectLuma: Int = 0,
    /** Mean Y (luma) over the same analysis grid—used for AE when green is not identified. */
    val avgFrameLuma: Int = 0,
    val displayBoundingBox: Rect? = null,
    /** When > 0, overlay maps boxes from this analyzer frame size (e.g. 720p pipeline). */
    val frameWidth: Int = 0,
    val frameHeight: Int = 0
)

enum class TfliteDelegate {
    UNKNOWN,
    GPU,
    NNAPI,
    CPU
}

class KiwiDetectionProcessor(
    private val context: Context
) {
    private val TAG = "GreenDetection"
    private val detectionThreshold = 0.25f
    private val nmsIouThreshold = 0.50f
    private val kiwiClassIndex = 1
    @Volatile
    private var activeDelegate: TfliteDelegate = TfliteDelegate.UNKNOWN

    private val interpreter: Interpreter by lazy {
        val options = Interpreter.Options()
        val compatList = CompatibilityList()

        if (compatList.isDelegateSupportedOnThisDevice) {
            // If the device has a supported GPU, add the GPU delegate
            val delegateOptions = compatList.bestOptionsForThisDevice
            options.addDelegate(GpuDelegate(delegateOptions))
            activeDelegate = TfliteDelegate.GPU
            Log.i(TAG, "TFLite: Using GPU Delegate")
        } else {
            // Try NNAPI (Android Neural Networks API) if GPU is not supported
            try {
                options.addDelegate(NnApiDelegate())
                activeDelegate = TfliteDelegate.NNAPI
                Log.i(TAG, "TFLite: Using NNAPI Delegate")
            } catch (e: Exception) {
                // Fallback to CPU with multiple threads
                options.setNumThreads(4)
                activeDelegate = TfliteDelegate.CPU
                Log.i(TAG, "TFLite: Falling back to CPU (4 threads)")
            }
        }

        Interpreter(loadModelFile("model-hdr-320.tflite"), options)
    }

    /**
     * Eagerly initialises the TFLite interpreter and warms up the GPU delegate.
     *
     * On first install the GPU driver must compile GLSL shaders from scratch (1–3 s).
     * Call this once on a background thread during preview so that compilation is
     * finished before recording starts and no longer competes with the video encoder.
     */
    fun warmUp() {
        try {
            val inputShape = interpreter.getInputTensor(0).shape()
            val inputH = inputShape.getOrNull(1) ?: 320
            val inputW = inputShape.getOrNull(2) ?: 320
            val inType = interpreter.getInputTensor(0).dataType()
            val bytesPerChannel = if (inType == DataType.UINT8) 1 else 4
            // Run one dummy inference with a zeroed buffer so the GPU driver compiles and
            // caches all shaders now, not during the first real recording frame.
            val dummyInput = ByteBuffer
                .allocateDirect(inputW * inputH * 3 * bytesPerChannel)
                .order(ByteOrder.nativeOrder())
            val outputShape = interpreter.getOutputTensor(0).shape()
            val d1 = outputShape.getOrNull(1) ?: 1
            val d2 = outputShape.getOrNull(2) ?: 1
            val dummyOutput = Array(1) { Array(d1) { FloatArray(d2) } }
            interpreter.run(dummyInput, dummyOutput)
            Log.i(TAG, "TFLite warm-up complete (delegate=$activeDelegate)")
        } catch (e: Exception) {
            Log.w(TAG, "TFLite warm-up failed (non-fatal)", e)
        }
    }

    /**
     * Full-frame luma only (same Y sampling as [processImage]); no model inference.
     * Used for non–Fruit scan types so exposure meters the scene without running kiwi detection.
     */
    fun processImageFrameLumaOnly(image: Image): DetectionResult {
        val width = image.width
        val height = image.height
        val frameLuma = computeFrameLuma(image)
        return DetectionResult(
            confidenceScore = 0f,
            status = "Searching",
            boundingBoxes = emptyList(),
            tfliteDelegate = activeDelegate,
            objectLuma = 0,
            avgFrameLuma = frameLuma,
            frameWidth = width,
            frameHeight = height
        )
    }

    fun processImage(image: Image): DetectionResult {
        val width = image.width
        val height = image.height

        val frameLuma = computeFrameLuma(image)
        
        val input = createModelInput(image)
        val predictions = runKiwiInference(input, width, height)

        if (predictions.isEmpty()) {
            return DetectionResult(
                confidenceScore = 0f,
                status = "Searching",
                boundingBoxes = emptyList(),
                tfliteDelegate = activeDelegate,
                objectLuma = 0,
                avgFrameLuma = frameLuma,
                frameWidth = width,
                frameHeight = height
            )
        }

        val clampedBoxes = predictions.mapNotNull { pred ->
            val box = Rect(
                pred.box.left.coerceIn(0, width),
                pred.box.top.coerceIn(0, height),
                pred.box.right.coerceIn(0, width),
                pred.box.bottom.coerceIn(0, height)
            )
            if (box.width() > 0 && box.height() > 0) box else null
        }
        if (clampedBoxes.isEmpty()) {
            return DetectionResult(
                confidenceScore = 0f,
                status = "Searching",
                boundingBoxes = emptyList(),
                tfliteDelegate = activeDelegate,
                objectLuma = 0,
                avgFrameLuma = frameLuma,
                frameWidth = width,
                frameHeight = height
            )
        }

        // Meter the *region around* each detected kiwi (bounding box inflated by 25% on
        // each side). This captures the kiwi plus its immediate surroundings and keeps the
        // exposure target localised to the subject, not the full frame.
        var lumaSum = 0L
        var lumaCount = 0
        for (box in clampedBoxes) {
            val luma = computeBoxLuma(image, inflateRect(box, 0.25f, width, height))
            if (luma > 0) {
                lumaSum += luma
                lumaCount++
            }
        }
        val subjectLuma = if (lumaCount > 0) (lumaSum / lumaCount).toInt() else frameLuma
        val bestConfidence = predictions.maxOfOrNull { it.confidence } ?: 0f

        return DetectionResult(
            confidenceScore = bestConfidence * 100f,
            status = "Identified",
            boundingBoxes = clampedBoxes,
            tfliteDelegate = activeDelegate,
            objectLuma = subjectLuma,
            avgFrameLuma = frameLuma,
            frameWidth = width,
            frameHeight = height
        )
    }

    /**
     * Efficiently updates luma metrics for a new frame using existing detection results.
     * This allows the exposure loop to run at a higher frequency (e.g. 30 FPS) than 
     * the expensive model inference (e.g. 15 FPS).
     */
    fun updateLumaOnly(image: Image, lastResult: DetectionResult?): DetectionResult {
        val width = image.width
        val height = image.height
        val frameLuma = computeFrameLuma(image)
        
        if (lastResult == null || lastResult.boundingBoxes.isEmpty()) {
            return DetectionResult(
                confidenceScore = 0f,
                status = "Searching",
                boundingBoxes = emptyList(),
                tfliteDelegate = activeDelegate,
                objectLuma = 0,
                avgFrameLuma = frameLuma,
                frameWidth = width,
                frameHeight = height
            )
        }

        // Re-measure luma in existing boxes on the NEW frame
        var lumaSum = 0L
        var lumaCount = 0
        for (box in lastResult.boundingBoxes) {
            val luma = computeBoxLuma(image, inflateRect(box, 0.25f, width, height))
            if (luma > 0) {
                lumaSum += luma
                lumaCount++
            }
        }
        val subjectLuma = if (lumaCount > 0) (lumaSum / lumaCount).toInt() else frameLuma

        return lastResult.copy(
            tfliteDelegate = activeDelegate,
            objectLuma = subjectLuma,
            avgFrameLuma = frameLuma,
            frameWidth = width,
            frameHeight = height
        )
    }

    private fun createModelInput(image: Image): Any {
        val inputShape = interpreter.getInputTensor(0).shape()
        val inputH = inputShape.getOrNull(1) ?: 640
        val inputW = inputShape.getOrNull(2) ?: 640
        val inType = interpreter.getInputTensor(0).dataType()
        val bytesPerChannel = if (inType == DataType.UINT8) 1 else 4
        val buffer = ByteBuffer
            .allocateDirect(inputW * inputH * 3 * bytesPerChannel)
            .order(ByteOrder.nativeOrder())

        val yPlane = image.planes[0].buffer
        val uPlane = image.planes[1].buffer
        val vPlane = image.planes[2].buffer
        val yRowStride = image.planes[0].rowStride
        val yPixelStride = image.planes[0].pixelStride
        val uvRowStride = image.planes[1].rowStride
        val uvPixelStride = image.planes[1].pixelStride
        // P010 stores samples MSB-aligned in 16-bit little-endian containers, so the high byte
        // (offset +1) already gives us an 8-bit approximation of the 10-bit value.
        val msbOffset = if (yPixelStride >= 2) 1 else 0

        for (yOut in 0 until inputH) {
            val srcY = (yOut * image.height) / inputH
            for (xOut in 0 until inputW) {
                val srcX = (xOut * image.width) / inputW
                val yIdx = srcY * yRowStride + srcX * yPixelStride + msbOffset
                val uvIdx = (srcY / 2) * uvRowStride + (srcX / 2) * uvPixelStride + msbOffset
                if (yIdx >= yPlane.limit() || uvIdx >= uPlane.limit() || uvIdx >= vPlane.limit()) {
                    if (inType == DataType.UINT8) {
                        repeat(3) { buffer.put(0) }
                    } else {
                        repeat(3) { buffer.putFloat(0f) }
                    }
                    continue
                }

                val yVal = yPlane.get(yIdx).toInt() and 0xFF
                val uVal = uPlane.get(uvIdx).toInt() and 0xFF
                val vVal = vPlane.get(uvIdx).toInt() and 0xFF
                val r = (yVal + 1.402f * (vVal - 128)).toInt().coerceIn(0, 255)
                val g = (yVal - 0.344136f * (uVal - 128) - 0.714136f * (vVal - 128)).toInt().coerceIn(0, 255)
                val b = (yVal + 1.772f * (uVal - 128)).toInt().coerceIn(0, 255)

                if (inType == DataType.UINT8) {
                    buffer.put(r.toByte())
                    buffer.put(g.toByte())
                    buffer.put(b.toByte())
                } else {
                    buffer.putFloat(r / 255f)
                    buffer.putFloat(g / 255f)
                    buffer.putFloat(b / 255f)
                }
            }
        }

        buffer.rewind()
        return buffer
    }

    private data class KiwiPrediction(val confidence: Float, val box: Rect)

    private fun runKiwiInference(input: Any, frameW: Int, frameH: Int): List<KiwiPrediction> {
        return try {
            val outputShape = interpreter.getOutputTensor(0).shape()
            if (outputShape.size < 3) return emptyList()

            val d1 = outputShape[1]
            val d2 = outputShape[2]
            val raw = Array(1) { Array(d1) { FloatArray(d2) } }
            interpreter.run(input, raw)

            val output = if (d1 >= 6) {
                raw // [1, features, anchors]
            } else {
                // [1, anchors, features] -> transpose to [1, features, anchors]
                val transposed = Array(1) { Array(d2) { FloatArray(d1) } }
                for (anchor in 0 until d1) {
                    for (feature in 0 until d2) {
                        transposed[0][feature][anchor] = raw[0][anchor][feature]
                    }
                }
                transposed
            }

            decodeYoloOutput(output, frameW, frameH)
        } catch (e: Exception) {
            Log.e(TAG, "Model inference failed", e)
            emptyList()
        }
    }

    private fun decodeYoloOutput(output: Array<Array<FloatArray>>, frameW: Int, frameH: Int): List<KiwiPrediction> {
        val features = output[0].size
        val anchors = output[0][0].size
        if (features < 6 || anchors <= 0) return emptyList()
        val candidates = ArrayList<KiwiPrediction>(anchors)

        for (i in 0 until anchors) {
            val cx = output[0][0][i]
            val cy = output[0][1][i]
            val w = output[0][2][i]
            val h = output[0][3][i]

            val score = when {
                features >= 6 -> {
                    val obj = output[0][4][i].coerceIn(0f, 1f)
                    val cls = output[0][(kiwiClassIndex + 5).coerceAtMost(features - 1)][i].coerceIn(0f, 1f)
                    if (features >= (kiwiClassIndex + 6)) obj * cls else cls
                }
                else -> 0f
            }

            if (score < detectionThreshold) continue

            val left = ((cx - (w / 2f)) * frameW).roundToInt()
            val top = ((cy - (h / 2f)) * frameH).roundToInt()
            val right = ((cx + (w / 2f)) * frameW).roundToInt()
            val bottom = ((cy + (h / 2f)) * frameH).roundToInt()
            if (right <= left || bottom <= top) continue

            candidates.add(KiwiPrediction(score, Rect(left, top, right, bottom)))
        }

        if (candidates.isEmpty()) return emptyList()
        val sorted = candidates.sortedByDescending { it.confidence }
        return applyNms(sorted)
    }

    private fun applyNms(sortedCandidates: List<KiwiPrediction>): List<KiwiPrediction> {
        val selected = ArrayList<KiwiPrediction>(sortedCandidates.size)
        for (candidate in sortedCandidates) {
            var overlaps = false
            for (picked in selected) {
                if (iou(candidate.box, picked.box) > nmsIouThreshold) {
                    overlaps = true
                    break
                }
            }
            if (!overlaps) selected.add(candidate)
        }
        return selected
    }

    private fun iou(a: Rect, b: Rect): Float {
        val interLeft = maxOf(a.left, b.left)
        val interTop = maxOf(a.top, b.top)
        val interRight = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)
        val interW = (interRight - interLeft).coerceAtLeast(0)
        val interH = (interBottom - interTop).coerceAtLeast(0)
        val interArea = interW * interH
        if (interArea <= 0) return 0f
        val aArea = a.width() * a.height()
        val bArea = b.width() * b.height()
        val union = aArea + bArea - interArea
        if (union <= 0) return 0f
        return interArea.toFloat() / union.toFloat()
    }

    private fun computeFrameLuma(image: Image): Int {
        val yPlane = image.planes[0].buffer
        val yRowStride = image.planes[0].rowStride
        val yPixelStride = image.planes[0].pixelStride
        val msbOffset = if (yPixelStride >= 2) 1 else 0
        var sum = 0L
        var count = 0
        val step = 8
        for (y in 0 until image.height step step) {
            for (x in 0 until image.width step step) {
                val idx = y * yRowStride + x * yPixelStride + msbOffset
                if (idx < yPlane.limit()) {
                    sum += yPlane.get(idx).toInt() and 0xFF
                    count++
                }
            }
        }
        return if (count > 0) (sum / count).toInt() else 0
    }

    /**
     * Returns [box] enlarged by [padFraction] on each side, clamped to the frame bounds.
     * e.g. padFraction=0.25 grows a 100x100 box to 150x150 (25% margin each side).
     */
    private fun inflateRect(box: Rect, padFraction: Float, frameW: Int, frameH: Int): Rect {
        val padX = (box.width() * padFraction).toInt()
        val padY = (box.height() * padFraction).toInt()
        return Rect(
            (box.left - padX).coerceAtLeast(0),
            (box.top - padY).coerceAtLeast(0),
            (box.right + padX).coerceAtMost(frameW),
            (box.bottom + padY).coerceAtMost(frameH)
        )
    }

    private fun computeBoxLuma(image: Image, box: Rect): Int {
        val yPlane = image.planes[0].buffer
        val yRowStride = image.planes[0].rowStride
        val yPixelStride = image.planes[0].pixelStride
        val msbOffset = if (yPixelStride >= 2) 1 else 0
        var sum = 0L
        var count = 0
        val step = 4
        for (y in box.top until box.bottom step step) {
            for (x in box.left until box.right step step) {
                val idx = y * yRowStride + x * yPixelStride + msbOffset
                if (idx < yPlane.limit()) {
                    sum += yPlane.get(idx).toInt() and 0xFF
                    count++
                }
            }
        }
        return if (count > 0) (sum / count).toInt() else 0
    }

    private fun loadModelFile(fileName: String): ByteBuffer {
        val afd: AssetFileDescriptor = context.assets.openFd(fileName)
        afd.createInputStream().use { input ->
            val data = ByteArray(afd.length.toInt())
            var read = 0
            while (read < data.size) {
                val r = input.read(data, read, data.size - read)
                if (r == -1) break
                read += r
            }
            return ByteBuffer.allocateDirect(data.size).order(ByteOrder.nativeOrder()).apply {
                put(data)
                rewind()
            }
        }
    }
}

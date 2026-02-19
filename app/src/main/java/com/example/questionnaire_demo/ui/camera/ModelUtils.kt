package com.example.questionnaire_demo.ui.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.Tensor
import java.io.File
import java.io.FileOutputStream

object ModelUtils {
    private var module: Module? = null
    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val STD  = floatArrayOf(0.229f, 0.224f, 0.225f)
    private const val MODEL_INPUT_SIZE = 256

    fun loadModel(context: Context): Module {
        module?.let { return it }
        val modelFile = File(context.filesDir, "fairscan-mobile.ptl")
        if (!modelFile.exists()) {
            context.assets.open("fairscan-mobile.ptl").use { input ->
                FileOutputStream(modelFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
        return Module.load(modelFile.absolutePath).also { module = it }
    }

    // ── POLYGON FITTING ────────────────────────────────────────────────────────
    // Takes the raw noisy mask bitmap and returns a clean filled quadrilateral.
    // Pipeline:
    //   1. Convert bitmap → OpenCV grayscale Mat
    //   2. Find all external contours (connected blobs of white pixels)
    //   3. Keep only the largest contour — most likely the document/object
    //   4. Approximate that contour as a polygon using approxPolyDP
    //   5. If it reduces to 4 points we have our quad; otherwise fall back to
    //      the bounding rotated rectangle which always gives exactly 4 corners
    //   6. Fill the quad onto a black Mat, convert back to Bitmap
    private fun fitQuadrilateral(maskBitmap: Bitmap): Bitmap {
        // Convert Bitmap → RGBA Mat → grayscale
        val src = Mat()
        Utils.bitmapToMat(maskBitmap, src)
        val gray = Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)

        // Hard threshold — anything non-zero becomes 255
        val binary = Mat()
        Imgproc.threshold(gray, binary, 1.0, 255.0, Imgproc.THRESH_BINARY)

        // Find contours (external only — we don't care about holes)
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        if (contours.isEmpty()) return maskBitmap

        // Pick the largest contour by area
        val largest = contours.maxByOrNull { Imgproc.contourArea(it) }!!

        // approxPolyDP needs a MatOfPoint2f
        val contour2f = MatOfPoint2f(*largest.toArray())

        // Epsilon controls how aggressively we simplify.
        // 2–5% of the arc length is a common sweet spot for documents.
        val epsilon = 0.03 * Imgproc.arcLength(contour2f, true)
        val approx = MatOfPoint2f()
        Imgproc.approxPolyDP(contour2f, approx, epsilon, true)

        // Build the 4-point hull we'll draw
        val quadPoints: MatOfPoint = if (approx.rows() == 4) {
            // Perfect — approxPolyDP already gave us 4 corners
            MatOfPoint(*approx.toArray())
        } else {
            // Fallback: minimum-area bounding rectangle, always exactly 4 points
            val rotatedRect = Imgproc.minAreaRect(contour2f)
            val boxPts = MatOfPoint2f()
            Imgproc.boxPoints(rotatedRect, boxPts)
            MatOfPoint(*boxPts.toArray())
        }

        // Draw filled quad onto a blank black Mat, then convert back to Bitmap
        val filled = Mat.zeros(src.size(), CvType.CV_8UC4)
        // Green fill: RGBA = (0, 255, 0, 255)
        Imgproc.fillConvexPoly(filled, quadPoints, Scalar(0.0, 255.0, 0.0, 255.0))

        val result = Bitmap.createBitmap(maskBitmap.width, maskBitmap.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(filled, result)

        // Clean up native Mats to avoid memory leaks
        src.release(); gray.release(); binary.release()
        hierarchy.release(); contour2f.release(); approx.release(); filled.release()

        return result
    }

    fun runInference(context: Context, bitmap: Bitmap): Bitmap {
        val model = loadModel(context)

        // ── STEP 1: prepare input ──────────────────────────────────────────
        val resized = Bitmap.createScaledBitmap(bitmap, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, true)
        val pixels = IntArray(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE)
        resized.getPixels(pixels, 0, MODEL_INPUT_SIZE, 0, 0, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE)
        val floatBuffer = FloatArray(3 * MODEL_INPUT_SIZE * MODEL_INPUT_SIZE)
        val channelSize = MODEL_INPUT_SIZE * MODEL_INPUT_SIZE
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = ((pixel shr 16) and 0xFF) / 255f
            val g = ((pixel shr 8)  and 0xFF) / 255f
            val b = ( pixel         and 0xFF) / 255f
            floatBuffer[i]                   = (r - MEAN[0]) / STD[0]
            floatBuffer[i + channelSize]     = (g - MEAN[1]) / STD[1]
            floatBuffer[i + 2 * channelSize] = (b - MEAN[2]) / STD[2]
        }
        val inputTensor = Tensor.fromBlob(
            floatBuffer,
            longArrayOf(1, 3, MODEL_INPUT_SIZE.toLong(), MODEL_INPUT_SIZE.toLong())
        )

        // ── STEP 2: run model ──────────────────────────────────────────────
        val outputTensor = model.forward(IValue.from(inputTensor)).toTensor()
        val outputData = outputTensor.dataAsFloatArray

        // ── STEP 3: build mask at model resolution (256×256) ──────────────
        val maskBitmap = Bitmap.createBitmap(MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, Bitmap.Config.ARGB_8888)
        for (i in outputData.indices) {
            val sigmoid = 1f / (1f + Math.exp(-outputData[i].toDouble())).toFloat()
            val color = if (sigmoid > 0.5f) 0xFF00FF00.toInt() else 0x00000000
            maskBitmap.setPixel(i % MODEL_INPUT_SIZE, i / MODEL_INPUT_SIZE, color)
        }

        // ── STEP 3.5: fit mask to a clean quadrilateral ───────────────────
        // This replaces the raw noisy pixel mask with a filled 4-sided polygon,
        // making the overlay look like a clean document detection box.
        val quadMask = fitQuadrilateral(maskBitmap)

        // ── STEP 4: scale mask up to match the original bitmap dimensions ──
        val scaledMask = Bitmap.createScaledBitmap(quadMask, bitmap.width, bitmap.height, true)

        // ── STEP 5: rotate output mask to match screen orientation ─────────
        val matrix = Matrix().apply { postRotate(90f) }
        return Bitmap.createBitmap(scaledMask, 0, 0, scaledMask.width, scaledMask.height, matrix, true)
    }
}
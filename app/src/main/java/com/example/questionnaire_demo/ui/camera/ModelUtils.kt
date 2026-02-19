package com.example.questionnaire_demo.ui.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
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

    fun runInference(context: Context, bitmap: Bitmap): Bitmap {
        val model = loadModel(context)

        // ── STEP 1: prepare input ──────────────────────────────────────────
        // Just resize the raw bitmap, no rotation. We'll fix the output instead.
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

        // ── STEP 3: build mask at model resolution (256x256) ──────────────
        val maskBitmap = Bitmap.createBitmap(MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, Bitmap.Config.ARGB_8888)
        for (i in outputData.indices) {
            val sigmoid = 1f / (1f + Math.exp(-outputData[i].toDouble())).toFloat()
            val color = if (sigmoid > 0.5f) 0xFF00FF00.toInt() else 0x00000000
            maskBitmap.setPixel(i % MODEL_INPUT_SIZE, i / MODEL_INPUT_SIZE, color)
        }

        // ── STEP 4: scale mask up to match the original bitmap dimensions ──
        // We do this BEFORE rotation so the aspect ratio is correct.
        val scaledMask = Bitmap.createScaledBitmap(maskBitmap, bitmap.width, bitmap.height, true)

        // ── STEP 5: rotate output mask to match screen orientation ─────────
        // Adjust these values if alignment is still off — only touch this block.
        val matrix = Matrix().apply {
            postRotate(90f)
        }
        return Bitmap.createBitmap(scaledMask, 0, 0, scaledMask.width, scaledMask.height, matrix, true)
    }
}
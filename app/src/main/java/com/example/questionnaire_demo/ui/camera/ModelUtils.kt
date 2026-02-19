package com.example.questionnaire_demo.ui.camera

import android.content.Context
import android.graphics.Bitmap
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.Tensor
import java.io.File
import java.io.FileOutputStream

object ModelUtils {

    // Cached module reference — loading from disk is expensive (~100-300ms),
    // so we load once and reuse. This is the Android equivalent of model.eval()
    // persisting across inference calls in your Python script.
    private var module: Module? = null

    // ImageNet normalization — same values as your Python preprocess()
    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val STD  = floatArrayOf(0.229f, 0.224f, 0.225f)
    private const val MODEL_INPUT_SIZE = 256

    /**
     * Copies the .ptl from assets to internal storage (PyTorch Android's
     * Module.load() requires a file path, not an InputStream).
     * Only copies if the file doesn't already exist.
     */
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

    /**
     * Runs the full pipeline: resize → normalize → inference → sigmoid → binary mask.
     * Returns a Bitmap of the mask (white = page, black = background),
     * sized to MODEL_INPUT_SIZE x MODEL_INPUT_SIZE.
     *
     * This mirrors your Python pipeline exactly:
     *   preprocess → model(input_tensor) → sigmoid → (mask > 0.5)
     */
    fun runInference(context: Context, bitmap: Bitmap): Bitmap {
        val model = loadModel(context)

        // 1. Resize to 256x256 — same as cv2.resize(img, (256, 256))
        val resized = Bitmap.createScaledBitmap(
            bitmap, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, true
        )

        // 2. Convert to float array in NCHW format and normalize
        //    Python: img = img.astype(np.float32) / 255.0
        //            img = (img - mean) / std
        //            img = np.transpose(img, (2, 0, 1))   ← HWC to CHW
        val pixels = IntArray(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE)
        resized.getPixels(pixels, 0, MODEL_INPUT_SIZE, 0, 0, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE)

        val floatBuffer = FloatArray(3 * MODEL_INPUT_SIZE * MODEL_INPUT_SIZE)
        val channelSize = MODEL_INPUT_SIZE * MODEL_INPUT_SIZE

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = ((pixel shr 16) and 0xFF) / 255f
            val g = ((pixel shr 8)  and 0xFF) / 255f
            val b = ( pixel         and 0xFF) / 255f

            // Store in CHW order (all R, then all G, then all B)
            floatBuffer[i]                   = (r - MEAN[0]) / STD[0]
            floatBuffer[i + channelSize]     = (g - MEAN[1]) / STD[1]
            floatBuffer[i + 2 * channelSize] = (b - MEAN[2]) / STD[2]
        }

        // Shape: [1, 3, 256, 256] — the "1" is the batch dimension (np.expand_dims)
        val inputTensor = Tensor.fromBlob(
            floatBuffer,
            longArrayOf(1, 3, MODEL_INPUT_SIZE.toLong(), MODEL_INPUT_SIZE.toLong())
        )

        // 3. Run inference — equivalent to: output = model(input_tensor)
        val outputTensor = model.forward(IValue.from(inputTensor)).toTensor()
        val outputData = outputTensor.dataAsFloatArray

        // 4. Sigmoid + threshold — equivalent to:
        //    mask = torch.sigmoid(output).squeeze().numpy()
        //    mask_binary = (mask > 0.5).astype(np.uint8) * 255
        val maskBitmap = Bitmap.createBitmap(
            MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, Bitmap.Config.ARGB_8888
        )

        for (i in outputData.indices) {
            val sigmoid = 1f / (1f + Math.exp(-outputData[i].toDouble())).toFloat()
            val isMask = sigmoid > 0.5f
            val color = if (isMask) 0xFF00FF00.toInt() else 0x00000000  // green or transparent
            val x = i % MODEL_INPUT_SIZE
            val y = i / MODEL_INPUT_SIZE
            maskBitmap.setPixel(x, y, color)
        }

        return maskBitmap
    }
}
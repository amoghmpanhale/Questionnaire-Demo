package com.example.questionnaire_demo.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.questionnaire_demo.R
import com.example.questionnaire_demo.databinding.FragmentCameraBinding
import org.opencv.android.OpenCVLoader
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

class CameraFragment : Fragment() {

    // ViewBinding: _binding is nullable so we can null it out in onDestroyView
    // to avoid memory leaks. The non-null `binding` getter is only safe to use
    // between onCreateView and onDestroyView.
    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!

    // ImageCapture use case — nullable because camera may not be initialized yet
    private var imageCapture: ImageCapture? = null

    // A dedicated background thread for camera operations.
    // Camera work should never block the main thread.
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCameraBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Check permissions before touching any camera API —
        // accessing the camera without CAMERA permission crashes immediately.
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions()
        }

        binding.imageCaptureButton.setOnClickListener { takePhoto() }

        // Single-thread executor: camera frame processing is sequential,
        // so one background thread is sufficient and keeps ordering guarantees.
        cameraExecutor = Executors.newSingleThreadExecutor()

        if (!OpenCVLoader.initLocal()) {
            Log.e(TAG, "OpenCV failed to load")
            return
        }
    }

    /**
     * Saves a JPEG photo to the device's MediaStore (shared photo gallery).
     * CameraX handles the JPEG encoding and disk I/O on its own internal thread;
     * our callback runs on the main executor we supply.
     */
    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        // Pass cameraExecutor here so the ImageProxy callback fires on our
        // background thread — toBitmap() and JPEG compression stay off the main thread.
        imageCapture.takePicture(
            cameraExecutor,  // background thread instead of mainExecutor
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun onCaptureSuccess(image: ImageProxy) {
                    // We're already on cameraExecutor (background thread), so
                    // toBitmap() + compress don't block the UI at all.
                    val rotationDegrees = image.imageInfo.rotationDegrees

                    val bitmap = image.toBitmap()
                    image.close() // always close before any early returns

                    val processedBitmap = processWithOpenCV(bitmap)

                    val stream = java.io.ByteArrayOutputStream()
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, stream)
                    val byteArray = stream.toByteArray()

                    // Bundle args and navigate — findNavController().navigate() is
                    // safe to call from a background thread in Navigation Component.
                    val bundle = Bundle().apply {
                        putByteArray("captured_image", byteArray)
                        // Pass the rotation so PreviewFragment can correct the orientation.
                        // ImageProxy gives us the degrees the image needs to be rotated
                        // clockwise to appear upright (0, 90, 180, or 270).
                        putInt("rotation_degrees", rotationDegrees)
                    }

                    // navigate() must be called on the main thread
                    requireActivity().runOnUiThread {
                        findNavController().navigate(
                            R.id.action_cameraFragment_to_previewFragment,
                            bundle
                        )
                    }
                }
            }
        )
    }

    /**
     * Binds CameraX use cases to the fragment's lifecycle.
     *
     * ProcessCameraProvider is a singleton that manages the camera hardware.
     * Binding use cases to a LifecycleOwner means CameraX automatically
     * opens/closes the camera when the lifecycle starts/stops — no manual teardown needed.
     */
    private fun startCamera() {
        // getInstance returns a ListenableFuture — the provider isn't ready instantly.
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview use case: streams camera frames into the PreviewView surface.
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }

            // ImageCapture use case: captures a still image on demand.
            // We store the reference so takePhoto() can call it later.
            imageCapture = ImageCapture.Builder().build()

            // Select the rear camera as default.
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        val bitmap = imageProxy.toBitmap()
                        val modelMask = ModelUtils.runInference(requireContext(), bitmap)  // feed rotated bitmap

                        requireActivity().runOnUiThread {
                            _binding?.overlayImageView?.setImageBitmap(modelMask)
                        }

                        imageProxy.close()
                    }
                }

            try {
                // Unbind any previously bound use cases before rebinding —
                // a use case can only be bound to one lifecycle at a time.
                cameraProvider.unbindAll()

                // Bind both use cases to this fragment's view lifecycle.
                // viewLifecycleOwner is preferred over `this` in fragments
                // because the view can be destroyed/recreated independently of the fragment.
                cameraProvider.bindToLifecycle(
                    viewLifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture,
                    imageAnalysis
                )

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

            // The listener runs on the main thread so it's safe to touch Views.
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    /** Launches the system permission request dialog for all required permissions. */
    private fun requestPermissions() {
        activityResultLauncher.launch(REQUIRED_PERMISSIONS)
    }

    /** Returns true only if every permission in REQUIRED_PERMISSIONS has been granted. */
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Modern permission request API (replaces onRequestPermissionsResult).
     * registerForActivityResult must be called before the fragment starts,
     * which is why it's a property initializer rather than called inside a function.
     */
    private val activityResultLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.entries.all { (key, granted) ->
                key !in REQUIRED_PERMISSIONS || granted
            }
            if (!allGranted) {
                Toast.makeText(requireContext(), "Camera permission denied", Toast.LENGTH_SHORT).show()
            } else {
                startCamera()
            }
        }

    override fun onDestroyView() {
        super.onDestroyView()
        // Shut down the background thread to avoid leaking it after the view is gone.
        cameraExecutor.shutdown()
        // Null out binding to prevent memory leaks — the View is about to be destroyed
        // but the Fragment instance may live on (e.g. in the back stack).
        _binding = null
    }

    private fun processWithOpenCV(bitmap: Bitmap): Bitmap = OpenCVUtils.detectPageQuad(bitmap)

    companion object {
        private const val TAG = "CameraXApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"

        // RECORD_AUDIO is included in case you re-add video capture later.
        // WRITE_EXTERNAL_STORAGE is only needed on Android 9 (P) and below;
        // scoped storage on Android 10+ doesn't require it for MediaStore writes.
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        ).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }
}
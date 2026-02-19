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
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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
    }

    /**
     * Saves a JPEG photo to the device's MediaStore (shared photo gallery).
     * CameraX handles the JPEG encoding and disk I/O on its own internal thread;
     * our callback runs on the main executor we supply.
     */
    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        // In-memory capture — no OutputFileOptions, no disk write.
        // CameraX calls back with an ImageProxy containing the raw image buffer.
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun onCaptureSuccess(image: ImageProxy) {
                    // ImageProxy wraps a YUV or JPEG buffer depending on device.
                    // toBitmap() is a CameraX extension function that handles
                    // the format conversion and rotation correction for you.
                    val bitmap = image.toBitmap()

                    // Always close ImageProxy when done — it holds a camera buffer
                    // slot. If you forget, the camera pipeline stalls.
                    image.close()

                    // Compress to JPEG bytes so we can pass it as a Bundle argument.
                    // Bitmaps are Parcelable but too large/risky to pass directly —
                    // the Binder IPC buffer has a ~1MB limit and will throw a
                    // TransactionTooLargeException on high-res images.
                    val stream = java.io.ByteArrayOutputStream()
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, stream)
                    val byteArray = stream.toByteArray()

                    // Navigate to the preview screen, carrying the image bytes.
                    val bundle = Bundle().apply {
                        putByteArray("captured_image", byteArray)
                    }
                    // Make sure you have a nav action set up from cameraFragment
                    // to previewFragment in your nav graph.
                    findNavController().navigate(
                        R.id.action_cameraFragment_to_previewFragment,
                        bundle
                    )
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
                    imageCapture  // <-- must be bound here to enable photo capture
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
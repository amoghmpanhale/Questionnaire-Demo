package com.example.questionnaire_demo.ui.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.questionnaire_demo.databinding.FragmentPreviewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PreviewFragment : Fragment() {

    private var _binding: FragmentPreviewBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPreviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val byteArray = arguments?.getByteArray("captured_image")
        val rotationDegrees = arguments?.getInt("rotation_degrees", 0) ?: 0

        if (byteArray != null) {
            // Decoding + rotating a high-res JPEG is CPU-intensive — doing it on
            // the main thread causes visible jank. We dispatch to IO (disk/CPU
            // bound work), then switch back to Main to update the UI.
            viewLifecycleOwner.lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    val raw = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)

                    // The JPEG buffer from ImageProxy doesn't have rotation baked in —
                    // it's stored as metadata. We must apply it ourselves with a Matrix.
                    // rotationDegrees comes from ImageProxy.imageInfo.rotationDegrees.
                    if (rotationDegrees != 0) {
                        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                        Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                            .also { if (it !== raw) raw.recycle() } // avoid leaking the original
                    } else {
                        raw
                    }
                }
                // Back on main thread — safe to touch Views now
                binding.previewImageView.setImageBitmap(bitmap)
            }
        }

        binding.retakeButton.setOnClickListener {
            findNavController().popBackStack()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
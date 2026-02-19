package com.example.questionnaire_demo.ui.formUpload

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.example.questionnaire_demo.databinding.FragmentFormBinding
import android.widget.AutoCompleteTextView


class FormFragment : Fragment() {

    private var _binding: FragmentFormBinding? = null
    private val binding get() = _binding!!

    private val viewModel: FormViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFormBinding.inflate(inflater, container, false)

        // get the AutoCompleteTextView
        val dropdown = binding.menu.editText as? AutoCompleteTextView

        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            viewModel.options
        )

        dropdown?.setAdapter(adapter)

        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}


package com.example.questionnaire_demo.ui.home

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class HomeViewModel : ViewModel() {

    private val _text1 = MutableLiveData<String>().apply {
        value = "upper text box"
    }

    private val _text2 = MutableLiveData<String>().apply{
        value = "Lower text box"
    }
    val text: LiveData<String> = _text1
    val lowerText: LiveData<String> = _text2
}
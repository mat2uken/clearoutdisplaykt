package com.example.cameraapp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.cameraapp.camera.CameraXService
import com.example.cameraapp.display.DisplayService

class CameraViewModelFactory(
    private val cameraXService: CameraXService,
    private val displayService: DisplayService // Add DisplayService
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CameraViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return CameraViewModel(cameraXService, displayService) as T // Pass DisplayService
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

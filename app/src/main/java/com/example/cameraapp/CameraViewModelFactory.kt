package com.example.cameraapp

import android.os.Bundle
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
// import androidx.lifecycle.ViewModelProvider // Replaced by AbstractSavedStateViewModelFactory
import androidx.savedstate.SavedStateRegistryOwner
import com.example.cameraapp.camera.CameraXService
import com.example.cameraapp.display.DisplayService

class CameraViewModelFactory(
    owner: SavedStateRegistryOwner,
    defaultArgs: Bundle? = null,
    private val cameraXService: CameraXService,
    private val displayService: DisplayService
) : AbstractSavedStateViewModelFactory(owner, defaultArgs) {
    override fun <T : ViewModel> create(
        key: String,
        modelClass: Class<T>,
        handle: SavedStateHandle // This is the SavedStateHandle instance
    ): T {
        if (modelClass.isAssignableFrom(CameraViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return CameraViewModel(cameraXService, displayService, handle) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

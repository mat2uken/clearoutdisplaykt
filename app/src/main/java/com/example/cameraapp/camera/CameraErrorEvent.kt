package com.example.cameraapp.camera

sealed class CameraErrorEvent {
    data class InitializationError(val message: String, val cause: Throwable? = null) : CameraErrorEvent()
    data class ControlError(val operation: String, val message: String, val cause: Throwable? = null) : CameraErrorEvent()
    // Potentially other specific error types can be added later if needed
}

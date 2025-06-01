package com.example.cameraapp.camera

import androidx.camera.core.*
import androidx.camera.core.CameraInfo.ExposureState
import androidx.camera.core.CameraInfo.ZoomState
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.StateFlow

// A wrapper class for results from camera operations that might also provide camera instance
sealed class CameraInitResult {
    data class Success(val camera: Camera) : CameraInitResult() // Provides the bound camera instance
    data class Failure(val exception: Exception) : CameraInitResult()
    object NotInitialized : CameraInitResult() // Initial state
}

interface CameraXService {

    /**
     * Initializes the camera with the given parameters. This should be called to start the camera
     * or to switch cameras. Implementations should handle unbinding previous use cases.
     * It will update the observable state flows like [zoomStateFlow], [exposureStateFlow], etc.
     */
    suspend fun initializeAndBindCamera(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        targetLensFacing: Int
    ): CameraInitResult

    /**
     * Observable current zoom state of the active camera. Null if no camera active.
     */
    val zoomStateFlow: StateFlow<ZoomState?>

    /**
     * Observable current exposure state of the active camera. Null if no camera active.
     */
    val exposureStateFlow: StateFlow<ExposureState?>

    /**
     * Observable state indicating if the active camera has a flash unit. Defaults to false.
     */
    val hasFlashUnitFlow: StateFlow<Boolean>

    /**
     * Observable state indicating the current actual torch state (ON/OFF).
     * This helps in knowing if enableTorch succeeded.
     */
    val torchStateFlow: StateFlow<Boolean>


    // --- Camera Controls ---

    fun setZoomRatio(ratio: Float): ListenableFuture<Void?>
    fun setExposureCompensationIndex(index: Int): ListenableFuture<Void?>
    fun enableTorch(enable: Boolean): ListenableFuture<Void?>
    fun startFocusAndMetering(action: FocusMeteringAction): ListenableFuture<FocusMeteringResult>
    fun setWhiteBalanceMode(awbMode: Int): ListenableFuture<Void?>

    // --- Surface Management ---
    /**
     * Sets the main surface provider for the active Preview use case.
     * Pass null to detach the current surface.
     */
    fun setMainSurfaceProvider(surfaceProvider: Preview.SurfaceProvider?)

    /**
     * Sets an external surface provider for the active Preview use case.
     * This typically means the main surface provider should be cleared first.
     * Pass null to detach the current surface.
     */
    fun setExternalSurfaceProvider(surfaceProvider: Preview.SurfaceProvider?)


    /**
     * Cleans up camera resources. Should be called when the service is no longer needed.
     */
    fun shutdown()
}

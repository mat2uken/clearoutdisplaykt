package com.example.cameraapp.camera

import android.content.Context
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.*
import androidx.camera.core.CameraInfo.ExposureState
import androidx.camera.core.CameraInfo.TorchState
import androidx.camera.core.CameraInfo.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException


// ContextAmbient is removed as it's not a good pattern.
// Executors will be passed directly or use ContextCompat.getMainExecutor.

class CameraXServiceImpl(private val context: Context) : CameraXService {
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var currentCamera: Camera? = null
    private var currentLifecycleOwner: LifecycleOwner? = null

    private var activePreviewUseCase: Preview? = null // Renamed from previewUseCase

    private val _zoomStateFlow = MutableStateFlow<ZoomState?>(null)
    override val zoomStateFlow: StateFlow<ZoomState?> = _zoomStateFlow.asStateFlow()

    private val _exposureStateFlow = MutableStateFlow<ExposureState?>(null)
    override val exposureStateFlow: StateFlow<ExposureState?> = _exposureStateFlow.asStateFlow()

    private val _hasFlashUnitFlow = MutableStateFlow(false)
    override val hasFlashUnitFlow: StateFlow<Boolean> = _hasFlashUnitFlow.asStateFlow()

    private val _torchStateFlow = MutableStateFlow(false)
    override val torchStateFlow: StateFlow<Boolean> = _torchStateFlow.asStateFlow()

    private var zoomStateObserver: Observer<ZoomState>? = null
    private var exposureStateObserver: Observer<ExposureState>? = null
    private var torchStateObserver: Observer<Int>? = null

    override suspend fun initializeAndBindCamera(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider, // This is the main surface provider
        targetLensFacing: Int
    ): CameraInitResult {
        currentLifecycleOwner = lifecycleOwner

        if (cameraProvider == null) {
            try {
                cameraProvider = ProcessCameraProvider.getInstance(context).awaitWithExecutor(ContextCompat.getMainExecutor(context))
            } catch (e: Exception) {
                Log.e("CameraXServiceImpl", "Error getting CameraProvider", e)
                return CameraInitResult.Failure(e)
            }
        }
        val provider = cameraProvider ?: return CameraInitResult.Failure(IllegalStateException("CameraProvider null after init attempt"))

        clearCameraObservers()
        provider.unbindAll()

        _zoomStateFlow.value = null
        _exposureStateFlow.value = null
        _hasFlashUnitFlow.value = false
        _torchStateFlow.value = false
        currentCamera = null
        activePreviewUseCase = null // Clear previous use case

        val newPreviewUseCase = Preview.Builder().build()
        this.activePreviewUseCase = newPreviewUseCase // Store the new use case

        // Set the initial surface provider (main one)
        // Preview.setSurfaceProvider is thread-safe and can be called directly.
        // No need to use cameraExecutor here for this specific call.
        newPreviewUseCase.setSurfaceProvider(surfaceProvider)
        Log.d("CameraXServiceImpl", "Main surface provider set on new Preview UseCase.")


        val cameraSelector = CameraSelector.Builder().requireLensFacing(targetLensFacing).build()

        try {
            Log.d("CameraXServiceImpl", "Binding camera with lens: $targetLensFacing")
            // Use activePreviewUseCase, ensure it's not null if we proceed
            val camera = provider.bindToLifecycle(lifecycleOwner, cameraSelector, activePreviewUseCase!!)
            currentCamera = camera
            setupCameraObservers(camera, lifecycleOwner)
            Log.d("CameraXServiceImpl", "Camera bound successfully. Flash: ${camera.cameraInfo.hasFlashUnit()}")
            return CameraInitResult.Success(camera)
        } catch (e: Exception) {
            Log.e("CameraXServiceImpl", "Error binding camera", e)
            this.activePreviewUseCase = null
            currentCamera = null
            return CameraInitResult.Failure(e)
        }
    }

    private fun setupCameraObservers(camera: Camera, lifecycleOwner: LifecycleOwner) {
        _hasFlashUnitFlow.value = camera.cameraInfo.hasFlashUnit()
        _torchStateFlow.value = camera.cameraInfo.torchState.value == TorchState.ON

        zoomStateObserver = Observer { _zoomStateFlow.value = it }
        camera.cameraInfo.zoomState.observe(lifecycleOwner, zoomStateObserver!!)

        exposureStateObserver = Observer { _exposureStateFlow.value = it }
        camera.cameraInfo.exposureState.observe(lifecycleOwner, exposureStateObserver!!)

        torchStateObserver = Observer { state -> _torchStateFlow.value = (state == TorchState.ON) }
        camera.cameraInfo.torchState.observe(lifecycleOwner, torchStateObserver!!)
    }

    private fun clearCameraObservers() {
        currentCamera?.let { cam ->
            currentLifecycleOwner?.let { owner ->
                zoomStateObserver?.let { cam.cameraInfo.zoomState.removeObserver(it) }
                exposureStateObserver?.let { cam.cameraInfo.exposureState.removeObserver(it) }
                torchStateObserver?.let { cam.cameraInfo.torchState.removeObserver(it) }
            }
        }
        zoomStateObserver = null
        exposureStateObserver = null
        torchStateObserver = null
    }

    override fun setZoomRatio(ratio: Float): ListenableFuture<Void?> {
        val camera = currentCamera ?: return Futures.immediateFailedFuture(IllegalStateException("Camera not initialized for setZoomRatio"))
        Log.d("CameraXServiceImpl", "setZoomRatio($ratio) called.")
        return camera.cameraControl.setZoomRatio(ratio)
    }

    override fun setExposureCompensationIndex(index: Int): ListenableFuture<Void?> {
        val camera = currentCamera ?: return Futures.immediateFailedFuture(IllegalStateException("Camera not initialized for setExposureCompensationIndex"))
        Log.d("CameraXServiceImpl", "setExposureCompensationIndex($index) called.")
        return camera.cameraControl.setExposureCompensationIndex(index)
    }

    override fun enableTorch(enable: Boolean): ListenableFuture<Void?> {
        val camera = currentCamera ?: return Futures.immediateFailedFuture(IllegalStateException("Camera not initialized for enableTorch"))
        if (!hasFlashUnitFlow.value && enable) {
            Log.w("CameraXServiceImpl", "enableTorch($enable) called but no flash unit available.")
            return Futures.immediateFailedFuture(IllegalStateException("No flash unit available to enable torch"))
        }
        Log.d("CameraXServiceImpl", "enableTorch($enable) called.")
        val future = camera.cameraControl.enableTorch(enable)
        future.addListener({
            try {
                future.get()
                Log.d("CameraXServiceImpl", "enableTorch($enable) operation successful via future.")
            } catch (e: Exception) {
                Log.e("CameraXServiceImpl", "enableTorch($enable) operation failed via future.", e)
            }
        }, cameraExecutor)
        return future
    }

    override fun startFocusAndMetering(action: FocusMeteringAction): ListenableFuture<FocusMeteringResult> {
        val camera = currentCamera ?: return Futures.immediateFailedFuture(IllegalStateException("Camera not initialized for startFocusAndMetering"))
        Log.d("CameraXServiceImpl", "startFocusAndMetering called with action: ${action.meteringPoints.firstOrNull()}")
        val future = camera.cameraControl.startFocusAndMetering(action)
        future.addListener({
            try {
                val result = future.get()
                Log.d("CameraXServiceImpl", "Focus/metering operation result: isFocusSuccessful=${result.isFocusSuccessful}")
            } catch (e: Exception) {
                Log.e("CameraXServiceImpl", "Focus/metering operation failed.", e)
            }
        }, cameraExecutor)
        return future
    }

    override fun setWhiteBalanceMode(awbMode: Int): ListenableFuture<Void?> {
        val camera = currentCamera ?: return Futures.immediateFailedFuture(IllegalStateException("Camera not initialized for setWhiteBalanceMode"))
        Log.d("CameraXServiceImpl", "setWhiteBalanceMode called with AWB Mode: $awbMode")

        return try {
            val captureRequestBuilder = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE, awbMode)

            val future = Camera2CameraControl.from(camera.cameraControl).setCaptureRequestOptions(captureRequestBuilder.build())
            future.addListener({
                try {
                    future.get()
                    Log.d("CameraXServiceImpl", "Set AWB mode to $awbMode operation successful.")
                } catch (e: Exception) {
                    Log.e("CameraXServiceImpl", "Set AWB mode to $awbMode operation failed.", e)
                }
            }, cameraExecutor)
            future
        } catch (e: Exception) {
            Log.e("CameraXServiceImpl", "Failed to build/set AWB options for mode $awbMode", e)
            Futures.immediateFailedFuture(e)
        }
    }

    override fun setMainSurfaceProvider(surfaceProvider: Preview.SurfaceProvider?) {
        Log.d("CameraXServiceImpl", "Setting main surface provider: ${if (surfaceProvider != null) "VALID" else "NULL"}")
        try {
            activePreviewUseCase?.setSurfaceProvider(surfaceProvider)
        } catch (e: IllegalStateException) {
            Log.e("CameraXServiceImpl", "Failed to set main surface provider", e)
        }
    }

    override fun setExternalSurfaceProvider(surfaceProvider: Preview.SurfaceProvider?) {
        Log.d("CameraXServiceImpl", "Setting external surface provider: ${if (surfaceProvider != null) "VALID" else "NULL"}")
        try {
            activePreviewUseCase?.setSurfaceProvider(surfaceProvider)
        } catch (e: IllegalStateException) {
            Log.e("CameraXServiceImpl", "Failed to set external surface provider", e)
        }
    }

    override fun shutdown() {
        Log.d("CameraXServiceImpl", "Shutdown called")
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.e("CameraXServiceImpl", "Error during unbindAll on shutdown", e)
        }
        clearCameraObservers()
        cameraExecutor.shutdown()
        currentCamera = null
        activePreviewUseCase = null // Nullify activePreviewUseCase
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
suspend fun <T> ListenableFuture<T>.awaitWithExecutor(executor: ExecutorService): T {
    return suspendCancellableCoroutine { continuation ->
        addListener({
            try {
                if (continuation.isActive) continuation.resume(get())
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        }, executor)

        continuation.invokeOnCancellation {
            cancel(true)
        }
    }
}

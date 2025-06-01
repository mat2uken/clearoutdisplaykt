package com.example.cameraapp.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException


class CameraXServiceImpl(private val context: Context) : CameraXService {
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var currentCamera: Camera? = null
    private var currentLifecycleOwner: LifecycleOwner? = null
    private var activePreviewUseCase: Preview? = null
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val _zoomStateFlow = MutableStateFlow<ZoomState?>(null)
    override val zoomStateFlow: StateFlow<ZoomState?> = _zoomStateFlow.asStateFlow()

    private val _exposureStateFlow = MutableStateFlow<ExposureState?>(null)
    override val exposureStateFlow: StateFlow<ExposureState?> = _exposureStateFlow.asStateFlow()

    private val _hasFlashUnitFlow = MutableStateFlow(false)
    override val hasFlashUnitFlow: StateFlow<Boolean> = _hasFlashUnitFlow.asStateFlow()

    private val _torchStateFlow = MutableStateFlow(false)
    override val torchStateFlow: StateFlow<Boolean> = _torchStateFlow.asStateFlow()

    private val _availableAwbModesFlow = MutableStateFlow<List<Int>>(emptyList())
    override val availableAwbModesFlow: StateFlow<List<Int>> = _availableAwbModesFlow.asStateFlow()

    private val _cameraErrorFlow = MutableSharedFlow<CameraErrorEvent>() // Defaults to 0 replay, 0 extraBufferCapacity
    override val cameraErrorFlow: SharedFlow<CameraErrorEvent> = _cameraErrorFlow.asSharedFlow()

    private var zoomStateObserver: Observer<ZoomState>? = null
    private var exposureStateObserver: Observer<ExposureState>? = null
    private var torchStateObserver: Observer<Int>? = null

    override suspend fun initializeAndBindCamera(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        targetLensFacing: Int
    ): CameraInitResult {
        currentLifecycleOwner = lifecycleOwner

        if (cameraProvider == null) {
            try {
                cameraProvider = ProcessCameraProvider.getInstance(context).awaitWithExecutor(ContextCompat.getMainExecutor(context))
            } catch (e: Exception) {
                val errorMsg = "Failed to get CameraProvider"
                Log.e("CameraXServiceImpl", errorMsg, e)
                _cameraErrorFlow.tryEmit(CameraErrorEvent.InitializationError(errorMsg, e))
                return CameraInitResult.Failure(e)
            }
        }
        val provider = cameraProvider ?: run {
            val errorMsg = "CameraProvider null after init attempt"
            Log.e("CameraXServiceImpl", errorMsg)
            _cameraErrorFlow.tryEmit(CameraErrorEvent.InitializationError(errorMsg, null))
            return CameraInitResult.Failure(IllegalStateException(errorMsg))
        }

        clearCameraObservers()
        try {
            provider.unbindAll()
        } catch (e: Exception) {
            Log.w("CameraXServiceImpl", "Error during unbindAll, continuing with initialization.", e)
            // Not a fatal error for initialization itself, but good to log.
        }

        _zoomStateFlow.value = null
        _exposureStateFlow.value = null
        _hasFlashUnitFlow.value = false
        _torchStateFlow.value = false
        _availableAwbModesFlow.value = emptyList()
        currentCamera = null
        activePreviewUseCase = null

        val newPreviewUseCase = Preview.Builder().build()
        this.activePreviewUseCase = newPreviewUseCase
        newPreviewUseCase.setSurfaceProvider(surfaceProvider)
        Log.d("CameraXServiceImpl", "Main surface provider set on new Preview UseCase.")

        val cameraSelector = CameraSelector.Builder().requireLensFacing(targetLensFacing).build()

        try {
            Log.d("CameraXServiceImpl", "Binding camera with lens: $targetLensFacing")
            val camera = provider.bindToLifecycle(lifecycleOwner, cameraSelector, activePreviewUseCase!!)
            currentCamera = camera
            setupCameraObservers(camera, lifecycleOwner)

            try {
                val characteristics = cameraManager.getCameraCharacteristics(camera.cameraInfo.cameraId)
                val modes = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
                _availableAwbModesFlow.value = modes?.toList() ?: emptyList()
                Log.d("CameraXServiceImpl", "Available AWB modes for ${camera.cameraInfo.cameraId}: ${_availableAwbModesFlow.value}")
            } catch (e: Exception) {
                val errorMsg = "Error getting AWB modes for ${camera.cameraInfo.cameraId}"
                Log.e("CameraXServiceImpl", errorMsg, e)
                _cameraErrorFlow.tryEmit(CameraErrorEvent.InitializationError(errorMsg, e))
                _availableAwbModesFlow.value = emptyList()
            }

            Log.d("CameraXServiceImpl", "Camera bound successfully. Flash: ${camera.cameraInfo.hasFlashUnit()}")
            return CameraInitResult.Success(camera)
        } catch (e: Exception) {
            val errorMsg = "Failed to bind camera with lens: $targetLensFacing"
            Log.e("CameraXServiceImpl", errorMsg, e)
            this.activePreviewUseCase = null
            currentCamera = null
            _availableAwbModesFlow.value = emptyList()
            _cameraErrorFlow.tryEmit(CameraErrorEvent.InitializationError(errorMsg, e))
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
        val camera = currentCamera
        if (camera == null) {
            val errorMsg = "Camera not initialized for setZoomRatio"
            Log.w("CameraXServiceImpl", errorMsg)
            _cameraErrorFlow.tryEmit(CameraErrorEvent.ControlError("setZoomRatio", errorMsg, null))
            return Futures.immediateFailedFuture(IllegalStateException(errorMsg))
        }
        Log.d("CameraXServiceImpl", "setZoomRatio($ratio) called.")
        val future = camera.cameraControl.setZoomRatio(ratio)
        future.addListener({
            try {
                future.get()
                Log.d("CameraXServiceImpl", "setZoomRatio($ratio) operation successful.")
            } catch (e: Exception) {
                Log.e("CameraXServiceImpl", "setZoomRatio($ratio) operation failed.", e)
                _cameraErrorFlow.tryEmit(CameraErrorEvent.ControlError("setZoomRatio", "Operation failed", e))
            }
        }, cameraExecutor)
        return future
    }

    override fun setExposureCompensationIndex(index: Int): ListenableFuture<Void?> {
        val camera = currentCamera
        if (camera == null) {
            val errorMsg = "Camera not initialized for setExposureCompensationIndex"
            Log.w("CameraXServiceImpl", errorMsg)
            _cameraErrorFlow.tryEmit(CameraErrorEvent.ControlError("setExposureCompensationIndex", errorMsg, null))
            return Futures.immediateFailedFuture(IllegalStateException(errorMsg))
        }
        Log.d("CameraXServiceImpl", "setExposureCompensationIndex($index) called.")
        val future = camera.cameraControl.setExposureCompensationIndex(index)
        future.addListener({
            try {
                future.get()
                Log.d("CameraXServiceImpl", "setExposureCompensationIndex($index) operation successful.")
            } catch (e: Exception) {
                Log.e("CameraXServiceImpl", "setExposureCompensationIndex($index) operation failed.", e)
                 _cameraErrorFlow.tryEmit(CameraErrorEvent.ControlError("setExposureCompensationIndex", "Operation failed", e))
            }
        }, cameraExecutor)
        return future
    }

    override fun enableTorch(enable: Boolean): ListenableFuture<Void?> {
        val camera = currentCamera
        if (camera == null) {
            val errorMsg = "Camera not initialized for enableTorch"
            Log.w("CameraXServiceImpl", errorMsg)
            _cameraErrorFlow.tryEmit(CameraErrorEvent.ControlError("enableTorch", errorMsg, null))
            return Futures.immediateFailedFuture(IllegalStateException(errorMsg))
        }
        if (enable && !hasFlashUnitFlow.value) {
            val errorMsg = "Cannot enable torch: No flash unit available."
            Log.w("CameraXServiceImpl", errorMsg)
            _cameraErrorFlow.tryEmit(CameraErrorEvent.ControlError("enableTorch", errorMsg, null))
            return Futures.immediateFailedFuture(IllegalStateException(errorMsg))
        }
        Log.d("CameraXServiceImpl", "enableTorch($enable) called.")
        val future = camera.cameraControl.enableTorch(enable)
        future.addListener({
            try {
                future.get()
                Log.d("CameraXServiceImpl", "enableTorch($enable) operation successful via future.")
            } catch (e: Exception) {
                Log.e("CameraXServiceImpl", "enableTorch($enable) operation failed via future.", e)
                _cameraErrorFlow.tryEmit(CameraErrorEvent.ControlError("enableTorch", "Operation failed to set torch state to $enable", e))
            }
        }, cameraExecutor)
        return future
    }

    override fun startFocusAndMetering(action: FocusMeteringAction): ListenableFuture<FocusMeteringResult> {
        val camera = currentCamera
        if (camera == null) {
            val errorMsg = "Camera not initialized for startFocusAndMetering"
            Log.w("CameraXServiceImpl", errorMsg)
            _cameraErrorFlow.tryEmit(CameraErrorEvent.ControlError("startFocusAndMetering", errorMsg, null))
            return Futures.immediateFailedFuture(IllegalStateException(errorMsg))
        }
        Log.d("CameraXServiceImpl", "startFocusAndMetering called with action: ${action.meteringPoints.firstOrNull()}")
        val future = camera.cameraControl.startFocusAndMetering(action)
        future.addListener({
            try {
                val result = future.get()
                Log.d("CameraXServiceImpl", "Focus/metering operation result: isFocusSuccessful=${result.isFocusSuccessful}")
            } catch (e: Exception) {
                Log.e("CameraXServiceImpl", "Focus/metering operation failed.", e)
                _cameraErrorFlow.tryEmit(CameraErrorEvent.ControlError("startFocusAndMetering", "Operation failed", e))
            }
        }, cameraExecutor)
        return future
    }

    override fun setWhiteBalanceMode(awbMode: Int): ListenableFuture<Void?> {
        val camera = currentCamera
        if (camera == null) {
            val errorMsg = "Camera not initialized for setWhiteBalanceMode"
            Log.w("CameraXServiceImpl", errorMsg)
            _cameraErrorFlow.tryEmit(CameraErrorEvent.ControlError("setWhiteBalanceMode", errorMsg, null))
            return Futures.immediateFailedFuture(IllegalStateException(errorMsg))
        }
        Log.d("CameraXServiceImpl", "setWhiteBalanceMode called with AWB Mode: $awbMode")
        try {
            val captureRequestBuilder = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE, awbMode)
            val future = Camera2CameraControl.from(camera.cameraControl).setCaptureRequestOptions(captureRequestBuilder.build())
            future.addListener({
                try {
                    future.get()
                    Log.d("CameraXServiceImpl", "Set AWB mode to $awbMode operation successful.")
                } catch (e: Exception) {
                    Log.e("CameraXServiceImpl", "Set AWB mode to $awbMode operation failed.", e)
                    _cameraErrorFlow.tryEmit(CameraErrorEvent.ControlError("setWhiteBalanceMode", "Operation failed", e))
                }
            }, cameraExecutor)
            return future
        } catch (e: Exception) {
            val errorMsg = "Failed to build/set AWB options for mode $awbMode"
            Log.e("CameraXServiceImpl", errorMsg, e)
            _cameraErrorFlow.tryEmit(CameraErrorEvent.ControlError("setWhiteBalanceMode", errorMsg, e))
            return Futures.immediateFailedFuture(e)
        }
    }

    override fun setMainSurfaceProvider(surfaceProvider: Preview.SurfaceProvider?) {
        Log.d("CameraXServiceImpl", "Setting main surface provider: ${if (surfaceProvider != null) "VALID" else "NULL"}")
        try {
            activePreviewUseCase?.setSurfaceProvider(surfaceProvider)
        } catch (e: IllegalStateException) {
            Log.e("CameraXServiceImpl", "Failed to set main surface provider", e)
            _cameraErrorFlow.tryEmit(CameraErrorEvent.ControlError("setMainSurfaceProvider", "Failed to set surface", e))
        }
    }

    override fun setExternalSurfaceProvider(surfaceProvider: Preview.SurfaceProvider?) {
        Log.d("CameraXServiceImpl", "Setting external surface provider: ${if (surfaceProvider != null) "VALID" else "NULL"}")
        try {
            activePreviewUseCase?.setSurfaceProvider(surfaceProvider)
        } catch (e: IllegalStateException) {
            Log.e("CameraXServiceImpl", "Failed to set external surface provider", e)
            _cameraErrorFlow.tryEmit(CameraErrorEvent.ControlError("setExternalSurfaceProvider", "Failed to set surface", e))
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
        _availableAwbModesFlow.value = emptyList()
        cameraExecutor.shutdown()
        currentCamera = null
        activePreviewUseCase = null
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

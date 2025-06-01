package com.example.cameraapp.fakes

import android.util.Log
import android.util.Range
import android.util.Rational
import androidx.camera.core.*
import androidx.camera.core.CameraInfo.ExposureState
import androidx.camera.core.CameraInfo.ZoomState
import androidx.lifecycle.LifecycleOwner
import com.example.cameraapp.camera.CameraErrorEvent
import com.example.cameraapp.camera.CameraInitResult
import com.example.cameraapp.camera.CameraXService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.mockk.mockk // Import mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow


class FakeCameraXService : CameraXService {

    private val _zoomStateFlow = MutableStateFlow<ZoomState?>(ZoomState.create(1.0f, 1.0f, 1.0f, 0.0f))
    override val zoomStateFlow: StateFlow<ZoomState?> = _zoomStateFlow.asStateFlow()

    private val _exposureStateFlow = MutableStateFlow<ExposureState?>(ExposureState.create(Range(0,0), 0, Rational(1,1), false))
    override val exposureStateFlow: StateFlow<ExposureState?> = _exposureStateFlow.asStateFlow()

    private val _hasFlashUnitFlow = MutableStateFlow(false)
    override val hasFlashUnitFlow: StateFlow<Boolean> = _hasFlashUnitFlow.asStateFlow()

    private val _torchStateFlow = MutableStateFlow(false)
    override val torchStateFlow: StateFlow<Boolean> = _torchStateFlow.asStateFlow()

    private val _availableAwbModesFlow = MutableStateFlow<List<Int>>(emptyList())
    override val availableAwbModesFlow: StateFlow<List<Int>> = _availableAwbModesFlow.asStateFlow()

    private val _cameraErrorFlow = MutableSharedFlow<CameraErrorEvent>()
    override val cameraErrorFlow: SharedFlow<CameraErrorEvent> = _cameraErrorFlow.asSharedFlow() // Corrected to SharedFlow

    var initCameraCalledWith: Triple<LifecycleOwner?, Preview.SurfaceProvider?, Int?>? = null
    var setZoomRatioCalledWith: Float? = null
    var setExposureIndexCalledWith: Int? = null
    var enableTorchCalledWith: Boolean? = null
    var startFocusAndMeteringCalledWith: FocusMeteringAction? = null
    var setWhiteBalanceModeCalledWith: Int? = null
    var setMainSurfaceProviderCalledWith: Preview.SurfaceProvider? = null
    var setExternalSurfaceProviderCalledWith: Preview.SurfaceProvider? = null
    var setMainSurfaceProviderCalled: Boolean = false // To track if it was called at all
    var setExternalSurfaceProviderCalled: Boolean = false // To track if it was called at all
    var shutdownCalled = false

    private val mockCamera = mockk<Camera>(relaxed = true)

    override suspend fun initializeAndBindCamera(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        targetLensFacing: Int
    ): CameraInitResult {
        initCameraCalledWith = Triple(lifecycleOwner, surfaceProvider, targetLensFacing)
        Log.d("FakeCameraXService", "initializeAndBindCamera: lens $targetLensFacing")
        if (targetLensFacing == CameraSelector.LENS_FACING_BACK) {
            _hasFlashUnitFlow.value = true
            _availableAwbModesFlow.value = listOf(
                CameraMetadata.CONTROL_AWB_MODE_AUTO,
                CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT,
                CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT,
                CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
                CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
            )
            _zoomStateFlow.value = ZoomState.create(1.0f, 1.0f, 8.0f, 0.0f)
            _exposureStateFlow.value = ExposureState.create(Range(-8,8), 0, Rational(1,3), true)
        } else {
            _hasFlashUnitFlow.value = false
            _availableAwbModesFlow.value = listOf(CameraMetadata.CONTROL_AWB_MODE_AUTO, CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT)
            _zoomStateFlow.value = ZoomState.create(1.0f, 1.0f, 1.0f, 0.0f) // No zoom for front
            _exposureStateFlow.value = ExposureState.create(Range(0,0), 0, Rational(1,1), false) // No exposure for front
        }
        _torchStateFlow.value = false // Reset torch
        return CameraInitResult.Success(mockCamera)
    }

    override fun setMainSurfaceProvider(surfaceProvider: Preview.SurfaceProvider?) {
        setMainSurfaceProviderCalled = true
        setMainSurfaceProviderCalledWith = surfaceProvider
        Log.d("FakeCameraXService", "setMainSurfaceProvider called with: $surfaceProvider")
    }

    override fun setExternalSurfaceProvider(surfaceProvider: Preview.SurfaceProvider?) {
        setExternalSurfaceProviderCalled = true
        setExternalSurfaceProviderCalledWith = surfaceProvider
        Log.d("FakeCameraXService", "setExternalSurfaceProvider called with: $surfaceProvider")
    }

    override fun setZoomRatio(ratio: Float): ListenableFuture<Void?> {
        setZoomRatioCalledWith = ratio
        val current = _zoomStateFlow.value
        if (current != null) {
             _zoomStateFlow.value = ZoomState.create(ratio.coerceIn(current.minZoomRatio, current.maxZoomRatio), current.minZoomRatio, current.maxZoomRatio, current.linearZoom)
        }
        Log.d("FakeCameraXService", "setZoomRatio: $ratio, New state: ${_zoomStateFlow.value?.zoomRatio}")
        return Futures.immediateFuture(null)
    }

    override fun setExposureCompensationIndex(index: Int): ListenableFuture<Void?> {
        setExposureIndexCalledWith = index
        val current = _exposureStateFlow.value
        if (current != null && current.isExposureCompensationSupported) {
             _exposureStateFlow.value = ExposureState.create(current.exposureCompensationRange, index.coerceIn(current.exposureCompensationRange.lower, current.exposureCompensationRange.upper), current.exposureCompensationStep, true)
        }
        Log.d("FakeCameraXService", "setExposureCompensationIndex: $index, New state: ${_exposureStateFlow.value?.exposureCompensationIndex}")
        return Futures.immediateFuture(null)
    }

    override fun enableTorch(enable: Boolean): ListenableFuture<Void?> {
        enableTorchCalledWith = enable
        if (_hasFlashUnitFlow.value) {
            _torchStateFlow.value = enable
        } else {
            _torchStateFlow.value = false // Ensure it's off if no flash
        }
        Log.d("FakeCameraXService", "enableTorch: $enable, CurrentTorchState: ${_torchStateFlow.value}")
        return Futures.immediateFuture(null)
    }

    override fun startFocusAndMetering(action: FocusMeteringAction): ListenableFuture<FocusMeteringResult> {
        startFocusAndMeteringCalledWith = action
        Log.d("FakeCameraXService", "startFocusAndMetering called for point X:${action.meteringPoints.firstOrNull()?.x} Y:${action.meteringPoints.firstOrNull()?.y}")
        return Futures.immediateFuture(FocusMeteringResult.create(true))
    }

    override fun setWhiteBalanceMode(awbMode: Int): ListenableFuture<Void?> {
        setWhiteBalanceModeCalledWith = awbMode
        Log.d("FakeCameraXService", "setWhiteBalanceMode: $awbMode")
        // Here, a real implementation might check if awbMode is in _availableAwbModesFlow.value
        // For the fake, we just record it. The ViewModel should ideally filter based on available modes.
        return Futures.immediateFuture(null)
    }

    override fun shutdown() {
        shutdownCalled = true
        Log.d("FakeCameraXService", "shutdown called")
    }

    // --- Test control methods for flows ---
    fun emitZoomState(zoomState: ZoomState?) { _zoomStateFlow.value = zoomState }
    fun emitExposureState(exposureState: ExposureState?) { _exposureStateFlow.value = exposureState }
    fun setHasFlashUnit(hasFlash: Boolean) { _hasFlashUnitFlow.value = hasFlash }
    fun setTorchState(isOn: Boolean) { _torchStateFlow.value = isOn }
    fun emitAvailableAwbModes(modes: List<Int>) { _availableAwbModesFlow.value = modes }
    fun emitCameraError(error: CameraErrorEvent) { _cameraErrorFlow.tryEmit(error) }
}

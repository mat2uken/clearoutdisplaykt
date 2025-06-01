package com.example.cameraapp

import android.util.Rational
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraInfo.ExposureState
import androidx.camera.core.CameraInfo.ZoomState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.cameraapp.camera.CameraXService
import kotlinx.coroutines.flow.*
import com.example.cameraapp.display.DisplayService
import android.view.Display
import androidx.lifecycle.SavedStateHandle
import android.hardware.camera2.CameraMetadata

class CameraViewModel(
    private val cameraXService: CameraXService,
    private val displayService: DisplayService,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        const val LENS_FACING_KEY = "lens_facing"
        const val IS_FLIPPED_KEY = "is_flipped"
        const val AWB_MODE_KEY = "awb_mode"
    }

    init {
        displayService.startListening()
    }

    private val _lensFacing = MutableStateFlow(
        savedStateHandle.get<Int>(LENS_FACING_KEY) ?: CameraSelector.LENS_FACING_BACK
    )
    val lensFacing: StateFlow<Int> = _lensFacing.asStateFlow()

    val hasFlashUnit: StateFlow<Boolean> = cameraXService.hasFlashUnitFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L, replayExpirationMillis = 0), false)

    val isLedOn: StateFlow<Boolean> = cameraXService.torchStateFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L, replayExpirationMillis = 0), false)

    private val _isFlippedHorizontally = MutableStateFlow(
        savedStateHandle.get<Boolean>(IS_FLIPPED_KEY) ?: false
    )
    val isFlippedHorizontally: StateFlow<Boolean> = _isFlippedHorizontally.asStateFlow()

    val zoomState: StateFlow<ZoomState?> = cameraXService.zoomStateFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L, replayExpirationMillis = 0), null)

    val currentZoomRatio: StateFlow<Float> = zoomState
        .mapNotNull { it?.zoomRatio }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L, replayExpirationMillis = 0), 1f)

    val minZoomRatio: StateFlow<Float> = zoomState
        .mapNotNull { it?.minZoomRatio }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L, replayExpirationMillis = 0), 1f)

    val maxZoomRatio: StateFlow<Float> = zoomState
        .mapNotNull { it?.maxZoomRatio }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L, replayExpirationMillis = 0), 1f)

    val isZoomSupported: StateFlow<Boolean> = zoomState
        .map { it != null && it.minZoomRatio < it.maxZoomRatio }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L, replayExpirationMillis = 0), false)

    val exposureState: StateFlow<ExposureState?> = cameraXService.exposureStateFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L, replayExpirationMillis = 0), null)

    val currentExposureIndex: StateFlow<Int> = exposureState
        .mapNotNull { it?.exposureCompensationIndex }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L, replayExpirationMillis = 0), 0)

    val minExposureIndex: StateFlow<Int> = exposureState
        .mapNotNull { it?.exposureCompensationRange?.lower }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L, replayExpirationMillis = 0), 0)

    val maxExposureIndex: StateFlow<Int> = exposureState
        .mapNotNull { it?.exposureCompensationRange?.upper }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L, replayExpirationMillis = 0), 0)

    val exposureStep: StateFlow<Rational?> = exposureState
        .map { it?.exposureCompensationStep }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L, replayExpirationMillis = 0), null)

    val isExposureSupported: StateFlow<Boolean> = exposureState
        .map { it?.isExposureCompensationSupported == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L, replayExpirationMillis = 0), false)

    private val _currentAwbModeInternal = MutableStateFlow(
        savedStateHandle.get<Int>(AWB_MODE_KEY) ?: CameraMetadata.CONTROL_AWB_MODE_AUTO
    )
    val currentAwbMode: StateFlow<Int> = _currentAwbModeInternal.asStateFlow()

    val externalDisplay: StateFlow<Display?> = displayService.displaysFlow
        .map { displays ->
            displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L, replayExpirationMillis = 0), null)

    private var mainSurfaceProvider: androidx.camera.core.Preview.SurfaceProvider? = null

    fun onSwitchCameraClicked() {
        _lensFacing.update { currentLensFacing ->
            if (currentLensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
        }
        savedStateHandle[LENS_FACING_KEY] = _lensFacing.value
    }

    fun onLedButtonClicked() {
        if (hasFlashUnit.value) {
            cameraXService.enableTorch(!isLedOn.value)
        }
    }

    fun onFlipClicked() {
        _isFlippedHorizontally.update { !it }
        savedStateHandle[IS_FLIPPED_KEY] = _isFlippedHorizontally.value
    }

    fun setZoomRatio(ratio: Float) {
        val currentMin = minZoomRatio.value
        val currentMax = maxZoomRatio.value
        cameraXService.setZoomRatio(ratio.coerceIn(currentMin, currentMax))
    }

    fun setExposureIndex(index: Int) {
        val currentMin = minExposureIndex.value
        val currentMax = maxExposureIndex.value
        cameraXService.setExposureCompensationIndex(index.coerceIn(currentMin, currentMax))
    }

    fun onWhiteBalanceModeSelected(mode: Int) {
        _currentAwbModeInternal.value = mode
        savedStateHandle[AWB_MODE_KEY] = _currentAwbModeInternal.value
        android.util.Log.d("CameraViewModel", "WB mode selected: $mode. Calling service.")
        cameraXService.setWhiteBalanceMode(mode)
    }

    fun primaryCameraInit(
        lifecycleOwner: androidx.lifecycle.LifecycleOwner,
        mainSurfaceProvider: androidx.camera.core.Preview.SurfaceProvider
    ) {
        this.mainSurfaceProvider = mainSurfaceProvider
        viewModelScope.launch {
            android.util.Log.d("CameraViewModel", "primaryCameraInit called with lens: ${lensFacing.value}")
            cameraXService.initializeAndBindCamera(lifecycleOwner, mainSurfaceProvider, lensFacing.value)

            if (_currentAwbModeInternal.value != CameraMetadata.CONTROL_AWB_MODE_AUTO) {
                 android.util.Log.d("CameraViewModel", "Re-applying AWB mode after init: ${_currentAwbModeInternal.value}")
                cameraXService.setWhiteBalanceMode(_currentAwbModeInternal.value)
            }
        }
    }

    fun attachExternalDisplaySurface(externalSurfaceProvider: androidx.camera.core.Preview.SurfaceProvider) {
        android.util.Log.d("CameraViewModel", "Attaching external display surface.")
        cameraXService.setMainSurfaceProvider(null)
        cameraXService.setExternalSurfaceProvider(externalSurfaceProvider)
    }

    fun detachExternalDisplaySurface() {
        android.util.Log.d("CameraViewModel", "Detaching external display surface.")
        cameraXService.setExternalSurfaceProvider(null)
        mainSurfaceProvider?.let {
            android.util.Log.d("CameraViewModel", "Restoring main display surface.")
            cameraXService.setMainSurfaceProvider(it)
        }
    }

    fun onPreviewTapped(viewWidth: Int, viewHeight: Int, x: Float, y: Float) {
        if (viewWidth == 0 || viewHeight == 0) {
            android.util.Log.w("CameraViewModel", "Preview dimensions are zero, cannot perform tap-to-focus.")
            return
        }

        val factory = androidx.camera.core.SurfaceOrientedMeteringPointFactory(viewWidth.toFloat(), viewHeight.toFloat())
        val meteringPoint = factory.createPoint(x, y)
        val action = androidx.camera.core.FocusMeteringAction.Builder(meteringPoint)
            .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        android.util.Log.d("CameraViewModel", "Requesting focus/metering at point: $meteringPoint (x=$x, y=$y, viewW=$viewWidth, viewH=$viewH=$viewHeight)")
        cameraXService.startFocusAndMetering(action)
    }

    override fun onCleared() {
        super.onCleared()
        displayService.stopListening()
    }
}

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
import com.example.cameraapp.camera.CameraErrorEvent // Import CameraErrorEvent
import com.example.cameraapp.camera.CameraInitResult // Import CameraInitResult
import kotlinx.coroutines.launch // For viewModelScope.launch

// User-facing error structure
data class UserError(val id: Long = System.nanoTime(), val message: String)

data class WbPreset(val name: String, val mode: Int)

class CameraViewModel(
    private val cameraXService: CameraXService,
    private val displayService: DisplayService,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        const val LENS_FACING_KEY = "lens_facing"
        const val IS_FLIPPED_KEY = "is_flipped"
        const val AWB_MODE_KEY = "awb_mode"

        private val allKnownWbPresets = listOf(
            WbPreset("Auto", CameraMetadata.CONTROL_AWB_MODE_AUTO),
            WbPreset("Incandescent", CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT),
            WbPreset("Fluorescent", CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT),
            WbPreset("Warm Fluorescent", CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT),
            WbPreset("Daylight", CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT),
            WbPreset("Cloudy Daylight", CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
            WbPreset("Twilight", CameraMetadata.CONTROL_AWB_MODE_TWILIGHT),
            WbPreset("Shade", CameraMetadata.CONTROL_AWB_MODE_SHADE)
        )
    }

    private val _displayedError = MutableStateFlow<UserError?>(null)
    val displayedError: StateFlow<UserError?> = _displayedError.asStateFlow()

    private val _toastMessages = MutableSharedFlow<String>(extraBufferCapacity = 3)
    val toastMessages: SharedFlow<String> = _toastMessages.asSharedFlow()

    init {
        displayService.startListening()

        viewModelScope.launch {
            cameraXService.cameraErrorFlow.collect { event ->
                when (event) {
                    is CameraErrorEvent.InitializationError -> {
                        android.util.Log.e("CameraViewModel", "InitializationError: ${event.message}", event.cause)
                        _displayedError.value = UserError(message = "Error initializing camera: ${event.message}")
                    }
                    is CameraErrorEvent.ControlError -> {
                        android.util.Log.w("CameraViewModel", "ControlError for ${event.operation}: ${event.message}", event.cause)
                        _toastMessages.tryEmit("Operation failed: ${event.operation}")
                    }
                }
            }
        }
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

    val supportedWbPresets: StateFlow<List<WbPreset>> = cameraXService.availableAwbModesFlow
        .map { availableModes ->
            if (availableModes.isEmpty()) {
                allKnownWbPresets.filter { preset -> preset.mode == CameraMetadata.CONTROL_AWB_MODE_AUTO }
            } else {
                allKnownWbPresets.filter { preset -> preset.mode in availableModes }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = listOf(WbPreset("Auto", CameraMetadata.CONTROL_AWB_MODE_AUTO)) // Default to Auto if service flow is slow
        )


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
            // cameraXService.setMainSurfaceProvider(mainSurfaceProvider) // Service's init should use the passed provider
            val result = cameraXService.initializeAndBindCamera(lifecycleOwner, mainSurfaceProvider, lensFacing.value)

            if (result is CameraInitResult.Failure) {
                android.util.Log.e("CameraViewModel", "CameraInitResult.Failure: ${result.exception.message}", result.exception)
                // _displayedError is already handled by the cameraErrorFlow collector for InitializationError
                // but direct result handling gives immediate feedback if desired.
                // _displayedError.value = UserError(message = "Failed to initialize camera: ${result.exception.localizedMessage ?: "Unknown error"}")
            } else if (result is CameraInitResult.Success) {
                val persistedAwbMode = _currentAwbModeInternal.value
                if (persistedAwbMode != CameraMetadata.CONTROL_AWB_MODE_AUTO) {
                     android.util.Log.d("CameraViewModel", "Re-applying AWB mode after init: $persistedAwbMode")
                    cameraXService.setWhiteBalanceMode(persistedAwbMode)
                }
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

    fun clearDisplayedError() {
        _displayedError.value = null
    }

    override fun onCleared() {
        super.onCleared()
        displayService.stopListening()
    }
}

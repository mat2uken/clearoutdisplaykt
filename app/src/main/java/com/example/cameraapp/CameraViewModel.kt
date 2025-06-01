package com.example.cameraapp

import android.util.Rational
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraInfo.ExposureState
import androidx.camera.core.CameraInfo.ZoomState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.cameraapp.camera.CameraXService // Import the interface
import kotlinx.coroutines.flow.*

// CameraXService will be injected (e.g. Hilt or manual factory in Activity/Fragment)
import com.example.cameraapp.display.DisplayService
import android.view.Display // Required for Display.DEFAULT_DISPLAY

class CameraViewModel(
    private val cameraXService: CameraXService,
    private val displayService: DisplayService // Add DisplayService
) : ViewModel() {

    init {
        displayService.startListening()
    }

    private val _lensFacing = MutableStateFlow(CameraSelector.LENS_FACING_BACK)
    val lensFacing: StateFlow<Int> = _lensFacing.asStateFlow()

    // --- Flash & LED ---
    // hasFlashUnit is now directly from the service's flow
    val hasFlashUnit: StateFlow<Boolean> = cameraXService.hasFlashUnitFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // isLedOn (actual torch state) is also from the service's flow
    val isLedOn: StateFlow<Boolean> = cameraXService.torchStateFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // --- Flipped ---
    private val _isFlippedHorizontally = MutableStateFlow(false)
    val isFlippedHorizontally: StateFlow<Boolean> = _isFlippedHorizontally.asStateFlow()

    // --- Zoom ---
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

    // --- Exposure ---
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


    // --- ViewModel Actions ---
    fun onSwitchCameraClicked() {
        _lensFacing.update { currentLensFacing ->
            if (currentLensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
        }
        // The UI (CameraScreen) will observe lensFacing and trigger
        // cameraXService.initializeAndBindCamera.
        // This will naturally update hasFlashUnitFlow and torchStateFlow from the service.
    }

    fun onLedButtonClicked() {
        if (hasFlashUnit.value) { // Check current capability from the service flow
            // Request to toggle the torch based on the *actual current torch state* from service
            cameraXService.enableTorch(!isLedOn.value)
        }
        // No need to update _isLedOn here; it's now a reflection of cameraXService.torchStateFlow
    }

    fun onFlipClicked() {
        _isFlippedHorizontally.update { !it }
    }

    fun setZoomRatio(ratio: Float) {
        val currentMin = minZoomRatio.value
        val currentMax = maxZoomRatio.value
        // Coerce in ViewModel to prevent unnecessary calls if slider allows out of bounds
        cameraXService.setZoomRatio(ratio.coerceIn(currentMin, currentMax))
    }

    fun setExposureIndex(index: Int) {
        val currentMin = minExposureIndex.value
        val currentMax = maxExposureIndex.value
        cameraXService.setExposureCompensationIndex(index.coerceIn(currentMin, currentMax))
    }

    // Note: initializeCamera/bindCamera equivalent will be called from the UI (Screen)
    // based on lifecycle and lensFacing changes. The ViewModel's role here is to
    // hold UI-related state and delegate actions to the service.

    // --- White Balance ---
    private val _currentAwbModeInternal = MutableStateFlow(android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_AUTO)
    val currentAwbMode: StateFlow<Int> = _currentAwbModeInternal.asStateFlow()

    // --- External Display ---
    val externalDisplay: StateFlow<Display?> = displayService.displaysFlow
        .map { displays ->
            displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L, replayExpirationMillis = 0), null)

    private var mainSurfaceProvider: androidx.camera.core.Preview.SurfaceProvider? = null


    fun onWhiteBalanceModeSelected(mode: Int) {
        _currentAwbModeInternal.value = mode
        android.util.Log.d("CameraViewModel", "WB mode selected: $mode. Calling service.")
        cameraXService.setWhiteBalanceMode(mode)
        // CameraXServiceImpl will log the future's result.
    }


    fun primaryCameraInit(
        lifecycleOwner: androidx.lifecycle.LifecycleOwner,
        mainSurfaceProvider: androidx.camera.core.Preview.SurfaceProvider // Renamed for clarity
    ) {
        this.mainSurfaceProvider = mainSurfaceProvider // Store it
        viewModelScope.launch {
            android.util.Log.d("CameraViewModel", "primaryCameraInit called with lens: ${lensFacing.value}")
            // Service will use this main provider initially. No explicit setMainSurfaceProvider needed here
            // as initializeAndBindCamera uses the passed provider as the main one.
            cameraXService.initializeAndBindCamera(lifecycleOwner, mainSurfaceProvider, lensFacing.value)

            if (_currentAwbModeInternal.value != android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_AUTO) {
                 android.util.Log.d("CameraViewModel", "Re-applying AWB mode after init: ${_currentAwbModeInternal.value}")
                cameraXService.setWhiteBalanceMode(_currentAwbModeInternal.value)
            }
        }
    }

    fun attachExternalDisplaySurface(externalSurfaceProvider: androidx.camera.core.Preview.SurfaceProvider) {
        android.util.Log.d("CameraViewModel", "Attaching external display surface.")
        cameraXService.setMainSurfaceProvider(null) // Clear main preview first
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
            .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS) // Optional: auto-cancel
            .build()

        android.util.Log.d("CameraViewModel", "Requesting focus/metering at point: $meteringPoint (x=$x, y=$y, viewW=$viewWidth, viewH=$viewH=$viewHeight)")
        cameraXService.startFocusAndMetering(action)
        // The CameraXServiceImpl will log the future's result.
    }

    override fun onCleared() {
        super.onCleared()
        displayService.stopListening()
        // cameraXService.shutdown() // This might be called here too if VM owns the service lifecycle
        // However, if service is a singleton or shared, shutdown is managed elsewhere.
        // For now, assuming service lifecycle might be tied to VM or a broader scope.
        // The subtask for MainActivity integration added a DisposableEffect for service shutdown.
    }
}

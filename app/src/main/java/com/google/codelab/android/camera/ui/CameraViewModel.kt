package com.google.codelab.android.camera.ui

import android.app.Application
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.google.codelab.android.camera.ExternalDisplayPresentation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.lang.StringBuilder
import java.util.concurrent.Executor

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val _cameraProviderFuture by lazy { ProcessCameraProvider.getInstance(application) }
    private val cameraProvider: ProcessCameraProvider get() = _cameraProviderFuture.get()
    private val mainExecutor: Executor by lazy { ContextCompat.getMainExecutor(application) }
    private val displayManager by lazy { application.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager }

    private var camera: androidx.camera.core.Camera? = null
    private var externalDisplayPresentation: ExternalDisplayPresentation? = null
    private var previewUseCase: Preview? = null
    private var currentLifecycleOwner: LifecycleOwner? = null

    // --- Camera Selection ---
    private val _selectedLensFacing = MutableStateFlow(CameraSelector.LENS_FACING_BACK)
    val selectedLensFacing: StateFlow<Int> = _selectedLensFacing.asStateFlow()

    private val _availableLenses = MutableStateFlow<List<Int>>(emptyList())
    val availableLenses: StateFlow<List<Int>> = _availableLenses.asStateFlow()

    // --- Zoom State ---
    private val _zoomRatio = MutableStateFlow(1f)
    val zoomRatio: StateFlow<Float> = _zoomRatio.asStateFlow()

    private val _minZoomRatio = MutableStateFlow(1f)
    val minZoomRatio: StateFlow<Float> = _minZoomRatio.asStateFlow()

    private val _maxZoomRatio = MutableStateFlow(1f)
    val maxZoomRatio: StateFlow<Float> = _maxZoomRatio.asStateFlow()

    private val _linearZoom = MutableStateFlow(0f) // For slider: 0f (min) to 1f (max)
    val linearZoom: StateFlow<Float> = _linearZoom.asStateFlow()


    // --- External Display State ---
    private val _isExternalDisplayConnected = MutableStateFlow(false)
    val isExternalDisplayConnected: StateFlow<Boolean> = _isExternalDisplayConnected.asStateFlow()

    private val _externalDisplayDetailedInfo = MutableStateFlow<String?>(null)
    val externalDisplayDetailedInfo: StateFlow<String?> = _externalDisplayDetailedInfo.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private lateinit var displayListener: DisplayManager.DisplayListener

    init {
        viewModelScope.launch {
            _cameraProviderFuture.addListener({
                updateAvailableLenses()
                // We now need LifecycleOwner to bind, so initial bind might be deferred
                // to when attachLifecycleOwner is called.
            }, mainExecutor)
        }
        displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                checkForExternalDisplays()
                _toastMessage.value = "External display connected"
            }
            override fun onDisplayRemoved(displayId: Int) {
                checkForExternalDisplays()
                _toastMessage.value = "External display disconnected"
            }
            override fun onDisplayChanged(displayId: Int) {
                checkForExternalDisplays()
            }
        }
        registerDisplayListener()
        checkForExternalDisplays() // Initial check
    }

    // --- Camera Control Functions ---
    fun setPreviewUseCase(preview: Preview) {
        Log.d("CameraViewModel", "New PreviewUseCase received: $preview. currentLifecycleOwner: $currentLifecycleOwner")
        this.previewUseCase = preview
        currentLifecycleOwner?.let { bindCameraUseCases(it) }
    }

    fun attachLifecycleOwner(lifecycleOwner: LifecycleOwner) {
        Log.d("CameraViewModel", "LifecycleOwner attached: $lifecycleOwner. previewUseCase: $previewUseCase")
        currentLifecycleOwner = lifecycleOwner
        if (previewUseCase != null && _cameraProviderFuture.isDone) {
            updateAvailableLenses() // Ensure lenses are checked before binding
            bindCameraUseCases(lifecycleOwner)
        }
    }

    fun detachLifecycleOwner() {
        currentLifecycleOwner = null
        cameraProvider.unbindAll()
    }

    private fun updateAvailableLenses() {
        val lenses = mutableListOf<Int>()
        if (cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
            lenses.add(CameraSelector.LENS_FACING_BACK)
        }
        if (cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
            lenses.add(CameraSelector.LENS_FACING_FRONT)
        }
        _availableLenses.value = lenses
        if (lenses.isNotEmpty() && !lenses.contains(_selectedLensFacing.value)) {
            // If current selection is not available, default to the first available
            _selectedLensFacing.value = lenses.first()
        }
    }

    fun switchCamera() {
        val currentLens = _selectedLensFacing.value
        val newLens = if (currentLens == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        if (_availableLenses.value.contains(newLens)) {
            _selectedLensFacing.value = newLens
            currentLifecycleOwner?.let { bindCameraUseCases(it) }
        }
    }

    fun setLinearZoom(linearValue: Float) {
        _linearZoom.value = linearValue.coerceIn(0f, 1f)
        val min = _minZoomRatio.value
        val max = _maxZoomRatio.value
        val newRatio = min + (max - min) * _linearZoom.value
        _zoomRatio.value = newRatio
        camera?.cameraControl?.setZoomRatio(newRatio)
    }


    private fun bindCameraUseCases(lifecycleOwner: LifecycleOwner) {
        if (previewUseCase == null) {
            _toastMessage.value = "Preview not ready for binding."
            return
        }
        if (!_cameraProviderFuture.isDone) {
            _toastMessage.value = "CameraProvider not ready."
            return
        }

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(_selectedLensFacing.value)
            .build()
        try {
            Log.d("CameraViewModel", "Binding camera use cases. Unbinding all first.")
            cameraProvider.unbindAll()
            Log.d("CameraViewModel", "Binding to lifecycle with selector: ${_selectedLensFacing.value} and preview: $previewUseCase")
            camera = cameraProvider.bindToLifecycle(
                lifecycleOwner, // USE THE PASSED LIFECYCLE OWNER
                cameraSelector,
                previewUseCase
            )
            Log.d("CameraViewModel", "Successfully bound to lifecycle. Camera: $camera")
            updateZoomLimits(camera?.cameraInfo)
            // Initial zoom update
            setLinearZoom(_linearZoom.value)

            if (_isExternalDisplayConnected.value && externalDisplayPresentation != null) {
                Log.d("CameraViewModel", "Attaching preview to external display's SurfaceProvider.")
                previewUseCase?.setSurfaceProvider(externalDisplayPresentation!!.getPreviewView().surfaceProvider)
            } else {
                Log.d("CameraViewModel", "No external display, or presentation is null. Main screen's PreviewView should be active via CameraScreen.")
            }

        } catch (exc: Exception) {
            Log.e("CameraViewModel", "Failed to bind camera use cases", exc)
            _toastMessage.value = "Failed to bind camera: ${exc.message}"
        }
    }

    private fun updateZoomLimits(cameraInfo: CameraInfo?) {
        cameraInfo?.zoomState?.value?.let { zoomState ->
            _minZoomRatio.value = zoomState.minZoomRatio
            _maxZoomRatio.value = zoomState.maxZoomRatio
            // Update linear zoom based on current ratio
            val currentRatio = zoomState.zoomRatio
            if ((zoomState.maxZoomRatio - zoomState.minZoomRatio) > 0) {
                _linearZoom.value = (currentRatio - zoomState.minZoomRatio) / (zoomState.maxZoomRatio - zoomState.minZoomRatio)
            } else {
                _linearZoom.value = 0f
            }
        }
    }

    fun clearToastMessage() {
        _toastMessage.value = null
    }

    // --- External Display Handling ---

    private fun registerDisplayListener() {
        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
    }

    private fun unregisterDisplayListener() {
        displayManager.unregisterDisplayListener(displayListener)
    }

    private fun checkForExternalDisplays() {
        val displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        if (displays.isNotEmpty()) {
            _isExternalDisplayConnected.value = true
            showExternalPresentation(displays[0])
        } else {
            _isExternalDisplayConnected.value = false
            dismissExternalPresentation()
        }
    }

    private fun showExternalPresentation(display: Display) {
        if (externalDisplayPresentation == null || externalDisplayPresentation?.display?.displayId != display.displayId) {
            dismissExternalPresentation() // Dismiss any existing one
            externalDisplayPresentation = ExternalDisplayPresentation(getApplication(), display)
            Log.d("CameraViewModel", "Showing external presentation on display: ${display.displayId}")
            externalDisplayPresentation?.show()
            // If previewUseCase is ready, connect it to the external display's PreviewView
            previewUseCase?.setSurfaceProvider(externalDisplayPresentation?.getPreviewView()?.surfaceProvider)
            Log.d("CameraViewModel", "Set surface provider for external display's PreviewView.")
        }
    }

    private fun dismissExternalPresentation() {
        externalDisplayPresentation?.dismiss()
        previewUseCase?.setSurfaceProvider(null) // Null out the surface provider
        Log.d("CameraViewModel", "External presentation dismissed, old surface provider nulled. CameraScreen will drive refresh.")
        externalDisplayPresentation = null
        clearExternalDisplayInfo() // Clear detailed info when presentation is dismissed
        // DO NOT REBIND HERE - CameraScreen's DisposableEffect will handle it if isExternalDisplayConnected changes
    }

    fun requestExternalDisplayInfo() {
        val displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        if (displays.isNotEmpty()) {
            val display = displays[0] // Assuming the first one is the one of interest
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION") // getRealMetrics is deprecated but necessary for older APIs if not handled by WindowManager
            display.getRealMetrics(metrics)

            val rotationDegrees = when (display.rotation) {
                Surface.ROTATION_0 -> "0°"
                Surface.ROTATION_90 -> "90°"
                Surface.ROTATION_180 -> "180°"
                Surface.ROTATION_270 -> "270°"
                else -> "Unknown"
            }

            val info = StringBuilder()
            info.append("Display ID: ${display.displayId}\n")
            info.append("Name: ${display.name}\n")
            info.append("Width: ${metrics.widthPixels}px\n")
            info.append("Height: ${metrics.heightPixels}px\n")
            info.append("Density DPI: ${metrics.densityDpi}\n")
            info.append("Actual X DPI: ${metrics.xdpi}\n")
            info.append("Actual Y DPI: ${metrics.ydpi}\n")
            info.append("Reported Rotation: $rotationDegrees (relative to natural orientation)\n")
            info.append("Refresh Rate: ${display.mode.refreshRate} Hz\n")
            // Add more info if deemed necessary, e.g., display.state

            _externalDisplayDetailedInfo.value = info.toString()
            Log.d("CameraViewModel", "External display info collected: ${info.toString().replace("\n", ", ")}")
        } else {
            _externalDisplayDetailedInfo.value = "No external display connected."
            Log.d("CameraViewModel", "Request for external display info, but no display connected.")
        }
    }

    fun clearExternalDisplayInfo() {
        _externalDisplayDetailedInfo.value = null
    }

    override fun onCleared() {
        super.onCleared()
        unregisterDisplayListener()
        detachLifecycleOwner() // Ensure unbinding and cleanup
        dismissExternalPresentation()
    }
}

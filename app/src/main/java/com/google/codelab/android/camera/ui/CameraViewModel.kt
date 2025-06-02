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
    // private var previewUseCase: Preview? = null // Replaced by _activePreviewUseCase
    private var currentLifecycleOwner: LifecycleOwner? = null

    // --- Active Preview Use Case (managed by ViewModel now) ---
    private val _activePreviewUseCase = MutableStateFlow<Preview?>(null)
    val activePreviewUseCase: StateFlow<Preview?> = _activePreviewUseCase.asStateFlow()

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

    private val _externalDisplayRotationDegrees = MutableStateFlow(Surface.ROTATION_0) // Use Surface constants
    val externalDisplayRotationDegrees: StateFlow<Int> = _externalDisplayRotationDegrees.asStateFlow()

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
                Log.d("CameraViewModel", "Display $displayId added. Resetting external display rotation to Surface.ROTATION_0.")
                _externalDisplayRotationDegrees.value = Surface.ROTATION_0 // Reset rotation for any new display
                checkForExternalDisplays() // This will call showExternalPresentation
                _toastMessage.value = "External display connected"
                requestExternalDisplayInfo()
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
    // fun setPreviewUseCase(preview: Preview) { // Removed
    //     Log.d("CameraViewModel", "New PreviewUseCase received: $preview. currentLifecycleOwner: $currentLifecycleOwner")
    //     this.previewUseCase = preview
    //     currentLifecycleOwner?.let { bindCameraUseCases(it) }
    // }

    fun attachLifecycleOwner(lifecycleOwner: LifecycleOwner) {
        Log.d("CameraViewModel", "LifecycleOwner attached: $lifecycleOwner.")
        currentLifecycleOwner = lifecycleOwner
        if (_cameraProviderFuture.isDone) { // Check if camera provider is ready
            updateAvailableLenses() // Ensure lenses are checked before binding
            bindCameraUseCases(lifecycleOwner) // Create and bind Preview here
        } else {
            // Listener on _cameraProviderFuture in init block will eventually call bind.
            _cameraProviderFuture.addListener({
                 Log.d("CameraViewModel", "CameraProvider ready via listener in attachLifecycleOwner. Binding use cases.")
                 updateAvailableLenses()
                 bindCameraUseCases(lifecycleOwner)
            }, mainExecutor)
        }
    }

    fun detachLifecycleOwner() {
        Log.d("CameraViewModel", "LifecycleOwner detached. Unbinding all and clearing active Preview.")
        currentLifecycleOwner = null
        cameraProvider.unbindAll()
        _activePreviewUseCase.value = null
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
        if (!_cameraProviderFuture.isDone) {
            _toastMessage.value = "CameraProvider not ready for binding."
            Log.w("CameraViewModel", "CameraProvider not ready for binding.")
            // It's possible attachLifecycleOwner's listener will call this again when provider is ready.
            return
        }
        Log.d("CameraViewModel", "bindCameraUseCases called. Unbinding all first.")
        cameraProvider.unbindAll()
        // Clear old preview use case's surface provider explicitly *before* nulling out the use case
        _activePreviewUseCase.value?.setSurfaceProvider(null)
        _activePreviewUseCase.value = null

        Log.d("CameraViewModel", "Creating Preview use case with targetRotation: ${_externalDisplayRotationDegrees.value}")
        val newPreview = Preview.Builder()
            .setTargetRotation(_externalDisplayRotationDegrees.value)
            .build()

        _activePreviewUseCase.value = newPreview // Expose the new preview use case

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(_selectedLensFacing.value)
            .build()
        try {
            Log.d("CameraViewModel", "Binding to lifecycle with selector: ${_selectedLensFacing.value}")
            // The Preview object is now created here. CameraScreen will observe _activePreviewUseCase
            // and set its own PreviewView's surfaceProvider.
            camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                newPreview
            )
            Log.d("CameraViewModel", "Successfully bound to lifecycle. Camera: $camera")
            updateZoomLimits(camera?.cameraInfo)
            setLinearZoom(_linearZoom.value)

            // If an external display is active, immediately set its surface provider.
            // CameraScreen will handle the main display's surface provider via observing _activePreviewUseCase.
            if (_isExternalDisplayConnected.value && externalDisplayPresentation != null) {
                Log.d("CameraViewModel", "External display is connected, setting its SurfaceProvider on new Preview.")
                newPreview.setSurfaceProvider(externalDisplayPresentation!!.getPreviewView().surfaceProvider)
            }

        } catch (exc: Exception) {
            Log.e("CameraViewModel", "Failed to bind camera use cases with new preview", exc)
            _activePreviewUseCase.value = null
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
        // Dismiss any existing presentation if it's for a different display or if current one is simply non-null
        // This check ensures we only proceed if we're truly setting up for 'display'
        // or if there's no current presentation.
        if (externalDisplayPresentation != null && externalDisplayPresentation?.display?.displayId != display.displayId) {
            dismissExternalPresentation(isPartOfRotationSequence = false) // It's a new display, so not part of rotation for old one
        } else if (externalDisplayPresentation != null && externalDisplayPresentation?.display?.displayId == display.displayId) {
            // This means we are "showing" the same display again, likely after a rotation.
            // The old presentation instance for this display was already dismissed by rotateExternalDisplay()
            // with isPartOfRotationSequence = true.
            // So, externalDisplayPresentation should be null here if called from rotateExternalDisplay.
            // If it's not null, it means something else called showExternalPresentation for the same display
            // without dismissing. This shouldn't typically happen with current logic.
            // For safety, dismiss it if it exists and we are trying to "show" it again.
             Log.w("CameraViewModel", "showExternalPresentation called for the same display that's supposedly active. Dismissing first.")
             dismissExternalPresentation(isPartOfRotationSequence = false) // Treat as a reset if this path is hit unexpectedly
        }


        // At this point, externalDisplayPresentation should be null, or we are about to replace it.
        // The _externalDisplayRotationDegrees.value is either 0 (for a new display via onDisplayAdded)
        // or the value set by rotateExternalDisplay().
        // ExternalDisplayPresentation constructor is now 2-argument: (Context, Display)
        externalDisplayPresentation = ExternalDisplayPresentation(
            getApplication(),
            display
        )
        Log.d("CameraViewModel", "Showing external presentation on display: ${display.displayId}. Rotation is handled by Preview use case.")
        externalDisplayPresentation?.show()
        // The following line used a stale reference 'previewUseCase'. It should be '_activePreviewUseCase.value'.
        // And this logic is now in bindCameraUseCases and after externalDisplayPresentation.show()
        // _activePreviewUseCase.value?.setSurfaceProvider(externalDisplayPresentation?.getPreviewView()?.surfaceProvider)
        // Corrected logic for setting surface provider on the *active* use case:

        // Temporarily disabled for blue screen test:
        // _activePreviewUseCase.value?.let { preview ->
        //     Log.d("CameraViewModel", "Setting surface provider for external display's PreviewView from active PreviewUseCase in showExternalPresentation.")
        //     preview.setSurfaceProvider(externalDisplayPresentation?.getPreviewView()?.surfaceProvider)
        // } ?: run {
        //     Log.w("CameraViewModel", "Cannot set surface provider in showExternalPresentation: _activePreviewUseCase is null.")
        // }
        Log.d("CameraViewModel", "Surface provider for external display temporarily NOT set due to blue screen test.")
        requestExternalDisplayInfo() // Request info when presentation is shown
    }

    private fun dismissExternalPresentation(isPartOfRotationSequence: Boolean = false) {
        externalDisplayPresentation?.dismiss()
        // The following line used a stale reference 'previewUseCase'. It should be '_activePreviewUseCase.value'.
        // _activePreviewUseCase.value?.setSurfaceProvider(null) // This is correct from previous step.
        Log.d("CameraViewModel", "External presentation dismissed. Part of rotation: $isPartOfRotationSequence")
        externalDisplayPresentation = null
        clearExternalDisplayInfo() // Clear detailed info when presentation is dismissed
        if (!isPartOfRotationSequence) {
            // _externalDisplayRotationDegrees.value = 0 // This was Surface.ROTATION_0 previously
            _externalDisplayRotationDegrees.value = Surface.ROTATION_0 // Correcting to use Surface constant
            Log.d("CameraViewModel", "Rotation reset to Surface.ROTATION_0 (not part of rotation sequence).")
        }
        // DO NOT REBIND HERE - CameraScreen's DisposableEffect will handle it if isExternalDisplayConnected changes
    }

    fun rotateExternalDisplay() {
        val currentRotationDegrees = _externalDisplayRotationDegrees.value
        val newRotationDegrees = (currentRotationDegrees + 90) % 360
        _externalDisplayRotationDegrees.value = newRotationDegrees // Set the new desired rotation

        Log.d("CameraViewModel", "Rotate external display requested. New rotation: $newRotationDegrees degrees.")

        val currentDisplay = externalDisplayPresentation?.display
        if (isExternalDisplayConnected.value && currentDisplay != null) {
            // We have an active presentation on a display.
            // Dismiss it (signaling it's part of rotation so rotation state isn't zeroed)
            // and then show it again (it will pick up the new _externalDisplayRotationDegrees value).
            dismissExternalPresentation(isPartOfRotationSequence = true)
            showExternalPresentation(currentDisplay) // This will use the new _externalDisplayRotationDegrees
            _toastMessage.value = "External display rotated to $newRotationDegrees°"
        } else if (isExternalDisplayConnected.value) {
            // A display is connected, but no presentation is currently active.
            Log.d("CameraViewModel", "Rotate requested: Display connected, but no active presentation. Attempting to show new one.")
            val displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            if (displays.isNotEmpty()) {
                // _externalDisplayRotationDegrees is already set to newRotationDegrees.
                // showExternalPresentation will use this value.
                showExternalPresentation(displays[0])
                _toastMessage.value = "External display shown with $newRotationDegrees° rotation."
            } else {
                Log.e("CameraViewModel", "Rotate requested: isExternalDisplayConnected true, but no displays found by DisplayManager.")
                _toastMessage.value = "Error: No display found to rotate."
                _externalDisplayRotationDegrees.value = currentRotationDegrees // Revert to old rotation
            }
        } else {
            Log.d("CameraViewModel", "Rotate requested: No external display connected.")
            _toastMessage.value = "No external display to rotate."
             _externalDisplayRotationDegrees.value = currentRotationDegrees // Revert to old rotation as action failed
        }
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

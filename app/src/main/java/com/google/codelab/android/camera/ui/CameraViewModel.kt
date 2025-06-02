package com.google.codelab.android.camera.ui

import android.app.Application
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
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
import java.util.concurrent.Executor

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val _cameraProviderFuture by lazy { ProcessCameraProvider.getInstance(application) }
    private val cameraProvider: ProcessCameraProvider get() = _cameraProviderFuture.get()
    private val mainExecutor: Executor by lazy { ContextCompat.getMainExecutor(application) }
    private val displayManager by lazy { application.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager }

    private var camera: androidx.camera.core.Camera? = null
    private var externalDisplayPresentation: ExternalDisplayPresentation? = null
    private var previewUseCase: Preview? = null
    private var externalPreviewUseCase: Preview? = null
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

            val useCases = mutableListOf<UseCase>()
            previewUseCase?.let { useCases.add(it) }

            if (_isExternalDisplayConnected.value && externalDisplayPresentation != null && externalPreviewUseCase != null) {
                externalPreviewUseCase?.let { useCases.add(it) }
                Log.d("CameraViewModel", "Adding externalPreviewUseCase to bind list.")
            }

            if (useCases.isNotEmpty()) {
                camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner, // USE THE PASSED LIFECYCLE OWNER
                    cameraSelector,
                    *useCases.toTypedArray()
                )
                Log.d("CameraViewModel", "Successfully bound to lifecycle. Camera: $camera. Bound ${useCases.size} use cases.")
            } else {
                Log.w("CameraViewModel", "No use cases to bind.")
                // Optionally, unbind all if no use cases are available, though previewUseCase null check at start should prevent this.
                cameraProvider.unbindAll()
            }
            Log.d("CameraViewModel", "Successfully bound to lifecycle. Camera: $camera")
            updateZoomLimits(camera?.cameraInfo)
            // Initial zoom update
            setLinearZoom(_linearZoom.value)

            // This logic for setting surface provider on external display is now handled in showExternalPresentation
            // and through externalPreviewUseCase.
            // if (_isExternalDisplayConnected.value && externalDisplayPresentation != null) {
            // Log.d("CameraViewModel", "Attaching preview to external display's SurfaceProvider.")
            // previewUseCase?.setSurfaceProvider(externalDisplayPresentation!!.getPreviewView().surfaceProvider)
            // } else {
            // Log.d("CameraViewModel", "No external display, or presentation is null. Main screen's PreviewView should be active via CameraScreen.")
            // }

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
            // Dismiss any existing presentation on a different display.
            // This also handles nulling out the old externalPreviewUseCase and its surface provider.
            dismissExternalPresentation()

            Log.d("CameraViewModel", "Creating new ExternalDisplayPresentation for display: ${display.displayId}")
            externalDisplayPresentation = ExternalDisplayPresentation(getApplication(), display)

            Log.d("CameraViewModel", "Creating new externalPreviewUseCase.")
            externalPreviewUseCase = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9) // Example: Set aspect ratio
                .build()

            externalDisplayPresentation?.show() // Show the presentation UI on the external display

            // Set the surface provider for the new externalPreviewUseCase
            externalPreviewUseCase?.setSurfaceProvider(externalDisplayPresentation?.getPreviewView()?.surfaceProvider)
            Log.d("CameraViewModel", "Set surface provider for externalPreviewUseCase on display ${display.displayId}.")

            // Rebind use cases to include the new externalPreviewUseCase
            currentLifecycleOwner?.let {
                Log.d("CameraViewModel", "Rebinding camera use cases after showing external presentation.")
                bindCameraUseCases(it)
            }
        } else {
            // Presentation already shown on this display, ensure surface provider is set (e.g. if app was paused and resumed)
            // This might be redundant if bindCameraUseCases handles it, but good for robustness.
            externalPreviewUseCase?.setSurfaceProvider(externalDisplayPresentation?.getPreviewView()?.surfaceProvider)
            Log.d("CameraViewModel", "External presentation already active. Ensured surface provider for externalPreviewUseCase.")
        }
    }

    private fun dismissExternalPresentation() {
        Log.d("CameraViewModel", "Dismissing external presentation.")
        externalDisplayPresentation?.dismiss()
        externalDisplayPresentation = null

        externalPreviewUseCase?.setSurfaceProvider(null)
        Log.d("CameraViewModel", "Cleared surface provider for externalPreviewUseCase.")
        externalPreviewUseCase = null
        Log.d("CameraViewModel", "Set externalPreviewUseCase to null.")

        // Rebind use cases to remove the externalPreviewUseCase
        currentLifecycleOwner?.let {
            Log.d("CameraViewModel", "Rebinding camera use cases after dismissing external presentation.")
            bindCameraUseCases(it)
        }
        // The old line: previewUseCase?.setSurfaceProvider(null) is removed as it's not needed.
        // Main preview's surface is managed by CameraScreen.
        Log.d("CameraViewModel", "External presentation dismissed. Main preview surface provider unchanged by this method.")
    }

    override fun onCleared() {
        super.onCleared()
        unregisterDisplayListener()
        detachLifecycleOwner() // Ensure unbinding and cleanup
        dismissExternalPresentation()
    }
}

package com.google.codelab.android.camera.ui

import android.app.Application
// import android.content.Context // ContextCompat.getMainExecutor needs it, Application is a Context.
import android.util.Log
import android.view.Surface // Still needed for Surface.ROTATION_0
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
// import androidx.compose.runtime.mutableStateOf // Not directly used in this file
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
// import com.google.codelab.android.camera.ExternalDisplayPresentation // Removed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
// import java.lang.StringBuilder // Removed
import java.util.concurrent.Executor

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val _cameraProviderFuture by lazy { ProcessCameraProvider.getInstance(application) }
    private val cameraProvider: ProcessCameraProvider get() = _cameraProviderFuture.get()
    private val mainExecutor: Executor by lazy { ContextCompat.getMainExecutor(application) }
    // private val displayManager by lazy { application.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager } // Removed

    private var camera: androidx.camera.core.Camera? = null
    // private var externalDisplayPresentation: ExternalDisplayPresentation? = null // Removed
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


    // --- External Display State --- // All Removed
    // private val _isExternalDisplayConnected = MutableStateFlow(false)
    // val isExternalDisplayConnected: StateFlow<Boolean> = _isExternalDisplayConnected.asStateFlow()

    // private val _externalDisplayDetailedInfo = MutableStateFlow<String?>(null)
    // val externalDisplayDetailedInfo: StateFlow<String?> = _externalDisplayDetailedInfo.asStateFlow()

    // private val _externalDisplayRotationDegrees = MutableStateFlow(Surface.ROTATION_0) // Use Surface constants
    // val externalDisplayRotationDegrees: StateFlow<Int> = _externalDisplayRotationDegrees.asStateFlow()

    // private val _cameraOutputResolution = MutableStateFlow<String?>(null)
    // val cameraOutputResolution: StateFlow<String?> = _cameraOutputResolution.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    // private lateinit var displayListener: DisplayManager.DisplayListener // Removed

    init {
        viewModelScope.launch {
            _cameraProviderFuture.addListener({
                updateAvailableLenses()
                // We now need LifecycleOwner to bind, so initial bind might be deferred
                // to when attachLifecycleOwner is called.
                // If currentLifecycleOwner is already set, trigger a bind.
                currentLifecycleOwner?.let {
                    if (_cameraProviderFuture.isDone) { // Ensure provider is truly ready
                        Log.d("CameraViewModel", "CameraProvider ready via future listener in init. Binding use cases.")
                        bindCameraUseCases(it)
                    }
                }
            }, mainExecutor)
        }
        // All display listener logic removed
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
        // _cameraOutputResolution.value = null // This StateFlow is being removed
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

        // Log.d("CameraViewModel", "Creating Preview use case with targetRotation: ${_externalDisplayRotationDegrees.value}") // _externalDisplayRotationDegrees removed
        Log.d("CameraViewModel", "Creating Preview use case with targetRotation: Surface.ROTATION_0")
        val newPreview = Preview.Builder()
            .setTargetRotation(Surface.ROTATION_0) // Default to Surface.ROTATION_0
            .build()

        _activePreviewUseCase.value = newPreview // Expose the new preview use case

        Log.d("CameraViewModel", "bindCameraUseCases: Entering. About to unbind all. Current main previewUseCase: ${this.previewUseCase}, its SurfaceInfo: (see notes in task). Current externalPreviewUseCase: ${this.externalPreviewUseCase}, its SurfaceInfo: (see notes in task).")
        cameraProvider.unbindAll() // Existing unbind

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
                *useCases.toTypedArray()
            )
            Log.d("CameraViewModel", "bindCameraUseCases: Successfully bound to lifecycle. Camera: $camera. Main previewUseCase: ${this.previewUseCase}. External externalPreviewUseCase: ${this.externalPreviewUseCase}.")
            // Log.d("CameraViewModel", "Successfully bound to lifecycle. Camera: $camera. Bound ${useCases.size} use cases.") // Replaced by more detailed log
            // Log.d("CameraViewModel", "Binding to lifecycle with selector: ${_selectedLensFacing.value} and preview: $previewUseCase") // This log is too generic now

            updateZoomLimits(camera?.cameraInfo)
            setLinearZoom(_linearZoom.value)

            // Synchronous resolution info retrieval removed as _cameraOutputResolution is removed.

            // External display surface provider logic removed.
            // if (_isExternalDisplayConnected.value && externalDisplayPresentation != null) {
            //     Log.d("CameraViewModel", "External display is connected, setting its SurfaceProvider on new Preview.")
            //     newPreview.setSurfaceProvider(externalDisplayPresentation!!.getPreviewView().surfaceProvider)
            // }
        } catch (exc: Exception) {
            Log.e("CameraViewModel", "Failed to bind camera use cases with new preview", exc)
            _activePreviewUseCase.value = null
            // _cameraOutputResolution.value = null; // This StateFlow is removed
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

    // --- External Display Handling --- // All methods removed
    // private fun registerDisplayListener() { ... }
    // private fun unregisterDisplayListener() { ... }
    // private fun checkForExternalDisplays() { ... }
    // private fun showExternalPresentation(display: Display) { ... }
    // private fun dismissExternalPresentation(isPartOfRotationSequence: Boolean = false) { ... }
    // fun rotateExternalDisplay() { ... }
    // fun requestExternalDisplayInfo() { ... }
    // fun clearExternalDisplayInfo() { ... }

    override fun onCleared() {
        super.onCleared()
        // unregisterDisplayListener() // Removed
        detachLifecycleOwner() // Ensure unbinding and cleanup
        // dismissExternalPresentation() // Removed
    }
}

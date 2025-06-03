package app.mat2uken.android.app.clearextoutcamera

import android.content.Context
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraManager(private val context: Context) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private lateinit var cameraExecutor: ExecutorService
    private var currentCameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

    init {
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    fun startCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val cameraProviderFuture: ListenableFuture<ProcessCameraProvider> = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindPreview(lifecycleOwner, previewView)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting camera provider: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindPreview(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        cameraProvider?.let { provider ->
            try {
                provider.unbindAll() // Unbind previous use cases

                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    currentCameraSelector,
                    preview
                )
                Log.d(TAG, "Camera bound successfully with selector: $currentCameraSelector")
            } catch (e: Exception) {
                Log.e(TAG, "Use case binding failed: ${e.message}", e)
            }
        } ?: Log.e(TAG, "CameraProvider is null, cannot bind preview.")
    }

    fun setZoomRatio(zoomRatio: Float) {
        camera?.cameraControl?.setZoomRatio(zoomRatio)?.addListener({
            Log.d(TAG, "Zoom ratio set to $zoomRatio")
        }, cameraExecutor) ?: Log.e(TAG, "Camera is null, cannot set zoom ratio.")
    }

    @Suppress("unused") // Potentially used by UI later
    fun getZoomState(): LiveData<ZoomState>? {
        return camera?.cameraInfo?.zoomState
    }


    fun getAvailableCameras(): List<CameraSelector> {
        val selectors = mutableListOf<CameraSelector>()
        cameraProvider?.let { provider ->
            if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                selectors.add(CameraSelector.DEFAULT_BACK_CAMERA)
            }
            if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                selectors.add(CameraSelector.DEFAULT_FRONT_CAMERA)
            }
            // You could potentially iterate through all available CameraInfo objects
            // to find more cameras if needed, e.g., external cameras.
            // val availableCameraInfos = provider.availableCameraInfos
            // availableCameraInfos.forEach { cameraInfo ->
            //     // Create CameraSelector from CameraInfo if needed
            // }
        } ?: Log.w(TAG, "CameraProvider not available to get cameras.")
        if (selectors.isEmpty()) {
            Log.w(TAG, "No default back or front cameras found. Returning empty list.")
        }
        return selectors
    }


    fun selectCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView, selector: CameraSelector) {
        if (cameraProvider == null) {
            Log.e(TAG, "CameraProvider is null. Cannot select camera.")
            // Optionally, you could try to re-initialize or call startCamera here
            return
        }
        if (cameraProvider?.hasCamera(selector) == true) {
            currentCameraSelector = selector
            Log.d(TAG, "Switching camera to: $selector")
            bindPreview(lifecycleOwner, previewView) // Rebind with the new selector
        } else {
            Log.e(TAG, "Camera selector $selector is not available on this device.")
        }
    }

    fun shutdown() {
        cameraExecutor.shutdown()
        // CameraProvider resources are managed by CameraX library, no explicit shutdown needed for it here
        // unless you are manually calling ProcessCameraProvider.shutdown() elsewhere for the whole process.
        Log.d(TAG, "CameraManager shutdown.")
    }

    companion object {
        private const val TAG = "CameraManager"
    }
}

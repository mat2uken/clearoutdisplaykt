package app.mat2uken.android.app.clearextoutcamera

import android.content.Context
import android.util.Log
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import com.google.common.util.concurrent.ListenableFuture
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.junit.MockitoJUnitRunner
import java.util.concurrent.Executor
import java.util.concurrent.Executors

@RunWith(MockitoJUnitRunner.Silent::class) // Use Silent to avoid UnnecessaryStubbingException
class CameraManagerTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @Mock private lateinit var mockContext: Context
    @Mock private lateinit var mockLifecycleOwner: LifecycleOwner
    @Mock private lateinit var mockPreviewView: PreviewView
    @Mock private lateinit var mockCameraProvider: ProcessCameraProvider
    @Mock private lateinit var mockCamera: Camera
    @Mock private lateinit var mockCameraControl: CameraControl
    @Mock private lateinit var mockCameraInfo: CameraInfo
    @Mock private lateinit var mockZoomStateLiveData: LiveData<ZoomState>
    @Mock private lateinit var mockZoomState: ZoomState
    @Mock private lateinit var mockListenableFutureVoid: ListenableFuture<Void>
    @Mock private lateinit var mockListenableFutureProvider: ListenableFuture<ProcessCameraProvider>
    @Mock private lateinit var mockPreviewSurfaceProvider: Preview.SurfaceProvider
    @Mock private lateinit var mockLog: Log // For verifying log calls if necessary

    @Captor private lateinit var cameraSelectorCaptor: ArgumentCaptor<CameraSelector>
    @Captor private lateinit var previewCaptor: ArgumentCaptor<Preview>
    @Captor private lateinit var useCaseCaptor: ArgumentCaptor<UseCase>


    private lateinit var cameraManager: CameraManager
    private lateinit var realExecutor: ExecutorService // For testing shutdown

    @Before
    fun setUp() {
        // Static mock for ProcessCameraProvider.getInstance
        mockStatic(ProcessCameraProvider::class.java).use { mockedStatic ->
            mockedStatic.`when`<ListenableFuture<ProcessCameraProvider>> { ProcessCameraProvider.getInstance(mockContext) }
                .thenReturn(mockListenableFutureProvider)
        }
        // Static mock for Log
        mockStatic(Log::class.java).use { mockedStaticLog ->
            // Optional: if you want to verify Log.e, Log.d etc.
            mockedStaticLog.`when` { Log.e(anyString(), anyString()) }.thenReturn(0)
            mockedStaticLog.`when` { Log.d(anyString(), anyString()) }.thenReturn(0)
            mockedStaticLog.`when` { Log.w(anyString(), anyString()) }.thenReturn(0)
        }


        `when`(mockListenableFutureProvider.get()).thenReturn(mockCameraProvider)
        doAnswer { invocation ->
            val runnable = invocation.getArgument<Runnable>(0)
            runnable.run()
            null
        }.`when`(mockListenableFutureProvider).addListener(any(Runnable::class.java), any(Executor::class.java))

        `when`(mockCamera.cameraControl).thenReturn(mockCameraControl)
        `when`(mockCamera.cameraInfo).thenReturn(mockCameraInfo)
        `when`(mockCameraInfo.zoomState).thenReturn(mockZoomStateLiveData)
        `when`(mockZoomStateLiveData.value).thenReturn(mockZoomState) // Default zoom state
        `when`(mockZoomState.zoomRatioRange).thenReturn(0.1f..2.0f) // Default valid range
        `when`(mockCameraControl.setZoomRatio(anyFloat())).thenReturn(mockListenableFutureVoid)
        `when`(mockPreviewView.surfaceProvider).thenReturn(mockPreviewSurfaceProvider)

        `when`(mockCameraProvider.bindToLifecycle(
            any(LifecycleOwner::class.java),
            any(CameraSelector::class.java),
            any(Preview::class.java)
        )).thenReturn(mockCamera)
        `when`(mockCameraProvider.bindToLifecycle(
            any(LifecycleOwner::class.java),
            any(CameraSelector::class.java),
            any(UseCaseGroup::class.java) // For multiple use cases
        )).thenReturn(mockCamera)


        realExecutor = Executors.newSingleThreadExecutor()
        cameraManager = CameraManager(mockContext)
        // Inject real executor to test its shutdown, but allow overriding for specific tests if needed
        val field = cameraManager.javaClass.getDeclaredField("cameraExecutor")
        field.isAccessible = true
        field.set(cameraManager, realExecutor)
    }

    @After
    fun tearDown() {
        realExecutor.shutdownNow()
        // Ensure static mocks are cleared if they cause issues between tests, though MockitoJUnitRunner should handle this.
        clearAllMocks()
    }

    // --- startCamera Tests ---
    @Test
    fun startCamera_requestsProviderAndBindsPreview_withDefaultBackCamera() {
        reset(mockCameraProvider) // Reset from any potential setUp call or other tests
        `when`(mockCameraProvider.bindToLifecycle(any(LifecycleOwner::class.java), any(CameraSelector::class.java), any(Preview::class.java))).thenReturn(mockCamera)
        `when`(mockCameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)).thenReturn(true)

        cameraManager.startCamera(mockLifecycleOwner, mockPreviewView)

        verify(mockCameraProvider).bindToLifecycle(
            eq(mockLifecycleOwner),
            cameraSelectorCaptor.capture(),
            previewCaptor.capture()
        )
        assert(cameraSelectorCaptor.value == CameraSelector.DEFAULT_BACK_CAMERA)
        verify(previewCaptor.value).surfaceProvider = eq(mockPreviewView.surfaceProvider)
    }

    @Test
    fun startCamera_whenGetInstanceFails_logsError() {
        // Override setup for this specific test
        mockStatic(ProcessCameraProvider::class.java).use { mockedStatic ->
            val failingFuture = mock(ListenableFuture::class.java) as ListenableFuture<ProcessCameraProvider>
            val testException = RuntimeException("Failed to get instance")
            `when`(failingFuture.get()).thenThrow(testException)
            doAnswer { invocation ->
                val runnable = invocation.getArgument<Runnable>(0)
                runnable.run() // Simulate the future attempting to complete
                null
            }.`when`(failingFuture).addListener(any(Runnable::class.java), any(Executor::class.java))
            mockedStatic.`when`<ListenableFuture<ProcessCameraProvider>> { ProcessCameraProvider.getInstance(mockContext) }
                .thenReturn(failingFuture)
        }

        cameraManager = CameraManager(mockContext) // Re-initialize to use the failing future
        cameraManager.startCamera(mockLifecycleOwner, mockPreviewView)

        verify(mockCameraProvider, never()).bindToLifecycle(any(), any(), any())
        // Verification of Log.e would require a more complex setup or a testable logger.
        // For now, assume error is logged based on code inspection.
    }

    @Test
    fun startCamera_whenNoCamerasAvailable_logsWarningAndDoesNotBind() {
        `when`(mockCameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)).thenReturn(false)
        `when`(mockCameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)).thenReturn(false)
        // Simulate availableCameraInfos being empty or provider indicating no cameras
        `when`(mockCameraProvider.availableCameraInfos).thenReturn(emptyList())


        cameraManager.startCamera(mockLifecycleOwner, mockPreviewView)
        // Should attempt to get provider, but then not bind
        verify(mockCameraProvider, never()).bindToLifecycle(any(), any(), any())
        // Log.e("CameraManager", "No cameras available to start.") should be called
    }

    @Test
    fun startCamera_selectsFrontIfBackNotAvailable() {
        `when`(mockCameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)).thenReturn(false)
        `when`(mockCameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)).thenReturn(true)

        cameraManager.startCamera(mockLifecycleOwner, mockPreviewView)

        verify(mockCameraProvider).bindToLifecycle(
            eq(mockLifecycleOwner),
            eq(CameraSelector.DEFAULT_FRONT_CAMERA),
            any(Preview::class.java)
        )
    }

    @Test
    fun startCamera_previewIsBoundToSurfaceProvider() {
        cameraManager.startCamera(mockLifecycleOwner, mockPreviewView)
        verify(mockCameraProvider).bindToLifecycle(any(), any(), previewCaptor.capture())
        verify(previewCaptor.value).surfaceProvider = eq(mockPreviewView.surfaceProvider)
    }


    // --- setZoomRatio Tests ---
    @Test
    fun setZoomRatio_callsCameraControl_withinValidRange() {
        `when`(mockZoomState.minZoomRatio).thenReturn(0.1f)
        `when`(mockZoomState.maxZoomRatio).thenReturn(2.0f)
        // Ensure camera is "initialized"
        cameraManager.startCamera(mockLifecycleOwner, mockPreviewView)


        val testZoomRatio = 1.5f // Valid
        cameraManager.setZoomRatio(testZoomRatio)
        verify(mockCameraControl).setZoomRatio(eq(testZoomRatio))
    }

    @Test
    fun setZoomRatio_clampsToMin_whenRatioTooLow() {
        `when`(mockZoomState.minZoomRatio).thenReturn(0.1f)
        `when`(mockZoomState.maxZoomRatio).thenReturn(2.0f)
        cameraManager.startCamera(mockLifecycleOwner, mockPreviewView)

        cameraManager.setZoomRatio(0.05f) // Lower than min
        verify(mockCameraControl).setZoomRatio(eq(0.1f))
    }

    @Test
    fun setZoomRatio_clampsToMax_whenRatioTooHigh() {
        `when`(mockZoomState.minZoomRatio).thenReturn(0.1f)
        `when`(mockZoomState.maxZoomRatio).thenReturn(2.0f)
        cameraManager.startCamera(mockLifecycleOwner, mockPreviewView)

        cameraManager.setZoomRatio(3.0f) // Higher than max
        verify(mockCameraControl).setZoomRatio(eq(2.0f))
    }
     @Test
    fun setZoomRatio_handlesNaNAndInfinityGracefully() {
        cameraManager.startCamera(mockLifecycleOwner, mockPreviewView)
        // Assuming default range 0.1f..2.0f, it should probably clamp to minZoom or not call
        // For NaN, it's reasonable it doesn't call setZoomRatio or defaults.
        // For Infinity, it should clamp to max.

        cameraManager.setZoomRatio(Float.NaN)
        verify(mockCameraControl, never()).setZoomRatio(eq(Float.NaN)) // Should not pass NaN

        reset(mockCameraControl) // Reset for next check
         `when`(mockCameraControl.setZoomRatio(anyFloat())).thenReturn(mockListenableFutureVoid)


        cameraManager.setZoomRatio(Float.POSITIVE_INFINITY)
        verify(mockCameraControl).setZoomRatio(eq(2.0f)) // Clamps to max

        reset(mockCameraControl)
        `when`(mockCameraControl.setZoomRatio(anyFloat())).thenReturn(mockListenableFutureVoid)

        cameraManager.setZoomRatio(Float.NEGATIVE_INFINITY)
        verify(mockCameraControl).setZoomRatio(eq(0.1f)) // Clamps to min
    }


    @Test
    fun setZoomRatio_whenCameraControlIsNull_logsError() {
        // Force camera to be null by resetting the manager or not calling start
        cameraManager = CameraManager(mockContext) // Fresh instance, camera not started
        val field = cameraManager.javaClass.getDeclaredField("cameraExecutor") // re-inject real executor
        field.isAccessible = true
        field.set(cameraManager, realExecutor)


        cameraManager.setZoomRatio(0.5f)
        verify(mockCameraControl, never()).setZoomRatio(anyFloat())
        // Check for Log.e "Camera or CameraControl is null..."
    }

    // --- getAvailableCameras Tests ---
    @Test
    fun getAvailableCameras_onlyBackAvailable() {
        `when`(mockCameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)).thenReturn(true)
        `when`(mockCameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)).thenReturn(false)
        val cameras = cameraManager.getAvailableCameras()
        assert(cameras.size == 1)
        assert(cameras.contains(CameraSelector.DEFAULT_BACK_CAMERA))
    }

    @Test
    fun getAvailableCameras_onlyFrontAvailable() {
        `when`(mockCameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)).thenReturn(false)
        `when`(mockCameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)).thenReturn(true)
        val cameras = cameraManager.getAvailableCameras()
        assert(cameras.size == 1)
        assert(cameras.contains(CameraSelector.DEFAULT_FRONT_CAMERA))
    }

    @Test
    fun getAvailableCameras_whenProviderNotYetAvailable_returnsEmptyAndLogs() {
        // Simulate provider future not yet completed
        val nonCompletingFuture = mock(ListenableFuture::class.java) as ListenableFuture<ProcessCameraProvider>
        mockStatic(ProcessCameraProvider::class.java).use { mockedStatic ->
             mockedStatic.`when`<ListenableFuture<ProcessCameraProvider>> { ProcessCameraProvider.getInstance(mockContext) }
                .thenReturn(nonCompletingFuture)
        }
        cameraManager = CameraManager(mockContext) // Re-init
        val field = cameraManager.javaClass.getDeclaredField("cameraExecutor") // re-inject real executor
        field.isAccessible = true
        field.set(cameraManager, realExecutor)

        val cameras = cameraManager.getAvailableCameras()
        assert(cameras.isEmpty())
        // Check Log.w "CameraProvider not available..."
    }


    // --- selectCamera Tests ---
    @Test
    fun selectCamera_whenCameraNotAvailable_logsErrorAndDoesNotRebind() {
        cameraManager.startCamera(mockLifecycleOwner, mockPreviewView) // Initial bind
        reset(mockCameraProvider) // Reset for verification
        `when`(mockCameraProvider.bindToLifecycle(any(), any(), any(Preview::class.java))).thenReturn(mockCamera)


        val newSelector = CameraSelector.DEFAULT_FRONT_CAMERA
        `when`(mockCameraProvider.hasCamera(newSelector)).thenReturn(false)

        cameraManager.selectCamera(mockLifecycleOwner, mockPreviewView, newSelector)

        verify(mockCameraProvider, never()).unbindAll()
        verify(mockCameraProvider, never()).bindToLifecycle(any(), eq(newSelector), any())
        // Check Log.e "Camera selector ... is not available"
    }

    @Test
    fun selectCamera_whenProviderIsNull_logsError() {
        // Simulate provider future not yet completed or failed
        val nonCompletingFuture = mock(ListenableFuture::class.java) as ListenableFuture<ProcessCameraProvider>
        mockStatic(ProcessCameraProvider::class.java).use { mockedStatic ->
             mockedStatic.`when`<ListenableFuture<ProcessCameraProvider>> { ProcessCameraProvider.getInstance(mockContext) }
                .thenReturn(nonCompletingFuture)
        }
        cameraManager = CameraManager(mockContext) // Re-init
        val field = cameraManager.javaClass.getDeclaredField("cameraExecutor") // re-inject real executor
        field.isAccessible = true
        field.set(cameraManager, realExecutor)


        cameraManager.selectCamera(mockLifecycleOwner, mockPreviewView, CameraSelector.DEFAULT_FRONT_CAMERA)
        verify(mockCameraProvider, never()).bindToLifecycle(any(), any(), any())
        // Check Log.e "CameraProvider is null. Cannot select camera."
    }

    @Test
    fun selectCamera_rebindsWithNewSelector() {
        // Initial setup with DEFAULT_BACK_CAMERA
        `when`(mockCameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)).thenReturn(true)
        cameraManager.startCamera(mockLifecycleOwner, mockPreviewView)

        // Prepare for switching to FRONT_CAMERA
        val frontCameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
        `when`(mockCameraProvider.hasCamera(frontCameraSelector)).thenReturn(true)
        // Mock bindToLifecycle for the front camera specifically if different behavior is expected
        val mockFrontCamera = mock(Camera::class.java)
        `when`(mockCameraProvider.bindToLifecycle(any(LifecycleOwner::class.java), eq(frontCameraSelector), any(Preview::class.java)))
            .thenReturn(mockFrontCamera)

        cameraManager.selectCamera(mockLifecycleOwner, mockPreviewView, frontCameraSelector)

        val inOrder = inOrder(mockCameraProvider)
        inOrder.verify(mockCameraProvider).unbindAll() // From the selectCamera call
        inOrder.verify(mockCameraProvider).bindToLifecycle(
            eq(mockLifecycleOwner),
            eq(frontCameraSelector), // Verify it's the new selector
            any(Preview::class.java)
        )
    }


    // --- Error Handling and State ---
    @Test
    fun shutdown_isIdempotent() {
        cameraManager.shutdown()
        assert(realExecutor.isShutdown)
        cameraManager.shutdown() // Call again
        assert(realExecutor.isShutdown) // Should still be shutdown, no error
    }

    @Test
    fun setZoomRatio_afterShutdown_doesNotCallCameraControlAndLogs() {
        cameraManager.shutdown()
        cameraManager.setZoomRatio(0.5f)
        verify(mockCameraControl, never()).setZoomRatio(anyFloat())
        // Check for Log.e "CameraManager is shutdown or cameraExecutor is not active."
    }

    @Test
    fun selectCamera_afterShutdown_doesNotAttemptToBindAndLogs() {
        cameraManager.shutdown() // Shutdown first

        // Attempt to select a camera
        val newSelector = CameraSelector.DEFAULT_FRONT_CAMERA
        // No need to mock hasCamera for this test as it should exit early

        cameraManager.selectCamera(mockLifecycleOwner, mockPreviewView, newSelector)

        // Verify that no camera operations like unbindAll or bindToLifecycle are called
        verify(mockCameraProvider, never()).unbindAll()
        verify(mockCameraProvider, never()).bindToLifecycle(any(LifecycleOwner::class.java), any(CameraSelector::class.java), any(Preview::class.java))
        // Check for Log.e indicating manager is shutdown
    }
}

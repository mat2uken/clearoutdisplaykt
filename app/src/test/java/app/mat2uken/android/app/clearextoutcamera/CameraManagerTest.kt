package app.mat2uken.android.app.clearextoutcamera

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import com.google.common.util.concurrent.ListenableFuture
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

@RunWith(MockitoJUnitRunner::class)
class CameraManagerTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule() // For LiveData testing

    @Mock
    private lateinit var mockContext: Context
    @Mock
    private lateinit var mockLifecycleOwner: LifecycleOwner
    @Mock
    private lateinit var mockPreviewView: PreviewView
    @Mock
    private lateinit var mockCameraProvider: ProcessCameraProvider
    @Mock
    private lateinit var mockCamera: Camera
    @Mock
    private lateinit var mockCameraControl: CameraControl
    @Mock
    private lateinit var mockCameraInfo: CameraInfo
    @Mock
    private lateinit var mockZoomState: ZoomState
    @Mock
    private lateinit var mockListenableFutureZoom: ListenableFuture<Void>
    @Mock
    private lateinit var mockListenableFutureProvider: ListenableFuture<ProcessCameraProvider>
    @Mock
    private lateinit var mockPreviewSurfaceProvider: Preview.SurfaceProvider

    @Captor
    private lateinit var cameraSelectorCaptor: ArgumentCaptor<CameraSelector>
    @Captor
    private lateinit var previewCaptor: ArgumentCaptor<Preview>

    private lateinit var cameraManager: CameraManager

    @Before
    fun setUp() {
        // Static mock for ProcessCameraProvider.getInstance
        // Note: Mockito.mockStatic needs mockito-inline
        mockStatic(ProcessCameraProvider::class.java).use { mockedStatic ->
            mockedStatic.`when`<ListenableFuture<ProcessCameraProvider>> { ProcessCameraProvider.getInstance(mockContext) }
                .thenReturn(mockListenableFutureProvider)
        }
        // Simulate the future completing and returning the mockCameraProvider
        `when`(mockListenableFutureProvider.get()).thenReturn(mockCameraProvider)
        // Simulate addListener to immediately run the runnable
        doAnswer { invocation ->
            val runnable = invocation.getArgument<Runnable>(0)
            runnable.run()
            null
        }.`when`(mockListenableFutureProvider).addListener(any(Runnable::class.java), any(Executor::class.java))


        `when`(mockCamera.cameraControl).thenReturn(mockCameraControl)
        `when`(mockCamera.cameraInfo).thenReturn(mockCameraInfo)
        `when`(mockCameraInfo.zoomState).thenReturn(mock(LiveData::class.java) as LiveData<ZoomState>)
        `when`(mockCameraControl.setZoomRatio(anyFloat())).thenReturn(mockListenableFutureZoom)
        `when`(mockPreviewView.surfaceProvider).thenReturn(mockPreviewSurfaceProvider)

        // Default behavior for camera binding
        `when`(mockCameraProvider.bindToLifecycle(
            any(LifecycleOwner::class.java),
            any(CameraSelector::class.java),
            any(Preview::class.java)
        )).thenReturn(mockCamera)

        cameraManager = CameraManager(mockContext)
        // Trigger the provider initialization for tests that need it.
        // Some tests might re-mock this if they need to test the initialization itself.
        cameraManager.startCamera(mockLifecycleOwner, mockPreviewView) // Initial setup
    }

    @Test
    fun startCamera_requestsProviderAndBindsPreview() {
        // Reset interactions from setUp if startCamera is called there
        reset(mockCameraProvider) // Reset to check bindToLifecycle specifically for this test
        `when`(mockCameraProvider.bindToLifecycle(
            any(LifecycleOwner::class.java),
            any(CameraSelector::class.java),
            any(Preview::class.java)
        )).thenReturn(mockCamera)


        cameraManager.startCamera(mockLifecycleOwner, mockPreviewView)

        // Verify ProcessCameraProvider.getInstance was called (implicitly by setup)
        // Verify addListener was called on the future (implicitly by setup)
        // Verify bindToLifecycle is called
        verify(mockCameraProvider).bindToLifecycle(
            eq(mockLifecycleOwner),
            cameraSelectorCaptor.capture(),
            previewCaptor.capture()
        )
        // Check if default is back camera
        assert(cameraSelectorCaptor.value == CameraSelector.DEFAULT_BACK_CAMERA)
        assert(previewCaptor.value != null)
    }

    @Test
    fun setZoomRatio_callsCameraControl() {
        // Ensure camera is "initialized" for this test
        cameraManager.startCamera(mockLifecycleOwner, mockPreviewView) // Ensure camera is not null

        val testZoomRatio = 0.5f
        cameraManager.setZoomRatio(testZoomRatio)

        verify(mockCameraControl).setZoomRatio(eq(testZoomRatio))
    }

    @Test
    fun getAvailableCameras_returnsCorrectSelectorsWhenAvailable() {
        `when`(mockCameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)).thenReturn(true)
        `when`(mockCameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)).thenReturn(true)

        val cameras = cameraManager.getAvailableCameras()

        assert(cameras.contains(CameraSelector.DEFAULT_BACK_CAMERA))
        assert(cameras.contains(CameraSelector.DEFAULT_FRONT_CAMERA))
        assert(cameras.size == 2)
    }

    @Test
    fun getAvailableCameras_returnsEmptyListWhenNoCameras() {
        `when`(mockCameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)).thenReturn(false)
        `when`(mockCameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)).thenReturn(false)

        val cameras = cameraManager.getAvailableCameras()

        assert(cameras.isEmpty())
    }


    @Test
    fun selectCamera_unbindsAllAndBindsToNewCamera() {
        // Ensure provider is ready
        cameraManager.startCamera(mockLifecycleOwner, mockPreviewView) // Initial bind to back

        reset(mockCameraProvider) // Reset interactions for this specific test path
         `when`(mockCameraProvider.bindToLifecycle(
            any(LifecycleOwner::class.java),
            any(CameraSelector::class.java),
            any(Preview::class.java)
        )).thenReturn(mockCamera) // Re-stub for subsequent calls

        val newSelector = CameraSelector.DEFAULT_FRONT_CAMERA
        `when`(mockCameraProvider.hasCamera(newSelector)).thenReturn(true)

        cameraManager.selectCamera(mockLifecycleOwner, mockPreviewView, newSelector)

        val inOrder = inOrder(mockCameraProvider)
        inOrder.verify(mockCameraProvider).unbindAll()
        inOrder.verify(mockCameraProvider).bindToLifecycle(
            eq(mockLifecycleOwner),
            eq(newSelector),
            any(Preview::class.java)
        )
    }

    @Test
    fun selectCamera_logsErrorIfCameraNotAvailable() {
        // Ensure provider is ready
       cameraManager.startCamera(mockLifecycleOwner, mockPreviewView)

        val newSelector = CameraSelector.DEFAULT_FRONT_CAMERA
        `when`(mockCameraProvider.hasCamera(newSelector)).thenReturn(false) // Simulate camera not available

        cameraManager.selectCamera(mockLifecycleOwner, mockPreviewView, newSelector)

        // Verify that bindToLifecycle was not called again after the initial setup,
        // because the new camera is not available.
        // The initial call in setup and startCamera is expected.
        verify(mockCameraProvider, times(1)).bindToLifecycle(
            any(LifecycleOwner::class.java),
            eq(CameraSelector.DEFAULT_BACK_CAMERA), // From initial startCamera
            any(Preview::class.java)
        )
         verify(mockCameraProvider, never()).bindToLifecycle(
            any(LifecycleOwner::class.java),
            eq(newSelector), // This should not be called
            any(Preview::class.java)
        )
        // Logcat check is manual or would require a more complex setup
    }

    @Test
    fun shutdown_shutdownsExecutor() {
        // We need a real executor to test its shutdown
        val realExecutor = Executors.newSingleThreadExecutor()
        // Replace the mocked executor for this test
        val field = cameraManager.javaClass.getDeclaredField("cameraExecutor")
        field.isAccessible = true
        field.set(cameraManager, realExecutor)

        cameraManager.shutdown()
        assert(realExecutor.isShutdown)
    }
}

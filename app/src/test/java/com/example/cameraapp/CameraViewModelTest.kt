package com.example.cameraapp

import androidx.camera.core.CameraInfo.ExposureState
import androidx.camera.core.CameraInfo.ZoomState
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.FocusMeteringResult
import androidx.camera.core.Preview // For mocking SurfaceProvider
import android.util.Range
import android.util.Rational
import android.hardware.camera2.CameraMetadata // For AWB constants
import androidx.lifecycle.LifecycleOwner // For mocking LifecycleOwner
import app.cash.turbine.test
import com.example.cameraapp.camera.CameraInitResult // Import this
import com.example.cameraapp.camera.CameraXService
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.Futures
import io.mockk.MockKAnnotations
import io.mockk.impl.annotations.RelaxedMockK
import io.mockk.verify
import io.mockk.every
import io.mockk.slot
import io.mockk.coEvery // For suspend functions
import io.mockk.mockk // For creating mocks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class CameraViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @RelaxedMockK
    private lateinit var mockCameraXService: CameraXService

    private lateinit var viewModel: CameraViewModel

    // Mock sources for the service's flows
    private lateinit var mockZoomStateFlow: MutableStateFlow<ZoomState?>
    private lateinit var mockExposureStateFlow: MutableStateFlow<ExposureState?>
    private lateinit var mockHasFlashUnitFlow: MutableStateFlow<Boolean>
    private lateinit var mockTorchStateFlow: MutableStateFlow<Boolean>

    @Before
    fun setUp() {
        MockKAnnotations.init(this)

        mockZoomStateFlow = MutableStateFlow(null)
        mockExposureStateFlow = MutableStateFlow(null)
        mockHasFlashUnitFlow = MutableStateFlow(false)
        mockTorchStateFlow = MutableStateFlow(false)

        every { mockCameraXService.zoomStateFlow } returns mockZoomStateFlow
        every { mockCameraXService.exposureStateFlow } returns mockExposureStateFlow
        every { mockCameraXService.hasFlashUnitFlow } returns mockHasFlashUnitFlow
        every { mockCameraXService.torchStateFlow } returns mockTorchStateFlow

        every { mockCameraXService.setZoomRatio(any()) } returns Futures.immediateFuture(null)
        every { mockCameraXService.setExposureCompensationIndex(any()) } returns Futures.immediateFuture(null)
        every { mockCameraXService.enableTorch(any()) } returns Futures.immediateFuture(null)
        every { mockCameraXService.setWhiteBalanceMode(any()) } returns Futures.immediateFuture(null)

        // Mock initializeAndBindCamera to return success for primaryCameraInit tests
        val mockCamera = mockk<androidx.camera.core.Camera>(relaxed = true)
        coEvery { mockCameraXService.initializeAndBindCamera(any(), any(), any()) } returns CameraInitResult.Success(mockCamera)


        viewModel = CameraViewModel(mockCameraXService)
    }

    // --- Previous tests for lensFacing and flip ---
    @Test
    fun `onSwitchCameraClicked toggles lensFacing from BACK to FRONT`() = runTest {
        viewModel.lensFacing.test {
            assertThat(awaitItem()).isEqualTo(CameraSelector.LENS_FACING_BACK)
            viewModel.onSwitchCameraClicked()
            assertThat(awaitItem()).isEqualTo(CameraSelector.LENS_FACING_FRONT)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `onSwitchCameraClicked toggles lensFacing from FRONT to BACK`() = runTest {
        viewModel.onSwitchCameraClicked() // Initial toggle to FRONT
        viewModel.lensFacing.test {
            assertThat(awaitItem()).isEqualTo(CameraSelector.LENS_FACING_FRONT)
            viewModel.onSwitchCameraClicked()
            assertThat(awaitItem()).isEqualTo(CameraSelector.LENS_FACING_BACK)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `onFlipClicked toggles isFlippedHorizontally`() = runTest {
        viewModel.isFlippedHorizontally.test {
            assertThat(awaitItem()).isFalse()
            viewModel.onFlipClicked()
            assertThat(awaitItem()).isTrue()
            viewModel.onFlipClicked()
            assertThat(awaitItem()).isFalse()
            cancelAndConsumeRemainingEvents()
        }
    }

    // --- Tests for Zoom ---
    @Test
    fun `setZoomRatio calls service with coerced value`() = runTest {
        val initialZoomState = ZoomState.create(2.0f, 1.0f, 4.0f, 0.1f)
        mockZoomStateFlow.value = initialZoomState
        advanceUntilIdle()

        viewModel.setZoomRatio(0.5f)
        verify { mockCameraXService.setZoomRatio(1.0f) }

        viewModel.setZoomRatio(5.0f)
        verify { mockCameraXService.setZoomRatio(4.0f) }

        viewModel.setZoomRatio(3.0f)
        verify { mockCameraXService.setZoomRatio(3.0f) }
    }

    @Test
    fun `zoom related StateFlows correctly reflect service zoomStateFlow`() = runTest {
        viewModel.currentZoomRatio.test {
            assertThat(awaitItem()).isEqualTo(1f)
            mockZoomStateFlow.value = ZoomState.create(1.5f, 1.0f, 3.0f, 0.1f)
            assertThat(awaitItem()).isEqualTo(1.5f)
            mockZoomStateFlow.value = ZoomState.create(2.5f, 1.0f, 3.0f, 0.1f)
            assertThat(awaitItem()).isEqualTo(2.5f)
            cancelAndConsumeRemainingEvents()
        }
        viewModel.minZoomRatio.test {
            assertThat(awaitItem()).isEqualTo(1f)
            mockZoomStateFlow.value = ZoomState.create(1.5f, 0.5f, 3.0f, 0.1f)
            assertThat(awaitItem()).isEqualTo(0.5f)
            cancelAndConsumeRemainingEvents()
        }
        viewModel.maxZoomRatio.test {
            assertThat(awaitItem()).isEqualTo(1f)
            mockZoomStateFlow.value = ZoomState.create(1.5f, 0.5f, 4.0f, 0.1f)
            assertThat(awaitItem()).isEqualTo(4.0f)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `isZoomSupported reflects zoomState correctly`() = runTest {
         viewModel.isZoomSupported.test {
            assertThat(awaitItem()).isFalse()

            mockZoomStateFlow.value = ZoomState.create(1.0f, 1.0f, 1.0f, 0.0f)
            assertThat(awaitItem()).isFalse()

            mockZoomStateFlow.value = ZoomState.create(2.0f, 1.0f, 3.0f, 0.1f)
            assertThat(awaitItem()).isTrue()
            cancelAndConsumeRemainingEvents()
        }
    }

    // --- Tests for Exposure ---
    @Test
    fun `setExposureIndex calls service with coerced value`() = runTest {
        val initialExposureState = ExposureState.create(Range(-2, 2), 0, Rational(1,1))
        mockExposureStateFlow.value = initialExposureState
        advanceUntilIdle()

        viewModel.setExposureIndex(-3)
        verify { mockCameraXService.setExposureCompensationIndex(-2) }

        viewModel.setExposureIndex(3)
        verify { mockCameraXService.setExposureCompensationIndex(2) }

        viewModel.setExposureIndex(1)
        verify { mockCameraXService.setExposureCompensationIndex(1) }
    }

    @Test
    fun `exposure related StateFlows correctly reflect service exposureStateFlow`() = runTest {
        viewModel.currentExposureIndex.test {
            assertThat(awaitItem()).isEqualTo(0)
            mockExposureStateFlow.value = ExposureState.create(Range(-5, 5), 2, Rational(1,1))
            assertThat(awaitItem()).isEqualTo(2)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `isExposureSupported reflects exposureState correctly`() = runTest {
        viewModel.isExposureSupported.test {
            assertThat(awaitItem()).isFalse()

            mockExposureStateFlow.value = ExposureState.create(Range(0,0), 0, Rational(1,1), false)
            assertThat(awaitItem()).isFalse()

            mockExposureStateFlow.value = ExposureState.create(Range(-2,2), 0, Rational(1,1), true)
            assertThat(awaitItem()).isTrue()
            cancelAndConsumeRemainingEvents()
        }
    }

    // --- Tests for LED logic with service ---
    @Test
    fun `onLedButtonClicked calls service enableTorch true when flash available and LED is off`() = runTest {
        mockHasFlashUnitFlow.value = true
        mockTorchStateFlow.value = false

        viewModel.onLedButtonClicked()

        verify { mockCameraXService.enableTorch(true) }
    }

    @Test
    fun `onLedButtonClicked calls service enableTorch false when flash available and LED is on`() = runTest {
        mockHasFlashUnitFlow.value = true
        mockTorchStateFlow.value = true

        viewModel.onLedButtonClicked()

        verify { mockCameraXService.enableTorch(false) }
    }

    @Test
    fun `onLedButtonClicked does NOT call service enableTorch when flash is unavailable`() = runTest {
        mockHasFlashUnitFlow.value = false
        mockTorchStateFlow.value = false

        viewModel.onLedButtonClicked()

        verify(exactly = 0) { mockCameraXService.enableTorch(any()) }
    }

    @Test
    fun `isLedOn StateFlow correctly reflects service torchStateFlow`() = runTest {
        viewModel.isLedOn.test {
            assertThat(awaitItem()).isFalse()
            mockTorchStateFlow.value = true
            assertThat(awaitItem()).isTrue()
            mockTorchStateFlow.value = false
            assertThat(awaitItem()).isFalse()
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `hasFlashUnit StateFlow correctly reflects service hasFlashUnitFlow`() = runTest {
        viewModel.hasFlashUnit.test {
            assertThat(awaitItem()).isFalse()
            mockHasFlashUnitFlow.value = true
            assertThat(awaitItem()).isTrue()
            mockHasFlashUnitFlow.value = false
            assertThat(awaitItem()).isFalse()
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `conceptual test updateHasFlashUnit is removed and its logic handled by service flow`() {
        mockHasFlashUnitFlow.value = true
        assertThat(viewModel.hasFlashUnit.value).isTrue()
        mockHasFlashUnitFlow.value = false
        assertThat(viewModel.hasFlashUnit.value).isFalse()
    }

    // --- Tests for Tap-to-Focus ---
    @Test
    fun `onPreviewTapped calls service with correctly constructed FocusMeteringAction`() = runTest {
        val viewWidth = 1280
        val viewHeight = 720
        val tapX = 300f
        val tapY = 400f

        val actionSlot = slot<FocusMeteringAction>()
        every { mockCameraXService.startFocusAndMetering(capture(actionSlot)) } returns Futures.immediateFuture(FocusMeteringResult.create(true))

        viewModel.onPreviewTapped(viewWidth, viewHeight, tapX, tapY)

        verify(exactly = 1) { mockCameraXService.startFocusAndMetering(actionSlot.captured) }

        val capturedAction = actionSlot.captured
        assertThat(capturedAction.meteringPoints).hasSize(1)
        val meteringPoint = capturedAction.meteringPoints.first()
        assertThat(meteringPoint).isNotNull()
        assertThat(capturedAction.autoCancelDurationMillis).isEqualTo(TimeUnit.SECONDS.toMillis(3))
    }

    @Test
    fun `onPreviewTapped does not call service if viewWidth is zero`() = runTest {
        viewModel.onPreviewTapped(viewWidth = 0, viewHeight = 720, x = 100f, y = 200f)
        verify(exactly = 0) { mockCameraXService.startFocusAndMetering(any()) }
    }

    @Test
    fun `onPreviewTapped does not call service if viewHeight is zero`() = runTest {
        viewModel.onPreviewTapped(viewWidth = 1280, viewHeight = 0, x = 100f, y = 200f)
        verify(exactly = 0) { mockCameraXService.startFocusAndMetering(any()) }
    }

    // --- Tests for White Balance ---
    @Test
    fun `onWhiteBalanceModeSelected updates currentAwbMode state and calls service`() = runTest {
        val selectedMode = CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT

        viewModel.currentAwbMode.test {
            assertThat(awaitItem()).isEqualTo(CameraMetadata.CONTROL_AWB_MODE_AUTO) // Initial

            viewModel.onWhiteBalanceModeSelected(selectedMode)

            assertThat(awaitItem()).isEqualTo(selectedMode)
            verify(exactly = 1) { mockCameraXService.setWhiteBalanceMode(selectedMode) }
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `primaryCameraInit re-applies non-AUTO AWB mode via service`() = runTest {
        val selectedMode = CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
        // Set a non-auto mode first
        viewModel.onWhiteBalanceModeSelected(selectedMode)
        // Clear invocations on the mock for setWhiteBalanceMode that happened due to the line above
        io.mockk.clearRecordedCalls(mockCameraXService)
        // Re-stub setWhiteBalanceMode as clearRecordedCalls might also clear stubs if not careful with parameters
        every { mockCameraXService.setWhiteBalanceMode(any()) } returns Futures.immediateFuture(null)


        val mockLifecycleOwner = mockk<LifecycleOwner>(relaxed = true)
        val mockSurfaceProvider = mockk<Preview.SurfaceProvider>(relaxed = true)
        viewModel.primaryCameraInit(mockLifecycleOwner, mockSurfaceProvider)
        advanceUntilIdle() // Ensure coroutine in primaryCameraInit completes

        verify(exactly = 1) { mockCameraXService.setWhiteBalanceMode(selectedMode) }
    }

    @Test
    fun `primaryCameraInit does not re-apply AWB mode if it is AUTO`() = runTest {
        assertThat(viewModel.currentAwbMode.value).isEqualTo(CameraMetadata.CONTROL_AWB_MODE_AUTO)

        io.mockk.clearRecordedCalls(mockCameraXService)
        every { mockCameraXService.setWhiteBalanceMode(any()) } returns Futures.immediateFuture(null)


        val mockLifecycleOwner = mockk<LifecycleOwner>(relaxed = true)
        val mockSurfaceProvider = mockk<Preview.SurfaceProvider>(relaxed = true)
        viewModel.primaryCameraInit(mockLifecycleOwner, mockSurfaceProvider)
        advanceUntilIdle()

        verify(exactly = 0) { mockCameraXService.setWhiteBalanceMode(CameraMetadata.CONTROL_AWB_MODE_AUTO) }
    }
}

// Helper for setting TestDispatchers
@ExperimentalCoroutinesApi
class MainDispatcherRule(
    private val testDispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : org.junit.rules.TestWatcher() {
    override fun starting(description: org.junit.runner.Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: org.junit.runner.Description) {
        Dispatchers.resetMain()
    }
}

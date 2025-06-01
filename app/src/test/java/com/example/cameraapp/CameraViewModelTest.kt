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
import androidx.lifecycle.SavedStateHandle // Import SavedStateHandle
import app.cash.turbine.test
import com.example.cameraapp.camera.CameraInitResult
import com.example.cameraapp.camera.CameraXService
import com.example.cameraapp.display.DisplayService
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.Futures
import io.mockk.MockKAnnotations
import io.mockk.impl.annotations.RelaxedMockK
import io.mockk.verify
import io.mockk.every
import io.mockk.slot
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verifyOrder // For ordered verification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit
import android.view.Display // For mocking Display

@OptIn(ExperimentalCoroutinesApi::class)
class CameraViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @RelaxedMockK
    private lateinit var mockCameraXService: CameraXService

    @RelaxedMockK
    private lateinit var mockDisplayService: DisplayService

    private lateinit var savedStateHandle: SavedStateHandle
    private lateinit var viewModel: CameraViewModel

    // Mock sources for the service's flows
    private lateinit var mockZoomStateFlow: MutableStateFlow<ZoomState?>
    private lateinit var mockExposureStateFlow: MutableStateFlow<ExposureState?>
    private lateinit var mockHasFlashUnitFlow: MutableStateFlow<Boolean>
    private lateinit var mockTorchStateFlow: MutableStateFlow<Boolean>
    private lateinit var mockDisplaysFlow: MutableStateFlow<List<Display>>


    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        savedStateHandle = SavedStateHandle()

        mockZoomStateFlow = MutableStateFlow(null)
        mockExposureStateFlow = MutableStateFlow(null)
        mockHasFlashUnitFlow = MutableStateFlow(false)
        mockTorchStateFlow = MutableStateFlow(false)
        mockDisplaysFlow = MutableStateFlow(emptyList())

        every { mockCameraXService.zoomStateFlow } returns mockZoomStateFlow
        every { mockCameraXService.exposureStateFlow } returns mockExposureStateFlow
        every { mockCameraXService.hasFlashUnitFlow } returns mockHasFlashUnitFlow
        every { mockCameraXService.torchStateFlow } returns mockTorchStateFlow

        every { mockDisplayService.displaysFlow } returns mockDisplaysFlow
        every { mockDisplayService.startListening() } returns Unit
        every { mockDisplayService.stopListening() } returns Unit

        every { mockCameraXService.setZoomRatio(any()) } returns Futures.immediateFuture(null)
        every { mockCameraXService.setExposureCompensationIndex(any()) } returns Futures.immediateFuture(null)
        every { mockCameraXService.enableTorch(any()) } returns Futures.immediateFuture(null)
        every { mockCameraXService.setWhiteBalanceMode(any()) } returns Futures.immediateFuture(null)
        every { mockCameraXService.startFocusAndMetering(any())} returns Futures.immediateFuture(FocusMeteringResult.create(true))

        // Stubs for surface provider methods
        every { mockCameraXService.setMainSurfaceProvider(any()) } returns Unit
        every { mockCameraXService.setExternalSurfaceProvider(any()) } returns Unit


        val mockCamera = mockk<androidx.camera.core.Camera>(relaxed = true)
        coEvery { mockCameraXService.initializeAndBindCamera(any(), any(), any()) } returns CameraInitResult.Success(mockCamera)

        viewModel = CameraViewModel(mockCameraXService, mockDisplayService, savedStateHandle)
    }

    @Test
    fun `onSwitchCameraClicked toggles lensFacing and saves to SavedStateHandle`() = runTest {
        viewModel.lensFacing.test {
            assertThat(awaitItem()).isEqualTo(CameraSelector.LENS_FACING_BACK)
            viewModel.onSwitchCameraClicked()
            val newLensFacing = awaitItem()
            assertThat(newLensFacing).isEqualTo(CameraSelector.LENS_FACING_FRONT)
            assertThat(savedStateHandle.get<Int>(CameraViewModel.LENS_FACING_KEY)).isEqualTo(CameraSelector.LENS_FACING_FRONT)

            viewModel.onSwitchCameraClicked()
            assertThat(awaitItem()).isEqualTo(CameraSelector.LENS_FACING_BACK)
            assertThat(savedStateHandle.get<Int>(CameraViewModel.LENS_FACING_KEY)).isEqualTo(CameraSelector.LENS_FACING_BACK)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `onFlipClicked toggles isFlippedHorizontally and saves to SavedStateHandle`() = runTest {
        viewModel.isFlippedHorizontally.test {
            assertThat(awaitItem()).isFalse()
            viewModel.onFlipClicked()
            assertThat(awaitItem()).isTrue()
            assertThat(savedStateHandle.get<Boolean>(CameraViewModel.IS_FLIPPED_KEY)).isTrue()

            viewModel.onFlipClicked()
            assertThat(awaitItem()).isFalse()
            assertThat(savedStateHandle.get<Boolean>(CameraViewModel.IS_FLIPPED_KEY)).isFalse()
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `onWhiteBalanceModeSelected updates currentAwbMode and saves to SavedStateHandle`() = runTest {
        val selectedMode = CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT
        viewModel.currentAwbMode.test {
            assertThat(awaitItem()).isEqualTo(CameraMetadata.CONTROL_AWB_MODE_AUTO)

            viewModel.onWhiteBalanceModeSelected(selectedMode)

            assertThat(awaitItem()).isEqualTo(selectedMode)
            verify(exactly = 1) { mockCameraXService.setWhiteBalanceMode(selectedMode) }
            assertThat(savedStateHandle.get<Int>(CameraViewModel.AWB_MODE_KEY)).isEqualTo(selectedMode)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `viewModel initializes lensFacing from SavedStateHandle`() = runTest {
        savedStateHandle[CameraViewModel.LENS_FACING_KEY] = CameraSelector.LENS_FACING_FRONT
        val newViewModel = CameraViewModel(mockCameraXService, mockDisplayService, savedStateHandle)
        assertThat(newViewModel.lensFacing.value).isEqualTo(CameraSelector.LENS_FACING_FRONT)
    }

    @Test
    fun `viewModel initializes isFlippedHorizontally from SavedStateHandle`() = runTest {
        savedStateHandle[CameraViewModel.IS_FLIPPED_KEY] = true
        val newViewModel = CameraViewModel(mockCameraXService, mockDisplayService, savedStateHandle)
        assertThat(newViewModel.isFlippedHorizontally.value).isTrue()
    }

    @Test
    fun `viewModel initializes currentAwbMode from SavedStateHandle`() = runTest {
        val savedMode = CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
        savedStateHandle[CameraViewModel.AWB_MODE_KEY] = savedMode
        val newViewModel = CameraViewModel(mockCameraXService, mockDisplayService, savedStateHandle)
        assertThat(newViewModel.currentAwbMode.value).isEqualTo(savedMode)
    }

    @Test
    fun `setZoomRatio calls service with coerced value`() = runTest {
        val initialZoomState = ZoomState.create(2.0f, 1.0f, 4.0f, 0.1f)
        mockZoomStateFlow.value = initialZoomState
        advanceUntilIdle()
        viewModel.setZoomRatio(0.5f); verify { mockCameraXService.setZoomRatio(1.0f) }
        viewModel.setZoomRatio(5.0f); verify { mockCameraXService.setZoomRatio(4.0f) }
        viewModel.setZoomRatio(3.0f); verify { mockCameraXService.setZoomRatio(3.0f) }
    }

    @Test
    fun `zoom related StateFlows correctly reflect service zoomStateFlow`() = runTest {
        viewModel.currentZoomRatio.test {
            assertThat(awaitItem()).isEqualTo(1f)
            mockZoomStateFlow.value = ZoomState.create(1.5f, 1.0f, 3.0f, 0.1f)
            assertThat(awaitItem()).isEqualTo(1.5f)
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

    @Test
    fun `setExposureIndex calls service with coerced value`() = runTest {
        val initialExposureState = ExposureState.create(Range(-2, 2), 0, Rational(1,1))
        mockExposureStateFlow.value = initialExposureState
        advanceUntilIdle()
        viewModel.setExposureIndex(-3); verify { mockCameraXService.setExposureCompensationIndex(-2) }
        viewModel.setExposureIndex(3); verify { mockCameraXService.setExposureCompensationIndex(2) }
        viewModel.setExposureIndex(1); verify { mockCameraXService.setExposureCompensationIndex(1) }
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

    @Test
    fun `onLedButtonClicked calls service enableTorch true when flash available and LED is off`() = runTest {
        mockHasFlashUnitFlow.value = true; mockTorchStateFlow.value = false
        viewModel.onLedButtonClicked(); verify { mockCameraXService.enableTorch(true) }
    }

    @Test
    fun `onLedButtonClicked calls service enableTorch false when flash available and LED is on`() = runTest {
        mockHasFlashUnitFlow.value = true; mockTorchStateFlow.value = true
        viewModel.onLedButtonClicked(); verify { mockCameraXService.enableTorch(false) }
    }

    @Test
    fun `onLedButtonClicked does NOT call service enableTorch when flash is unavailable`() = runTest {
        mockHasFlashUnitFlow.value = false; mockTorchStateFlow.value = false
        viewModel.onLedButtonClicked(); verify(exactly = 0) { mockCameraXService.enableTorch(any()) }
    }

    @Test
    fun `isLedOn StateFlow correctly reflects service torchStateFlow`() = runTest {
        viewModel.isLedOn.test {
            assertThat(awaitItem()).isFalse()
            mockTorchStateFlow.value = true; assertThat(awaitItem()).isTrue()
            mockTorchStateFlow.value = false; assertThat(awaitItem()).isFalse()
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `hasFlashUnit StateFlow correctly reflects service hasFlashUnitFlow`() = runTest {
        viewModel.hasFlashUnit.test {
            assertThat(awaitItem()).isFalse()
            mockHasFlashUnitFlow.value = true; assertThat(awaitItem()).isTrue()
            mockHasFlashUnitFlow.value = false; assertThat(awaitItem()).isFalse()
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `onPreviewTapped calls service with correctly constructed FocusMeteringAction`() = runTest {
        val viewWidth = 1280; val viewHeight = 720; val tapX = 300f; val tapY = 400f
        val actionSlot = slot<FocusMeteringAction>()
        every { mockCameraXService.startFocusAndMetering(capture(actionSlot)) } returns Futures.immediateFuture(FocusMeteringResult.create(true))
        viewModel.onPreviewTapped(viewWidth, viewHeight, tapX, tapY)
        verify(exactly = 1) { mockCameraXService.startFocusAndMetering(actionSlot.captured) }
        val capturedAction = actionSlot.captured
        assertThat(capturedAction.meteringPoints).hasSize(1)
        assertThat(capturedAction.meteringPoints.first()).isNotNull()
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

    @Test
    fun `primaryCameraInit re-applies non-AUTO AWB mode via service`() = runTest {
        val selectedMode = CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
        viewModel.onWhiteBalanceModeSelected(selectedMode)
        io.mockk.clearRecordedCalls(mockCameraXService)
        every { mockCameraXService.setWhiteBalanceMode(any()) } returns Futures.immediateFuture(null)
        val mockLifecycleOwner = mockk<LifecycleOwner>(relaxed = true)
        val mockSurfaceProvider = mockk<Preview.SurfaceProvider>(relaxed = true)
        viewModel.primaryCameraInit(mockLifecycleOwner, mockSurfaceProvider)
        advanceUntilIdle()
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

    // --- Tests for External Display Logic ---
    @Test
    fun `externalDisplay flow emits null when no external displays`() = runTest {
        val mockDefaultDisplay = mockk<Display>(relaxed = true)
        every { mockDefaultDisplay.displayId } returns Display.DEFAULT_DISPLAY

        viewModel.externalDisplay.test {
            // Initial value from .stateIn might be null or the last value if collected before.
            // For a fresh ViewModel, if displaysFlow starts empty then maps to null, it should be null.
            mockDisplaysFlow.value = emptyList() // Ensure starting condition
            assertThat(awaitItem()).isNull()

            mockDisplaysFlow.value = listOf(mockDefaultDisplay)
            advanceUntilIdle() // Allow map and stateIn to process
            assertThat(viewModel.externalDisplay.value).isNull() // Still null
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `externalDisplay flow emits the first non-default display`() = runTest {
        val mockDefaultDisplay = mockk<Display>(relaxed = true); every { mockDefaultDisplay.displayId } returns Display.DEFAULT_DISPLAY
        val mockExternalDisplay1 = mockk<Display>(relaxed = true); every { mockExternalDisplay1.displayId } returns (Display.DEFAULT_DISPLAY + 1)
        val mockExternalDisplay2 = mockk<Display>(relaxed = true); every { mockExternalDisplay2.displayId } returns (Display.DEFAULT_DISPLAY + 2)

        viewModel.externalDisplay.test {
            mockDisplaysFlow.value = emptyList()
            assertThat(awaitItem()).isNull() // Initial

            mockDisplaysFlow.value = listOf(mockDefaultDisplay, mockExternalDisplay1, mockExternalDisplay2)
            assertThat(awaitItem()).isEqualTo(mockExternalDisplay1)

            mockDisplaysFlow.value = listOf(mockDefaultDisplay)
            assertThat(awaitItem()).isNull()

            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `attachExternalDisplaySurface calls service to set external and clear main provider`() = runTest {
        val mockExtSurfaceProvider = mockk<Preview.SurfaceProvider>(relaxed = true)
        viewModel.attachExternalDisplaySurface(mockExtSurfaceProvider)
        verifyOrder {
            mockCameraXService.setMainSurfaceProvider(null)
            mockCameraXService.setExternalSurfaceProvider(mockExtSurfaceProvider)
        }
    }

    @Test
    fun `detachExternalDisplaySurface calls service to clear external and restore main provider if stored`() = runTest {
        val mockMainSurfaceProvider = mockk<Preview.SurfaceProvider>(relaxed = true)
        val mockLifecycleOwner = mockk<LifecycleOwner>(relaxed = true)

        viewModel.primaryCameraInit(mockLifecycleOwner, mockMainSurfaceProvider)
        advanceUntilIdle() // Ensure primaryCameraInit's coroutine can complete and set mainSurfaceProvider

        io.mockk.clearRecordedCalls(mockCameraXService) // Clear calls from init
        // Re-stub because clearMocks might remove them
        every { mockCameraXService.setMainSurfaceProvider(any()) } returns Unit
        every { mockCameraXService.setExternalSurfaceProvider(any()) } returns Unit

        viewModel.detachExternalDisplaySurface()

        verifyOrder {
            mockCameraXService.setExternalSurfaceProvider(null)
            mockCameraXService.setMainSurfaceProvider(mockMainSurfaceProvider)
        }
    }

    @Test
    fun `detachExternalDisplaySurface calls service to clear external and does not set main if none stored`() = runTest {
        // ViewModel is fresh, mainSurfaceProvider is initially null.
        viewModel.detachExternalDisplaySurface()
        verify(exactly = 1) { mockCameraXService.setExternalSurfaceProvider(null) }
        verify(exactly = 0) { mockCameraXService.setMainSurfaceProvider(any()) }
    }

    @Test
    fun `ViewModel calls startListening on init and stopListening onCleared`() = runTest {
        verify(exactly = 1) { mockDisplayService.startListening() } // Called in init {}
        viewModel.onCleared() // Manually call for test
        verify(exactly = 1) { mockDisplayService.stopListening() }
    }
}

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

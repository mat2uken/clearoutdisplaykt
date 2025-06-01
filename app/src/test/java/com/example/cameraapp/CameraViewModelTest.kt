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
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.example.cameraapp.camera.CameraErrorEvent // Import CameraErrorEvent
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
import io.mockk.verifyOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow // Import MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit
import android.view.Display

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
    private lateinit var mockAvailableAwbModesFlow: MutableStateFlow<List<Int>>
    private lateinit var mockCameraErrorFlow: MutableSharedFlow<CameraErrorEvent>


    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        savedStateHandle = SavedStateHandle()

        mockZoomStateFlow = MutableStateFlow(null)
        mockExposureStateFlow = MutableStateFlow(null)
        mockHasFlashUnitFlow = MutableStateFlow(false)
        mockTorchStateFlow = MutableStateFlow(false)
        mockDisplaysFlow = MutableStateFlow(emptyList())
        mockAvailableAwbModesFlow = MutableStateFlow(emptyList())
        mockCameraErrorFlow = MutableSharedFlow(replay = 0, extraBufferCapacity = 3) // Allow some buffer

        every { mockCameraXService.zoomStateFlow } returns mockZoomStateFlow
        every { mockCameraXService.exposureStateFlow } returns mockExposureStateFlow
        every { mockCameraXService.hasFlashUnitFlow } returns mockHasFlashUnitFlow
        every { mockCameraXService.torchStateFlow } returns mockTorchStateFlow
        every { mockCameraXService.availableAwbModesFlow } returns mockAvailableAwbModesFlow
        every { mockCameraXService.cameraErrorFlow } returns mockCameraErrorFlow.asSharedFlow() // Use asSharedFlow()

        every { mockDisplayService.displaysFlow } returns mockDisplaysFlow
        every { mockDisplayService.startListening() } returns Unit
        every { mockDisplayService.stopListening() } returns Unit

        every { mockCameraXService.setZoomRatio(any()) } returns Futures.immediateFuture(null)
        every { mockCameraXService.setExposureCompensationIndex(any()) } returns Futures.immediateFuture(null)
        every { mockCameraXService.enableTorch(any()) } returns Futures.immediateFuture(null)
        every { mockCameraXService.setWhiteBalanceMode(any()) } returns Futures.immediateFuture(null)
        every { mockCameraXService.startFocusAndMetering(any())} returns Futures.immediateFuture(FocusMeteringResult.create(true))

        every { mockCameraXService.setMainSurfaceProvider(any()) } returns Unit
        every { mockCameraXService.setExternalSurfaceProvider(any()) } returns Unit

        val mockCamera = mockk<androidx.camera.core.Camera>(relaxed = true)
        coEvery { mockCameraXService.initializeAndBindCamera(any(), any(), any()) } returns CameraInitResult.Success(mockCamera)

        viewModel = CameraViewModel(mockCameraXService, mockDisplayService, savedStateHandle)
    }

    // --- Tests for lensFacing, flip, AWB state saving/loading ---
    @Test
    fun `onSwitchCameraClicked toggles lensFacing and saves to SavedStateHandle`() = runTest {
        viewModel.lensFacing.test {
            assertThat(awaitItem()).isEqualTo(CameraSelector.LENS_FACING_BACK)
            viewModel.onSwitchCameraClicked()
            assertThat(awaitItem()).isEqualTo(CameraSelector.LENS_FACING_FRONT)
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
            assertThat(awaitItem()).isFalse(); viewModel.onFlipClicked()
            assertThat(awaitItem()).isTrue(); assertThat(savedStateHandle.get<Boolean>(CameraViewModel.IS_FLIPPED_KEY)).isTrue()
            viewModel.onFlipClicked()
            assertThat(awaitItem()).isFalse(); assertThat(savedStateHandle.get<Boolean>(CameraViewModel.IS_FLIPPED_KEY)).isFalse()
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
    @Test fun `viewModel initializes lensFacing from SavedStateHandle`() = runTest {
        savedStateHandle[CameraViewModel.LENS_FACING_KEY] = CameraSelector.LENS_FACING_FRONT
        val newViewModel = CameraViewModel(mockCameraXService, mockDisplayService, savedStateHandle)
        assertThat(newViewModel.lensFacing.value).isEqualTo(CameraSelector.LENS_FACING_FRONT)
    }
    @Test fun `viewModel initializes isFlippedHorizontally from SavedStateHandle`() = runTest {
        savedStateHandle[CameraViewModel.IS_FLIPPED_KEY] = true
        val newViewModel = CameraViewModel(mockCameraXService, mockDisplayService, savedStateHandle)
        assertThat(newViewModel.isFlippedHorizontally.value).isTrue()
    }
    @Test fun `viewModel initializes currentAwbMode from SavedStateHandle`() = runTest {
        val savedMode = CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
        savedStateHandle[CameraViewModel.AWB_MODE_KEY] = savedMode
        val newViewModel = CameraViewModel(mockCameraXService, mockDisplayService, savedStateHandle)
        assertThat(newViewModel.currentAwbMode.value).isEqualTo(savedMode)
    }

    // --- Tests for Zoom, Exposure, LED, TapToFocus, ExternalDisplay, SupportedAWB (existing) ---
    @Test fun `setZoomRatio calls service with coerced value`() = runTest {
        val initialZoomState = ZoomState.create(2.0f, 1.0f, 4.0f, 0.1f); mockZoomStateFlow.value = initialZoomState; advanceUntilIdle()
        viewModel.setZoomRatio(0.5f); verify { mockCameraXService.setZoomRatio(1.0f) }
        viewModel.setZoomRatio(5.0f); verify { mockCameraXService.setZoomRatio(4.0f) }
        viewModel.setZoomRatio(3.0f); verify { mockCameraXService.setZoomRatio(3.0f) }
    }
    @Test fun `isZoomSupported reflects zoomState correctly`() = runTest {
         viewModel.isZoomSupported.test {
            assertThat(awaitItem()).isFalse()
            mockZoomStateFlow.value = ZoomState.create(1.0f, 1.0f, 1.0f, 0.0f); assertThat(awaitItem()).isFalse()
            mockZoomStateFlow.value = ZoomState.create(2.0f, 1.0f, 3.0f, 0.1f); assertThat(awaitItem()).isTrue()
            cancelAndConsumeRemainingEvents()
        }
    }
    @Test fun `setExposureIndex calls service with coerced value`() = runTest {
        val initialExposureState = ExposureState.create(Range(-2, 2), 0, Rational(1,1)); mockExposureStateFlow.value = initialExposureState; advanceUntilIdle()
        viewModel.setExposureIndex(-3); verify { mockCameraXService.setExposureCompensationIndex(-2) }
        viewModel.setExposureIndex(3); verify { mockCameraXService.setExposureCompensationIndex(2) }
        viewModel.setExposureIndex(1); verify { mockCameraXService.setExposureCompensationIndex(1) }
    }
    @Test fun `isExposureSupported reflects exposureState correctly`() = runTest {
        viewModel.isExposureSupported.test {
            assertThat(awaitItem()).isFalse()
            mockExposureStateFlow.value = ExposureState.create(Range(0,0), 0, Rational(1,1), false); assertThat(awaitItem()).isFalse()
            mockExposureStateFlow.value = ExposureState.create(Range(-2,2), 0, Rational(1,1), true); assertThat(awaitItem()).isTrue()
            cancelAndConsumeRemainingEvents()
        }
    }
    @Test fun `onLedButtonClicked calls service enableTorch true when flash available and LED is off`() = runTest {
        mockHasFlashUnitFlow.value = true; mockTorchStateFlow.value = false
        viewModel.onLedButtonClicked(); verify { mockCameraXService.enableTorch(true) }
    }
    @Test fun `onLedButtonClicked calls service enableTorch false when flash available and LED is on`() = runTest {
        mockHasFlashUnitFlow.value = true; mockTorchStateFlow.value = true
        viewModel.onLedButtonClicked(); verify { mockCameraXService.enableTorch(false) }
    }
    @Test fun `onLedButtonClicked does NOT call service enableTorch when flash is unavailable`() = runTest {
        mockHasFlashUnitFlow.value = false; mockTorchStateFlow.value = false
        viewModel.onLedButtonClicked(); verify(exactly = 0) { mockCameraXService.enableTorch(any()) }
    }
    @Test fun `isLedOn StateFlow correctly reflects service torchStateFlow`() = runTest {
        viewModel.isLedOn.test { assertThat(awaitItem()).isFalse(); mockTorchStateFlow.value = true; assertThat(awaitItem()).isTrue(); mockTorchStateFlow.value = false; assertThat(awaitItem()).isFalse(); cancelAndConsumeRemainingEvents() }
    }
    @Test fun `hasFlashUnit StateFlow correctly reflects service hasFlashUnitFlow`() = runTest {
        viewModel.hasFlashUnit.test { assertThat(awaitItem()).isFalse(); mockHasFlashUnitFlow.value = true; assertThat(awaitItem()).isTrue(); mockHasFlashUnitFlow.value = false; assertThat(awaitItem()).isFalse(); cancelAndConsumeRemainingEvents() }
    }
    @Test fun `onPreviewTapped calls service with correctly constructed FocusMeteringAction`() = runTest {
        val viewWidth = 1280; val viewHeight = 720; val tapX = 300f; val tapY = 400f; val actionSlot = slot<FocusMeteringAction>()
        every { mockCameraXService.startFocusAndMetering(capture(actionSlot)) } returns Futures.immediateFuture(FocusMeteringResult.create(true))
        viewModel.onPreviewTapped(viewWidth, viewHeight, tapX, tapY); verify(exactly = 1) { mockCameraXService.startFocusAndMetering(actionSlot.captured) }
        val ca = actionSlot.captured; assertThat(ca.meteringPoints).hasSize(1); assertThat(ca.meteringPoints.first()).isNotNull(); assertThat(ca.autoCancelDurationMillis).isEqualTo(TimeUnit.SECONDS.toMillis(3))
    }
    @Test fun `onPreviewTapped does not call service if viewWidth is zero`() = runTest { viewModel.onPreviewTapped(0, 720, 1f, 1f); verify(exactly = 0) { mockCameraXService.startFocusAndMetering(any()) } }
    @Test fun `onPreviewTapped does not call service if viewHeight is zero`() = runTest { viewModel.onPreviewTapped(1280, 0, 1f, 1f); verify(exactly = 0) { mockCameraXService.startFocusAndMetering(any()) } }
    @Test fun `primaryCameraInit re-applies non-AUTO AWB mode via service`() = runTest {
        val sm = CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT; viewModel.onWhiteBalanceModeSelected(sm); io.mockk.clearRecordedCalls(mockCameraXService); every { mockCameraXService.setWhiteBalanceMode(any()) } returns Futures.immediateFuture(null)
        viewModel.primaryCameraInit(mockk(relaxed=true), mockk(relaxed=true)); advanceUntilIdle(); verify(exactly = 1) { mockCameraXService.setWhiteBalanceMode(sm) }
    }
    @Test fun `primaryCameraInit does not re-apply AWB mode if it is AUTO`() = runTest {
        assertThat(viewModel.currentAwbMode.value).isEqualTo(CameraMetadata.CONTROL_AWB_MODE_AUTO); io.mockk.clearRecordedCalls(mockCameraXService); every { mockCameraXService.setWhiteBalanceMode(any()) } returns Futures.immediateFuture(null)
        viewModel.primaryCameraInit(mockk(relaxed=true), mockk(relaxed=true)); advanceUntilIdle(); verify(exactly = 0) { mockCameraXService.setWhiteBalanceMode(CameraMetadata.CONTROL_AWB_MODE_AUTO) }
    }
    @Test fun `externalDisplay flow emits null when no external displays`() = runTest {
        val d = mockk<Display>(relaxed=true); every { d.displayId } returns Display.DEFAULT_DISPLAY; viewModel.externalDisplay.test { mockDisplaysFlow.value = emptyList();assertThat(awaitItem()).isNull(); mockDisplaysFlow.value = listOf(d); advanceUntilIdle();assertThat(viewModel.externalDisplay.value).isNull(); cancelAndConsumeRemainingEvents() }
    }
    @Test fun `externalDisplay flow emits the first non-default display`() = runTest {
        val d0 = mockk<Display>(relaxed=true); every { d0.displayId } returns Display.DEFAULT_DISPLAY; val d1 = mockk<Display>(relaxed=true); every { d1.displayId } returns (Display.DEFAULT_DISPLAY+1); val d2 = mockk<Display>(relaxed=true); every { d2.displayId } returns (Display.DEFAULT_DISPLAY+2)
        viewModel.externalDisplay.test { mockDisplaysFlow.value = emptyList(); assertThat(awaitItem()).isNull(); mockDisplaysFlow.value = listOf(d0,d1,d2); assertThat(awaitItem()).isEqualTo(d1); mockDisplaysFlow.value = listOf(d0); assertThat(awaitItem()).isNull(); cancelAndConsumeRemainingEvents() }
    }
    @Test fun `attachExternalDisplaySurface calls service to set external and clear main provider`() = runTest { val sp = mockk<Preview.SurfaceProvider>(relaxed=true); viewModel.attachExternalDisplaySurface(sp); verifyOrder { mockCameraXService.setMainSurfaceProvider(null); mockCameraXService.setExternalSurfaceProvider(sp) } }
    @Test fun `detachExternalDisplaySurface calls service to clear external and restore main provider if stored`() = runTest {
        val msp = mockk<Preview.SurfaceProvider>(relaxed=true); viewModel.primaryCameraInit(mockk(relaxed=true), msp); advanceUntilIdle(); io.mockk.clearRecordedCalls(mockCameraXService); every {mockCameraXService.setMainSurfaceProvider(any())} returns Unit; every {mockCameraXService.setExternalSurfaceProvider(any())} returns Unit
        viewModel.detachExternalDisplaySurface(); verifyOrder { mockCameraXService.setExternalSurfaceProvider(null); mockCameraXService.setMainSurfaceProvider(msp) }
    }
    @Test fun `detachExternalDisplaySurface calls service to clear external and does not set main if none stored`() = runTest { viewModel.detachExternalDisplaySurface(); verify(exactly=1){mockCameraXService.setExternalSurfaceProvider(null)}; verify(exactly=0){mockCameraXService.setMainSurfaceProvider(any())} }
    @Test fun `ViewModel calls startListening on init and stopListening onCleared`() = runTest { verify(exactly=1){mockDisplayService.startListening()}; viewModel.onCleared(); verify(exactly=1){mockDisplayService.stopListening()} }
    @Test fun `supportedWbPresets emits emptyList initially`() = runTest { viewModel.supportedWbPresets.test{assertThat(awaitItem()).isEmpty(); cancelAndConsumeRemainingEvents()} }
    @Test fun `supportedWbPresets emits correct subset based on service flow`() = runTest {
        val exp = listOf(WbPreset("Auto",0),WbPreset("Daylight",5)); val modes = listOf(0,5,999) // CONTROL_AWB_MODE_AUTO is 1, DAYLIGHT is 5 in reality, but test with 0 and 5 for simplicity if constants are complex to get in test. Using actual values.
        val expected = listOf(WbPreset("Auto", CameraMetadata.CONTROL_AWB_MODE_AUTO), WbPreset("Daylight", CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT))
        val actualModes = listOf(CameraMetadata.CONTROL_AWB_MODE_AUTO, CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT, 999)
        viewModel.supportedWbPresets.test{assertThat(awaitItem()).isEmpty();mockAvailableAwbModesFlow.value=actualModes;assertThat(awaitItem()).containsExactlyElementsIn(expected).inOrder();cancelAndConsumeRemainingEvents()}
    }
    @Test fun `supportedWbPresets emits all known presets if all modes are available`() = runTest {
        val allMaster = CameraViewModel.allKnownWbPresets; viewModel.supportedWbPresets.test{assertThat(awaitItem()).isEmpty();mockAvailableAwbModesFlow.value=allMaster.map{it.mode};val em=awaitItem();assertThat(em).hasSize(allMaster.size);assertThat(em).containsExactlyElementsIn(allMaster).inOrder();cancelAndConsumeRemainingEvents()}
    }
    @Test fun `supportedWbPresets emits emptyList when service provides empty list of modes again`() = runTest {
        viewModel.supportedWbPresets.test{assertThat(awaitItem()).isEmpty();mockAvailableAwbModesFlow.value=listOf(CameraMetadata.CONTROL_AWB_MODE_AUTO);assertThat(awaitItem()).hasSize(1);mockAvailableAwbModesFlow.value=emptyList();assertThat(awaitItem()).isEmpty();cancelAndConsumeRemainingEvents()}
    }
    @Test fun `supportedWbPresets filters out modes not in master list`() = runTest {
        val modes=listOf(CameraMetadata.CONTROL_AWB_MODE_AUTO,100,CameraMetadata.CONTROL_AWB_MODE_SHADE,101); val exp=listOf(WbPreset("Auto",CameraMetadata.CONTROL_AWB_MODE_AUTO),WbPreset("Shade",CameraMetadata.CONTROL_AWB_MODE_SHADE))
        viewModel.supportedWbPresets.test{assertThat(awaitItem()).isEmpty();mockAvailableAwbModesFlow.value=modes;assertThat(awaitItem()).containsExactlyElementsIn(exp).inOrder();cancelAndConsumeRemainingEvents()}
    }

    // --- Tests for Error Handling ---
    @Test
    fun `viewModel displayedError is updated on InitializationError from service`() = runTest {
        val errorMessage = "Camera hardware unavailable"
        val initError = CameraErrorEvent.InitializationError(errorMessage, RuntimeException("Cause"))

        viewModel.displayedError.test {
            // This awaitItem() might receive the initial null from displayedError's stateIn.
            // Depending on test dispatcher, collection might start before or after emit.
            // If it starts after, first awaitItem will get the error.
            // If it starts before, first is null, second is error.
            val firstItem = awaitItem()

            mockCameraErrorFlow.emit(initError)

            val receivedUserError = if (firstItem == null) awaitItem() else firstItem // Adjust based on timing
            assertThat(receivedUserError).isNotNull()
            assertThat(receivedUserError?.message).isEqualTo("Error initializing camera: $errorMessage")
            // Consume any other potential emissions if tests run in parallel or flow is hot
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `viewModel toastMessages emits on ControlError from service`() = runTest {
        val operationName = "setZoomRatio"
        val errorMessage = "Zoom failed"
        val controlError = CameraErrorEvent.ControlError(operationName, errorMessage, RuntimeException("Cause"))

        viewModel.toastMessages.test {
            // SharedFlows with test { } don't await an initial item unless one was already replayed.
            mockCameraErrorFlow.emit(controlError)

            val receivedToastMessage = awaitItem()
            assertThat(receivedToastMessage).isEqualTo("Operation failed: $operationName")
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `clearDisplayedError sets displayedError to null`() = runTest {
        val initError = CameraErrorEvent.InitializationError("Initial error", null)

        viewModel.displayedError.test {
            val initial = awaitItem() // Consume initial null or any previous state

            mockCameraErrorFlow.emit(initError)
            val errorState = awaitItem()
            assertThat(errorState).isNotNull()

            viewModel.clearDisplayedError()
            assertThat(awaitItem()).isNull()
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `primaryCameraInit sets displayedError on CameraInitResult Failure`() = runTest {
        val failureMessage = "Test Init Failure From Result"
        val exception = RuntimeException(failureMessage)
        coEvery { mockCameraXService.initializeAndBindCamera(any(), any(), any()) } returns CameraInitResult.Failure(exception)

        viewModel.displayedError.test {
            assertThat(awaitItem()).isNull()

            val mockLifecycleOwner = mockk<LifecycleOwner>(relaxed = true)
            val mockSurfaceProvider = mockk<Preview.SurfaceProvider>(relaxed = true)
            viewModel.primaryCameraInit(mockLifecycleOwner, mockSurfaceProvider)

            // advanceUntilIdle() // ensure coroutine in primaryCameraInit processing completes for direct set

            val receivedUserError = awaitItem() // This will be from the direct set in primaryCameraInit
            assertThat(receivedUserError).isNotNull()
            assertThat(receivedUserError?.message).isEqualTo("Failed to initialize camera: $failureMessage")
            cancelAndConsumeRemainingEvents()
        }
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

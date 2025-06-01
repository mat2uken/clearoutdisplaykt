package com.example.cameraapp

import android.util.Range
import android.util.Rational
import androidx.camera.core.CameraInfo.ExposureState
import androidx.camera.core.CameraInfo.ZoomState
import androidx.camera.core.CameraSelector
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import com.example.cameraapp.camera.CameraErrorEvent
import com.example.cameraapp.fakes.FakeCameraXService
import com.example.cameraapp.fakes.FakeDisplayService
import com.example.cameraapp.ui.theme.CameraAppTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.hardware.camera2.CameraMetadata
import android.view.Display // Import Display
import io.mockk.every // Import every
import io.mockk.mockk // Import mockk

@RunWith(AndroidJUnit4::class)
class CameraScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var fakeCameraXService: FakeCameraXService
    private lateinit var fakeDisplayService: FakeDisplayService
    private lateinit var viewModel: CameraViewModel
    private lateinit var savedStateHandle: SavedStateHandle

    @Before
    fun setUp() {
        fakeCameraXService = FakeCameraXService()
        fakeDisplayService = FakeDisplayService()
        savedStateHandle = SavedStateHandle()

        viewModel = CameraViewModel(fakeCameraXService, fakeDisplayService, savedStateHandle)
    }

    private fun setCameraScreenContent() {
        composeTestRule.setContent {
            CameraAppTheme {
                CameraScreen(viewModel = viewModel)
            }
        }
    }

    @Test
    fun coreControlButtons_areDisplayed() {
        setCameraScreenContent()
        composeTestRule.onNodeWithContentDescription("Switch Camera").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Turn LED On").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Flip Preview").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Adjust Exposure").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Adjust White Balance").assertIsDisplayed()
    }

    @Test
    fun ledButton_isDisabled_whenNoFlashUnit() {
        fakeCameraXService.setHasFlashUnit(false)
        setCameraScreenContent()
        composeTestRule.onNodeWithContentDescription("Turn LED On").assertIsNotEnabled()
    }

    @Test
    fun ledButton_isEnabled_whenFlashUnitExists() {
        fakeCameraXService.setHasFlashUnit(true)
        setCameraScreenContent()
        composeTestRule.onNodeWithContentDescription("Turn LED On").assertIsEnabled()
    }

    @Test
    fun zoomSlider_isNotDisplayed_whenZoomNotSupported() {
        fakeCameraXService.emitZoomState(ZoomState.create(1.0f, 1.0f, 1.0f, 0.0f))
        setCameraScreenContent()
        composeTestRule.onNodeWithText("Zoom: N/A").assertIsDisplayed()
    }

    @Test
    fun zoomSlider_isDisplayed_whenZoomIsSupported() {
        fakeCameraXService.emitZoomState(ZoomState.create(2.0f, 1.0f, 4.0f, 0.1f))
        setCameraScreenContent()
        composeTestRule.onNodeWithText("Zoom: N/A").assertDoesNotExist()
        composeTestRule.onNode(hasText("Zoom:", substring = true) and hasText("x", substring = true)).assertIsDisplayed()
    }

    @Test
    fun exposureButton_isDisabled_whenExposureNotSupported() {
        fakeCameraXService.emitExposureState(ExposureState.create(Range(0,0), 0, Rational(1,1), false))
        setCameraScreenContent()
        composeTestRule.onNodeWithContentDescription("Adjust Exposure").assertIsNotEnabled()
    }

    @Test
    fun exposureButton_isEnabled_whenExposureIsSupported() {
        fakeCameraXService.emitExposureState(ExposureState.create(Range(-2,2), 0, Rational(1,1), true))
        setCameraScreenContent()
        composeTestRule.onNodeWithContentDescription("Adjust Exposure").assertIsEnabled()
    }

    @Test
    fun externalDisplayIndicator_isNotDisplayed_initially() {
        // Default in FakeDisplayService is emptyList, which is fine for initial.
        setCameraScreenContent()
        composeTestRule.onNodeWithText("EXT OUT").assertDoesNotExist()
    }

    @Test
    fun errorDisplay_isNotDisplayed_initially() {
        setCameraScreenContent()
        composeTestRule.onNodeWithContentDescription("Dismiss error").assertDoesNotExist()
    }

    @Test
    fun switchCameraButton_click_togglesLensFacing_andReinitializesCamera() {
        setCameraScreenContent()
        composeTestRule.waitForIdle()
        fakeCameraXService.initCameraCalledWith = null

        composeTestRule.onNodeWithContentDescription("Switch Camera").performClick()
        composeTestRule.waitForIdle()
        assertThat(fakeCameraXService.initCameraCalledWith?.third).isEqualTo(CameraSelector.LENS_FACING_FRONT)

        composeTestRule.onNodeWithContentDescription("Switch Camera").performClick()
        composeTestRule.waitForIdle()
        assertThat(fakeCameraXService.initCameraCalledWith?.third).isEqualTo(CameraSelector.LENS_FACING_BACK)
    }

    @Test
    fun ledButton_click_togglesLedState_whenFlashAvailable() {
        fakeCameraXService.setHasFlashUnit(true)
        fakeCameraXService.setTorchState(false)
        setCameraScreenContent()

        composeTestRule.onNodeWithContentDescription("Turn LED On").assertIsEnabled()
        composeTestRule.onNodeWithContentDescription("Turn LED On").performClick()
        composeTestRule.waitForIdle()
        assertThat(fakeCameraXService.enableTorchCalledWith).isTrue()
        fakeCameraXService.setTorchState(true)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Turn LED Off").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Turn LED On").assertDoesNotExist()

        composeTestRule.onNodeWithContentDescription("Turn LED Off").performClick()
        composeTestRule.waitForIdle()
        assertThat(fakeCameraXService.enableTorchCalledWith).isFalse()
        fakeCameraXService.setTorchState(false)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Turn LED On").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Turn LED Off").assertDoesNotExist()
    }

    @Test
    fun flipButton_click_togglesFlipState_andUpdatesContentDescription() {
        setCameraScreenContent()
        composeTestRule.onNodeWithContentDescription("Flip Preview").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Flip Preview").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Unflip Preview").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Flip Preview").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Unflip Preview").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Flip Preview").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Unflip Preview").assertDoesNotExist()
    }

    @Test
    fun zoomControls_displayCorrectState_whenZoomSupported() {
        fakeCameraXService.emitZoomState(ZoomState.create(2.0f, 1.0f, 4.0f, 0.1f))
        setCameraScreenContent()
        composeTestRule.onNode(hasText("Zoom: 2.00x", substring = true)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Zoom: N/A").assertDoesNotExist()
    }

    @Test
    fun exposureDialog_appearsAndFunctionsCorrectly() {
        val initialExposureRange = Range(-2, 2)
        val exposureStep = Rational(1, 1)
        fakeCameraXService.emitExposureState(ExposureState.create(initialExposureRange, 0, exposureStep, true))
        setCameraScreenContent()

        composeTestRule.onNodeWithContentDescription("Adjust Exposure").performClick()
        composeTestRule.onNodeWithText("Adjust Exposure", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Current: 0.0 EV", useUnmergedTree = true).assertIsDisplayed()

        composeTestRule.onNodeWithText("+", useUnmergedTree = true).performClick()
        composeTestRule.waitForIdle()
        assertThat(fakeCameraXService.setExposureIndexCalledWith).isEqualTo(1)
        fakeCameraXService.emitExposureState(ExposureState.create(initialExposureRange, 1, exposureStep, true))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Current: 1.0 EV", useUnmergedTree = true).assertIsDisplayed()

        composeTestRule.onNodeWithText("-", useUnmergedTree = true).performClick()
        fakeCameraXService.emitExposureState(ExposureState.create(initialExposureRange, 0, exposureStep, true))
        composeTestRule.waitForIdle()
        assertThat(fakeCameraXService.setExposureIndexCalledWith).isEqualTo(0)

        composeTestRule.onNodeWithText("-", useUnmergedTree = true).performClick()
        fakeCameraXService.emitExposureState(ExposureState.create(initialExposureRange, -1, exposureStep, true))
        composeTestRule.waitForIdle()
        assertThat(fakeCameraXService.setExposureIndexCalledWith).isEqualTo(-1)
        composeTestRule.onNodeWithText("Current: -1.0 EV", useUnmergedTree = true).assertIsDisplayed()

        fakeCameraXService.emitExposureState(ExposureState.create(Range(-1, 1), -1, exposureStep, true))
        composeTestRule.onNodeWithText("Done", useUnmergedTree = true).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Adjust Exposure").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("-", useUnmergedTree = true).assertIsNotEnabled()
        composeTestRule.onNodeWithText("+", useUnmergedTree = true).assertIsEnabled()
        composeTestRule.onNodeWithText("Done", useUnmergedTree = true).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Adjust Exposure", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun whiteBalanceDialog_displaysSupportedPresets_andCallsService() {
        val availableModes = listOf(
            CameraMetadata.CONTROL_AWB_MODE_AUTO,
            CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
        )
        fakeCameraXService.emitAvailableAwbModes(availableModes)
        setCameraScreenContent()

        composeTestRule.onNodeWithContentDescription("Adjust White Balance").performClick()
        composeTestRule.onNodeWithText("Select White Balance", useUnmergedTree = true).assertIsDisplayed()

        composeTestRule.onNodeWithText("Auto", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Daylight", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Incandescent", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Cloudy Daylight", useUnmergedTree = true).assertDoesNotExist()

        composeTestRule.onNodeWithText("Daylight", useUnmergedTree = true).performClick()
        composeTestRule.waitForIdle()
        assertThat(fakeCameraXService.setWhiteBalanceModeCalledWith).isEqualTo(CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT)

        composeTestRule.onNodeWithText("Select White Balance", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun whiteBalanceDialog_showsNotAvailable_whenNoPresetsSupported() {
        fakeCameraXService.emitAvailableAwbModes(emptyList())
        setCameraScreenContent()

        composeTestRule.onNodeWithContentDescription("Adjust White Balance").performClick()
        composeTestRule.onNodeWithText("No AWB presets available for this camera.", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Auto", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Cancel", useUnmergedTree = true).performClick()
    }

    @Test
    fun persistentError_isDisplayed_whenInitializationErrorOccurs() {
        setCameraScreenContent()

        val errorMessage = "Simulated Init Failed"
        val displayMessage = "Error initializing camera: $errorMessage"

        fakeCameraXService.emitCameraError(CameraErrorEvent.InitializationError(errorMessage))

        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(displayMessage, substring = true, useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Dismiss error", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun persistentError_isDismissed_whenDismissButtonClicked() {
        setCameraScreenContent()

        val errorMessage = "Dismiss Test Error"
        val displayMessage = "Error initializing camera: $errorMessage"

        fakeCameraXService.emitCameraError(CameraErrorEvent.InitializationError(errorMessage))
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(displayMessage, substring = true, useUnmergedTree = true).assertIsDisplayed()
        val dismissButton = composeTestRule.onNodeWithContentDescription("Dismiss error", useUnmergedTree = true)
        dismissButton.assertIsDisplayed()

        dismissButton.performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(displayMessage, substring = true, useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Dismiss error", useUnmergedTree = true).assertDoesNotExist()
    }

    // --- New External Display Indicator Tests ---
    @Test
    fun externalDisplayIndicator_isNotDisplayed_whenNoExternalDisplay() {
        fakeDisplayService.emitDisplays(emptyList())
        setCameraScreenContent()
        composeTestRule.onNodeWithText("EXT OUT").assertDoesNotExist()
    }

    @Test
    fun externalDisplayIndicator_isDisplayed_whenExternalDisplayConnected() {
        // setCameraScreenContent() // Set content first to establish initial UI state

        val mockDefaultDisplay = mockk<Display>(relaxed = true)
        every { mockDefaultDisplay.displayId } returns Display.DEFAULT_DISPLAY
        val mockExternalDisplay = mockk<Display>(relaxed = true)
        every { mockExternalDisplay.displayId } returns Display.DEFAULT_DISPLAY + 1

        fakeDisplayService.emitDisplays(listOf(mockDefaultDisplay, mockExternalDisplay))
        setCameraScreenContent() // Re-set or ensure recomposition after state change if VM init relies on it
                                 // For this test, setting content AFTER emitting is fine as VM collects on init.
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("EXT OUT").assertIsDisplayed()
    }

    @Test
    fun externalDisplayIndicator_isNotDisplayed_whenExternalDisplayDisconnected() {
        val mockDefaultDisplay = mockk<Display>(relaxed = true)
        every { mockDefaultDisplay.displayId } returns Display.DEFAULT_DISPLAY
        val mockExternalDisplay = mockk<Display>(relaxed = true)
        every { mockExternalDisplay.displayId } returns Display.DEFAULT_DISPLAY + 1

        fakeDisplayService.emitDisplays(listOf(mockDefaultDisplay, mockExternalDisplay))
        setCameraScreenContent()
        composeTestRule.onNodeWithText("EXT OUT").assertIsDisplayed() // Verify initial state for this test

        fakeDisplayService.emitDisplays(listOf(mockDefaultDisplay))
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("EXT OUT").assertDoesNotExist()
    }

    @Test
    fun externalDisplayIndicator_isNotDisplayed_whenOnlyDefaultDisplayPresent() {
        val mockDefaultDisplay = mockk<Display>(relaxed = true)
        every { mockDefaultDisplay.displayId } returns Display.DEFAULT_DISPLAY

        fakeDisplayService.emitDisplays(listOf(mockDefaultDisplay))
        setCameraScreenContent()

        composeTestRule.onNodeWithText("EXT OUT").assertDoesNotExist()
    }
}

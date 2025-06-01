package com.example.cameraapp

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
// No GrantPermissionRule needed here
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class PermissionFlowNotGrantedTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun whenCameraPermissionNotGranted_permissionRequestUiIsDisplayed() {
        // Activity launches, permission is NOT granted by default.
        // HandleCameraPermission should show the rationale/request UI.

        composeTestRule.waitForIdle() // Allow UI to settle

        // Verify permission request UI is visible (texts from HandleCameraPermission)
        composeTestRule.onNodeWithText("Camera permission is required to use this app.", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Request permission").assertIsDisplayed()

        // Verify CameraScreen elements are NOT visible
        composeTestRule.onNodeWithContentDescription("Switch Camera").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Adjust Exposure").assertDoesNotExist()
        // ... (add more checks for other CameraScreen elements if desired)
    }
}

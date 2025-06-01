package com.example.cameraapp

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class PermissionFlowGrantedTest {

    // GrantPermissionRule must be defined before createAndroidComposeRule for it to take effect
    // before the Activity is launched. Rule order can be specified if needed.
    @get:Rule(order = 0)
    val grantPermissionRule: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.CAMERA)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun whenCameraPermissionGranted_cameraScreenIsDisplayed() {
        // Activity launches, permission is granted by the rule.
        // HandleCameraPermission should detect this and navigate to CameraScreen.

        composeTestRule.waitForIdle() // Allow UI to settle

        // Verify CameraScreen elements are visible
        // These content descriptions are from CameraScreen's buttons
        composeTestRule.onNodeWithContentDescription("Switch Camera").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Adjust Exposure").assertIsDisplayed()
        // ... (add more checks for other CameraScreen elements if desired)

        // Verify permission request UI is NOT visible (texts from HandleCameraPermission)
        composeTestRule.onNodeWithText("Request permission").assertDoesNotExist()
        composeTestRule.onNodeWithText("Camera permission is required to use this app.").assertDoesNotExist()
    }
}

package com.google.codelab.android.camera

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.camera.view.PreviewView

class ExternalDisplayPresentation(
    outerContext: Context,
    display: Display
) : Presentation(outerContext, display) {

    private lateinit var previewView: PreviewView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val frameLayout = FrameLayout(context)
        previewView = PreviewView(context)
        previewView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        frameLayout.addView(previewView) // Add previewView to frameLayout first

        // Begin dynamic aspect ratio and rotation logic
        val displayMetrics = DisplayMetrics()
        @Suppress("DEPRECATION") // getRealMetrics is deprecated but necessary for older APIs if not handled by WindowManager
        display.getRealMetrics(displayMetrics)
        val displayWidth = displayMetrics.widthPixels
        val displayHeight = displayMetrics.heightPixels

        val cameraAspectRatio = 16.0 / 9.0
        val displayAspectRatio = if (displayHeight > 0) displayWidth.toDouble() / displayHeight.toDouble() else 0.0

        Log.d("ExternalDisplay", "Display: ${displayWidth}x${displayHeight} (AR: $displayAspectRatio), Camera AR: $cameraAspectRatio")

        if (displayWidth <= 0 || displayHeight <= 0) {
            Log.w("ExternalDisplay", "Invalid display dimensions. Defaulting to FIT_CENTER.")
            previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
            frameLayout.rotation = 0f
        } else if (displayAspectRatio >= 1.0) { // Landscape-ish or square display
            if (displayAspectRatio >= cameraAspectRatio) {
                previewView.scaleType = PreviewView.ScaleType.FILL_CENTER // Fills height, crops width
                Log.d("ExternalDisplay", "Display is wider or same AR as camera. Using FILL_CENTER.")
            } else { // displayAspectRatio < cameraAspectRatio
                previewView.scaleType = PreviewView.ScaleType.FILL_CENTER // Fills width, crops height
                Log.d("ExternalDisplay", "Display is narrower than camera (but landscape/square). Using FILL_CENTER.")
            }
            frameLayout.rotation = 0f
        } else { // Portrait-ish display (displayAspectRatio < 1.0)
            // Scenario 1: No rotation
            // Scale type will be FILL_CENTER. This will fill display width, crop camera height.
            val cameraHeightForDisplayWidth = displayWidth / cameraAspectRatio
            val croppedHeightNoRotation = cameraHeightForDisplayWidth - displayHeight
            val percentCroppedNoRotation = if (cameraHeightForDisplayWidth > 0) croppedHeightNoRotation / cameraHeightForDisplayWidth else 0.0

            // Scenario 2: With 90-degree rotation
            val effectiveDisplayWidth = displayHeight // becomes width for camera
            val effectiveDisplayHeight = displayWidth  // becomes height for camera
            val rotatedDisplayAspectRatio = if (effectiveDisplayHeight > 0) effectiveDisplayWidth.toDouble() / effectiveDisplayHeight.toDouble() else 0.0

            var percentCroppedWithRotation = 1.0 // Default to max crop

            if (effectiveDisplayHeight > 0 && effectiveDisplayWidth > 0) {
                if (rotatedDisplayAspectRatio >= cameraAspectRatio) {
                    // Rotated display is wider or same AR. Fill effective height (orig width), crop effective width (orig height).
                    val cameraWidthForEffectiveDisplayHeight = effectiveDisplayHeight * cameraAspectRatio
                    val croppedWidthWithRotation = cameraWidthForEffectiveDisplayHeight - effectiveDisplayWidth
                    percentCroppedWithRotation = if (cameraWidthForEffectiveDisplayHeight > 0) croppedWidthWithRotation / cameraWidthForEffectiveDisplayHeight else 0.0
                } else {
                    // Rotated display is narrower. Fill effective width (orig height), crop effective height (orig width).
                    val cameraHeightForEffectiveDisplayWidth = effectiveDisplayWidth / cameraAspectRatio
                    val croppedHeightWithRotation = cameraHeightForEffectiveDisplayWidth - effectiveDisplayHeight
                    percentCroppedWithRotation = if (cameraHeightForEffectiveDisplayWidth > 0) croppedHeightWithRotation / cameraHeightForEffectiveDisplayWidth else 0.0
                }
            }

            Log.d("ExternalDisplay", "Portrait: No rotation crop %: $percentCroppedNoRotation, With rotation crop %: $percentCroppedWithRotation")

            val rotationThreshold = 0.75 // Max acceptable crop percentage when choosing rotation
            if (percentCroppedWithRotation < percentCroppedNoRotation && percentCroppedWithRotation < rotationThreshold) {
                Log.d("ExternalDisplay", "Applying 90-degree rotation for portrait display.")
                frameLayout.rotation = 90f
                previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
            } else {
                Log.d("ExternalDisplay", "Not rotating for portrait display. Using FILL_CENTER.")
                frameLayout.rotation = 0f
                previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        }

        if (frameLayout.rotation == 90f || frameLayout.rotation == 270f) {
            val pLayoutParams = previewView.layoutParams
            // Check if parent is FrameLayout to safely cast
            if (pLayoutParams is FrameLayout.LayoutParams) {
                 // Swap width and height for PreviewView because the parent FrameLayout is rotated
                pLayoutParams.width = displayHeight
                pLayoutParams.height = displayWidth
                previewView.layoutParams = pLayoutParams
                Log.d("ExternalDisplay", "Adjusted PreviewView layout params for rotation: ${pLayoutParams.width}x${pLayoutParams.height}")
            } else {
                 Log.w("ExternalDisplay", "Could not adjust PreviewView layout params: not FrameLayout.LayoutParams")
                 // Fallback or alternative adjustment might be needed if parent isn't a FrameLayout or if this doesn't work.
                 // For now, we assume previewView is directly in frameLayout which has MATCH_PARENT.
                 // The rotation of frameLayout should make previewView (MATCH_PARENT) adapt,
                 // but explicitly setting dimensions for the rotated view can be more robust.
                 // If MATCH_PARENT on PreviewView works as expected within a rotated FrameLayout,
                 // this explicit adjustment might not be strictly necessary but acts as a safeguard.
            }
        }
        // setContentView must be called after frameLayout is fully configured, including rotation and its children.
        setContentView(frameLayout)
    }

    fun getPreviewView(): PreviewView {
        return previewView
    }
}

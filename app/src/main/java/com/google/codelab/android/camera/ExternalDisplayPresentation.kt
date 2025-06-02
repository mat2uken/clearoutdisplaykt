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
        frameLayout.addView(previewView)

        // New FIT_CENTER based logic
        val displayMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(displayMetrics)
        var displayWidth = displayMetrics.widthPixels
        var displayHeight = displayMetrics.heightPixels

        val cameraAspectRatio = 16.0 / 9.0
        Log.d("ExternalDisplay", "Initial Display: ${displayWidth}x${displayHeight}, Camera AR: $cameraAspectRatio")

        // Set default rotation and scale type
        frameLayout.rotation = 0f
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER

        if (displayWidth <= 0 || displayHeight <= 0) {
            Log.w("ExternalDisplay", "Invalid display dimensions. Using FIT_CENTER with no rotation.")
            // Defaults already set, so just log and proceed to set content view
        } else {
            // Calculate scaled dimensions and area for NO rotation
            var nonRotatedPreviewWidth: Double
            var nonRotatedPreviewHeight: Double
            val displayAr = displayWidth.toDouble() / displayHeight.toDouble()

            if (displayAr > cameraAspectRatio) { // Display is wider than camera -> letterbox
                nonRotatedPreviewHeight = displayHeight.toDouble()
                nonRotatedPreviewWidth = nonRotatedPreviewHeight * cameraAspectRatio
            } else { // Display is narrower or same AR as camera -> pillarbox or perfect fit
                nonRotatedPreviewWidth = displayWidth.toDouble()
                nonRotatedPreviewHeight = nonRotatedPreviewWidth / cameraAspectRatio
            }
            val areaNoRotation = nonRotatedPreviewWidth * nonRotatedPreviewHeight
            Log.d("ExternalDisplay", "No rotation: Preview ${String.format("%.0f", nonRotatedPreviewWidth)}x${String.format("%.0f", nonRotatedPreviewHeight)}, Area: ${String.format("%.0f", areaNoRotation)}")

            // Calculate scaled dimensions and area FOR 90-degree ROTATION
            val rotatedDisplayEffWidth = displayHeight // Effective display width for camera is original display height
            val rotatedDisplayEffHeight = displayWidth  // Effective display height for camera is original display width
            var rotatedPreviewWidth: Double
            var rotatedPreviewHeight: Double
            // Avoid division by zero if rotatedDisplayEffHeight is 0 (e.g. displayWidth was 0)
            val rotatedDisplayAr = if (rotatedDisplayEffHeight > 0) rotatedDisplayEffWidth.toDouble() / rotatedDisplayEffHeight.toDouble() else 0.0

            if (rotatedDisplayEffWidth <= 0 || rotatedDisplayEffHeight <= 0) { // Should not happen if initial check passed, but good for safety
                 rotatedPreviewWidth = 0.0
                 rotatedPreviewHeight = 0.0
            } else if (rotatedDisplayAr > cameraAspectRatio) {
                rotatedPreviewHeight = rotatedDisplayEffHeight.toDouble()
                rotatedPreviewWidth = rotatedPreviewHeight * cameraAspectRatio
            } else {
                rotatedPreviewWidth = rotatedDisplayEffWidth.toDouble()
                rotatedPreviewHeight = rotatedPreviewWidth / cameraAspectRatio
            }
            val areaWithRotation = rotatedPreviewWidth * rotatedPreviewHeight
            Log.d("ExternalDisplay", "With 90deg rotation: Preview ${String.format("%.0f", rotatedPreviewWidth)}x${String.format("%.0f", rotatedPreviewHeight)}, Area: ${String.format("%.0f", areaWithRotation)} (original display ${displayWidth}x${displayHeight} considered as ${rotatedDisplayEffWidth}x${rotatedDisplayEffHeight} for preview)")

            // Compare areas only if display is portrait (height > width)
            if (displayHeight > displayWidth) { // Portrait display
                if (areaWithRotation > areaNoRotation) {
                    Log.d("ExternalDisplay", "Portrait display: Rotation provides larger preview area. Applying 90-degree rotation.")
                    frameLayout.rotation = 90f
                } else {
                    Log.d("ExternalDisplay", "Portrait display: No rotation provides larger or equal preview area.")
                    frameLayout.rotation = 0f // Ensure it's reset
                }
            } else { // Landscape or square display
                Log.d("ExternalDisplay", "Landscape/Square display: No rotation chosen.")
                frameLayout.rotation = 0f
            }
        }

        // Explicitly set scale type (already default, but for clarity per instruction)
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        Log.d("ExternalDisplay", "Applied ScaleType: FIT_CENTER, Rotation: ${frameLayout.rotation} degrees")

        // Adjust PreviewView layout parameters if rotation is applied
        if (frameLayout.rotation == 90f || frameLayout.rotation == 270f) {
            val pLayoutParams = previewView.layoutParams
            // Check if params actually need changing to avoid unnecessary layout pass
            if (pLayoutParams.width != displayHeight || pLayoutParams.height != displayWidth) {
                 pLayoutParams.width = displayHeight // Fill the new 'width' of FrameLayout (which is original displayHeight)
                 pLayoutParams.height = displayWidth  // Fill the new 'height' of FrameLayout (which is original displayWidth)
                 previewView.layoutParams = pLayoutParams
                 Log.d("ExternalDisplay", "Adjusted PreviewView layout params for rotation: ${pLayoutParams.width}x${pLayoutParams.height}")
            } else {
                 Log.d("ExternalDisplay", "PreviewView layout params already match rotated dimensions.")
            }
        } else {
            // If not rotated, ensure MATCH_PARENT is used if it was changed.
            // PreviewView is initialized with MATCH_PARENT. This block ensures it stays that way
            // if any prior logic might have set specific dimensions.
            val pLayoutParams = previewView.layoutParams
            if (pLayoutParams.width != ViewGroup.LayoutParams.MATCH_PARENT || pLayoutParams.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                 pLayoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
                 pLayoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
                 previewView.layoutParams = pLayoutParams
                 Log.d("ExternalDisplay", "Reset PreviewView layout params to MATCH_PARENT for no rotation.")
            }
        }
        setContentView(frameLayout)
    }

    fun getPreviewView(): PreviewView {
        return previewView
    }
}

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
        Log.d("ExternalDisplay", "Raw Display Metrics: Width=$displayWidth, Height=$displayHeight")

        val displayCategory = if (displayWidth >= displayHeight) "Landscape/Square" else "Portrait"
        Log.d("ExternalDisplay", "Detected Display Category: $displayCategory")

        val cameraAspectRatio = 16.0 / 9.0
        // The "Initial Display" log below is slightly redundant with "Raw Display Metrics" but also shows Camera AR.
        // Keeping it for now as per current task focused on adding, not removing/refactoring existing logs.
        Log.d("ExternalDisplay", "Initial Display: ${displayWidth}x${displayHeight}, Camera AR: $cameraAspectRatio")

        // Configure PreviewView and FrameLayout based on determined displayCategory
        if (displayWidth <= 0 || displayHeight <= 0) {
            Log.w("ExternalDisplay", "Invalid display dimensions. Defaulting to FIT_CENTER and no rotation.")
            frameLayout.rotation = 0f
            previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
            // Ensure PreviewView layout params are MATCH_PARENT (should be default)
            val pLayoutParams = previewView.layoutParams
            if (pLayoutParams.width != ViewGroup.LayoutParams.MATCH_PARENT || pLayoutParams.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                pLayoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
                pLayoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
                previewView.layoutParams = pLayoutParams
            }
        } else if (displayCategory == "Landscape/Square") {
            Log.d("ExternalDisplay", "Applying Landscape/Square strategy.")
            frameLayout.rotation = 0f

            // Explicitly set PreviewView dimensions
            val pLayoutParams = previewView.layoutParams
            if (pLayoutParams.width != displayWidth || pLayoutParams.height != displayHeight) {
                pLayoutParams.width = displayWidth
                pLayoutParams.height = displayHeight
                previewView.layoutParams = pLayoutParams
                Log.d("ExternalDisplay", "Explicitly set PreviewView layout to ${displayWidth}x${displayHeight} for Landscape.")
            } else {
                Log.d("ExternalDisplay", "PreviewView layout already ${displayWidth}x${displayHeight} for Landscape.")
            }
            previewView.scaleType = PreviewView.ScaleType.FIT_CENTER // Should remain FIT_CENTER
            Log.d("ExternalDisplay", "Landscape/Square: Rotation=0, ScaleType=FIT_CENTER, Layout=${displayWidth}x${displayHeight}.")

        } else { // Portrait
            Log.d("ExternalDisplay", "Applying Portrait strategy.")
            frameLayout.rotation = 90f

            val pLayoutParams = previewView.layoutParams
            // Set specific dimensions for PreviewView to fill the rotated FrameLayout
            // Check if params actually need changing to avoid unnecessary layout pass
            if (pLayoutParams.width != displayHeight || pLayoutParams.height != displayWidth) {
                pLayoutParams.width = displayHeight // Rotated FrameLayout's new width is original display height
                pLayoutParams.height = displayWidth  // Rotated FrameLayout's new height is original display width
                previewView.layoutParams = pLayoutParams
                Log.d("ExternalDisplay", "Adjusted PreviewView layout params for rotation: ${pLayoutParams.width}x${pLayoutParams.height}")
            } else {
                Log.d("ExternalDisplay", "PreviewView layout params already match rotated dimensions for Portrait.")
            }
            previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
            Log.d("ExternalDisplay", "Portrait: Rotation=90, ScaleType=FIT_CENTER, Layout=${pLayoutParams.width}x${pLayoutParams.height}.")
        }
        setContentView(frameLayout)
    }

    fun getPreviewView(): PreviewView {
        return previewView
    }
}

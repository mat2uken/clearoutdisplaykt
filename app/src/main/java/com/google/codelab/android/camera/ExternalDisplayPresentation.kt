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
    display: Display,
    val initialRotationDegrees: Int // New parameter
) : Presentation(outerContext, display) {

    private lateinit var previewView: PreviewView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set the Presentation window layout to MATCH_PARENT
        val currentWindow = this.window
        if (currentWindow == null) {
            Log.e("ExternalDisplay", "Presentation window is null, cannot set layout parameters.")
        } else {
            currentWindow.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            Log.d("ExternalDisplay", "Set Presentation window layout to MATCH_PARENT.")
        }

        val frameLayout = FrameLayout(context)
        previewView = PreviewView(context)
        previewView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        frameLayout.addView(previewView)

        // Get physical display metrics
        val physicalDisplayMetrics = DisplayMetrics()
        @Suppress("DEPRECATION") // display.getRealMetrics is deprecated but needed.
        display.getRealMetrics(physicalDisplayMetrics)
        val physicalDisplayWidth = physicalDisplayMetrics.widthPixels
        val physicalDisplayHeight = physicalDisplayMetrics.heightPixels
        Log.d("ExternalDisplay", "Physical Display: ${physicalDisplayWidth}x${physicalDisplayHeight}. Initial Rotation from ViewModel: $initialRotationDegrees deg.")

        // Apply rotation to FrameLayout based on initialRotationDegrees from ViewModel
        frameLayout.rotation = initialRotationDegrees.toFloat()
        Log.d("ExternalDisplay", "Applied rotation ${frameLayout.rotation} deg to FrameLayout.")

        // Set PreviewView Layout Parameters based on physical dimensions and FrameLayout's rotation
        val pLayoutParams = previewView.layoutParams // Get existing params (should be MATCH_PARENT initially)
        if (initialRotationDegrees == 90 || initialRotationDegrees == 270) {
            // When FrameLayout is rotated 90/270, its 'width' for children (like PreviewView)
            // effectively corresponds to the display's physical height, and its 'height' to physical width.
            // PreviewView (MATCH_PARENT) should fill this new coordinate system.
            // So we give PreviewView explicit dimensions that are swapped relative to physical display.
            pLayoutParams.width = physicalDisplayHeight
            pLayoutParams.height = physicalDisplayWidth
            Log.d("ExternalDisplay", "PreviewView target layout for 90/270 rot: ${pLayoutParams.width}x${pLayoutParams.height} (fills rotated FrameLayout)")
        } else { // 0 or 180 degrees
            // FrameLayout's coordinate system aligns with physical display.
            // PreviewView (MATCH_PARENT) should fill this.
            // So we give PreviewView explicit dimensions matching physical display.
            pLayoutParams.width = physicalDisplayWidth
            pLayoutParams.height = physicalDisplayHeight
            Log.d("ExternalDisplay", "PreviewView target layout for 0/180 rot: ${pLayoutParams.width}x${pLayoutParams.height} (fills FrameLayout)")
        }
        previewView.layoutParams = pLayoutParams // Apply the modified params
        Log.d("ExternalDisplay", "Applied PreviewView layout params: width=${pLayoutParams.width}, height=${pLayoutParams.height}")

        // Set ScaleType for PreviewView
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        Log.d("ExternalDisplay", "Set PreviewView ScaleType to FIT_CENTER.")

        setContentView(frameLayout)
    }

    fun getPreviewView(): PreviewView {
        return previewView
    }
}

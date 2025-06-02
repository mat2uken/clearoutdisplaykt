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

        // 1. Ensure Presentation window fills the display
        val currentWindow = this.window
        if (currentWindow == null) {
            Log.e("ExternalDisplay", "Presentation window is null, cannot set layout parameters.")
        } else {
            currentWindow.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            Log.d("ExternalDisplay", "Set Presentation window layout to MATCH_PARENT.")
        }

        // 2. Get physical display metrics
        val displayMetrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") // display.getRealMetrics is deprecated but needed.
        display.getRealMetrics(displayMetrics) // 'display' is the constructor param for Presentation
        val physicalDisplayWidth = displayMetrics.widthPixels
        val physicalDisplayHeight = displayMetrics.heightPixels
        Log.d("ExternalDisplay", "Physical Display: ${physicalDisplayWidth}x${physicalDisplayHeight}.")

        // 3. Setup FrameLayout (never rotates)
        val frameLayout = FrameLayout(context) // Ensure context is the Presentation's context
        frameLayout.rotation = 0f
        Log.d("ExternalDisplay", "FrameLayout rotation set to 0f.")

        // 4. Setup PreviewView (fixed size, never rotates its own content via rotation prop)
        previewView = PreviewView(context) // Ensure previewView is a class member if accessed elsewhere (e.g. getPreviewView())
        val pLayoutParams = ViewGroup.LayoutParams(physicalDisplayWidth, physicalDisplayHeight)
        previewView.layoutParams = pLayoutParams
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        Log.d("ExternalDisplay", "PreviewView layout set to ${physicalDisplayWidth}x${physicalDisplayHeight}, ScaleType FIT_CENTER.")

        // 5. Add PreviewView to FrameLayout and set content view
        frameLayout.addView(previewView)
        setContentView(frameLayout)

        Log.d("ExternalDisplay", "ExternalDisplayPresentation onCreate complete. PreviewView should fill physical display size with FIT_CENTER scaling.")
    }

    fun getPreviewView(): PreviewView {
        return previewView
    }
}

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
        Log.d("ExternalDisplay", "onCreate: Starting setup.")

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
        // 'display' is a property of the Presentation class, inherited from its constructor arguments.
        @Suppress("DEPRECATION") // display.getRealMetrics is deprecated but needed.
        this.display.getRealMetrics(displayMetrics)
        val physicalDisplayWidth = displayMetrics.widthPixels
        val physicalDisplayHeight = displayMetrics.heightPixels
        Log.d("ExternalDisplay", "Physical Display Metrics: ${physicalDisplayWidth}x${physicalDisplayHeight}.")

        // 3. Setup FrameLayout (using Presentation's context)
        // Ensure 'context' is Presentation's context (it is by default when just 'context' is used inside Presentation class)
        val frameLayout = FrameLayout(this.context)
        val frameLayoutParams = ViewGroup.LayoutParams(physicalDisplayWidth, physicalDisplayHeight)
        frameLayout.layoutParams = frameLayoutParams
        frameLayout.rotation = 0f
        Log.d("ExternalDisplay", "FrameLayout (context: ${this.context}) layout set to ${physicalDisplayWidth}x${physicalDisplayHeight}, rotation 0f.")

        // 4. Setup PreviewView (using Presentation's context)
        // Ensure 'previewView' is a class member: private lateinit var previewView: PreviewView
        previewView = PreviewView(this.context)
        val previewViewLayoutParams = ViewGroup.LayoutParams(physicalDisplayWidth, physicalDisplayHeight)
        previewView.layoutParams = previewViewLayoutParams
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        Log.d("ExternalDisplay", "PreviewView (context: ${this.context}) layout set to ${physicalDisplayWidth}x${physicalDisplayHeight}, ScaleType FIT_CENTER.")

        // 5. Add PreviewView to FrameLayout and set content view
        frameLayout.addView(previewView)
        setContentView(frameLayout)

        Log.d("ExternalDisplay", "ExternalDisplayPresentation onCreate complete. All components explicitly sized to physical display.")
    }

    fun getPreviewView(): PreviewView {
        return previewView
    }
}

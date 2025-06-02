package com.google.codelab.android.camera

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.util.DisplayMetrics // Re-adding for the restored onCreate
import android.util.Log
import android.view.Display
// import android.view.View // No longer used in the restored onCreate
import android.view.ViewGroup
import android.widget.FrameLayout
// import androidx.camera.view.PreviewView // No longer directly used in the new onCreate, but member previewView still exists
import androidx.camera.view.PreviewView // Keeping for member `previewView`

class ExternalDisplayPresentation(
    outerContext: Context,
    display: Display
) : Presentation(outerContext, display) {

    private lateinit var previewView: PreviewView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("ExternalDisplay", "onCreate: Restoring PreviewView in full-screen layout.")

        // 1. Ensure Presentation window fills the display
        val currentWindow = this.window
        if (currentWindow == null) {
            Log.e("ExternalDisplay", "Presentation window is null.")
        } else {
            currentWindow.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            Log.d("ExternalDisplay", "Presentation window layout set to MATCH_PARENT.")
        }

        // 2. Get physical display metrics
        val displayMetrics = android.util.DisplayMetrics()
        // 'display' is a property of the Presentation class, inherited from its constructor arguments.
        @Suppress("DEPRECATION") // display.getRealMetrics is deprecated but needed.
        this.display.getRealMetrics(displayMetrics)
        val physicalDisplayWidth = displayMetrics.widthPixels
        val physicalDisplayHeight = displayMetrics.heightPixels
        Log.d("ExternalDisplay", "Physical Display Metrics: ${physicalDisplayWidth}x${physicalDisplayHeight}.")

        // 3. Setup FrameLayout (using Presentation's context, explicitly sized, no rotation)
        val frameLayout = FrameLayout(this.context)
        val frameLayoutParams = ViewGroup.LayoutParams(physicalDisplayWidth, physicalDisplayHeight)
        frameLayout.layoutParams = frameLayoutParams
        frameLayout.rotation = 0f
        Log.d("ExternalDisplay", "FrameLayout (context: ${this.context}) layout set to ${physicalDisplayWidth}x${physicalDisplayHeight}, rotation 0f.")

        // 4. Setup PreviewView (using Presentation's context, explicitly sized)
        // 'previewView' must be a class member (private lateinit var previewView: PreviewView) to be accessible by getPreviewView()
        previewView = PreviewView(this.context)
        val previewViewLayoutParams = ViewGroup.LayoutParams(physicalDisplayWidth, physicalDisplayHeight)
        previewView.layoutParams = previewViewLayoutParams
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        Log.d("ExternalDisplay", "PreviewView (context: ${this.context}) layout set to ${physicalDisplayWidth}x${physicalDisplayHeight}, ScaleType FIT_CENTER.")

        // 5. Add PreviewView to FrameLayout and set content view
        frameLayout.addView(previewView)
        setContentView(frameLayout)

        Log.d("ExternalDisplay", "ExternalDisplayPresentation onCreate complete. PreviewView restored in explicitly sized full-screen layout.")
    }

    fun getPreviewView(): PreviewView {
        return previewView
    }
}

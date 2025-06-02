package com.google.codelab.android.camera

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.os.Bundle
// import android.util.DisplayMetrics // No longer directly used in the new onCreate
import android.util.Log
import android.view.Display
import android.view.View
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
        Log.d("ExternalDisplay", "Blue Screen Test: onCreate starting.")

        // 1. Ensure Presentation window fills the display
        val currentWindow = this.window
        if (currentWindow == null) {
            Log.e("ExternalDisplay", "Blue Screen Test: Presentation window is null.")
        } else {
            currentWindow.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            Log.d("ExternalDisplay", "Blue Screen Test: Presentation window layout set to MATCH_PARENT.")
        }

        // 2. Create a simple View to paint blue
        val blueView = View(this.context) // Use Presentation's context

        // 3. Set its background color to blue
        blueView.setBackgroundColor(android.graphics.Color.BLUE)
        Log.d("ExternalDisplay", "Blue Screen Test: View background color set to BLUE.")

        // 4. Set its layout parameters to fill its parent
        val layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        blueView.layoutParams = layoutParams
        Log.d("ExternalDisplay", "Blue Screen Test: View layout params set to MATCH_PARENT.")

        // 5. Set this blueView as the content view
        try {
            setContentView(blueView)
            Log.d("ExternalDisplay", "Blue Screen Test: setContentView(blueView) called successfully.")
        } catch (e: Exception) {
            Log.e("ExternalDisplay", "Blue Screen Test: Error calling setContentView(blueView)", e)
            // Fallback to a simple FrameLayout if direct View fails, though it shouldn't.
            val fallbackLayout = FrameLayout(this.context)
            fallbackLayout.setBackgroundColor(android.graphics.Color.RED) // Red to indicate fallback
            setContentView(fallbackLayout)
             Log.d("ExternalDisplay", "Blue Screen Test: Fallback to RED FrameLayout executed.")
        }
        Log.d("ExternalDisplay", "Blue Screen Test: onCreate complete.")
    }

    fun getPreviewView(): PreviewView {
        return previewView
    }
}

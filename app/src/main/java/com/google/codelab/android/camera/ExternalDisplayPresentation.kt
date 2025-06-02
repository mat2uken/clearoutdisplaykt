package com.google.codelab.android.camera

import android.app.Presentation
import android.content.Context
import android.graphics.Color // For debug background colors
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
        Log.d("ExternalDisplay", "onCreate: Debug with background colors. FIT_CENTER test.")

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

        // 3. Setup FrameLayout
        val frameLayout = FrameLayout(this.context)
        val frameLayoutParams = ViewGroup.LayoutParams(physicalDisplayWidth, physicalDisplayHeight)
        frameLayout.layoutParams = frameLayoutParams
        frameLayout.rotation = 0f
        frameLayout.setBackgroundColor(android.graphics.Color.RED)
        Log.d("ExternalDisplay", "FrameLayout layout: ${physicalDisplayWidth}x${physicalDisplayHeight}, rotation 0f, BG RED.")

        // 4. Setup PreviewView
        previewView = PreviewView(this.context) // previewView is a class member
        val previewViewLayoutParams = ViewGroup.LayoutParams(physicalDisplayWidth, physicalDisplayHeight)
        previewView.layoutParams = previewViewLayoutParams
        previewView.setBackgroundColor(android.graphics.Color.GREEN)
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        Log.d("ExternalDisplay", "PreviewView layout: ${physicalDisplayWidth}x${physicalDisplayHeight}, BG GREEN, ScaleType FIT_CENTER.")

        // 5. Add PreviewView to FrameLayout and set content view
        frameLayout.addView(previewView)
        setContentView(frameLayout)

        Log.d("ExternalDisplay", "onCreate complete. FIT_CENTER test with colored backgrounds.")
    }

    fun getPreviewView(): PreviewView {
        return previewView
    }
}

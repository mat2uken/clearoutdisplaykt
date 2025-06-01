package com.example.cameraapp

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.ViewGroup
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView

class ExternalDisplayPresentation(
    outerContext: Context,
    display: Display,
    private val existingPreviewUseCase: Preview?
) : Presentation(outerContext, display) {

    private lateinit var externalPreviewView: PreviewView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        externalPreviewView = PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        setContentView(externalPreviewView)
        Log.d("ExternalDisplay", "Presentation onCreate for display: ${display.name}")
    }

    override fun onStart() {
        super.onStart()
        Log.d("ExternalDisplay", "Presentation onStart, attempting to set SurfaceProvider.")
        try {
            existingPreviewUseCase?.setSurfaceProvider(externalPreviewView.surfaceProvider)
            Log.d("ExternalDisplay", "SurfaceProvider set on external PreviewView.")
        } catch (e: Exception) {
            Log.e("ExternalDisplay", "Error setting surface provider on external PreviewView", e)
        }
    }

    override fun onStop() {
        super.onStop()
        Log.d("ExternalDisplay", "Presentation onStop, attempting to clear SurfaceProvider.")
        try {
            // Important: Clear the surface provider so the main activity can reclaim it.
            // This is crucial if the same Preview use case is shared.
            existingPreviewUseCase?.setSurfaceProvider(null)
            Log.d("ExternalDisplay", "SurfaceProvider cleared from external PreviewView.")
        } catch (e: Exception) {
            Log.e("ExternalDisplay", "Error clearing surface provider from external PreviewView", e)
        }
    }

    fun release() {
        // Custom method to ensure resources are cleaned up if needed,
        // though onStop should handle SurfaceProvider.
        Log.d("ExternalDisplay", "Presentation release called.")
    }
}

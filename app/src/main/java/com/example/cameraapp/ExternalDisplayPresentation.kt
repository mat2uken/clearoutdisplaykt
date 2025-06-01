package com.example.cameraapp

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.ViewGroup
import androidx.camera.core.Preview // Explicit import for Preview.SurfaceProvider
import androidx.camera.view.PreviewView

class ExternalDisplayPresentation(
    context: Context, // Activity context
    display: Display,
    private val onSurfaceProviderReady: (Preview.SurfaceProvider) -> Unit,
    private val onDismissed: () -> Unit
) : Presentation(context, display) {

    private lateinit var externalPreviewView: PreviewView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("ExternalDisplay", "Presentation onCreate for display: ${display.name}")
        // Use getContext() as it's the context for the Presentation window
        externalPreviewView = PreviewView(getContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        setContentView(externalPreviewView)
    }

    override fun onStart() {
        super.onStart()
        Log.d("ExternalDisplay", "Presentation onStart, invoking onSurfaceProviderReady.")
        // Best to ensure the view is attached to the window and its surface is ready.
        // Posting to the view's message queue helps ensure this.
        externalPreviewView.post {
            if (isShowing && window != null) { // Check if presentation is still active
                 Log.d("ExternalDisplay", "External PreviewView surface provider is now being sent.")
                onSurfaceProviderReady(externalPreviewView.surfaceProvider)
            } else {
                Log.w("ExternalDisplay", "Presentation not showing or window null when surface provider was to be sent.")
            }
        }
    }

    // onStop is a good place to signal dismissal if the display is removed or presentation is explicitly stopped.
    override fun onStop() {
        super.onStop() // Call super first
        Log.d("ExternalDisplay", "Presentation onStop, invoking onDismissed.")
        onDismissed()
    }

    // onDismiss is called when the dialog is dismissed (e.g. by calling dismiss() or if display is removed)
    // This is another valid place for onDismissed, but onStop is more general for window detachment.
    // override fun onDismiss() {
    //     super.onDismiss()
    //     Log.d("ExternalDisplay", "Presentation onDismiss, invoking onDismissed.")
    //     onDismissed()
    // }
}

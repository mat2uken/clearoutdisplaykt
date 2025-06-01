package com.example.cameraapp.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidDisplayService(context: Context) : DisplayService {

    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    // DisplayManager listener callbacks are typically on the thread associated with the Handler.
    // Using Main Looper for simplicity as UI updates will follow.
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _displaysFlow = MutableStateFlow<List<Display>>(emptyList())
    override val displaysFlow: StateFlow<List<Display>> = _displaysFlow.asStateFlow()

    private var isListening = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            Log.d("AndroidDisplayService", "Display added: $displayId")
            updateDisplays()
        }

        override fun onDisplayRemoved(displayId: Int) {
            Log.d("AndroidDisplayService", "Display removed: $displayId")
            updateDisplays()
        }

        override fun onDisplayChanged(displayId: Int) {
            Log.d("AndroidDisplayService", "Display changed: $displayId")
            updateDisplays()
        }
    }

    private fun updateDisplays() {
        _displaysFlow.value = displayManager.displays.toList()
        Log.d("AndroidDisplayService", "Displays updated: ${_displaysFlow.value.map { it.displayId + " (" + it.name + ")" }}")
    }

    override fun startListening() {
        if (isListening) return
        Log.d("AndroidDisplayService", "Starting to listen for display changes.")
        try {
            displayManager.registerDisplayListener(displayListener, mainHandler)
            updateDisplays() // Initial update
            isListening = true
        } catch (e: Exception) {
            Log.e("AndroidDisplayService", "Error registering display listener", e)
        }
    }

    override fun stopListening() {
        if (!isListening) return
        Log.d("AndroidDisplayService", "Stopping listening for display changes.")
        try {
            displayManager.unregisterDisplayListener(displayListener)
            isListening = false
        } catch (e: Exception) {
            Log.e("AndroidDisplayService", "Error unregistering display listener", e)
        }
    }
}

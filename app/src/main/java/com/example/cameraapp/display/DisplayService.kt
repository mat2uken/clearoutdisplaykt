package com.example.cameraapp.display

import android.view.Display
import kotlinx.coroutines.flow.StateFlow

interface DisplayService {
    /**
     * A flow emitting the current list of connected displays.
     * The implementation should update this list when displays are added, removed, or changed.
     */
    val displaysFlow: StateFlow<List<Display>>

    /**
     * Starts listening for display changes. Call this when observation of displaysFlow begins.
     */
    fun startListening()

    /**
     * Stops listening for display changes. Call this when observation is no longer needed.
     */
    fun stopListening()
}

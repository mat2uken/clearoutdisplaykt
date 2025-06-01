package com.example.cameraapp.fakes

import android.util.Log
import android.view.Display
import com.example.cameraapp.display.DisplayService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeDisplayService : DisplayService {
    private val _displaysFlow = MutableStateFlow<List<Display>>(emptyList())
    override val displaysFlow: StateFlow<List<Display>> = _displaysFlow.asStateFlow()

    var startListeningCalledCount = 0
    var stopListeningCalledCount = 0

    override fun startListening() {
        startListeningCalledCount++
        Log.d("FakeDisplayService", "startListening called ($startListeningCalledCount times)")
    }

    override fun stopListening() {
        stopListeningCalledCount++
        Log.d("FakeDisplayService", "stopListening called ($stopListeningCalledCount times)")
    }

    // --- Test control method ---
    fun emitDisplays(displays: List<Display>) {
        Log.d("FakeDisplayService", "Emitting displays: ${displays.map { it.displayId }}")
        _displaysFlow.value = displays
    }
}

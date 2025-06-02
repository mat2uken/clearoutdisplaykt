package com.google.codelab.android.camera.ui

import android.util.Log
import android.widget.Toast
import androidx.camera.core.AspectRatio
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tv // Using Tv icon for external display
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun CameraScreen(
    viewModel: CameraViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Observe states from ViewModel
    val selectedLensFacing by viewModel.selectedLensFacing.collectAsState()
    val availableLenses by viewModel.availableLenses.collectAsState()
    // val zoomRatio by viewModel.zoomRatio.collectAsState() // Current actual zoom ratio - UNUSED for now
    val linearZoom by viewModel.linearZoom.collectAsState() // Slider position (0f-1f)
    val minZoom by viewModel.minZoomRatio.collectAsState()
    val maxZoom by viewModel.maxZoomRatio.collectAsState()
    val isExternalDisplayConnected by viewModel.isExternalDisplayConnected.collectAsState()
    val toastMessage by viewModel.toastMessage.collectAsState()
    val externalDisplayInfo by viewModel.externalDisplayDetailedInfo.collectAsState()
    val activePreview by viewModel.activePreviewUseCase.collectAsState() // Observe the Preview from ViewModel

    // For PreviewView
    val previewView = remember { PreviewView(context) }

    // Toast message handling
    LaunchedEffect(toastMessage) {
        toastMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearToastMessage()
            Log.d("CameraScreen", "Toast: $it") // Added log
        }
    }

    // Lifecycle effect to manage camera binding via ViewModel
    DisposableEffect(selectedLensFacing, lifecycleOwner) { // Re-run if lens or lifecycle owner changes
        Log.d("CameraScreen", "DisposableEffect for attach/detach. Lens: $selectedLensFacing, Lifecycle: $lifecycleOwner")
        viewModel.attachLifecycleOwner(lifecycleOwner) // ViewModel now handles Preview creation

        onDispose {
            Log.d("CameraScreen", "DisposableEffect onDispose for attach/detach. Lens: $selectedLensFacing")
            viewModel.detachLifecycleOwner()
        }
    }

    // LaunchedEffect to connect the PreviewView's surfaceProvider to the active Preview from ViewModel
    LaunchedEffect(activePreview, previewView, isExternalDisplayConnected) {
        val currentPreview = activePreview
        if (currentPreview != null) {
            if (isExternalDisplayConnected) {
                // If an external display is connected, ViewModel is responsible for setting its surface provider.
                // Clear the main screen's previewView from the CameraX Preview object to avoid conflicts.
                // (Though CameraX might handle one surface at a time gracefully, this makes intent clear)
                Log.d("CameraScreen", "External display connected. Clearing main PreviewView's surfaceProvider from active Preview object (if it was set).")
                // It's typically not set directly on previewView but on the Preview object.
                // If the ViewModel ensures only one surface is active on the Preview object, this might not be strictly needed.
                // For safety, ensuring the main previewView is not the target when external is active:
                // currentPreview.setSurfaceProvider(null) // This would detach ALL surfaces. Not what we want.
                // Instead, rely on ViewModel to manage which surface is connected to the Preview object.
                // If ViewModel sets external display's surface, this LaunchedEffect should not fight it for main screen.
                // So, only set main screen's surface if no external display is active.
            } else {
                Log.d("CameraScreen", "No external display. Setting main PreviewView's surfaceProvider.")
                currentPreview.setSurfaceProvider(previewView.surfaceProvider)
            }
        } else {
            Log.d("CameraScreen", "activePreview is null. Main PreviewView's surfaceProvider cannot be set.")
            // Potentially clear the surface from previewView if necessary, though usually it's managed by CameraX when Preview is unbound.
            // previewView.surfaceProvider = null; // This is not how you clear it.
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                view.scaleType = PreviewView.ScaleType.FILL_CENTER
                Log.d("CameraScreen", "AndroidView updated, PreviewView scaleType set.")
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Zoom Slider
            if (minZoom < maxZoom) { // Show slider only if zoom is supported
                Slider(
                    value = linearZoom,
                    onValueChange = { viewModel.setLinearZoom(it) },
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth()
                )
                // Text(text = "Zoom: ${"%.2f".format(zoomRatio)}x", style = MaterialTheme.typography.bodySmall) // zoomRatio from VM is actual
                 Text(text = "Zoom: ${"%.2f".format(minZoom + (maxZoom - minZoom) * linearZoom)}x", style = MaterialTheme.typography.bodySmall)


            }

            Spacer(modifier = Modifier.height(16.dp))

            // Controls Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Camera Switch Button
                if (availableLenses.size > 1) {
                    IconButton(onClick = {
                        Log.d("CameraScreen", "Switch camera button clicked")
                        viewModel.switchCamera()
                    }) {
                        Icon(
                            imageVector = Icons.Filled.Cameraswitch,
                            contentDescription = "Switch Camera",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f)) // Keep spacing if no switch
                }

                // New Rotate External Display Button
                if (isExternalDisplayConnected) {
                    IconButton(onClick = {
                        Log.d("CameraScreen", "Rotate external display button clicked")
                        viewModel.rotateExternalDisplay()
                    }) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Rotate External Display",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer // Consistent with camera switch
                        )
                    }
                } else {
                     Spacer(modifier = Modifier.weight(1f)) // Occupy space if no rotate button
                }

                // External Display Indicator / Info Button (TV icon)
                if (isExternalDisplayConnected) {
                    IconButton(onClick = {
                        Log.d("CameraScreen", "External display icon clicked, requesting info.")
                        viewModel.requestExternalDisplayInfo()
                    }) {
                        Icon(
                            imageVector = Icons.Filled.Tv,
                            contentDescription = "External Display Connected - Show Info",
                            tint = MaterialTheme.colorScheme.primary // Keep primary for info
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f)) // Occupy space if no TV icon
                }
            }
        }

        // AlertDialog for External Display Info
        externalDisplayInfo?.let { info ->
            AlertDialog(
                onDismissRequest = {
                    viewModel.clearExternalDisplayInfo()
                },
                title = { Text("External Display Information") },
                text = {
                    Box(modifier = Modifier.heightIn(max = 400.dp)) { // Constrain the max height
                        val scrollState = rememberScrollState()
                        Text(
                            text = info, // This is the externalDisplayDetailedInfo string
                            modifier = Modifier.verticalScroll(scrollState)
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = { viewModel.clearExternalDisplayInfo() }) {
                        Text("OK")
                    }
                }
            )
        }
    }
}

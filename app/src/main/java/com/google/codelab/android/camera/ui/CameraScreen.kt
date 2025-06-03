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
import androidx.compose.foundation.verticalScroll // Keep for potential future use, though not strictly needed by this simplified version.
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
// import androidx.compose.material.icons.filled.Refresh // Removed
// import androidx.compose.material.icons.filled.Tv // Removed
// import androidx.compose.material3.AlertDialog // Removed
// import androidx.compose.material3.Button // Removed, unless used elsewhere
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
    // val isExternalDisplayConnected by viewModel.isExternalDisplayConnected.collectAsState() // Removed
    val toastMessage by viewModel.toastMessage.collectAsState()
    // val externalDisplayInfo by viewModel.externalDisplayDetailedInfo.collectAsState() // Removed
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
    LaunchedEffect(activePreview, previewView.surfaceProvider) { // Keyed by activePreview and surfaceProvider
        activePreview?.setSurfaceProvider(previewView.surfaceProvider)
        Log.d("CameraScreen", "Main PreviewView's surfaceProvider was (re)set for activePreview.")
        // When activePreview becomes null (on detach), this won't run to set it to null.
        // The ViewModel's unbindAll() and setting _activePreviewUseCase.value = null should handle detachment from CameraX core.
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

                // New Rotate External Display Button - REMOVED
                // Spacer to maintain balance if only one button (camera switch) is present
                // If availableLenses.size <= 1, then two spacers will center nothing, which is fine.
                // If availableLenses.size > 1, then camera switch is on left, spacer on right.
                // This assumes we want the camera switch button to be on the left if it's the only one.
                // If centered is preferred, Row arrangement might need to change or use more spacers.
                Spacer(modifier = Modifier.weight(1f))


                // External Display Indicator / Info Button (TV icon) - REMOVED
                // Spacer to maintain balance if only one button (camera switch) is present
                Spacer(modifier = Modifier.weight(1f))
            }
        }

        // AlertDialog for External Display Info - REMOVED
    }
}

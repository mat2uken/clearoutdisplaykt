package com.example.cameraapp

import android.Manifest
import android.content.Context
import android.hardware.camera2.CameraMetadata
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.view.Display
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSavedStateRegistryOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.core.*
import androidx.camera.core.Preview as CameraXPreview
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.cameraapp.camera.CameraXService // For CameraAppEntry type hint
import com.example.cameraapp.camera.CameraXServiceImpl
import com.example.cameraapp.display.DisplayService // For CameraAppEntry type hint
import com.example.cameraapp.display.AndroidDisplayService
import com.example.cameraapp.ui.theme.CameraAppTheme
import com.google.accompanist.permissions.*
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalPermissionsApi::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // These services are created here and remembered across recompositions.
            // In a DI setup, they would be injected or provided differently.
            val appApplicationContext = applicationContext
            val cameraXService = remember { CameraXServiceImpl(appApplicationContext) }
            val displayService = remember { AndroidDisplayService(appApplicationContext) }

            val owner = LocalSavedStateRegistryOwner.current
            val factory = remember(cameraXService, displayService, owner) {
                CameraViewModelFactory(owner, null, cameraXService, displayService)
            }
            val viewModel: CameraViewModel = viewModel(key = "camera_view_model", factory = factory)

            CameraAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Pass viewModel and necessary services if CameraScreen needs them directly
                    // For service shutdown, it's better to handle it here with DisposableEffect
                    HandleCameraPermission(viewModel) // Pass viewModel
                }
            }

            DisposableEffect(cameraXService) {
                onDispose {
                    Log.d("MainActivity", "CameraXService shutdown called from MainActivity DisposableEffect.")
                    cameraXService.shutdown()
                    // DisplayService is shut down in ViewModel's onCleared
                }
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun HandleCameraPermission(viewModel: CameraViewModel) { // Accept viewModel
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)
    when {
        cameraPermissionState.status.isGranted -> CameraScreen(viewModel = viewModel) // Pass viewModel
        cameraPermissionState.status.shouldShowRationale -> {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("Camera permission is required to use this app.", style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { cameraPermissionState.launchPermissionRequest() }) { Text("Request permission") }
            }
        }
        else -> {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("Camera permission denied. Please enable it in app settings.", style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
    LaunchedEffect(key1 = true) {
        if (!cameraPermissionState.status.isGranted && !cameraPermissionState.status.shouldShowRationale) {
            cameraPermissionState.launchPermissionRequest()
        }
    }
}

@Composable
fun CameraScreen(viewModel: CameraViewModel) { // ViewModel is now a parameter
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // States are collected from the ViewModel
    val lensFacing by viewModel.lensFacing.collectAsState()
    val hasFlashUnit by viewModel.hasFlashUnit.collectAsState()
    val isLedOn by viewModel.isLedOn.collectAsState()
    val isFlippedHorizontally by viewModel.isFlippedHorizontally.collectAsState()
    val currentZoomRatio by viewModel.currentZoomRatio.collectAsState()
    val minZoomRatio by viewModel.minZoomRatio.collectAsState()
    val maxZoomRatio by viewModel.maxZoomRatio.collectAsState()
    val isZoomSupported by viewModel.isZoomSupported.collectAsState()
    val currentExposureIndex by viewModel.currentExposureIndex.collectAsState()
    val minExposureIndex by viewModel.minExposureIndex.collectAsState()
    val maxExposureIndex by viewModel.maxExposureIndex.collectAsState()
    val exposureStep by viewModel.exposureStep.collectAsState()
    val isExposureSupported by viewModel.isExposureSupported.collectAsState()
    val currentAwbMode by viewModel.currentAwbMode.collectAsState()
    val supportedWbPresets by viewModel.supportedWbPresets.collectAsState()
    val currentExternalDisplay by viewModel.externalDisplay.collectAsState()
    val displayedError by viewModel.displayedError.collectAsState()

    LaunchedEffect(key1 = Unit) { // For collecting toast messages
        viewModel.toastMessages.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    var showExposureDialog by remember { mutableStateOf(false) }
    var localExternalPresentation by remember { mutableStateOf<ExternalDisplayPresentation?>(null) }
    var showWhiteBalanceDialog by remember { mutableStateOf(false) }
    val isWbSupported by remember { mutableStateOf(true) } // Placeholder, ideally from VM

    LaunchedEffect(key1 = lifecycleOwner, key2 = lensFacing) {
        Log.d("CameraScreen", "Main LaunchedEffect for camera init. Lens: $lensFacing")
        viewModel.primaryCameraInit(lifecycleOwner, previewView.surfaceProvider)
    }

    DisposableEffect(currentExternalDisplay, context, viewModel) {
        if (currentExternalDisplay != null) {
            if (localExternalPresentation == null) {
                Log.d("CameraScreen", "Creating ExternalDisplayPresentation for: ${currentExternalDisplay?.name}")
                Toast.makeText(context, "Ext display connected: ${currentExternalDisplay?.name}", Toast.LENGTH_LONG).show()
                localExternalPresentation = ExternalDisplayPresentation(
                    context = context,
                    display = currentExternalDisplay!!,
                    onSurfaceProviderReady = { externalSurfaceProvider ->
                        Log.d("CameraScreen", "External surface provider ready for display: ${currentExternalDisplay!!.displayId}")
                        viewModel.attachExternalDisplaySurface(externalSurfaceProvider)
                    },
                    onDismissed = {
                        Log.d("CameraScreen", "ExternalDisplayPresentation dismissed for display: ${currentExternalDisplay?.name ?: "Unknown"}")
                        viewModel.detachExternalDisplaySurface()
                    }
                ).also { it.show() }
            }
        } else {
            localExternalPresentation?.dismiss()
            localExternalPresentation = null
            // If it was outputting when currentExternalDisplay became null (e.g. display removed)
            // viewModel.detachExternalDisplaySurface() might be needed if onDismissed isn't guaranteed.
            // The onDismissed callback should handle this.
        }
        onDispose {
            Log.d("CameraScreen", "ExternalDisplay DisposableEffect onDispose. Dismissing presentation if any.")
            localExternalPresentation?.dismiss()
            localExternalPresentation = null
            if (currentExternalDisplay != null) {
                 viewModel.detachExternalDisplaySurface()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { offset ->
                        if (previewView.width > 0 && previewView.height > 0) {
                            viewModel.onPreviewTapped(
                                viewWidth = previewView.width,
                                viewHeight = previewView.height,
                                x = offset.x,
                                y = offset.y
                            )
                        } else {
                            Log.w("CameraScreen", "PreviewView dimensions are not valid for tap-to-focus.")
                        }
                    })
                }
                .scale(scaleX = if (isFlippedHorizontally) -1f else 1f, scaleY = 1f)
        )

        Column( // Controls Column
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.7f))
                .padding(bottom = 16.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 8.dp)) {
                if (isZoomSupported) {
                    Slider(
                        value = currentZoomRatio,
                        onValueChange = { viewModel.setZoomRatio(it) },
                        valueRange = minZoomRatio..maxZoomRatio,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(String.format("Zoom: %.2fx", currentZoomRatio), modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 4.dp))
                } else {
                    Text("Zoom: N/A", modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                Button(onClick = { viewModel.onSwitchCameraClicked() }, modifier = Modifier.padding(2.dp)) {
                    Icon(Icons.Filled.Cameraswitch, contentDescription = "Switch Camera")
                }
                Button(onClick = { viewModel.onLedButtonClicked() }, enabled = hasFlashUnit, modifier = Modifier.padding(2.dp)) {
                    Icon(if (isLedOn) Icons.Filled.FlashOff else Icons.Filled.FlashOn, contentDescription = if (isLedOn) "Turn LED Off" else "Turn LED On")
                }
                Button(onClick = { viewModel.onFlipClicked() }, modifier = Modifier.padding(2.dp)) {
                    Icon(Icons.Filled.Flip, contentDescription = if (isFlippedHorizontally) "Unflip Preview" else "Flip Preview")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceAround
            ){
                Button(onClick = { showExposureDialog = true }, enabled = isExposureSupported, modifier = Modifier.padding(2.dp)) {
                    Icon(Icons.Filled.Tune, contentDescription = "Adjust Exposure", modifier = Modifier.padding(end = 4.dp))
                    Text("Exp")
                }
                Button(onClick = { showWhiteBalanceDialog = true }, enabled = isWbSupported, modifier = Modifier.padding(2.dp)) {
                    Icon(Icons.Filled.WbSunny, contentDescription = "Adjust White Balance", modifier = Modifier.padding(end = 4.dp))
                    Text("WB")
                }
            }
        }

        AnimatedVisibility(
            visible = displayedError != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(16.dp)
        ) {
            displayedError?.let { error ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = error.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
                        IconButton(onClick = { viewModel.clearDisplayedError() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Dismiss error", tint = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
            }
        }

        if (currentExternalDisplay != null) {
            Text("EXT OUT", color = Color.Red, modifier = Modifier.align(Alignment.TopStart).padding(8.dp).background(Color.White.copy(alpha = 0.7f)).padding(4.dp))
        }
    }

    if (showExposureDialog && isExposureSupported) {
        ExposureDialog(currentExposureIndex, minExposureIndex, maxExposureIndex, exposureStep, onDismiss = { showExposureDialog = false }) { newIndex ->
            viewModel.setExposureIndex(newIndex)
        }
    }
    if (showWhiteBalanceDialog) {
        WhiteBalanceDialog(currentAwbMode, supportedWbPresets, onDismiss = { showWhiteBalanceDialog = false }) { selectedMode ->
            viewModel.onWhiteBalanceModeSelected(selectedMode)
        }
    }
    // Removed DisposableEffect for cameraXService.shutdown() from CameraScreen, moved to MainActivity
}

@Composable
fun ExposureDialog(currentExposure: Int, minExposure: Int, maxExposure: Int, exposureStepValue: Rational?, onDismiss: () -> Unit, onExposureChange: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Adjust Exposure") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                val evValue = exposureStepValue?.let { String.format("%.1f EV", currentExposure * it.toFloat()) } ?: "$currentExposure"
                Text("Current: $evValue", style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { if (currentExposure > minExposure) onExposureChange(currentExposure - 1) }, enabled = currentExposure > minExposure) { Text("-") }
                    Button(onClick = { if (currentExposure < maxExposure) onExposureChange(currentExposure + 1) }, enabled = currentExposure < maxExposure) { Text("+") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
fun WhiteBalanceDialog(currentMode: Int, presets: List<WbPreset>, onDismiss: () -> Unit, onModeSelected: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select White Balance") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (presets.isEmpty()) {
                    Text("No AWB presets available for this camera.", modifier = Modifier.padding(16.dp).fillMaxWidth(), textAlign = TextAlign.Center)
                } else {
                    presets.forEach { preset ->
                        TextButton(onClick = { onModeSelected(preset.mode) }, modifier = Modifier.fillMaxWidth()) {
                            Text(preset.name, color = if (preset.mode == currentMode) MaterialTheme.colorScheme.primary else LocalContentColor.current, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    CameraAppTheme {
        // HandleCameraPermission(viewModel()) // This would need a way to provide a preview/fake VM
        // For simplicity, directly preview CameraScreen with a dummy/default VM if possible,
        // or accept that previewing the full CameraScreen with real VM is complex.
        // The current setup with viewModel() in HandleCameraPermission will use the real factory path.
        // For a true isolated preview of CameraScreen, you'd pass a fake ViewModel.
        Surface { Text("Preview of CameraScreen requires ViewModel setup") }
    }
}

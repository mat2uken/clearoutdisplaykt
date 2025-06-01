package com.example.cameraapp

import android.Manifest
import android.content.Context
import android.hardware.camera2.CameraMetadata
// import android.hardware.camera2.CaptureRequest // Not directly used here anymore
// import androidx.camera.camera2.interop.Camera2CameraControl // Not directly used here anymore
// import androidx.camera.camera2.interop.CaptureRequestOptions // Not directly used here anymore
// import android.hardware.display.DisplayManager // Replaced by DisplayService
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.view.Display // Still needed for Display.DEFAULT_DISPLAY
import android.widget.Toast // Keep for now, though can be moved to VM/events
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.core.*
import androidx.camera.core.Preview as CameraXPreview
// import androidx.camera.lifecycle.ProcessCameraProvider // Not directly used here
// import androidx.core.content.ContextCompat // Not directly used here
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.cameraapp.camera.CameraXServiceImpl
import com.example.cameraapp.display.AndroidDisplayService // Import AndroidDisplayService
import com.example.cameraapp.ui.theme.CameraAppTheme
import com.google.accompanist.permissions.*
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalPermissionsApi::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CameraAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HandleCameraPermission()
                }
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun HandleCameraPermission() {
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)
    when {
        cameraPermissionState.status.isGranted -> CameraScreen()
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
fun CameraScreen() {
    val context = LocalContext.current // Activity context
    val lifecycleOwner = LocalLifecycleOwner.current
    val savedStateRegistryOwner = LocalSavedStateRegistryOwner.current

    val appApplicationContext = context.applicationContext
    val cameraXService = remember { CameraXServiceImpl(appApplicationContext) }
    val displayService = remember { AndroidDisplayService(appApplicationContext) }
    val cameraViewModelFactory = remember(cameraXService, displayService, savedStateRegistryOwner) {
        CameraViewModelFactory(savedStateRegistryOwner, null, cameraXService, displayService)
    }
    val viewModel: CameraViewModel = viewModel(factory = cameraViewModelFactory)

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
    val currentExternalDisplay by viewModel.externalDisplay.collectAsState() // From ViewModel

    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    var showExposureDialog by remember { mutableStateOf(false) }
    // val previewUseCaseRef = remember { mutableStateOf<CameraXPreview?>(null) } // No longer needed for external display logic here
    var localExternalPresentation by remember { mutableStateOf<ExternalDisplayPresentation?>(null) } // local ref to presentation
    // var isOutputtingToExternal by remember { mutableStateOf(false) } // Replaced by currentExternalDisplay != null
    var showWhiteBalanceDialog by remember { mutableStateOf(false) }
    val isWbSupported by remember { mutableStateOf(true) } // Placeholder


    LaunchedEffect(key1 = lifecycleOwner, key2 = lensFacing) {
        Log.d("CameraScreen", "Main LaunchedEffect for camera init. Lens: $lensFacing")
        viewModel.primaryCameraInit(lifecycleOwner, previewView.surfaceProvider)
    }

    // Manage ExternalDisplayPresentation lifecycle based on ViewModel state
    DisposableEffect(currentExternalDisplay) {
        if (currentExternalDisplay != null) {
            if (localExternalPresentation == null) {
                Log.d("CameraScreen", "Creating ExternalDisplayPresentation for: ${currentExternalDisplay?.name}")
                Toast.makeText(context, "Ext display connected: ${currentExternalDisplay?.name}", Toast.LENGTH_LONG).show()
                localExternalPresentation = ExternalDisplayPresentation(
                    context = context, // Activity context is crucial for Presentation
                    display = currentExternalDisplay!!,
                    onSurfaceProviderReady = { externalSurfaceProvider ->
                        Log.d("CameraScreen", "External surface provider ready for display: ${currentExternalDisplay!!.displayId}")
                        viewModel.attachExternalDisplaySurface(externalSurfaceProvider)
                    },
                    onDismissed = {
                        Log.d("CameraScreen", "ExternalDisplayPresentation dismissed for display: ${currentExternalDisplay!!.displayId}")
                        viewModel.detachExternalDisplaySurface()
                        // localExternalPresentation = null // This will be handled by currentExternalDisplay becoming null
                    }
                ).also { it.show() }
            }
        } else {
            localExternalPresentation?.let {
                Log.d("CameraScreen", "Dismissing ExternalDisplayPresentation as currentExternalDisplay is null.")
                it.dismiss()
                localExternalPresentation = null
            }
        }
        onDispose {
            Log.d("CameraScreen", "ExternalDisplay DisposableEffect onDispose. Dismissing presentation if any.")
            localExternalPresentation?.dismiss()
            localExternalPresentation = null
        }
    }


    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
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
            if (currentExternalDisplay != null) { // Use VM state for indicator
                Text("EXT OUT", color = Color.Red, modifier = Modifier.align(Alignment.TopStart).padding(8.dp).background(Color.White.copy(alpha = 0.7f)).padding(4.dp))
            }
        }

        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
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
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).padding(bottom = 8.dp),
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

    if (showExposureDialog && isExposureSupported) {
        ExposureDialog(
            currentExposure = currentExposureIndex,
            minExposure = minExposureIndex,
            maxExposure = maxExposureIndex,
            exposureStepValue = exposureStep,
            onDismiss = { showExposureDialog = false }
        ) { newIndex ->
            viewModel.setExposureIndex(newIndex)
        }
    }
    if (showWhiteBalanceDialog) {
        WhiteBalanceDialog(
            currentMode = currentAwbMode,
            onDismiss = { showWhiteBalanceDialog = false }
        ) { selectedMode ->
            viewModel.onWhiteBalanceModeSelected(selectedMode)
        }
    }

    DisposableEffect(cameraXService) {
        onDispose {
            Log.d("CameraScreen", "CameraXService shutdown called from DisposableEffect.")
            cameraXService.shutdown()
            // DisplayService is stopped in ViewModel's onCleared
        }
    }
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
fun WhiteBalanceDialog(currentMode: Int, onDismiss: () -> Unit, onModeSelected: (Int) -> Unit) {
    val wbModes = listOf("Auto" to CameraMetadata.CONTROL_AWB_MODE_AUTO, "Incandescent" to CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT, "Fluorescent" to CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT, "Daylight" to CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT, "Cloudy" to CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select White Balance") },
        text = {
            Column {
                wbModes.forEach { (name, mode) ->
                    TextButton(onClick = { onModeSelected(mode) }, modifier = Modifier.fillMaxWidth()) {
                        Text(name, color = if (mode == currentMode) MaterialTheme.colorScheme.primary else LocalContentColor.current)
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
        CameraScreen()
    }
}

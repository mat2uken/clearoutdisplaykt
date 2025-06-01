package com.example.cameraapp

import android.Manifest
import android.content.Context
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.view.Display
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
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
import androidx.camera.core.Preview as CameraXPreview // Alias to avoid name clash
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.cameraapp.ui.theme.CameraAppTheme
import com.google.accompanist.permissions.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalPermissionsApi::class) // Required for Accompanist Permissions
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CameraAppTheme { // Apply the theme here
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background // Use theme background
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
        cameraPermissionState.status.isGranted -> {
            CameraScreen()
        }
        cameraPermissionState.status.shouldShowRationale -> {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Camera permission is required to use this app.", style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { cameraPermissionState.launchPermissionRequest() }) {
                    Text("Request permission")
                }
            }
        }
        else -> {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Camera permission denied. Please enable it in app settings.", style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(8.dp))
                // Button(onClick = { /* Open app settings intent */ }) { Text("Open Settings") }
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
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply {
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }}
    val cameraExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    var lensFacing by rememberSaveable { mutableStateOf(CameraSelector.LENS_FACING_BACK) }

    // State for zoom
    var currentZoomRatio by remember { mutableStateOf(1f) }
    var minZoomRatio by remember { mutableStateOf(1f) }
    var maxZoomRatio by remember { mutableStateOf(1f) }

    // Hold the Camera instance
    var camera: Camera? by remember { mutableStateOf(null) }

    // State for Exposure Dialog
    var showExposureDialog by remember { mutableStateOf(false) }
    var currentExposureIndex by remember { mutableStateOf(0) }
    var minExposureIndex by remember { mutableStateOf(0) }
    var maxExposureIndex by remember { mutableStateOf(0) }
    var exposureStep by remember { mutableStateOf<Rational?>(null) }
    var isExposureSupported by remember { mutableStateOf(false) }

    // LED states
    var hasFlashUnit by remember { mutableStateOf(false) }
    var isLedOn by rememberSaveable { mutableStateOf(false) }

    // Flip state
    var isFlippedHorizontally by rememberSaveable { mutableStateOf(false) }

    // External display states
    val previewUseCaseRef = remember { mutableStateOf<CameraXPreview?>(null) }
    var externalPresentation by remember { mutableStateOf<ExternalDisplayPresentation?>(null) }
    var isOutputtingToExternal by remember { mutableStateOf(false) }

    // White Balance States
    var showWhiteBalanceDialog by remember { mutableStateOf(false) }
    var currentAwbMode by rememberSaveable { mutableStateOf(CameraMetadata.CONTROL_AWB_MODE_AUTO) }
    val isWbSupported by remember { mutableStateOf(true) } // Assume true for now


    LaunchedEffect(key1 = lifecycleOwner, key2 = lensFacing) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                cameraProvider.unbindAll()

                val localPreviewUseCase = CameraXPreview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                previewUseCaseRef.value = localPreviewUseCase

                val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

                val boundCamera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    localPreviewUseCase
                )
                camera = boundCamera

                // Update zoom states
                boundCamera.cameraInfo.zoomState.observe(lifecycleOwner) { zs ->
                    minZoomRatio = zs.minZoomRatio; maxZoomRatio = zs.maxZoomRatio
                }
                currentZoomRatio = boundCamera.cameraInfo.zoomState.value?.zoomRatio ?: 1f

                // Update exposure states
                boundCamera.cameraInfo.exposureState.observe(lifecycleOwner) { es ->
                    isExposureSupported = es.isExposureCompensationSupported
                    if (isExposureSupported) {
                        minExposureIndex = es.exposureCompensationRange.lower
                        maxExposureIndex = es.exposureCompensationRange.upper
                        exposureStep = es.exposureCompensationStep
                        currentExposureIndex = es.exposureCompensationIndex
                    } else {
                        currentExposureIndex = 0
                    }
                }
                if(isExposureSupported) currentExposureIndex = boundCamera.cameraInfo.exposureState.value?.exposureCompensationIndex ?:0 else currentExposureIndex = 0

                // Update LED/Flash state
                hasFlashUnit = boundCamera.cameraInfo.hasFlashUnit()
                if (hasFlashUnit) {
                    boundCamera.cameraControl.enableTorch(isLedOn).addListener({}, cameraExecutor)
                } else {
                    if (isLedOn) isLedOn = false
                }

                // Apply initial/saved AWB mode
                if (currentAwbMode != CameraMetadata.CONTROL_AWB_MODE_AUTO) {
                    try {
                        val captureRequestBuilder = CaptureRequestOptions.Builder()
                            .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, currentAwbMode)
                        Camera2CameraControl.from(boundCamera.cameraControl).setCaptureRequestOptions(captureRequestBuilder.build())
                        Log.d("CameraXApp", "Initial AWB mode set to: $currentAwbMode")
                    } catch (e: Exception) {
                        Log.e("CameraXApp", "Failed to set initial AWB mode: $currentAwbMode", e)
                    }
                }

            } catch (exc: Exception) {
                Log.e("CameraXApp", "Use case binding or state observation failed: ${exc.localizedMessage}", exc)
                camera = null; previewUseCaseRef.value = null
                isExposureSupported = false; hasFlashUnit = false
                if(isLedOn) isLedOn = false
            }
        }, ContextCompat.getMainExecutor(context))
    }

    val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    DisposableEffect(key1 = lifecycleOwner, key2 = previewUseCaseRef.value) {
        val displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                val display = displayManager.getDisplay(displayId)
                if (display != null && display.displayId != Display.DEFAULT_DISPLAY) {
                    if (externalPresentation == null && previewUseCaseRef.value != null) {
                        Log.d("CameraXApp", "External display added: ${display.name}")
                        Toast.makeText(context, "Ext display connected: ${display.name}", Toast.LENGTH_SHORT).show()
                        previewUseCaseRef.value?.setSurfaceProvider(null)
                        externalPresentation = ExternalDisplayPresentation(context, display, previewUseCaseRef.value)
                        externalPresentation?.show()
                        isOutputtingToExternal = true
                    }
                }
            }
            override fun onDisplayRemoved(displayId: Int) {
                externalPresentation?.let {
                    if (it.display.displayId == displayId) {
                        Log.d("CameraXApp", "External display removed: ${it.display.name}")
                        Toast.makeText(context, "Ext display disconnected: ${it.display.name}", Toast.LENGTH_SHORT).show()
                        it.dismiss()
                        externalPresentation = null
                        isOutputtingToExternal = false
                        try { previewUseCaseRef.value?.setSurfaceProvider(previewView.surfaceProvider) }
                        catch (e: Exception) { Log.e("CameraXApp", "Error restoring SP to main PreviewView", e) }
                    }
                }
            }
            override fun onDisplayChanged(displayId: Int) { }
        }
        displayManager.registerDisplayListener(displayListener, null)
        if (previewUseCaseRef.value != null) {
            displayManager.displays.find { it.displayId != Display.DEFAULT_DISPLAY }?.let { disp ->
                if (externalPresentation == null) {
                    Log.d("CameraXApp", "Initial external display found: ${disp.name}")
                    Toast.makeText(context, "Initial ext display: ${disp.name}", Toast.LENGTH_SHORT).show()
                    previewUseCaseRef.value?.setSurfaceProvider(null)
                    externalPresentation = ExternalDisplayPresentation(context, disp, previewUseCaseRef.value)
                    externalPresentation?.show()
                    isOutputtingToExternal = true
                }
            }
        }
        onDispose {
            displayManager.unregisterDisplayListener(displayListener)
            externalPresentation?.dismiss()
            if (isOutputtingToExternal) {
                try { previewUseCaseRef.value?.setSurfaceProvider(previewView.surfaceProvider) }
                catch (e: Exception) { Log.e("CameraXApp", "Error restoring SP to main PreviewView onDispose", e) }
            }
            externalPresentation = null; isOutputtingToExternal = false
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { offset ->
                            camera?.let { cam ->
                                val factory = SurfaceOrientedMeteringPointFactory(previewView.width.toFloat(), previewView.height.toFloat())
                                val meteringPoint = factory.createPoint(offset.x, offset.y)
                                val action = FocusMeteringAction.Builder(meteringPoint).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
                                cam.cameraControl.startFocusAndMetering(action).addListener({ Log.d("CameraXApp", "Tap to focus success.") }, cameraExecutor)
                            }
                        })
                    }
                    .scale(scaleX = if (isFlippedHorizontally) -1f else 1f, scaleY = 1f)
            )
            if (isOutputtingToExternal) {
                Text("EXT OUT", color = Color.Red, modifier = Modifier.align(Alignment.TopStart).padding(8.dp).background(Color.White.copy(alpha = 0.7f)).padding(4.dp))
            }
        }

        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) { // Added bottom padding to this Column
            if (minZoomRatio < maxZoomRatio) {
                Slider(
                    value = currentZoomRatio,
                    onValueChange = { newZoomRatio -> currentZoomRatio = newZoomRatio; camera?.cameraControl?.setZoomRatio(newZoomRatio) },
                    valueRange = minZoomRatio..maxZoomRatio,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(String.format("Zoom: %.2fx", currentZoomRatio), modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 4.dp)) // Reduced bottom padding
            } else {
                Text("Zoom: N/A", modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), // Consistent padding
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            Button(onClick = { lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK }, modifier = Modifier.padding(2.dp)) {
                Icon(Icons.Filled.Cameraswitch, contentDescription = "Switch Camera")
            }
            Button(onClick = { val newLedState = !isLedOn; camera?.cameraControl?.enableTorch(newLedState)?.addListener({ isLedOn = newLedState }, cameraExecutor) }, enabled = hasFlashUnit, modifier = Modifier.padding(2.dp)) {
                Icon(if (isLedOn) Icons.Filled.FlashOff else Icons.Filled.FlashOn, contentDescription = if (isLedOn) "Turn LED Off" else "Turn LED On")
            }
            Button(onClick = { isFlippedHorizontally = !isFlippedHorizontally }, modifier = Modifier.padding(2.dp)) {
                Icon(Icons.Filled.Flip, contentDescription = if (isFlippedHorizontally) "Unflip Preview" else "Flip Preview")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).padding(bottom = 8.dp), // Consistent padding and final bottom padding
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
        ExposureDialog(currentExposure, minExposure, maxExposure, exposureStep, onDismiss = { showExposureDialog = false }) { newIndex ->
            currentExposureIndex = newIndex; camera?.cameraControl?.setExposureCompensationIndex(newIndex)
        }
    }
    if (showWhiteBalanceDialog) {
        WhiteBalanceDialog(currentAwbMode, onDismiss = { showWhiteBalanceDialog = false }) { selectedMode ->
            currentAwbMode = selectedMode; showWhiteBalanceDialog = false
            camera?.let { cam ->
                try {
                    val cr = CaptureRequestOptions.Builder().setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, selectedMode).build()
                    Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(cr).addListener({ Log.d("CameraXApp", "Set AWB to $selectedMode") }, cameraExecutor)
                } catch (e: Exception) { Log.e("CameraXApp", "Failed to set AWB", e) }
            }
        }
    }

    DisposableEffect(Unit) { onDispose { cameraExecutor.shutdown() } }
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
    val wbModes = listOf("Auto" to CameraMetadata.CONTROL_AWB_MODE_AUTO, "Incandescent" to CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT, "Fluorescent" to CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT, "Daylight" to CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT, "Cloudy" to CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT) // Renamed "Cloudy Daylight" to "Cloudy" for brevity
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
    CameraAppTheme { // Apply theme to preview as well
        CameraScreen()
    }
}

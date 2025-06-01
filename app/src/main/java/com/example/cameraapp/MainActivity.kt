package com.example.cameraapp

import android.Manifest
import android.os.Bundle
import android.util.Log
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
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
import com.google.accompanist.permissions.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// import com.example.cameraapp.ui.theme.CameraAppTheme // Assuming a theme is generated

@OptIn(ExperimentalPermissionsApi::class) // Required for Accompanist Permissions
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // CameraAppTheme { // Theme will be added later
            Surface(
                modifier = Modifier.fillMaxSize(),
                // color = MaterialTheme.colorScheme.background // Theme will be added later
            ) {
                HandleCameraPermission()
            }
            // }
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
                Text("Camera permission is required to use this app.")
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
                Text("Camera permission denied. Please enable it in app settings to use the camera features.")
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


    LaunchedEffect(key1 = lifecycleOwner, key2 = lensFacing) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                cameraProvider.unbindAll()

                val previewUseCase = CameraXPreview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

                val boundCamera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    previewUseCase
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
                        currentExposureIndex = 0 // Reset
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

            } catch (exc: Exception) {
                Log.e("CameraXApp", "Use case binding or state observation failed: ${exc.localizedMessage}", exc)
                camera = null
                isExposureSupported = false
                hasFlashUnit = false
                if(isLedOn) isLedOn = false
            }
        }, ContextCompat.getMainExecutor(context))
    }

    Column(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { offset ->
                            camera?.let { cam ->
                                val factory = SurfaceOrientedMeteringPointFactory(
                                    previewView.width.toFloat(),
                                    previewView.height.toFloat()
                                )
                                val meteringPoint = factory.createPoint(offset.x, offset.y)
                                val action = FocusMeteringAction.Builder(meteringPoint)
                                    .setAutoCancelDuration(3, TimeUnit.SECONDS)
                                    .build()

                                Log.d("CameraXApp", "Tap to focus at: ${offset.x}, ${offset.y} -> Point: $meteringPoint")
                                cam.cameraControl.startFocusAndMetering(action)
                                    .addListener(
                                        { Log.d("CameraXApp", "Tap to focus success.") },
                                        cameraExecutor
                                    )
                            }
                        }
                    )
                }
                .scale(scaleX = if (isFlippedHorizontally) -1f else 1f, scaleY = 1f)
        )

        // Zoom Controls
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            if (minZoomRatio < maxZoomRatio) {
                Slider(
                    value = currentZoomRatio,
                    onValueChange = { newZoomRatio ->
                        currentZoomRatio = newZoomRatio
                        camera?.cameraControl?.setZoomRatio(newZoomRatio)
                    },
                    valueRange = minZoomRatio..maxZoomRatio,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(String.format("Zoom: %.2fx", currentZoomRatio),
                     modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp))
            } else {
                Text("Zoom: N/A",
                     modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            Button(onClick = {
                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                    CameraSelector.LENS_FACING_FRONT
                } else {
                    CameraSelector.LENS_FACING_BACK
                }
            }) { Text("Switch") }
            Button(
                onClick = {
                    val newLedState = !isLedOn
                    camera?.cameraControl?.enableTorch(newLedState)?.addListener({
                        isLedOn = newLedState
                    }, cameraExecutor)
                },
                enabled = hasFlashUnit
            ) {
                Text(if (isLedOn) "LED OFF" else "LED ON")
            }
            Button(onClick = { isFlippedHorizontally = !isFlippedHorizontally }) {
                Text(if (isFlippedHorizontally) "Unflip" else "Flip")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceAround
        ){
            Button(
                onClick = { showExposureDialog = true },
                enabled = isExposureSupported
            ) { Text("Exposure") }
            Button(onClick = { /* TODO: WB */ }) { Text("WB") }
        }
    }

    if (showExposureDialog && isExposureSupported) {
        ExposureDialog(
            currentExposure = currentExposureIndex,
            minExposure = minExposureIndex,
            maxExposure = maxExposureIndex,
            exposureStepValue = exposureStep,
            onDismiss = { showExposureDialog = false },
            onExposureChange = { newIndex ->
                currentExposureIndex = newIndex
                camera?.cameraControl?.setExposureCompensationIndex(newIndex)
            }
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }
}

@Composable
fun ExposureDialog(
    currentExposure: Int,
    minExposure: Int,
    maxExposure: Int,
    exposureStepValue: Rational?,
    onDismiss: () -> Unit,
    onExposureChange: (Int) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Adjust Exposure") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val evValue = exposureStepValue?.let { step ->
                    String.format("%.1f EV", currentExposure * step.toFloat())
                } ?: "$currentExposure"
                Text("Current: $evValue", style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = {
                            if (currentExposure > minExposure) {
                                onExposureChange(currentExposure - 1)
                            }
                        },
                        enabled = currentExposure > minExposure
                    ) { Text("-") }
                    Button(
                        onClick = {
                            if (currentExposure < maxExposure) {
                                onExposureChange(currentExposure + 1)
                            }
                        },
                        enabled = currentExposure < maxExposure
                    ) { Text("+") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    // CameraAppTheme { // Theme will be added later
    CameraScreen()
    // }
}

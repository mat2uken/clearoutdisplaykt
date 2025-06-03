package app.mat2uken.android.app.clearextoutcamera

import android.Manifest
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.CameraSelector
// import androidx.camera.core.Preview // Removed, CameraManager handles its own Preview objects
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView // This is specific enough
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.LifecycleOwner // Added this import
import androidx.compose.runtime.livedata.observeAsState // Added this import
// import androidx.compose.ui.tooling.preview.Preview // Removed for aliasing
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.Slider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview as ComposablePreview
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import com.google.accompanist.systemuicontroller.rememberSystemUiController
// Removed java.util.concurrent.Executor as it's managed by CameraManager
// Removed kotlin.coroutines.resume and suspendCoroutine as direct CameraProvider access is moved

class MainActivity : ComponentActivity() {
    private lateinit var cameraManager: CameraManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraManager = CameraManager(this)
        setContent {
            ClearExtOutCameraTheme {
                MainScreen(cameraManager)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraManager.shutdown()
    }
}

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(cameraManager: CameraManager) {
    val systemUiController = rememberSystemUiController()
    SideEffect {
        systemUiController.isSystemBarsVisible = false // Hide system bars
    }

    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(Unit) {
        if (!cameraPermissionState.status.isGranted && !cameraPermissionState.status.shouldShowRationale) {
            cameraPermissionState.launchPermissionRequest()
        }
    }

    Scaffold { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            when {
                cameraPermissionState.status.isGranted -> {
                    CameraScreen(cameraManager, lifecycleOwner)
                }
                cameraPermissionState.status.shouldShowRationale -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("Camera permission is required to use this feature.")
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { cameraPermissionState.launchPermissionRequest() }) {
                            Text("Request Permission")
                        }
                    }
                }
                else -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("Camera permission denied. Please grant permission in settings.")
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { cameraPermissionState.launchPermissionRequest() }) {
                            Text("Request Permission")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CameraScreen(cameraManager: CameraManager, lifecycleOwner: LifecycleOwner) {
    val context = LocalContext.current
    // PreviewView needs to be remembered for AndroidView
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    // Start camera when the composable enters the composition and lifecycle owner is active
    LaunchedEffect(lifecycleOwner) {
        cameraManager.startCamera(lifecycleOwner, previewView)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
                .fillMaxWidth()
        ) {
            ZoomControl(cameraManager)
            Spacer(modifier = Modifier.height(16.dp))
            CameraSelection(cameraManager, lifecycleOwner, previewView)
        }
    }
}


@Composable
fun ZoomControl(cameraManager: CameraManager) {
    // Explicitly use observeAsState and handle its nullable State result.
    // cameraManager.getZoomState() returns LiveData<ZoomState>?
    // zoomStateObserved will be State<ZoomState?>? (State of a nullable ZoomState, if LiveData is not null)
    val zoomStateObserved: State<ZoomState?>? = cameraManager.getZoomState()?.observeAsState(initial = null)

    // Get the actual ZoomState object from the observed State.
    val actualZoomState: ZoomState? = zoomStateObserved?.value

    val zoomRatioRange = actualZoomState?.zoomRatioRange ?: 0f..1f // Default if zoomState or its range is null

    // This 'currentSliderPosition' is the mutable state for the Slider, initialized from observed state or default.
    var currentSliderPosition by remember { mutableFloatStateOf(actualZoomState?.zoomRatio ?: zoomRatioRange.start) }

    // Effect to update the slider's position if the underlying LiveData (actualZoomState?.zoomRatio) changes.
    LaunchedEffect(actualZoomState?.zoomRatio) {
        actualZoomState?.zoomRatio?.let { newRatioFromLiveData ->
            if (currentSliderPosition != newRatioFromLiveData) { // Avoid recomposition loop if already in sync
                currentSliderPosition = newRatioFromLiveData
            }
        }
    }

    Text("Zoom", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
    // Display the current zoom ratio value using the slider's current position
    Text(
        text = "Current Ratio: ${String.format("%.2f", currentSliderPosition)}x",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 8.dp)
    )
    Slider(
        value = currentSliderPosition, // Slider's value is bound to our mutable state
        onValueChange = { newSliderValue ->
            currentSliderPosition = newSliderValue // Update local UI state immediately
            cameraManager.setZoomRatio(newSliderValue) // Propagate change to CameraManager
        },
        valueRange = zoomRatioRange, // Use the calculated or default range
        modifier = Modifier.fillMaxWidth()
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraSelection(cameraManager: CameraManager, lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
    var expanded by remember { mutableStateOf(false) }
    val availableCameras = remember { cameraManager.getAvailableCameras() }
    var selectedCameraIndex by remember { mutableIntStateOf(
        availableCameras.indexOf(CameraSelector.DEFAULT_BACK_CAMERA).coerceAtLeast(0)
    )}

    if (availableCameras.isEmpty()) {
        Text("No cameras available.", color = MaterialTheme.colorScheme.error)
        return
    }

    val selectedCameraDisplayName = when (availableCameras.getOrNull(selectedCameraIndex)) {
        CameraSelector.DEFAULT_BACK_CAMERA -> "Back Camera"
        CameraSelector.DEFAULT_FRONT_CAMERA -> "Front Camera"
        else -> "Unknown Camera" // Should ideally map actual camera info if more than default front/back
    }

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            TextField(
                value = selectedCameraDisplayName,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                availableCameras.forEachIndexed { index, cameraSelector ->
                    val displayName = when (cameraSelector) {
                        CameraSelector.DEFAULT_BACK_CAMERA -> "Back Camera"
                        CameraSelector.DEFAULT_FRONT_CAMERA -> "Front Camera"
                        else -> "Camera ${index + 1}" // Generic name for other cameras
                    }
                    DropdownMenuItem(
                        text = { Text(displayName) },
                        onClick = {
                            selectedCameraIndex = index
                            expanded = false
                            cameraManager.selectCamera(lifecycleOwner, previewView, cameraSelector)
                            Log.d("CameraSelection", "Selected: $displayName")
                        }
                    )
                }
            }
        }
    }
}

// Basic Theme
@Composable
fun ClearExtOutCameraTheme(content: @Composable () -> Unit) {
    // Ensure this uses the theme defined in ui.theme.ClearExtOutCameraTheme if it exists
    // For now, using a basic MaterialTheme.
    // If your project has ui.theme.ClearExtOutCameraTheme, you should use that.
    // e.g., app.mat2uken.android.app.clearextoutcamera.ui.theme.ClearExtOutCameraTheme { content() }
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            content()
        }
    }
}

@ComposablePreview(showBackground = true) // Changed to aliased import
@Composable
fun DefaultPreview() {
    ClearExtOutCameraTheme {
        // MainScreen now requires a CameraManager.
        // For preview, we can't easily provide a real one.
        // So, we'll show a placeholder or a simplified MainScreen for UI preview.
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Camera App Preview (Permissions will be requested)")
        }
    }
}


@ComposablePreview(showBackground = true) // Changed to aliased import
@Composable
fun PermissionDeniedPreview() {
    ClearExtOutCameraTheme {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Camera permission denied. Please grant permission in settings.")
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { /* Stub */ }) {
                Text("Request Permission")
            }
        }
    }
}

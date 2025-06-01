# Camera App - Design Document

## 1. Introduction

This document outlines the design and architecture of the Camera App, an Android application built with Kotlin and Jetpack Compose. The application provides real-time camera preview and a suite of controls for adjusting camera parameters, along with support for external display mirroring. The architecture emphasizes testability and separation of concerns through a ViewModel-Service pattern.

## 2. Core Features & Specifications

The application implements the following features:

*   **Real-time Camera Preview:** Displays a live feed from the selected camera, maintaining aspect ratio.
*   **Camera Switching (Front/Back):** Allows users to toggle between available front and back-facing cameras.
*   **Zoom Adjustment:**
    *   UI: Slider control.
    *   Range: Dynamically determined by the active camera's capabilities. Min/Max zoom ratios are fetched from `CameraInfo`.
    *   Functionality: Updates camera zoom level via `CameraControl.setZoomRatio()`.
*   **Exposure Adjustment:**
    *   UI: Button opening a dialog with "+/-" controls.
    *   Range: Dynamically determined by `CameraInfo.exposureState` (range and step).
    *   Functionality: Updates camera exposure compensation index via `CameraControl.setExposureCompensationIndex()`.
    *   Availability: Control is enabled only if the camera supports exposure compensation.
*   **LED Flashlight Control:**
    *   UI: Toggle button/icon.
    *   Functionality: Turns the camera's torch (flash unit) on/off via `CameraControl.enableTorch()`.
    *   Availability: Control is enabled only if the active camera has a flash unit (`CameraInfo.hasFlashUnit()`).
*   **Tap-to-Focus:**
    *   UI: Tapping anywhere on the preview surface.
    *   Functionality: Initiates auto-focus and metering at the tapped point using `CameraControl.startFocusAndMetering()` with a `SurfaceOrientedMeteringPointFactory`. Auto-cancels after 3 seconds to revert to continuous auto-focus.
*   **Horizontal Flip (Mirroring Preview):**
    *   UI: Toggle button/icon.
    *   Functionality: Visually flips the camera preview horizontally using `Modifier.scale(scaleX = -1f)`. This is a UI-level flip.
*   **External Display Integration:**
    *   Detection: Automatically detects connection/disconnection of external displays.
    *   Notification: Shows Toast messages for display connection/disconnection events (Note: Toasts were present in original plan, but removed during refactor to simplify; can be re-added in ViewModel/UI based on DisplayService state changes).
    *   Preview Mirroring: When an external display is connected, the camera preview is mirrored to it using an `android.app.Presentation` dialog. The main app preview may go blank during this time.
    *   UI Indicator: An icon/text ("EXT OUT") is displayed on the main app UI when outputting to an external display.
*   **White Balance Adjustment:**
    *   UI: Button opening a dialog with preset selection (Auto, Incandescent, Fluorescent, Daylight, Cloudy).
    *   Functionality: Sets the camera's auto white balance mode (`CONTROL_AWB_MODE`) via Camera2 interop (`Camera2CameraControl.setCaptureRequestOptions()`).
*   **State Persistence:**
    *   User preferences for selected lens (`lensFacing`), horizontal flip state (`isFlippedHorizontally`), and selected AWB mode (`currentAwbMode`) are persisted across application restarts (including process death) using `SavedStateHandle` in the ViewModel.

## 3. Architecture Overview

### 3.1. Pattern: MVVM with Service Layer Abstractions

The application employs a Model-View-ViewModel (MVVM) architecture, with additional service layers to abstract platform-specific functionalities and hardware interactions. This promotes separation of concerns, testability, and maintainability.

*   **View (UI Layer):** `CameraScreen` composable (within `MainActivity.kt`), `ExternalDisplayPresentation`. Built with Jetpack Compose. Responsible for rendering UI based on state from ViewModel and forwarding user input to ViewModel.
*   **ViewModel:** `CameraViewModel`. Acts as a state holder for the UI and a mediator between the UI and the service layers. Contains UI logic and prepares data for display. It is lifecycle-aware and uses `StateFlow` for exposing state.
*   **Services:**
    *   `CameraXService` (Interface) / `CameraXServiceImpl` (Implementation): Abstracts all interactions with the CameraX library (camera initialization, control, state observation).
    *   `DisplayService` (Interface) / `AndroidDisplayService` (Implementation): Abstracts interactions with Android's `DisplayManager` for detecting external displays.
*   **Model:** Implicitly, the data from CameraX (e.g., `ZoomState`, `ExposureState`) and `DisplayManager` serves as the model, which the services process and the ViewModel adapts for the UI.

### 3.2. Text-based Architecture Diagram & Data Flow

```
+--------------------------+     User Events    +---------------------+     Service Calls     +---------------------------------+
| CameraScreen (Compose UI)|------------------->|  CameraViewModel    |---------------------->| CameraXService / DisplayService |
|  - MainActivity          |<-------------------| (StateFlows for UI) |<----------------------| (StateFlows with camera/display |
|  - ExtDisplayPresentation|   Observes State   +---------------------+   Observes State    |  data, e.g., ZoomState, List<Display>)|
+--------------------------+                                                                +---------------------------------+
                                                                                                    |           ^
                                                                                                    |           | (Hardware Callbacks / Listener events)
                                                                                                    V           |
                                                                                           +---------------------------------+
                                                                                           | Android Hardware APIs           |
                                                                                           | (CameraX, DisplayManager)       |
                                                                                           +---------------------------------+
```

**Data Flow Example (Zoom):**
1.  **UI Interaction:** User moves the zoom slider in `CameraScreen`.
2.  **Action to ViewModel:** `CameraScreen` calls `viewModel.setZoomRatio(newSliderValue)`.
3.  **ViewModel Logic & Service Call:** `CameraViewModel` coerces the value and then calls `cameraXService.setZoomRatio(coercedValue)`.
4.  **Service to Hardware:** `CameraXServiceImpl` calls `camera.cameraControl.setZoomRatio()`.
5.  **Hardware Update & State Emission:** CameraX hardware updates its zoom. `CameraInfo.zoomState` (LiveData) emits a new `ZoomState`.
6.  **Service Observes & Updates Flow:** `CameraXServiceImpl`'s observer for `zoomState` LiveData receives the update and emits it to its internal `_zoomStateFlow: MutableStateFlow<ZoomState?>`.
7.  **ViewModel Collects & Updates UI State:** `CameraViewModel` collects `cameraXService.zoomStateFlow`, processes it (e.g., deriving `currentZoomRatio`, `minZoomRatio`, `maxZoomRatio`), and updates its own `StateFlow`s exposed to the UI.
8.  **UI Recomposition:** `CameraScreen` collects the updated `StateFlow`s from `CameraViewModel` and re-composes to reflect the new zoom state (e.g., slider position, text value).

## 4. Key Components - Detailed Design

### 4.1. `CameraScreen.kt` (within `MainActivity.kt`)
*   **Role:** Primary UI layer built with Jetpack Compose. Renders the camera preview, control buttons, and dialogs. Collects user input.
*   **State Observation:** Observes `StateFlow`s from `CameraViewModel` for all dynamic UI data (e.g., `lensFacing`, `isLedOn`, `zoomState`, `externalDisplay`). Uses `collectAsState()` for this.
*   **Action Delegation:** Forwards all user actions (button clicks, slider changes, taps) to methods on `CameraViewModel`.
*   **Lifecycle Management:**
    *   Obtains `LifecycleOwner` for camera operations.
    *   Manages the lifecycle of `ExternalDisplayPresentation` based on `viewModel.externalDisplay` state using `DisposableEffect`.
    *   Manages `CameraXService` lifecycle by calling `cameraXService.shutdown()` in a `DisposableEffect` tied to the `cameraXService` instance remembered in `CameraScreen`.
*   **Key UI Elements:** `AndroidView` for `PreviewView`, `Slider`, `Button`, `Icon`, `AlertDialog` (for exposure and white balance).

### 4.2. `CameraViewModel.kt`
*   **Role:**
    *   Holds and manages UI-related state.
    *   Handles UI logic and business rules.
    *   Mediates between `CameraScreen` and backend services (`CameraXService`, `DisplayService`).
    *   Lifecycle-aware (extends `androidx.lifecycle.ViewModel`). Uses `viewModelScope` for coroutines.
*   **Dependencies (Constructor Injected):**
    *   `cameraXService: CameraXService`
    *   `displayService: DisplayService`
    *   `savedStateHandle: SavedStateHandle`
*   **Key `StateFlow`s Exposed to UI:**
    *   `lensFacing: StateFlow<Int>`
    *   `hasFlashUnit: StateFlow<Boolean>` (derived from `cameraXService.hasFlashUnitFlow`)
    *   `isLedOn: StateFlow<Boolean>` (derived from `cameraXService.torchStateFlow`)
    *   `isFlippedHorizontally: StateFlow<Boolean>`
    *   `zoomState: StateFlow<ZoomState?>` (from `cameraXService.zoomStateFlow`)
        *   Derived: `currentZoomRatio`, `minZoomRatio`, `maxZoomRatio`, `isZoomSupported`
    *   `exposureState: StateFlow<ExposureState?>` (from `cameraXService.exposureStateFlow`)
        *   Derived: `currentExposureIndex`, `minExposureIndex`, `maxExposureIndex`, `exposureStep`, `isExposureSupported`
    *   `currentAwbMode: StateFlow<Int>`
    *   `externalDisplay: StateFlow<Display?>` (derived from `displayService.displaysFlow`)
*   **Public Methods for UI Actions:**
    *   `primaryCameraInit(lifecycleOwner: LifecycleOwner, mainSurfaceProvider: Preview.SurfaceProvider)`: Triggers camera initialization via `CameraXService`. Also re-applies persisted AWB mode.
    *   `onSwitchCameraClicked()`
    *   `onLedButtonClicked()`
    *   `onFlipClicked()`
    *   `setZoomRatio(ratio: Float)` (includes coercion)
    *   `setExposureIndex(index: Int)` (includes coercion)
    *   `onPreviewTapped(viewWidth: Int, viewHeight: Int, x: Float, y: Float)`
    *   `onWhiteBalanceModeSelected(mode: Int)`
    *   `attachExternalDisplaySurface(externalSurfaceProvider: Preview.SurfaceProvider)`
    *   `detachExternalDisplaySurface()`
*   **`SavedStateHandle` Usage:**
    *   Persists and restores `lensFacing`, `isFlippedHorizontally`, `currentAwbMode`.
    *   Keys: `CameraViewModel.LENS_FACING_KEY`, `CameraViewModel.IS_FLIPPED_KEY`, `CameraViewModel.AWB_MODE_KEY`.
*   **Lifecycle:** Calls `displayService.startListening()` in `init` and `displayService.stopListening()` in `onCleared()`.

### 4.3. `CameraXService.kt` (Interface)
*   **Purpose:** Defines a contract for abstracting all CameraX hardware interactions, making `CameraViewModel` independent of specific CameraX APIs and thus more testable.
*   **Key Methods:**
    *   `suspend fun initializeAndBindCamera(lifecycleOwner: LifecycleOwner, surfaceProvider: Preview.SurfaceProvider, targetLensFacing: Int): CameraInitResult`
    *   `fun setMainSurfaceProvider(surfaceProvider: Preview.SurfaceProvider?)`
    *   `fun setExternalSurfaceProvider(surfaceProvider: Preview.SurfaceProvider?)`
    *   `fun setZoomRatio(ratio: Float): ListenableFuture<Void?>`
    *   `fun setExposureCompensationIndex(index: Int): ListenableFuture<Void?>`
    *   `fun enableTorch(enable: Boolean): ListenableFuture<Void?>`
    *   `fun startFocusAndMetering(action: FocusMeteringAction): ListenableFuture<FocusMeteringResult>`
    *   `fun setWhiteBalanceMode(awbMode: Int): ListenableFuture<Void?>`
    *   `fun shutdown()`
*   **Key `StateFlow`s Exposed:**
    *   `zoomStateFlow: StateFlow<ZoomState?>`
    *   `exposureStateFlow: StateFlow<ExposureState?>`
    *   `hasFlashUnitFlow: StateFlow<Boolean>`
    *   `torchStateFlow: StateFlow<Boolean>` (actual torch hardware state)
*   **`CameraInitResult` Sealed Class:** `Success(val camera: Camera)`, `Failure(val exception: Exception)`, `NotInitialized`.

### 4.4. `CameraXServiceImpl.kt`
*   **Role:** Concrete implementation of `CameraXService` using the CameraX library.
*   **Constructor:** Takes `context: Context`.
*   **Key Implementation Details:**
    *   Manages `ProcessCameraProvider`, `currentCamera: Camera?`, `activePreviewUseCase: Preview?`.
    *   Uses a dedicated `cameraExecutor: ExecutorService` (single thread) for camera operations and future listeners.
    *   `initializeAndBindCamera()`: Handles CameraX setup, binding the `activePreviewUseCase` to the provided `lifecycleOwner` and initial (main) `surfaceProvider`. Sets up `Observer`s on `currentCamera.cameraInfo` LiveData properties (`zoomState`, `exposureState`, `torchState`, `hasFlashUnit()`) to update internal `MutableStateFlow`s that back the public `StateFlow`s.
    *   Control methods (`setZoomRatio`, `enableTorch`, etc.) interact with `currentCamera.cameraControl` and return `ListenableFuture`s. Listens to futures to log results. For AWB, uses `Camera2CameraControl`.
    *   `setMainSurfaceProvider`/`setExternalSurfaceProvider`: Call `activePreviewUseCase?.setSurfaceProvider()` to switch the preview output. Includes try-catch for `IllegalStateException`.
    *   `shutdown()`: Unbinds all use cases, clears observers, and shuts down `cameraExecutor`.

### 4.5. `DisplayService.kt` (Interface)
*   **Purpose:** Defines a contract for abstracting Android `DisplayManager` interactions, primarily for detecting external display connections and disconnections.
*   **Key Properties/Methods:**
    *   `displaysFlow: StateFlow<List<Display>>`: Emits the current list of all connected displays.
    *   `startListening()`: To begin monitoring display changes.
    *   `stopListening()`: To cease monitoring.

### 4.6. `AndroidDisplayService.kt`
*   **Role:** Concrete implementation of `DisplayService` using Android's `DisplayManager`.
*   **Constructor:** Takes `context: Context`.
*   **Implementation Details:**
    *   Retrieves `DisplayManager` system service.
    *   Implements `DisplayManager.DisplayListener`.
    *   Uses a `Handler(Looper.getMainLooper())` for `DisplayManager.registerDisplayListener` callbacks.
    *   `startListening()` registers the listener and performs an initial query of displays. Manages `isListening` flag.
    *   `stopListening()` unregisters the listener.
    *   On listener callbacks (`onDisplayAdded`, `onDisplayRemoved`, `onDisplayChanged`), it updates an internal `MutableStateFlow` with the current list from `displayManager.displays`, which then updates the public `displaysFlow`.

### 4.7. `ExternalDisplayPresentation.kt`
*   **Role:** An `android.app.Presentation` dialog responsible for rendering content (specifically a `PreviewView`) on a secondary display.
*   **Constructor:** Takes `context: Context` (Activity context), `display: Display`, `onSurfaceProviderReady: (Preview.SurfaceProvider) -> Unit`, and `onDismissed: () -> Unit`.
*   **Lifecycle & Interaction:**
    *   Creates its own `PreviewView` instance (`externalPreviewView`).
    *   In `onStart()`, calls `onSurfaceProviderReady(externalPreviewView.surfaceProvider)` (posted to handler to ensure view readiness).
    *   In `onStop()`, calls the `onDismissed()` callback.

### 4.8. `CameraViewModelFactory.kt`
*   **Role:** Implements `androidx.lifecycle.AbstractSavedStateViewModelFactory`.
*   **Purpose:** To create instances of `CameraViewModel`, providing its dependencies: `CameraXService`, `DisplayService`, and `SavedStateHandle`.
*   **Constructor:** Takes `owner: SavedStateRegistryOwner`, `defaultArgs: Bundle?`, `cameraXService: CameraXService`, `displayService: DisplayService`.
*   **`create()` Method:** Instantiates `CameraViewModel` with the dependencies and the `SavedStateHandle` provided by the factory system.

## 5. Functional Relationships & Data Flow Examples

*(Refer to Section 3.2 for a general data flow example. Specific flows for other features like LED control, External Display activation follow similar patterns: UI event -> ViewModel -> Service -> Hardware -> Service updates Flow -> ViewModel updates Flow -> UI recomposes.)*

*   **External Display Activation Flow:**
    1.  `AndroidDisplayService` detects a new display via `DisplayManager.DisplayListener`.
    2.  `AndroidDisplayService` updates its `_displaysFlow` with the new list of `Display` objects.
    3.  `CameraViewModel` collects `displayService.displaysFlow` and its derived `externalDisplay` `StateFlow` emits the new external `Display` object.
    4.  `CameraScreen` collects `viewModel.externalDisplay`. `DisposableEffect` reacts to the new non-null `Display`.
    5.  `CameraScreen` creates and shows an `ExternalDisplayPresentation` instance, passing callbacks.
    6.  `ExternalDisplayPresentation.onStart()` (via post) calls its `onSurfaceProviderReady` callback with its internal `PreviewView.surfaceProvider`.
    7.  The callback (wired in `CameraScreen`) calls `viewModel.attachExternalDisplaySurface(externalSurfaceProvider)`.
    8.  `CameraViewModel` calls `cameraXService.setMainSurfaceProvider(null)` then `cameraXService.setExternalSurfaceProvider(externalSurfaceProvider)`.
    9.  `CameraXServiceImpl` updates the `SurfaceProvider` on its `activePreviewUseCase`.
    10. CameraX starts rendering frames to the external display's `PreviewView`. The main app's `PreviewView` goes blank.
    11. `CameraScreen` updates its UI indicator (e.g., "EXT OUT") based on `viewModel.externalDisplay != null`.

*   **Tap-to-Focus Flow:**
    1.  User taps on `PreviewView` in `CameraScreen`.
    2.  `Modifier.pointerInput`'s `onTap` lambda calls `viewModel.onPreviewTapped(viewWidth, viewHeight, tapX, tapY)`.
    3.  `CameraViewModel` creates `SurfaceOrientedMeteringPointFactory`, `MeteringPoint`, and `FocusMeteringAction` (with auto-cancel).
    4.  `CameraViewModel` calls `cameraXService.startFocusAndMetering(action)`.
    5.  `CameraXServiceImpl` calls `currentCamera.cameraControl.startFocusAndMetering(action)`. The result/failure is logged by a listener within the service.
    6.  CameraX hardware performs focus and metering.

## 6. State Persistence
User preferences that are persisted across application sessions (including process death) via `SavedStateHandle` in `CameraViewModel` include:
*   **Selected Camera Lens:** Front or back (`lensFacing`). Key: `CameraViewModel.LENS_FACING_KEY`.
*   **Horizontal Flip State:** Whether the preview is flipped (`isFlippedHorizontally`). Key: `CameraViewModel.IS_FLIPPED_KEY`.
*   **Selected White Balance Mode:** The last user-selected AWB preset (`currentAwbMode`). Key: `CameraViewModel.AWB_MODE_KEY`.

Other states like current zoom/exposure values, LED on/off status are considered transient and reset to camera defaults or initial app defaults when the camera re-initializes. The selected AWB mode is explicitly re-applied by the ViewModel during camera initialization if it's not the default "AUTO" mode.

## 7. Threading Model
The application utilizes several threads/dispatchers:
*   **Main/UI Thread:** All Jetpack Compose UI operations (composition, recomposition, layout, drawing) and user input events occur on the main thread. `StateFlow` updates collected by the UI trigger recomposition on this thread.
*   **`viewModelScope` (in `CameraViewModel`):** Coroutines launched using `viewModelScope` typically run on `Dispatchers.Main.immediate` by default, suitable for UI-related logic and updating `StateFlow`s that drive the UI. Calls to service methods are made from this scope.
*   **`cameraExecutor` (in `CameraXServiceImpl`):** A dedicated `Executors.newSingleThreadExecutor()` used for:
    *   Callbacks for `ListenableFuture`s returned by CameraX control methods (e.g., logging results of `enableTorch`, `startFocusAndMetering`, `setWhiteBalanceMode`).
    *   CameraX's `Preview.setSurfaceProvider` can also take an executor; however, the current implementation calls it directly as it's thread-safe. If issues arise, this could be revisited.
*   **`mainHandler` (in `AndroidDisplayService`):** A `Handler(Looper.getMainLooper())` is used for registering the `DisplayManager.DisplayListener`, as its callbacks are expected on a Looper thread.

## 8. Testing Strategy
*   **ViewModel Unit Tests (`CameraViewModelTest.kt`):**
    *   Primary focus of automated testing.
    *   Uses JUnit4.
    *   **MockK:** Used to mock dependencies (`CameraXService`, `DisplayService`).
    *   **Turbine:** Used for testing `StateFlow` emissions from `CameraViewModel` in a structured and readable way.
    *   **Truth:** Used for assertions.
    *   **`kotlinx-coroutines-test`:** Provides `runTest` and `TestDispatcher` (via `MainDispatcherRule`) for testing coroutine-based logic.
    *   **`SavedStateHandle`:** A test instance is provided to the ViewModel during tests. Specific tests verify loading from and saving to the handle.
    *   **Coverage:** Tests aim to cover:
        *   Correct initial state (including from `SavedStateHandle`).
        *   State changes in response to action method calls (including saving to `SavedStateHandle`).
        *   Correct delegation of actions to mocked services (verifying method calls and arguments).
        *   Correct derivation of UI state from service `StateFlow`s.
        *   Lifecycle methods like `onCleared` for `DisplayService.stopListening`.
*   **Service Layer Testing:** `CameraXServiceImpl` and `AndroidDisplayService` are harder to unit test directly due to their heavy reliance on Android framework APIs. Their testability is primarily achieved by ensuring they implement their respective interfaces correctly, and their logic is verified through integration testing (manual or automated UI tests) when used by the ViewModel.
*   **UI Testing (Compose):** Not currently implemented in this scope, but would be the next layer, using `ComposeTestRule` to verify UI element states and interactions.

## 9. Refactoring Summary
The application underwent a significant refactoring from an initial, more monolithic structure to the current MVVM-Service architecture.
*   **Initial State:** Logic spread within `CameraScreen` composable, direct CameraX calls, local `remember` / `rememberSaveable` for state.
*   **Refactoring Goals:** Improve testability, separation of concerns, state management robustness, and code organization.
*   **Key Refactoring Steps:**
    1.  **`CameraViewModel` Introduction:** Centralized UI state and basic UI logic.
    2.  **Service Abstraction:** Defined `CameraXService` and `DisplayService` interfaces.
    3.  **ViewModel-Service Interaction:** `CameraViewModel` refactored to depend on these service interfaces.
    4.  **Concrete Service Implementations:** `CameraXServiceImpl` and `AndroidDisplayService` created.
    5.  **State Migration:** UI state progressively moved from `CameraScreen` local state to `CameraViewModel` (observing service flows or managing local UI preferences).
    6.  **`SavedStateHandle` Integration:** Added to `CameraViewModel` for robust persistence of user preferences.
    7.  **Unit Testing:** Developed unit tests for `CameraViewModel` concurrently with refactoring.
*   **Benefits Achieved:**
    *   **Testability:** `CameraViewModel` logic is now highly unit-testable.
    *   **Separation of Concerns:** UI, UI logic/state, camera hardware interaction, and display detection are now distinct layers.
    *   **Maintainability:** Clearer architecture makes the codebase easier to understand and modify.
    *   **Scalability:** Easier to add new features or modify existing ones without impacting unrelated components.

## 10. Potential Future Enhancements
*   **Dynamic WB Preset Filtering:** Query available AWB modes from `CameraCharacteristics` via `CameraXService` and only enable/show supported presets in the `WhiteBalanceDialog`.
*   **Granular Error Feedback to UI:** Expose error states/events from `CameraViewModel` (originating from service call failures) to show Toasts or other UI indicators to the user. (Original Toast for display connection/disconnection can be re-added this way).
*   **Image/Video Capture Functionality:** Extend `CameraXService` and `CameraViewModel` to support image capture and video recording.
*   **Advanced Camera Controls:** Expose more Camera2 controls if needed (e.g., manual focus, sensor sensitivity, frame duration) via the service layer.
*   **Compose UI Tests:** Implement automated UI tests using `ComposeTestRule`.
*   **Dependency Injection Framework:** Introduce a DI framework like Hilt for managing dependencies of services and ViewModels, replacing manual factory implementations.
*   **True Simultaneous Multi-Preview:** Investigate more advanced techniques if simultaneous preview on main app and external display is strictly required (e.g., frame copying or checking for multi-stream support per device).
```

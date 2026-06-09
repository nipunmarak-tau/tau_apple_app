# Tau Research — iOS

Native iOS port of the Tau Research Android app, built with **Swift 5.9+** and **SwiftUI**
(MVVM + Clean Architecture). Bundle ID: `com.tau.research`. Minimum deployment target: **iOS 17**.

## Architecture

```
TauResearch/
├── TauResearchApp.swift     # @main entry
├── App/                     # AppRouter, navigation state
├── Models/                  # Plain Swift structs (DTOs, domain types)
├── Storage/                 # TokenManager (Keychain), DeviceManager, ApiCache, SwiftData
├── Network/                 # URLSession-based APIClient, auth interceptor, reachability
├── Repositories/            # Auth, Dashboard, SiteBlock, Recording, Upload, Settings
├── Services/                # RecordingService, UploadService, UploadStateBus
├── Camera/                  # AVFoundation controller, preview UIViewRepresentable
├── ML/                      # Vision + Core ML kiwi detector
├── Monitoring/              # AppHealthMonitor (os_log + analytics hooks)
├── ViewModels/              # @Observable view models
├── Views/                   # SwiftUI screens
├── Theme/                   # Brand colors
├── Utils/                   # Formatting, GPX logger, CSV logger
└── Resources/               # Assets.xcassets, Info.plist, ML model
```

## Android → iOS dependency mapping

| Android / Kotlin            | iOS / Swift                             |
| --------------------------- | --------------------------------------- |
| Jetpack Compose             | SwiftUI                                 |
| ViewModel + StateFlow       | `@Observable` classes (iOS 17)          |
| Camera2 API                 | AVFoundation (`AVCaptureSession`)       |
| TensorFlow Lite             | Core ML + Vision (`VNCoreMLRequest`)    |
| ObjectBox                   | SwiftData (`@Model`)                    |
| Retrofit + OkHttp           | `URLSession` + `async/await`            |
| EncryptedSharedPreferences  | Keychain Services                       |
| ExoPlayer                   | `AVPlayer` / `VideoPlayer`              |
| FusedLocationProvider       | `CLLocationManager`                     |
| Foreground Service          | `BGTaskScheduler` + background audio    |
| WorkManager / coroutines    | Swift Concurrency (`Task`, actors)      |
| Hilt DI                     | Manual constructor injection            |
| Gson                        | `Codable` (JSONEncoder/Decoder)         |
| New Relic                   | `os_log` + (optional) MetricKit         |

## Setup

### 1. Convert the TFLite model to Core ML (one-time, on macOS)

```bash
cd TauResearch/Resources
python3 -m venv .venv && source .venv/bin/activate
pip install coremltools tensorflow
python convert_tflite_to_coreml.py model-hdr-320.tflite KiwiDetector.mlpackage
```

`KiwiDetector.mlpackage` is what the app loads at runtime
(see `ML/KiwiDetectionProcessor.swift`).

### 2. Generate the Xcode project

The Swift sources are organised in folders, and an
[XcodeGen](https://github.com/yonaskolb/XcodeGen) spec (`project.yml`) is included.

```bash
brew install xcodegen
xcodegen generate
open TauResearch.xcodeproj
```

### 3. Run

Build & run on a physical iOS device (the camera/AVFoundation paths require hardware).

## Backend

The app talks to `https://kiwifruitiq.tau.co.nz/` (same backend as the Android app). Token is
JWT, stored in the iOS Keychain.

## Cleaning up the old Android project

This repo previously hosted the Android (Jetpack Compose) implementation. Folders that are
no longer used by the iOS build:

- `app/`, `build/`, `.gradle/`, `gradle/`, `.kotlin/`, `.idea/`
- `gradlew`, `gradlew.bat`, `build.gradle.kts`, `settings.gradle.kts`
- `gradle.properties`, `local.properties`, `TODO.md`
- `docs/PROJECT_DOCUMENTATION.md`, `docs/RED_FLAGS_AND_TROUBLESHOOTING.md`

They can be removed via Finder or `git rm -r`.

## Features ported

- [x] Email/password login (JWT) + device registration
- [x] Dashboard listing local recordings (SwiftData), upload queue, online/offline state
- [x] Create Site Block screen (dropdowns + numeric fields)
- [x] Recording screen — AVCaptureSession preview, AE loop, GPS readiness gate, pause/resume
- [x] Kiwi detection via Core ML + Vision (YOLO-style output decoder + NMS)
- [x] Per-frame CSV metrics + GPX logging during recording
- [x] Multi-part video upload to S3 (presigned URLs) with progress
- [x] Settings (target EV100, shutter, video resolution)
- [x] Background upload service via `URLSession` background configuration

## Known caveats

- HDR10 (DolbyVision) is set up but requires HEVC + iOS 17.0+ on supported hardware.
- Picture-in-Picture during recording is wired through `AVPictureInPictureController` but
  is currently disabled until the encoder pipeline is verified end-to-end on device.
- New Relic / Firebase observability are stubbed in `AppHealthMonitor` — wire your SDK of
  choice there if you want telemetry parity with the Android app.

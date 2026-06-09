# Tau Android Application - Comprehensive Project Documentation

## Table of Contents
1. [Project Overview](#project-overview)
2. [Development & release process](#development--release-process)
3. [Architecture Overview](#architecture-overview)
4. [Project Structure](#project-structure)
5. [Configuration Files](#configuration-files)
6. [Application Entry Points](#application-entry-points)
7. [Data Layer](#data-layer)
8. [UI Layer (Jetpack Compose)](#ui-layer-jetpack-compose)
9. [ViewModel Layer](#viewmodel-layer)
10. [Repository Layer](#repository-layer)
11. [Network Layer](#network-layer)
12. [Camera Functionality](#camera-functionality) — includes [kiwi detection & auto-exposure](#kiwi-detection-and-auto-exposure-brightness)
13. [File Storage & Upload](#file-storage--upload)
14. [Navigation System](#navigation-system)
15. [Permissions & Security](#permissions--security)
16. [Dependencies & Libraries](#dependencies--libraries)
17. [Build Configuration](#build-configuration)
18. [Key Features & Workflows](#key-features--workflows)

---

## Project Overview

**Project Name:** Tau Android Application (Jetpack Compose)  
**Gradle namespace / application ID:** `com.tau.research` (see `app/build.gradle.kts`)  
**Kotlin sources package:** `com.example.cameraaccess` (directory `app/src/main/java/com/example/cameraaccess/`)  
**Technology Stack:** 
- **Language:** Kotlin
- **UI Framework:** Jetpack Compose
- **Architecture Pattern:** MVVM (Model-View-ViewModel)
- **Database:** ObjectBox (NoSQL database)
- **Network:** Retrofit + OkHttp
- **Min SDK:** 31 (Android 12)
- **Target SDK:** 36
- **Compile SDK:** 36
- **Version:** `versionName` **1.3** / `versionCode` **4** (see `app/build.gradle.kts`)

### Purpose
This is a mobile application built with Jetpack Compose for camera access, video recording, GPS tracking, optional on-device processing (TensorFlow Lite), and cloud upload. The app is designed for field recording with location tracking (GPX), video capture in a bound foreground **RecordingService**, and upload to a remote server using multipart upload for large video files.

---

## Development & release process

This section describes how the project is built and run today (Gradle wrapper, plugins, and local workflows).

### Prerequisites
- **JDK 11** (project uses `jvmToolchain(11)` / Java 11 compatibility).
- **Android Studio** (recent stable) with Android SDK **36** and build tools compatible with **Android Gradle Plugin 8.7.3**.
- Physical device or emulator with **API 31+**; camera, location, and microphone are core to recording.

### Get the code and open the project
1. Clone the repository and open the root folder in Android Studio.
2. Let Gradle sync finish (wrapper uses **Gradle 9.1.0**; see `gradle/wrapper/gradle-wrapper.properties`).
3. `settings.gradle.kts` uses **dependency resolution management** with `FAIL_ON_PROJECT_REPOS`; third-party repos are not declared inside module scripts.

### Firebase and observability (local builds)
- **Firebase:** the app applies `com.google.gms.google-services` and Firebase libraries (Analytics, Crashlytics, Performance). A valid **`app/google-services.json`** from your Firebase project is required for a successful sync/build when those features are enabled.
- **Embrace:** configured via the Embrace Gradle plugin and `app/src/main/embrace-config.json`. Treat API tokens as secrets; obtain team-approved credentials for release builds.
- **New Relic:** the root `build.gradle.kts` adds the New Relic agent classpath; `app/build.gradle.kts` applies `newrelic`. Ensure your environment follows your organisation’s policy for agent keys and dashboards.

### Run a debug build
- In Android Studio: select the **app** configuration, choose a device, **Run**.
- From the project root (CLI):

```bash
./gradlew :app:installDebug
```

### Release build (current Gradle settings)
- Release builds use **R8 minification and resource shrinking** (`isMinifyEnabled = true`, `isShrinkResources = true`) with `proguard-android-optimize.txt` plus `app/proguard-rules.pro`.
- **Signing:** `release` currently uses `signingConfig = signingConfigs.getByName("debug")` for convenience. Replace with a proper release keystore before shipping to end users.
- Assemble an APK/AAB from the root:

```bash
./gradlew :app:assembleRelease
```

### Code generation (ObjectBox)
- ObjectBox uses **KSP** (`alias(libs.plugins.ksp)`), not KAPT. The generated **`MyObjectBox`** builder lives under the entities package and is used from `ObjectBox.kt`.

### Quality checks
- **Lint:** `abortOnError` is **false** and `checkReleaseBuilds` is **false** in `app/build.gradle.kts` (warnings do not fail the build by default).
- Tests live under `app/src/test/java/` and `app/src/androidTest/java/` when present.

---

## Architecture Overview

The application follows the **MVVM (Model-View-ViewModel)** architecture pattern with clear separation of concerns. Long-running capture runs in a foreground **`RecordingService`** (camera, microphone, location) bound from **`VideoRecordingViewModel`**. Uploads use a separate foreground **`UploadService`**. Firebase, Embrace, and New Relic provide analytics and monitoring.

```
┌─────────────────────────────────────────────────────────┐
│                    UI Layer (Compose)                    │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌─────────┐ │
│  │  Login   │  │Dashboard │  │  Record  │  │Settings │ │
│  └──────────┘  └──────────┘  └──────────┘  └─────────┘ │
└─────────────────────┬───────────────────────────────────┘
                      │
┌─────────────────────▼───────────────────────────────────┐
│              ViewModel Layer                             │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐      │
│  │LoginViewModel│ │VideoRecording│ │SettingsViewModel│ │
│  └─────────────┘ └─────────────┘ └─────────────┘      │
└─────────────────────┬───────────────────────────────────┘
                      │
┌─────────────────────▼───────────────────────────────────┐
│              Repository Layer                            │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐  │
│  │AuthRepo  │ │Recording │ │Settings  │ │Dashboard │  │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘  │
└─────────────────────┬───────────────────────────────────┘
                      │
      ┌───────────────┴───────────────┐
      │                               │
┌─────▼──────┐              ┌─────────▼────────┐
│  ObjectBox │              │  Network (Retrofit)│
│  Database  │              │  API Service      │
└────────────┘              └───────────────────┘
      ┌─────────────────────────────────────────┐
      │ Foreground services: RecordingService,   │
      │ UploadService                          │
      └─────────────────────────────────────────┘
```

---

## Project Structure

### Directory Tree (Key Directories)

```
app/src/main/
├── AndroidManifest.xml           # App permissions, components declaration
├── java/com/example/cameraaccess/
│   ├── CameraApp.kt              # Application class (initializes ObjectBox)
│   ├── MainActivity.kt           # Main entry activity
│   │
│   ├── camera/                   # Camera selection utilities
│   │   └── CameraSelector.kt
│   │
│   ├── controller/               # Camera2 API wrapper
│   │   └── Camera2Controller.kt
│   │
│   ├── data/                     # Data layer
│   │   ├── db/
│   │   │   └── ObjectBox.kt      # ObjectBox initialization
│   │   ├── entities/             # ObjectBox entity models
│   │   │   ├── RecordingEntity.kt
│   │   │   └── CameraSettingsEntity.kt
│   │   ├── model/                # Data transfer objects (DTOs)
│   │   │   ├── LoginRequest.kt
│   │   │   ├── LoginResponse.kt
│   │   │   ├── SiteBlockModels.kt
│   │   │   └── ApiCache.kt
│   │   ├── network/              # Network layer
│   │   │   ├── RetrofitClient.kt
│   │   │   └── ApiService.kt
│   │   └── repositories/         # Repository implementations
│   │       ├── AuthRepository.kt
│   │       ├── RecordingRepository.kt
│   │       ├── SettingsRepository.kt
│   │       └── DashboardRepository.kt
│   │
│   ├── navigation/               # Navigation configuration
│   │   └── AppNavGraph.kt
│   │
│   ├── service/                  # Foreground services
│   │   ├── RecordingService.kt   # Camera / mic / location / GPX during capture
│   │   ├── UploadService.kt      # Foreground service for file upload
│   │   └── UploadStateBus.kt     # Event bus for upload status
│   │
│   ├── processing/               # On-device inference (e.g. TFLite)
│   │   └── KiwiDetectionProcessor.kt
│   │
│   ├── upload/                   # Upload UI / ViewModel / repository
│   │   ├── model/
│   │   ├── repository/
│   │   ├── ui/
│   │   └── viewmodel/
│   │
│   ├── ui/                       # Compose UI screens
│   │   ├── login/
│   │   ├── dashboard/
│   │   ├── record/
│   │   ├── createsiteblock/
│   │   ├── theme/                # App theming
│   │   └── utils/                # UI utilities
│   │
│   ├── utils/                    # Utility classes
│   │   ├── TokenManager.kt       # Token storage (SharedPreferences)
│   │   ├── DeviceManager.kt
│   │   └── NetworkConnectivityObserver.kt
│   │
│   └── viewmodel/                # ViewModels
│       ├── LoginViewModel.kt
│       ├── VideoRecordingViewModel.kt
│       ├── SettingsViewModel.kt
│       └── DashboardViewModel.kt
│
└── res/                          # Resources
    ├── drawable/                 # Images, icons
    ├── mipmap-*/                 # App launcher icons
    ├── values/                   # Strings, colors, themes
    └── xml/                      # Backup rules, data extraction
```

---

## Configuration Files

### 1. Root `build.gradle.kts`
**Location:** `/build.gradle.kts`

**Purpose:** Top-level build configuration for all modules.

**Key Configuration:**
- **`buildscript`** block: adds **New Relic** agent Gradle plugin classpath from the version catalog.
- Declares plugins (**apply false**) for the app module:
  - `com.android.application` (Android Gradle Plugin)
  - `org.jetbrains.kotlin.android`
  - `org.jetbrains.kotlin.plugin.compose`
  - `io.objectbox` (ObjectBox)
  - `io.embrace.gradle` (Embrace)
  - `com.google.gms.google-services` (Firebase)
  - `com.google.firebase.crashlytics`
  - `com.google.firebase.firebase-perf`

**Link:** Referenced by all modules. Plugins declared here are applied in `app/build.gradle.kts`.

---

### 2. App Module `build.gradle.kts`
**Location:** `/app/build.gradle.kts`

**Purpose:** Module-specific build configuration and dependencies.

**Key Settings:**
```kotlin
namespace = "com.tau.research"
applicationId = "com.tau.research"
compileSdk = 36
minSdk = 31
targetSdk = 36
versionCode = 4
versionName = "1.3"
```

**Plugins (applied):** Android application, Kotlin, Compose, **KSP**, ObjectBox, **Embrace**, **Google Services**, **Crashlytics**, **Performance**, and **`newrelic`** (via `apply(plugin = "newrelic")`).

**Build Features:**
- Compose enabled; **`buildConfig = true`**; **`resValues = true`**
- **`androidResources { noCompress += "tflite" }`** for TensorFlow Lite assets
- **Lint:** `checkReleaseBuilds = false`, `abortOnError = false`

**Dependencies (high level):**
- **Core AndroidX:** core-ktx, lifecycle-runtime-ktx, fragment-ktx
- **Compose:** UI, Material3, Material icons extended, lifecycle-viewmodel-compose
- **Navigation:** `navigation-compose:2.7.3` (declared in module; catalog also lists JVM stubs for tooling)
- **Network:** Retrofit 2.9.0, OkHttp 4.12.0 (+ logging interceptor)
- **Database:** ObjectBox Kotlin (version from catalog)
- **Location:** Google Play Services Location **21.3.0** (pinned in `dependencies` block)
- **Media:** Media3 ExoPlayer + UI
- **ML:** TensorFlow Lite **2.14.0**
- **Security:** AndroidX Security Crypto (encrypted preferences for tokens)
- **Observability:** New Relic agent, Embrace SDK, Firebase BOM (Analytics, Crashlytics, Performance)
- **Testing:** JUnit, Espresso, Compose UI tests

**Links to:**
- `/gradle/libs.versions.toml` - Version catalog
- Root `build.gradle.kts` - Plugin declarations

---

### 3. `settings.gradle.kts`
**Location:** `/settings.gradle.kts`

**Purpose:** Project structure and repository configuration.

**Configuration:**
- Root project name: **"camera access"**
- Includes app module: `include(":app")`
- **`pluginManagement`:** Google (filtered by regex), Maven Central, Gradle Plugin Portal
- **`plugins`:** Foojay **toolchains resolver** convention (`org.gradle.toolchains.foojay-resolver-convention` **1.0.0**)
- **`dependencyResolutionManagement`:** `repositoriesMode = FAIL_ON_PROJECT_REPOS`; `google()` and `mavenCentral()` only
- **ObjectBox:** `resolutionStrategy` maps plugin id `io.objectbox` to `io.objectbox:objectbox-gradle-plugin`

**Links to:**
- All modules in the project
- Plugin management system

---

### 4. `gradle/libs.versions.toml`
**Location:** `/gradle/libs.versions.toml`

**Purpose:** Centralized version catalog for dependencies.

**Sections:**
- **[versions]:** Version numbers for libraries
- **[libraries]:** Library declarations with version references
- **[plugins]:** Plugin declarations

**Key Versions (see file for full list):**
- AGP: **8.7.3**
- Kotlin: **2.2.10**
- KSP: **2.3.2**
- Compose BOM: **2024.09.00**
- ObjectBox: **4.0.3**
- Play Services Location (catalog): **22.1.0** (app module may pin a different line version)
- Firebase / Embrace / New Relic: versions in `[versions]` for plugins and BOM usage in `app/build.gradle.kts`

**Links to:**
- `app/build.gradle.kts` - Referenced via `libs.*` aliases

---

### 5. `gradle.properties`
**Location:** `/gradle.properties`

**Purpose:** Project-wide Gradle settings.

**Key Properties:**
- **`org.gradle.jvmargs`** — `-Xmx2048m`, UTF-8, and multiple `--add-opens` flags for the JDK compiler modules (Kotlin daemon compatibility).
- **`kotlin.daemon.jvmargs`** — matching `--add-opens` set for the Kotlin daemon.
- **`android.useAndroidX=true`**
- **`kotlin.code.style=official`**
- **`android.nonTransitiveRClass=true`**
- **`android.defaults.buildfeatures.resvalues=false`**, **`android.uniquePackageNames=false`**, **`android.dependency.useConstraints=true`**
- **`android.r8.strictFullModeForKeepRules=false`**
- **`android.builtInKotlin=false`**, **`android.newDsl=false`** — project opts out of AGP built-in Kotlin behaviour to work cleanly with **KSP** / source sets.
- **`android.suppressUnsupportedCompileSdk=36`**

---

### 6. `AndroidManifest.xml`
**Location:** `/app/src/main/AndroidManifest.xml`

**Purpose:** Declares app components, permissions, and hardware requirements.

**Permissions Declared (summary):**
- **Capture / network:** `CAMERA`, `RECORD_AUDIO`, `INTERNET`, `ACCESS_NETWORK_STATE`
- **Location:** `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`
- **Storage / media (scoped by SDK):** `WRITE_EXTERNAL_STORAGE` (maxSdk 28), `READ_EXTERNAL_STORAGE` (maxSdk 32), `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO`
- **Foreground work:** `FOREGROUND_SERVICE`, `POST_NOTIFICATIONS`, `WAKE_LOCK`, `FOREGROUND_SERVICE_DATA_SYNC`, `FOREGROUND_SERVICE_CAMERA`, `FOREGROUND_SERVICE_MICROPHONE`, `FOREGROUND_SERVICE_LOCATION`

**Components Declared:**
- **Application:** `CameraApp` - Custom Application class
- **Activity:** `MainActivity` - Main launcher activity (portrait, picture-in-picture enabled)
- **Service:** `UploadService` - Foreground service (`dataSync`) for uploads
- **Service:** `RecordingService` - Foreground service (`camera|microphone|location`) for recording session

**Links to:**
- `CameraApp.kt` - Application class
- `MainActivity.kt` - Launcher activity
- `UploadService.kt` - Background service
- Resource files: `@string/app_name`, `@xml/backup_rules`

---

### 7. `proguard-rules.pro`
**Location:** `/app/proguard-rules.pro`

**Purpose:** R8 / ProGuard keep rules for **release** (minification is **enabled**).

**Status:** Keeps New Relic classes, Gson-related attributes, and model/network packages under `com.example.cameraaccess.data.model` and `com.example.cameraaccess.data.network` to avoid JSON breakage after obfuscation.

---

## Application Entry Points

### 1. `CameraApp.kt` (Application Class)
**Location:** `/app/src/main/java/com/example/cameraaccess/CameraApp.kt`

**Purpose:** Application-level initialization.

**Responsibilities:**
- Initialize ObjectBox database when app starts
- Called before any Activity creation

**Code Flow:**
```kotlin
override fun onCreate() {
    super.onCreate()
    ObjectBox.init(this)
    RetrofitClient.init(this)
}
```

**Links to:**
- `AndroidManifest.xml` - Declared as `android:name=".CameraApp"`
- `data/db/ObjectBox.kt` - Calls `ObjectBox.init()`
- `data/network/RetrofitClient.kt` - **`RetrofitClient.init`** stores application context for the auth interceptor

---

### 2. `MainActivity.kt`
**Location:** `/app/src/main/java/com/example/cameraaccess/MainActivity.kt`

**Purpose:** Main entry point of the application UI.

**Responsibilities:**
- Start **Embrace** and **New Relic** (APM) with your environment’s tokens as configured in source
- Set up Jetpack Compose **Material 3** theme and **`AppNavGraph`**
- **`onUserLeaveHint`:** when recording is active, enters **picture-in-picture** with a 9:16 aspect ratio

**Code Flow (conceptual):**
```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    Embrace.start(this)
    NewRelic.withApplicationToken("…").start(applicationContext)
    setContent {
        MaterialTheme {
            AppNavGraph()
        }
    }
}
```

**Links to:**
- `AndroidManifest.xml` - Declared as launcher activity
- `navigation/AppNavGraph.kt` - Contains navigation logic
- `CameraApp.kt` - **`RetrofitClient.init`** and **`ObjectBox.init`** run at application startup

---

## Data Layer

### ObjectBox Database

#### `ObjectBox.kt`
**Location:** `/app/src/main/java/com/example/cameraaccess/data/db/ObjectBox.kt`

**Purpose:** ObjectBox database singleton initialization.

**Configuration:**
- Singleton pattern for database access
- Initialized in `CameraApp.onCreate()`
- Generated `MyObjectBox` builder (auto-generated by ObjectBox)

**Usage:**
```kotlin
ObjectBox.store.boxFor(Entity::class.java)  // Get box for entity
```

**Links to:**
- `CameraApp.kt` - Initialized here
- All repositories that use ObjectBox
- Entity classes in `data/entities/`

---

### Entity Models

#### 1. `RecordingEntity.kt`
**Location:** `/app/src/main/java/com/example/cameraaccess/data/entities/RecordingEntity.kt`

**Purpose:** Database entity for video recordings.

**Fields:**
- `id: Long` - Primary key (auto-generated)
- `videoPath: String` - Path to video file
- `gpxPath: String` - Path to GPS tracking file
- `createdAt: Long` - Timestamp
- `blockId: Int` - Site block identifier
- `scanTypeId: Int` - Scan type identifier
- `rowWidth: Double` - Recording parameters
- `rowHeight: Double`
- `bayLength: Double`
- `addedMultiplier: Double`
- `duration: Long` - Video duration in milliseconds

**Usage:** Stored in ObjectBox, used by `RecordingRepository`.

**Links to:**
- `RecordingRepository.kt` - CRUD operations
- `VideoRecordingViewModel.kt` - Creates entities after recording
- `UploadService.kt` - Reads entities for upload

---

#### 2. `CameraSettingsEntity.kt`
**Location:** `/app/src/main/java/com/example/cameraaccess/data/entities/CameraSettingsEntity.kt`

**Purpose:** Store camera configuration settings.

**Fields:**
- `id: Long` - Primary key
- `previewWidth: Int` / `previewHeight: Int` - Preview resolution
- `videoWidth: Int` / `videoHeight: Int` - Video resolution
- `iso: Int` - ISO sensitivity (default 100)
- `fps: Int` - Frame rate (default 30)
- `exposureTimeNs: Long` - Exposure time in nanoseconds (default 4,000,000)

**Links to:**
- `SettingsRepository.kt` - Manages settings
- `SettingsViewModel.kt` - UI state management

---

## Repository Layer

### 1. `RecordingRepository.kt`
**Location:** `/app/src/main/java/com/example/cameraaccess/data/repositories/RecordingRepository.kt`

**Purpose:** Manage recording data persistence and retrieval.

**Key Methods:**
- `saveRecording()` - Save new recording with metadata
- `getAllRecordings()` - Retrieve all recordings
- `getLatestRecording()` - Get most recent recording
- `cleanupAfterSuccessfulUpload()` - Delete files and DB record after upload

**Database Operations:**
- Uses ObjectBox `Box<RecordingEntity>` for CRUD
- Calculates video duration using `MediaMetadataRetriever`
- File management (delete after upload)

**Links to:**
- `RecordingEntity.kt` - Entity model
- `ObjectBox.kt` - Database access
- `VideoRecordingViewModel.kt` - Saves recordings
- `UploadService.kt` - Retrieves and cleans up recordings

---

### 2. `AuthRepository.kt`
**Location:** `/app/src/main/java/com/example/cameraaccess/data/repositories/AuthRepository.kt`

**Purpose:** Handle authentication operations.

**Links to:**
- `ApiService.kt` - Login API call
- `TokenManager.kt` - Token storage
- `LoginViewModel.kt` - Authentication logic

---

### 3. `SettingsRepository.kt`
**Purpose:** Manage camera settings persistence.

**Links to:**
- `CameraSettingsEntity.kt` - Settings entity
- `SettingsViewModel.kt` - Settings UI

---

## Network Layer

### `RetrofitClient.kt`
**Location:** `/app/src/main/java/com/example/cameraaccess/data/network/RetrofitClient.kt`

**Purpose:** Retrofit HTTP client singleton with authentication interceptor.

**Configuration:**
- Base URL: `https://kiwifruitiq.tau.co.nz/`
- OkHttp client with auth interceptor
- Gson converter for JSON serialization

**Authentication:**
- Interceptor adds `Authorization: Bearer {token}` header
- Token retrieved from `TokenManager`
- On **HTTP 401**, the client **clears** stored tokens (global logout signal; navigation to login is handled by app flow when the next screen checks the token)

**Initialization:**
- Called from **`CameraApp.onCreate()`** (application startup), not from `MainActivity`

**Links to:**
- `CameraApp.kt` - **`RetrofitClient.init`**
- `ApiService.kt` - Interface implementation
- `TokenManager.kt` - Token retrieval

---

### `ApiService.kt`
**Location:** `/app/src/main/java/com/example/cameraaccess/data/network/ApiService.kt`

**Purpose:** Retrofit interface defining API endpoints.

**Endpoints:**
```kotlin
POST /api/token                           - Login
POST /api/app/device                      - Register device
GET  /api/app/site-block                  - Get site blocks
POST /api/app/site-block                  - Create site block
POST /api/app/multi-video                 - Start multipart video upload
PUT  /api/app/multi-video                 - Complete multipart upload
```

**Request/Response Models:**
- `LoginRequest` / `LoginResponse`
- `SiteBlockCreateRequest` / `SiteBlockCreateResponse`
- `MultiVideoStartRequest` / `MultiVideoStartResponse`
- `MultiVideoCompleteRequest`

**Links to:**
- `RetrofitClient.kt` - Creates service instance
- All repositories making API calls
- `UploadService.kt` - Uses for multipart upload

---

## ViewModel Layer

### 1. `VideoRecordingViewModel.kt`
**Location:** `/app/src/main/java/com/example/cameraaccess/viewmodel/VideoRecordingViewModel.kt`

**Purpose:** Manage video recording state and logic.

**Key Responsibilities:**
- Control `Camera2Controller` for recording
- Manage GPS tracking (GPX file creation)
- Track recording duration
- Handle pause/resume
- Save recordings to repository

**State Management:**
- `recording: StateFlow<Boolean>` - Recording status
- `paused: StateFlow<Boolean>` - Pause status
- `recordingDuration: StateFlow<Long>` - Duration in milliseconds
- `lastSegment: StateFlow<RecordingSegment?>` - Last recorded segment

**Methods:**
- `startRecording()` - Begin video recording with GPS
- `stopRecording()` - Stop and save recording
- `togglePauseResume()` - Pause/resume recording
- `startGpxLogging()` - Begin GPS tracking
- `stopGpxLogging()` - Stop GPS and save GPX file

**Links to:**
- `Camera2Controller.kt` - Camera operations
- `RecordingRepository.kt` - Save recordings
- `RecordingScreen.kt` (UI) - Consumes state
- `FusedLocationProviderClient` - GPS tracking

---

### 2. `LoginViewModel.kt`
**Purpose:** Handle authentication flow.

**Links to:**
- `AuthRepository.kt` - Login API call
- `TokenManager.kt` - Save tokens
- `LoginScreen.kt` - UI

---

### 3. `SettingsViewModel.kt`
**Purpose:** Manage camera settings UI state.

**Links to:**
- `SettingsRepository.kt` - Persist settings
- `SettingsScreen.kt` - UI

---

### 4. `DashboardViewModel.kt`
**Purpose:** Manage dashboard screen state.

**Links to:**
- `DashboardRepository.kt` - Fetch recordings
- `DashboardScreen.kt` - UI

---

## UI Layer (Jetpack Compose)

### Navigation

#### `AppNavGraph.kt`
**Location:** `/app/src/main/java/com/example/cameraaccess/navigation/AppNavGraph.kt`

**Purpose:** Define navigation routes and flow.

**Navigation Graph (primary user path):**
```
login → dashboard → create_site_block → record
  ↑         │
  └─────────┘ (after logout from dashboard)
```

**Routes:**
1. **"login"** - `LoginScreen`
   - Start destination if no token exists
   - Navigates to "dashboard" on success

2. **"dashboard"** - `DashboardScreen`
   - Start destination if token exists
   - Shows list of recordings
   - Navigate to "create_site_block" to start capture

3. **"create_site_block"** - `CreateSiteBlockScreen`
   - Create/select site block
   - Navigates directly to **"record"** (no mandatory stop on **"settings"**)

4. **"settings"** - `SettingsScreen`
   - Still registered on the `NavHost` (e.g. preview / future entry points); **no in-app `navigate("settings")` from the main graph at present**. Exposure, ISO, and related controls are also driven from the recording flow via **`SettingsViewModel`** on **`RecordingScreen`**.

5. **"record"** - `RecordingScreen`
   - Main recording interface; can navigate back to **"dashboard"**

**Token Check:**
- Uses `TokenManager` to determine start destination
- Logout clears token and navigates to login

**Links to:**
- `MainActivity.kt` - Entry point
- All screen composables
- `TokenManager.kt` - Authentication state

---

### Screen Components

#### 1. `LoginScreen.kt`
**Purpose:** User authentication interface.

**Features:**
- Username/password input
- Login API call
- Token storage
- Navigation to dashboard on success

**Links to:**
- `LoginViewModel.kt` - Business logic
- `AppNavGraph.kt` - Navigation

---

#### 2. `DashboardScreen.kt`
**Purpose:** Main screen showing recording list.

**Features:**
- Display all recordings
- Start new capture button
- Logout functionality
- Upload status for recordings

**Links to:**
- `DashboardViewModel.kt` - State management
- `RecordingRepository.kt` - Fetch recordings
- `UploadService.kt` - Upload triggers

---

#### 3. `RecordingScreen.kt`
**Purpose:** Video recording interface.

**Features:**
- Camera preview (TextureView), picture-in-picture while recording
- Record/pause/stop controls
- **On-device kiwi detection** (TensorFlow Lite) with overlay; exposure loop ties brightness metering **before vs after** fruit is identified (see [Kiwi detection and auto-exposure](#kiwi-detection-and-auto-exposure-brightness))
- GPS status indicator
- Permission handling
- Recording duration display

**Links to:**
- `VideoRecordingViewModel.kt` - Recording logic
- `Camera2Controller.kt` - Camera operations
- `CameraSelector.kt` - Camera selection

---

#### 4. `SettingsScreen.kt`
**Purpose:** Camera configuration interface.

**Features:**
- Resolution selection
- ISO settings
- FPS selection
- Exposure time adjustment

**Links to:**
- `SettingsViewModel.kt` - Settings management
- `CameraSettingsEntity.kt` - Persistence

---

## Camera Functionality

### `RecordingService.kt`
**Location:** `/app/src/main/java/com/example/cameraaccess/service/RecordingService.kt`

**Purpose:** Foreground **Android `Service`** used during the capture session. It owns or coordinates **location** (including fused updates), **sensor** heading, **GNSS** callbacks where used, **GPX** file writing, and exposes **`Camera2Controller`** to the rest of the app via a **binder**. **`VideoRecordingViewModel`** binds to this service and issues intent actions (start preview, start/stop recording, tear down).

**Links to:**
- `AndroidManifest.xml` — `foregroundServiceType="camera|microphone|location"`
- `VideoRecordingViewModel.kt` — binds and sends commands
- `Camera2Controller.kt` — camera pipeline

### `Camera2Controller.kt`
**Location:** `/app/src/main/java/com/example/cameraaccess/controller/Camera2Controller.kt`

**Purpose:** Wrapper around Android Camera2 API for video recording (singleton accessed from **`RecordingService`** and related UI flows).

**Key Features:**
- Camera device management
- Preview session
- Video recording with MediaRecorder
- **YUV `ImageReader` analysis stream** feeding **`KiwiDetectionProcessor`** (TensorFlow Lite); results sent via **`onDetectionUpdate`**
- ISO and exposure control (`updateExposure` for manual sensor AE when allowed)
- Pause/resume recording support

**Methods:**
- `startPreview()` - Start camera preview
- `startRecording()` - Begin video recording
- `stopRecording()` - Stop and return file path
- `pauseRecording()` - Pause recording (API 24+)
- `resumeRecording()` - Resume recording

**File Management:**
- Saves videos to `Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)`
- Filenames: `VID_yyyyMMdd_HHmmss.mp4`

**Links to:**
- `VideoRecordingViewModel.kt` - Called by ViewModel
- `RecordingScreen.kt` - UI integration via TextureView

### Kiwi detection and auto-exposure (brightness)

This describes how **kiwi fruit** is detected on-device and how **brightness / exposure** is chosen **before** the model is confident vs **after** kiwis are identified.

#### Detection pipeline (`KiwiDetectionProcessor` + `Camera2Controller`)

- **Model:** TensorFlow Lite asset **`model-hdr-320.tflite`**, loaded by **`KiwiDetectionProcessor`** (`processing/KiwiDetectionProcessor.kt`). Inference runs on **YUV_420_888** frames from an **`ImageReader`** analysis surface sized for the preview/recording pipeline (see comments in **`Camera2Controller`** about keeping analysis **8-bit YUV** while **10-bit HDR** may apply only to the encoder surface).
- **Class of interest:** Kiwi is **`kiwiClassIndex = 1`**. Detections are filtered, scored, and **NMS**-deduplicated (`detectionThreshold`, `nmsIouThreshold`, `maxDetections`).
- **Outputs (`DetectionResult`):**
  - **`status`:** e.g. **`"Searching"`** when there are no valid boxes, **`"Identified"`** when kiwis are found.
  - **`confidenceScore`:** confidence-derived signal (scaled from model output; used by UI and exposure logic).
  - **`avgFrameLuma`:** mean **Y (luma)** over the analysis frame — used when the fruit is **not** yet driving metering (“full scene” brightness).
  - **`objectLuma`:** mean luma **inside detected bounding boxes** — used once fruit is considered **identified** for object-centric metering.
- **Delivery to UI:** `Camera2Controller` invokes **`onDetectionUpdate`** with each processed frame. **`VideoRecordingViewModel`** assigns that callback and **`processAndSmoothResult`**: it applies an **exponential moving average** (**`alpha = 0.15f`**) to smooth confidence and both lumas before publishing **`detectionResult`** (reduces flicker).

#### When the app treats kiwis as “identified” (exposure metering switch)

On **`RecordingScreen`**, a background **`LaunchedEffect`** loop (~30 Hz while preview or recording is active) decides the **brightness sample** for auto-exposure:

| Phase | Condition (simplified) | Brightness value used for metering |
|--------|-------------------------|-------------------------------------|
| **Before / not yet identified** | No stable object signal: `confidenceScore ≤ 3%` **or** `objectLuma == 0` | **Full-frame / scene luma:** prefer **`avgFrameLuma`** from the same YUV analysis path as detection; if that is zero (e.g. some HDR preview surfaces), fall back to a small **TextureView `getBitmap`** and average RGB→luma. |
| **After identified** | `confidenceScore > 3%` **and** `objectLuma > 0` | **Object luma:** **`objectLuma`** — brightness inside the detected kiwi regions only. |

So **before** kiwi detection is confident, exposure tracks the **whole frame** (or bitmap fallback). **After** identification, exposure tracks **kiwi pixels** so the fruit stays correctly exposed even if the background is bright or dark.

#### Auto-exposure / “brightness” adjustment (same loop)

When **manual sensor control is allowed** (`runManualAe` — not **scene-based HDR**, where manual ISO/shutter must stay off per `Camera2Controller`):

- **Target:** default **`targetStops = 1.5`** relative to **`brightnessBase = 64.0`** (log₂ scale vs that base luma).
- **Measured stops:** `currentStops = log₂(avgVal / brightnessBase)` where **`avgVal`** is the table above (scene vs object luma).
- **Correction:** error vs target drives a multiplicative **factor** (clamped), with an **anti-saturation** pull-down when luma is near max.
- **ISO / shutter strategy:** adjust **exposure power** = `ISO × (1 / shutterDenom)`; prefer **fast shutter** within `[minDenom, maxDenom]` and clamp **ISO** in `[minIso, maxIso]`, then **smooth** ISO and shutter with a **0.7 / 0.3** blend toward the goal for stable video.
- **Application:** `Camera2Controller.updateExposure(iso, exposureTimeNs)` applies **`SENSOR_SENSITIVITY`** and **`SENSOR_EXPOSURE_TIME`**; UI chips and **`SettingsViewModel`** stay in sync for display and persistence.

**Files to read in order:** `KiwiDetectionProcessor.kt` → `Camera2Controller.kt` (ImageReader + `onDetectionUpdate`) → `VideoRecordingViewModel.kt` (`processAndSmoothResult`) → `RecordingScreen.kt` (brightness source branch + AE loop).

---

### `CameraSelector.kt`
**Purpose:** Utility for camera selection (front/back/ultra-wide).

**Links to:**
- `RecordingScreen.kt` - Camera selection UI

---

## File Storage & Upload

### `UploadService.kt`
**Location:** `/app/src/main/java/com/example/cameraaccess/service/UploadService.kt`

**Purpose:** Foreground service for uploading videos and GPX files to server.

**Service Type:** `dataSync` (Foreground Service)

**Upload Process:**
1. **Start Multi-Video API Call**
   - POST `/api/app/multi-video` with metadata
   - Receive presigned S3 URLs for multipart upload
   - Receive GPX presigned URL
   - Receive CSV presigned URL

2. **Multipart Video Upload**
   - Split video into 16MB parts
   - Upload each part to S3 presigned URL
   - Collect ETags for each part

3. **Complete Multi-Video**
   - PUT `/api/app/multi-video` with all ETags
   - Finalize upload on server

4. **GPX Upload**
   - Upload GPX file to presigned URL
   - Include S3 tagging headers

5. **CSV Upload**
   - Upload CSV file to presigned URL

6. **Cleanup**
   - Delete local video and GPX files
   - Delete local CSV file
   - Remove database record

**Features:**
- Foreground notification during upload
- Wake lock to prevent device sleep
- Progress updates via `UploadStateBus`
- Error handling and logging

**Links to:**
- `RecordingRepository.kt` - Fetch recordings to upload
- `ApiService.kt` - API calls
- `UploadStateBus.kt` - Progress notifications
- `AndroidManifest.xml` - Service declaration

---

### `UploadStateBus.kt`
**Purpose:** Event bus for upload progress and status.

**Functions:**
- `updateProgress(progress: Float)` - 0.0 to 1.0
- `finishSuccess()` - Upload completed
- `finishError(error: String)` - Upload failed

**Links to:**
- `UploadService.kt` - Emits events
- UI components - Subscribe to updates

---

## Utilities

### `TokenManager.kt`
**Location:** `/app/src/main/java/com/example/cameraaccess/utils/TokenManager.kt`

**Purpose:** Manage authentication tokens using SharedPreferences.

**Storage:**
- **Primary:** `EncryptedSharedPreferences` (AndroidX Security Crypto) file **`auth_prefs_encrypted`**, keys **`access_token`**, **`refresh_token`**
- **Legacy:** plain `SharedPreferences` **`auth_prefs`** — on first launch, tokens are **migrated** from plain to encrypted storage and the old keys are cleared

**Methods:**
- `saveAccessToken(token: String)`
- `saveRefreshToken(token: String)`
- `getAccessToken(): String?`
- `clear()` - Clear all tokens (logout)

**Links to:**
- `AuthRepository.kt` - Save tokens after login
- `RetrofitClient.kt` - Retrieve token for API calls
- `AppNavGraph.kt` - Check token for navigation
- `LoginViewModel.kt` - Token storage

---

## Monitoring & observability

The current process includes active runtime monitoring and issue instrumentation across key flows.

### `AppHealthMonitor.kt`
**Location:** `/app/src/main/java/com/example/cameraaccess/monitoring/AppHealthMonitor.kt`

**Purpose:** Centralized health/event reporting utility used across startup, auth, recording, upload, and token paths.

**Current usage highlights:**
- Captures structured issue reports and key operational metrics
- Emits health and flow events into Firebase Analytics
- Forwards handled exceptions/metrics to New Relic integration points
- Supports startup/device health snapshot reporting used by app initialization paths

**Linked flows/files (examples):**
- `CameraApp.kt`, `MainActivity.kt`
- `RetrofitClient.kt`, `AuthRepository.kt`, `TokenManager.kt`
- `Camera2Controller.kt`, `RecordingService.kt`, `RecordingScreen.kt`
- `UploadService.kt`, `UploadStateBus.kt`, `RecordingRepository.kt`

---

### `NetworkConnectivityObserver.kt`
**Purpose:** Observe network connectivity state.

**Links to:**
- UI components that need connectivity status

---

### `DeviceManager.kt`
**Purpose:** Device information utilities.

**Links to:**
- `ApiService.kt` - Device registration

---

## Permissions & Security

### Permissions (from AndroidManifest.xml)

| Permission | Purpose | When Required |
|-----------|---------|---------------|
| `CAMERA` | Video recording | At recording start |
| `RECORD_AUDIO` | Audio capture | At recording start |
| `ACCESS_FINE_LOCATION` | GPS tracking | At recording start |
| `ACCESS_COARSE_LOCATION` | Location fallback | At recording start |
| `INTERNET` | Network access | Always |
| `ACCESS_NETWORK_STATE` | Connectivity | As needed |
| `WRITE_EXTERNAL_STORAGE` / `READ_EXTERNAL_STORAGE` | Legacy storage | Capped by `maxSdkVersion` in manifest |
| `READ_MEDIA_*` | Media read (Android 13+) | When accessing gallery / media |
| `FOREGROUND_SERVICE` | Foreground services | Service start |
| `POST_NOTIFICATIONS` | Service / upload notifications | Android 13+ |
| `FOREGROUND_SERVICE_DATA_SYNC` | Upload service type | `UploadService` |
| `FOREGROUND_SERVICE_CAMERA` / `MICROPHONE` / `LOCATION` | Recording service types | `RecordingService` |
| `WAKE_LOCK` | Keep device awake during upload | Upload path |

### Permission Handling

Permissions are requested at runtime in:
- `RecordingScreen.kt` - Uses `ActivityResultLauncher`
- `VideoRecordingViewModel.kt` - Checks permissions before recording

---

## Dependencies & Libraries

### Core Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| **AndroidX Core KTX** | 1.10.1 | Kotlin extensions |
| **Lifecycle Runtime KTX** | 2.6.1 | Lifecycle management |
| **Activity Compose** | 1.8.0 | Compose integration |
| **Compose BOM** | 2024.09.00 | Compose version management |
| **Compose UI** | (from BOM) | UI components |
| **Material3** | (from BOM) | Material Design 3 |
| **Navigation Compose** | 2.7.3 | Navigation |
| **Retrofit** | 2.9.0 | HTTP client |
| **OkHttp** | 4.12.0 | HTTP client implementation |
| **Gson** | (via Retrofit) | JSON serialization |
| **ObjectBox Kotlin** | 4.0.3 | Database |
| **Play Services Location** | 21.3.0 | GPS/Location |
| **Media3 ExoPlayer** | 1.3.1 | Video playback |
| **TensorFlow Lite** | 2.14.0 | On-device inference (e.g. green detection) |
| **AndroidX Security Crypto** | 1.1.0 | Encrypted token storage |
| **Fragment KTX** | 1.8.5 | Fragment helpers |
| **Firebase BOM** | 34.9.0 (module) | Analytics, Crashlytics, Performance |
| **Embrace Android SDK** | 8.1.0 | Session / performance telemetry |
| **New Relic Android Agent** | 7.6.15 | APM (started from `MainActivity`) |

### Development Dependencies

- **JUnit 4** - Unit testing
- **AndroidX Test JUnit** - Android unit testing
- **Espresso** - UI testing
- **Compose UI Test** - Compose UI testing

---

## Build Configuration

### Build Variants

**Debug:**
- Default debug settings (no custom release signing).

**Release:**
- **Minification:** enabled (`isMinifyEnabled = true`)
- **Resource shrinking:** enabled (`isShrinkResources = true`)
- **ProGuard / R8:** `proguard-android-optimize.txt` + `proguard-rules.pro`
- **NDK:** `debugSymbolLevel = "FULL"` for native debug symbols
- **Signing:** currently wired to the **debug** keystore (`signingConfig = signingConfigs.getByName("debug")`) — change before production release

### Compilation Settings

- **Java Version:** 11
- **Kotlin JVM Target:** 11
- **Compose Compiler:** Kotlin Compose compiler plugin (Kotlin **2.2.10** in this repo)

---

## Key Features & Workflows

### 1. Authentication Flow

```
User Opens App
    ↓
TokenManager checks for token
    ↓
┌───────────┬───────────┐
│ Has Token │ No Token  │
└─────┬─────┴─────┬─────┘
      │           │
Dashboard    Login Screen
      │           │
      │      User enters credentials
      │           │
      │      LoginViewModel calls AuthRepository
      │           │
      │      API: POST /api/token
      │           │
      │      TokenManager saves token
      │           │
      └───────→ Dashboard
```

**Files Involved:**
- `MainActivity.kt` → `AppNavGraph.kt` (token check)
- `LoginScreen.kt` → `LoginViewModel.kt` → `AuthRepository.kt`
- `TokenManager.kt` (storage)

---

### 2. New Scan + Recording Flow

```
User on Dashboard
    ↓
Tap "New Scan"
    ↓
DashboardViewModel.startNewScan()
    ↓
If online: fetch latest site/block config (fallback to cache if available)
If offline: continue using cached data path
    ↓
Navigate to "create_site_block"
    ↓
Create/Select Site Block metadata (site, block, scan type, row/bay values)
    ↓
CreateSiteBlockViewModel.submit() saves metadata into ApiCache
    ↓
Navigate to "record"   (settings route optional / parallel via SettingsViewModel on screen)
    ↓
┌─────────────────────────────┐
│ RecordingScreen             │
│ - Request permissions       │
│ - Bind/start RecordingService│
│ - Camera preview (TextureView)│
│ - User clicks Record        │
└───────────┬─────────────────┘
            ↓
VideoRecordingViewModel (binds to RecordingService, sends intents)
    ↓
┌─────────────────────────────┐
│ RecordingService            │
│ - Camera2Controller singleton│
│ - Location / sensors / GPX  │
│ - Foreground notification   │
└───────────┬─────────────────┘
            ↓
Start GPS / GNSS-related logging as implemented in service
    ↓
GPX file creation
    ↓
User stops recording
    ↓
Stop capture via service / controller
    ↓
RecordingRepository.saveRecording()
    ↓
RecordingEntity saved to ObjectBox
    ↓
Return to Dashboard (recording appears in list)
```

**Files Involved:**
- `DashboardViewModel.kt` (startNewScan + cache-aware navigation)
- `CreateSiteBlockScreen.kt` / `CreateSiteBlockViewModel.kt` (metadata form + submit cache)
- `RecordingScreen.kt` (UI)
- `VideoRecordingViewModel.kt` (logic, service binding)
- `RecordingService.kt` (foreground capture session)
- `Camera2Controller.kt` (camera)
- `RecordingRepository.kt` (persistence)
- `RecordingEntity.kt` (data model)

---

### 3. Upload Flow

```
User selects one or multiple recordings on Dashboard
    ↓
DashboardViewModel queues IDs + starts UploadService intent(s)
    ↓
UploadService enqueues IDs into Channel<Long> (sequential worker)
    ↓
┌─────────────────────────────┐
│ UploadService               │
│ - Create foreground notif   │
│ - Acquire wake lock         │
└───────────┬─────────────────┘
            ↓
Fetch RecordingEntity from ObjectBox
    ↓
API: POST /api/app/multi-video
    ↓
Receive:
- S3 presigned URLs for video parts
- GPX presigned URL
- CSV presigned URL
- Upload metadata
    ↓
┌─────────────────────────────┐
│ Multipart Video Upload      │
│ - Split video (16MB parts)  │
│ - Upload each part to S3    │
│ - Collect ETags             │
└───────────┬─────────────────┘
            ↓
API: PUT /api/app/multi-video (with ETags)
    ↓
Upload GPX file to presigned URL
    ↓
Upload CSV file to presigned URL
    ↓
RecordingRepository.cleanupAfterSuccessfulUpload()
    ↓
- Delete local video file
- Delete local GPX file
- Delete local CSV file
- Remove RecordingEntity from ObjectBox
    ↓
UploadStateBus.finishSuccess()
    ↓
UI updates (recording removed from list)
```

**Files Involved:**
- `DashboardScreen.kt` (triggers upload)
- `UploadService.kt` (upload logic)
- `ApiService.kt` (API calls)
- `RecordingRepository.kt` (cleanup)
- `UploadStateBus.kt` (progress events)

---

### 4. Settings Persistence Flow

```
User adjusts camera parameters (SettingsScreen and/or RecordingScreen via SettingsViewModel)
    ↓
SettingsViewModel updates state
    ↓
Save to SettingsRepository
    ↓
SettingsRepository saves to ObjectBox (CameraSettingsEntity)
    ↓
Settings persist across app restarts
    ↓
When recording starts, settings loaded and applied to Camera2Controller (via RecordingService / ViewModel path)
```

**Files Involved:**
- `SettingsScreen.kt` (optional route UI)
- `RecordingScreen.kt` (loads settings into UI state for capture)
- `SettingsViewModel.kt` (state)
- `SettingsRepository.kt` (persistence)
- `CameraSettingsEntity.kt` (data model)
- `VideoRecordingViewModel.kt` / **`RecordingService`** (apply settings during capture)

---

## Important File Relationships

### Initialization Chain

```
1. AndroidManifest.xml
   ↓ (declares CameraApp as application class)
2. CameraApp.onCreate()
   ↓ (initializes ObjectBox + RetrofitClient context)
3. ObjectBox.init() + RetrofitClient.init()
   ↓
4. MainActivity.onCreate()
   ↓ (Embrace + New Relic, setContent)
5. AppNavGraph()
   ↓ (checks token, navigates to screen)
```

### Data Flow (Recording)

```
RecordingScreen (UI)
    ↓ (user interaction)
VideoRecordingViewModel
    ↓ (binds / intents)
RecordingService → Camera2Controller + location/sensors
    ↓ (creates files)
RecordingRepository.saveRecording()
    ↓ (persists)
ObjectBox (RecordingEntity)
```

### Data Flow (Upload)

```
DashboardScreen (UI)
    ↓ (triggers upload)
UploadService (Intent)
    ↓ (fetches)
RecordingRepository.getRecordingById()
    ↓ (retrieves)
ObjectBox (RecordingEntity)
    ↓ (uploads)
ApiService (multipart upload)
    ↓ (after success)
RecordingRepository.cleanupAfterSuccessfulUpload()
    ↓ (deletes files & record)
File System + ObjectBox
```

---

## Diagram Guide: New Scan to Upload

Use this section as a blueprint to create a full process flow chart with step screenshots and notes.

### 1) Recommended Diagram Scope

Create one primary flow from:

**New Scan / New Recording**
→ **Capture session**
→ **Save local artifacts**
→ **Upload trigger**
→ **Upload pipeline**
→ **Success cleanup / failure retry**

If the chart gets crowded, split it into 3 swimlanes:

- **User actions (UI screens)**
- **App logic (ViewModel + Services)**
- **Storage/Network (ObjectBox + Files + API)**

### 2) Main Flow Nodes (Use These Labels)

1. **Open recording screen**
   - Screen: `RecordingScreen`
   - Notes: User starts a new scan/recording workflow.

2. **Validate permissions and device state**
   - Components: `VideoRecordingViewModel`, permission checks, location/camera readiness
   - Notes: Branch if permission denied (show error / stop flow).

3. **Start recording service**
   - Component: `RecordingService`
   - Notes: Foreground service starts camera + sensor capture pipeline.

4. **Capture media and telemetry**
   - Components: `Camera2Controller`, GPS/sensor collectors
   - Notes: Video and tracking data are collected in parallel while recording is active.

5. **Stop recording**
   - Trigger: User stops recording
   - Notes: Finalize video file and metadata.

6. **Persist recording entry**
   - Components: `RecordingRepository.saveRecording()`, `RecordingEntity`
   - Storage: ObjectBox + file system
   - Notes: Recording becomes visible for later upload.

7. **Navigate to dashboard / upload queue**
   - Screen: `DashboardScreen`
   - Notes: User can review pending recordings.

8. **Trigger upload**
   - Component: `UploadService` (Intent-driven)
   - Notes: Upload can be per-item or batch (based on current app behavior/config).

9. **Fetch local recording data**
   - Components: `RecordingRepository.getRecordingById()`, ObjectBox
   - Notes: Prepare local files and metadata before network call.

10. **Perform multipart upload**
    - Components: `ApiService`, repository/service upload logic
    - Notes: Upload part size is 16MB; network failures should branch to retry state.

11. **Handle upload response**
    - Success path: Continue to cleanup
    - Failure path: Mark as failed/pending, keep local data for retry

12. **Cleanup after successful upload**
    - Component: `RecordingRepository.cleanupAfterSuccessfulUpload()`
    - Notes: Remove uploaded files + DB entry to prevent duplicate uploads.

### 3) Decision Branches You Should Draw

Add diamond nodes for these checks:

- **Permissions granted?**
  - No -> show permission guidance / block recording
  - Yes -> continue
- **Recording start successful?**
  - No -> show start error
  - Yes -> continue
- **Upload success?**
  - No -> retry/backoff/manual retry path
  - Yes -> cleanup and mark complete

### 4) Screenshot/Image Checklist for the Diagram

Capture and place small images beside each major phase:

- Recording start screen (`RecordingScreen`) before user taps record
- Active recording state (timer/recording indicators visible)
- Stop/finish state after capture
- Dashboard list with pending upload item
- Upload in progress state (if visible in UI)
- Upload success state OR item removed from pending list
- Upload failure/retry state (if available)

Tip: keep all screenshots from the same device size and orientation for consistent diagram visuals.

### 5) Important Notes to Place Beside Steps

Use short sticky-note style callouts in the chart:

- **Foreground services:** recording and upload run in services (`RecordingService`, `UploadService`)
- **Local-first reliability:** data is saved locally before/while awaiting network upload
- **Data safety:** successful upload triggers explicit cleanup; failed upload retains local data
- **Storage split:** metadata in ObjectBox, media files in file system
- **Token/security:** auth token is stored in encrypted preferences

### 6) Suggested Legend (for Readability)

- **Rounded rectangle:** user/UI step
- **Rectangle:** app/service processing step
- **Cylinder:** local storage (ObjectBox/files)
- **Cloud:** API/network
- **Diamond:** decision/condition
- **Red border:** failure/error branch
- **Green border:** success path

### 7) Draft Flow Text (Quick Copy for Diagram Tool)

```
Open RecordingScreen
 -> Permissions granted?
   -> No: Show permission error/help
   -> Yes: Start RecordingService
 -> Capture video + GPS/sensors
 -> User stops recording
 -> Save RecordingEntity + local files
 -> Show item in DashboardScreen
 -> User triggers upload
 -> UploadService loads recording from ObjectBox
 -> Multipart upload via ApiService
 -> Upload success?
   -> No: Mark pending/failed for retry
   -> Yes: Cleanup local files + DB record
 -> Done
```

---

## Summary of Key Settings

### Critical Configuration Values

1. **Application ID / Gradle namespace:** `com.tau.research`
2. **Kotlin package:** `com.example.cameraaccess`
3. **Min SDK:** 31 (Android 12)
4. **Target SDK:** 36
5. **Base API URL:** `https://kiwifruitiq.tau.co.nz/`
6. **Database:** ObjectBox (local NoSQL), **KSP** code generation
7. **Token Storage:** **EncryptedSharedPreferences** (`auth_prefs_encrypted`), with migration from legacy `auth_prefs`
8. **Video Storage:** External Storage Movies directory
9. **Upload Part Size:** 16MB (multipart upload)
10. **Camera Settings Defaults:**
   - Resolution: 1920x1080
   - ISO: 100
   - FPS: 30
   - Exposure: 4,000,000ns

### Important Paths

- **Videos:** `Environment.DIRECTORY_MOVIES/VID_*.mp4`
- **GPX Files:** Application files directory
- **Database:** ObjectBox (managed internally)
- **Settings:** `CameraSettingsEntity` in ObjectBox; tokens in **EncryptedSharedPreferences**

---

## Development Notes

Gradle setup, Firebase, observability, and CLI commands are documented under **[Development & release process](#development--release-process)**.

### Compose Preview

Compose preview tools are on the debug classpath via **`androidx.compose.ui:ui-tooling`** (`@Preview`).

### Testing

- Unit tests: `app/src/test/java/`
- Instrumented tests: `app/src/androidTest/java/`
- UI tests: Compose UI Test (`ui-test-junit4`)

---

## Conclusion

This Android application is a comprehensive field recording solution with:
- **Modern UI:** Jetpack Compose with Material Design 3
- **Robust Architecture:** MVVM with clear separation of concerns
- **Local Storage:** ObjectBox for efficient data persistence; **encrypted** auth token storage
- **Cloud Integration:** Multipart upload to S3 via REST API
- **GPS Tracking:** GPX generation during capture via **`RecordingService`**
- **Foreground processing:** **`RecordingService`** for capture; **`UploadService`** for uploads
- **Observability:** Embrace, New Relic, and Firebase (as configured in the project)

The codebase separates UI, ViewModels, repositories, services, and network layers to keep behaviour testable and easier to evolve.

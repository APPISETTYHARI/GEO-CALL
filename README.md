# GeoCall — Android Auto-Call Location App

A premium, fully featured Android application that **automatically places a phone call** (or triggers a manual call overlay) when you enter a set geographic radius. The app runs a persistent Kotlin-based foreground service for continuous GPS tracking and utilizes a glassmorphic Web-based UI built with Leaflet for map interactions.

---

## 📸 App Architecture & Flow

```
┌─────────────────────────────────────┐
│          MainActivity.kt            │
│  ┌───────────────────────────────┐  │
│  │   WebView (geocall.html)     │  │
│  │   Leaflet Map + Controls     │  │
│  └──────────┬────────────────────┘  │
│         NativeBridge (JS ↔ Kotlin)  │
└──────────────┬──────────────────────┘
               │ startForegroundService()
┌──────────────▼──────────────────────┐
│      GeoWatchService.kt            │
│  ✦ FusedLocationProvider (3s GPS)  │
│  ✦ Haversine distance check        │
│  ✦ Vibration + TTS announcement    │
│  ✦ Intent.ACTION_CALL (auto-dial)  │
│  ✦ Persistent notification + HUD   │
│  ✦ WakeLock (CPU stays active)     │
└─────────────────────────────────────┘
```

---

## ✨ Key Features

1. **Persistent Location Tracking**
   - Utilizes `FusedLocationProviderClient` for high-accuracy GPS coordinates every 3 seconds.
   - Runs as a **Foreground Service** to prevent Android system sleep termination.
   - Acquires a `WakeLock` so tracking remains active even when the screen is off.

2. **Auto Call vs. Manual Modes**
   - **Auto Call ON**: Automatically places the phone call via `Intent.ACTION_CALL` after entering the geofence radius.
   - **Auto Call OFF**: Vibrates, plays TextToSpeech alerts, and presents a visual "Call Now" overlay in the map HUD.

3. **Premium Map Interface**
   - Interactive OpenStreetMap view with a dark, Uber-like CartoDB theme.
   - Search address functionality using Nominatim geocoding.
   - Live simulated walking mode (walk virtual marker to target destination).
   - Dynamic tracking HUD showing nearest contact, distance remaining, and journey progress bar.

4. **Speech & Haptics**
   - Generates haptic wave vibration patterns when entering geofence boundaries.
   - Speaks context-aware announcements (e.g. *"Arriving near Ajay. Auto calling now."*) using Android TextToSpeech.

5. **Launcher Icon**
   - Features a custom glowing app logo blending a location pin and phone graphic on a dark rounded metallic square.

---

## 📂 Project Structure

```
d:\geo-call\
├── android-app/                       # Root Android Studio project
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── AndroidManifest.xml   # Permission declarations & service registry
│   │   │   ├── assets/
│   │   │   │   └── geocall.html       # Web interface (Leaflet Map, CSS styling, UI logic)
│   │   │   ├── java/com/example/geocall/
│   │   │   │   ├── MainActivity.kt    # WebView host, Permission handler & JS NativeBridge
│   │   │   │   ├── GeoWatchService.kt # Foreground service (GPS, TTS, AutoCall logic)
│   │   │   │   └── GeofenceData.kt    # Geofence model & JSON parsing
│   │   │   └── res/                   # App resources, customized densities launcher icons
│   │   └── build.gradle.kts           # Gradle module build configuration
│   └── settings.gradle.kts            # Project settings
└── README.md                          # Project overview & documentation
```

---

## 🚀 How to Build & Run

### Prerequisites
- Android SDK installed.
- Android device connected via USB with **USB Debugging** enabled in Developer Options.

### 1. Build and Install using Gradle Wrapper
Run the following command in the `android-app/` directory:
```powershell
# Windows
.\gradlew.bat installDebug
```

### 2. Launch the Application via ADB
If the app does not open automatically, launch it using:
```powershell
& "C:\Users\ajay6\AppData\Local\Android\Sdk\platform-tools\adb.exe" shell am start -n com.example.geocall/.MainActivity
```

---

## ⚙️ Required Permissions

To run correctly, the app requests the following system permissions:

| Permission | Purpose |
| :--- | :--- |
| `CALL_PHONE` | Places calls directly without redirecting to the dialer interface. |
| `ACCESS_FINE_LOCATION` | Precise GPS-based location tracking. |
| `ACCESS_BACKGROUND_LOCATION` | Track location when screen is off or app minimized. |
| `FOREGROUND_SERVICE_LOCATION` | Required to run location services in the foreground on Android 10+. |
| `WAKE_LOCK` | Keeps the CPU awake during active foreground tracking. |
| `VIBRATE` | Device vibration feedback on entering target boundaries. |
| `POST_NOTIFICATIONS` | Displays the persistent tracking notification on Android 13+. |

---

## 🧪 Simulation Mode (For easy testing)

Since you cannot easily travel to test geofence triggers:
1. Open the app and grant location/call permissions.
2. Search a place or tap the map to place a pin.
3. Enter a mock contact name, phone number, select a radius, and tap **+ Add Geofence**.
4. Enable **Simulation Mode** (🧪 toggle at the bottom).
5. Tap **Start Watching**.
6. Tap **Simulate Walk to Target** or manually tap somewhere on the map to jump your simulated position inside the radius to trigger the haptic feedback, announcement, and call!

# BYD Camera Recorder

An Android dashcam app that uses the built-in AVM (Around View Monitor) cameras in BYD vehicles.

## Features

- **4-channel 360° recording** — Simultaneously encodes front/rear/left/right cameras in H.264
- **Telemetry overlay** — Real-time compositing of speed, gear (P/R/N/D), turn signals, accelerator/brake, and headlights onto video and preview. Also displays battery level, driving range, energy mode (EV/HEV/FUEL), and drive mode (ECO/SPT)
- **GPS overlay** — Embeds speed (km/h or mph) and coordinates directly into video; saves GPX tracks
- **Parking guard mode** — Triple-detection using accelerometer (impact), camera motion detection, and BYD radar (8 sensors for proximity); 12-second pre-event buffer
- **Engine-state-based auto switching** — Immediately resumes recording on engine ON (PowerLevel ≥ 2); immediately enters parking guard on engine OFF (ON→ACC) with no 30-second wait. Also supports parking guard entry after speed = 0 for 30 seconds
- **Smartphone remote access & real-time alerts** — Connect via vehicle Wi-Fi to view and download footage from a Flutter app. Instant push notifications for impact, motion, and radar events via SSE (Server-Sent Events)
- **Automatic segment management** — Oldest segments deleted automatically when storage is full (locked clips protected)
- **In-app language selection** — Korean / English toggle

## Compatible Vehicles

| Model | Status |
|-------|--------|
| BYD Atto 3 | Verified |
| BYD Seal | Expected compatible · Unverified |
| BYD Dolphin | Expected compatible · Unverified |
| BYD Sealion 7 | Expected compatible · Unverified |

Any vehicle that uses the BYD `AVMCamera` API should work. To add a new model, implement `VehicleProfile` and open a PR.

## Requirements

- Android SDK (with Build-tools, API 23+)
- JDK 8 or higher
- ADB (for installation and testing)

On macOS with Android Studio installed, the SDK is auto-detected at `~/Library/Android/sdk`.

## Build

```bash
bash build.sh
# → build/byd-dashcam-debug.apk
```

### Release Build

```bash
BYD_CAMERA_SIGNING_MODE=release \
BYD_CAMERA_RELEASE_SIGNING_DIR=/path/to/signing \
bash build.sh
```

### Build with Phone UI

If you've modified the smartphone remote access UI, build it first:

```bash
npm --prefix phone-ui install
npm --prefix phone-ui run build
bash build.sh
```

## Installation

```bash
# Install on emulator or connected device
adb install -r build/byd-dashcam-debug.apk

# Pre-grant permissions (convenient for emulator testing)
adb shell pm grant com.ggpark.byddashcam android.permission.CAMERA
adb shell pm grant com.ggpark.byddashcam android.permission.ACCESS_FINE_LOCATION
adb shell dumpsys deviceidle whitelist +com.ggpark.byddashcam
```

## Emulator Testing

In environments without a real AVM camera, a color-bar `FixtureFrameSource` is used automatically.
The app is landscape-only, so set your emulator to landscape orientation as well.

## Remote Access API

Connect to the vehicle Wi-Fi, then access `http://<vehicle-IP>:8080`.

| Endpoint | Description |
|----------|-------------|
| `GET /api/segments` | Segment list (JSON) |
| `GET /api/segments/{id}/cameras/{cam}` | Video streaming / download |
| `GET /api/preview` | Live preview (MJPEG) |
| `GET /api/settings` | Read settings |
| `POST /api/settings` | Update settings |
| `GET /api/debug/logs` | Telemetry debug log (JSON) |
| `GET /api/debug/logs.txt` | Telemetry debug log file download |

## Project Structure

```
src/                          Java source files
  AvmCameraController           AVM camera HAL wrapper
  CameraRecorderService         Foreground service (recording & parking guard control)
  FrameProcessor                Frame processing (NV21 splitting, encoder dispatch)
  GpsDataProvider               GPS data collection (LocationManager wrapper)
  GpsOverlayRenderer            GPS & telemetry overlay compositing (NV21 & Bitmap)
  TelemetryOverlayView          Telemetry overlay on preview screen (custom View)
  VehicleDataProvider           BYD vehicle API polling (speed, gear, turn signals, lights, battery, engine, radar)
  VehicleTelemetry              Telemetry data snapshot (immutable)
  LogBuffer                     Circular buffer for last 500 telemetry log entries
  ImpactDetector                Impact detection (accelerometer)
  ParkingGuardController        Parking guard state machine
  PhoneAccessServer             Smartphone Wi-Fi access server
  VehicleProfileRegistry        Vehicle model auto-detection
  LocaleHelper                  In-app language switching
phone-ui/                     Smartphone remote access web UI (Vite)
mobile/                       Smartphone app (Flutter iOS/Android)
res/                          Android resources
assets/                       Static assets (phone UI bundle)
stubs/                        AVMCamera API stubs (for compilation)
vendor/                       bmmcamera.jar (runtime DEX)
docs/                         Landing page
build.sh                      Build script
```

## Telemetry API Access

Accesses BYD's private vehicle APIs via Java Reflection, using a fast 100 ms poll (speed, gear, lights) and a slow 5 s poll (battery, engine, energy).

| API Class | Method | Purpose | Poll Interval |
|-----------|--------|---------|---------------|
| `BYDAutoSpeedDevice` | `getCurrentSpeed()` | Speed (km/h) | 100 ms |
| `BYDAutoSpeedDevice` | `getAccelerateDeepness()` | Accelerator pedal (0–100%) | 100 ms |
| `BYDAutoSpeedDevice` | `getBrakeDeepness()` | Brake pedal (0–100%) | 100 ms |
| `BYDAutoGearboxDevice` | `getGearboxAutoModeType()` | Gear position (P/R/N/D) | 100 ms |
| `BYDAutoLightDevice` | `getTurnLightFlashState()` | Turn signal state | 100 ms |
| `BYDAutoLightDevice` | `getLightStatus(int type)` | Light status (type=2: low beam, type=3: high beam) | 100 ms |
| `BYDAutoBodyworkDevice` | `getPowerLevel()` | Engine state (0=OFF, 1=ACC, 2=ON, 3=OK, 4=FAKE_OK, 255=INVALID) | 5 s |
| `BYDAutoStatisticDevice` | `getBatteryPercent()` | Battery level (0–100%) | 5 s |
| `BYDAutoStatisticDevice` | `getDrivingRange()` | Electric driving range (km) | 5 s |
| `BYDAutoEnergyDevice` | `getEnergyMode()` | Energy mode (0=STOP, 1=EV, 2=FORCE_EV, 3=HEV, 4=FUEL, 5=KEEP) | 5 s |
| `BYDAutoEnergyDevice` | `getOperationMode()` | Drive mode (1=ECO, 2=SPT) | 5 s |
| `BYDAutoRadarDevice` | `getAllRadarProbeStates()` | 8 radar sensor states (0=error, 1=safe, 2=green, 3=yellow, 4=red) | 200 ms |

### Radar Sensor Layout

| Index | Position |
|-------|----------|
| 0 | LEFT_FRONT |
| 1 | RIGHT_FRONT |
| 2 | LEFT_REAR |
| 3 | RIGHT_REAR |
| 4 | LEFT |
| 5 | RIGHT |
| 6 | FRONT_LEFT_MID |
| 7 | FRONT_RIGHT_MID |

On devices where the BYD API is unavailable, the app gracefully degrades to `UNAVAILABLE` telemetry.

## License

MIT


# SmartNav — Smartphone Dead Reckoning vs SLAM

**SmartNav** is an Android application (Kotlin + Jetpack Compose) that implements two competing approaches to smartphone navigation and visualizes them for comparison:

- **Dead Reckoning (DR)** using IMU sensors (linear acceleration + rotation vector → quaternion rotation → integration)
- **SLAM** using **Google ARCore** (visual–inertial odometry via ARCore)

This repository contains the application source, instructions to build and run, sample data placeholders, and a short guide to reproduce the path overlay and performance plots.

---

## Demo Video `https://youtu.be/<your_video_id>`

---

## Quick Links
- App platform: **Android (Kotlin)**
- UI: **Jetpack Compose**
- Rendering: **GLSurfaceView + OpenGL ES 2.0** for ARCore camera feed
- SLAM engine: **ARCore (Google)**

---

## Features

- Real-time Dead Reckoning from IMU (linear acceleration and rotation vector)
- Real-time SLAM pose capture using ARCore
- Start / Stop recording of trajectories
- Mini-map overlay and full path graph (X–Z plane)
- 3D DR visualization with rotation controls (roll/pitch/yaw)
- Exportable path data (CSV) for offline analysis
- Placeholder images and scripts for plotting overlay and computing ATE

---

## Project Structure (top-level)

```
SmartNavigation/
├── app/
│   ├── src/
│   │   └── main/
│   │       ├── java/com/example/smartnavigation/
│   │       │   ├── MainActivity.kt
│   │       │   ├── DeadReckoningScreen.kt
│   │       │   └── SlamScreen.kt
│   │       └── res/
│   └── build.gradle
├── gradle/
├── build.gradle
└── README.md
```

---

## How It Works (high-level)

The Dead Reckoning pipeline uses the Android `Sensor.TYPE_LINEAR_ACCELERATION` and `Sensor.TYPE_ROTATION_VECTOR`. Each acceleration reading is filtered using a first-order low-pass filter, rotated from device frame to world frame using quaternion math, integrated into velocity and then integrated into position. Damping and thresholds are applied to mitigate drift.

The ARCore SLAM pipeline creates an ARCore `Session`, configures camera and depth options, and provides world poses each frame. The app samples those poses and stores them as `PathPoint(x,y,z,timestamp)`.

---

## Build & Run (Development)

1. Clone the repository:
```bash
git clone https://github.com/jhilpatel06/SmartNavigation.git
cd SmartNavigation
```

2. Open in Android Studio (Arctic Fox or newer recommended). Import the Gradle project.

3. Install required SDKs and tools:
   - Android SDK with API level ≥ 30
   - ARCore SDK (Gradle dependency usually: `com.google.ar:core:<version>`)
   - Enable Camera permission on the phone

4. Connect an ARCore-supported Android device (or use an emulator with ARCore support if configured).

5. Run the app from Android Studio.

---

## Exporting & Analyzing Trajectories

When you stop recording in the app, the recorded points (for DR and SLAM) can be exported to CSV in the following format:

```
timestamp_ms, method, x, y, z
1610000000000, DR, 0.0123, 0.0012, 0.021
1610000000100, SLAM, 0.0130, 0.0005, 0.0205
```

A simple Python script (not included) can read both trajectories, time-align them, compute Absolute Trajectory Error (ATE), and plot overlays.

Example ATE formula:

\[
ATE = \sqrt{\frac{1}{N} \sum_{i=1}^{N} \| p_{DR}(i) - p_{SLAM}(i) \|^2}
\]

---

## Helpful Links & Repositories

- Google ARCore (official): https://developers.google.com/ar  
- ARCore Android SDK examples: https://github.com/google-ar/arcore-android-sdk  
- Madgwick orientation filter (reference implementation): https://github.com/xioTechnologies/Fusion  
- Android sensor handling examples: https://github.com/abresc/android-sensor-fusion

---

## Report & Deliverables

Include the following in your submission package:

- `README.md` (this file)
- `SmartNav_Report.tex` (LaTeX single-file report; replace placeholder images)
- `app/` directory (source code)
- `video/` (demo video links)
- `data/` (recorded CSV trajectories)
- `plots/` (overlay images, ATE plots)

---

## License & Credits

This project was developed as part of the course IE415 – Control of Autonomous Systems.  
Credit: Google ARCore for the SLAM capability. Portions of code are adapted from public examples and are attributed in the report.

---



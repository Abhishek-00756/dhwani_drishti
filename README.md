# Dhwani Drishti

> An on-device AI assistive vision system that converts a smartphone camera view into spoken and spatial-audio information for visually impaired users.

Dhwani Drishti is an Android application designed to improve independent environmental awareness through **real-time object detection, monocular depth estimation, spatial reasoning, object tracking, risk prioritization, OCR, voice commands, and audio feedback**.

The current main branch is built around a lightweight **YOLOv8n 80-class object detector** and **MiDaS Small relative-depth estimation**, with the processing performed locally on the Android device.

---

## Table of Contents

- [Why Dhwani Drishti](#why-dhwani-drishti)
- [Current Capabilities](#current-capabilities)
- [System Architecture](#system-architecture)
- [Operating Modes](#operating-modes)
- [Object Detection](#object-detection)
- [Depth Estimation](#depth-estimation)
- [Depth and Object Fusion](#depth-and-object-fusion)
- [Spatial Zones](#spatial-zones)
- [Object Tracking](#object-tracking)
- [Risk Engine](#risk-engine)
- [Automatic Voice Announcements](#automatic-voice-announcements)
- [Voice Interaction](#voice-interaction)
- [OCR / Read Mode](#ocr--read-mode)
- [Soundscape Mode](#soundscape-mode)
- [Depth Calibration](#depth-calibration)
- [Camera Pipeline](#camera-pipeline)
- [Technology Stack](#technology-stack)
- [Project Structure](#project-structure)
- [Model Assets](#model-assets)
- [Installation and Development](#installation-and-development)
- [Current Status](#current-status)
- [Limitations](#limitations)
- [Future Roadmap](#future-roadmap)

---

## Why Dhwani Drishti

A conventional object-detection application can answer:

> **What object is visible?**

For an assistive mobility system, that information alone is often insufficient. The user may also need to know:

- **Where is the object?**
- **How close is it relative to the current scene?**
- **Is it getting closer?**
- **Should it be prioritized as a potential hazard?**
- **Can the information be communicated without requiring the user to look at a screen?**

Dhwani Drishti combines these layers into a single audio-first pipeline:

```text
Camera
  ↓
Object Detection + Depth Estimation
  ↓
Depth / Object Fusion
  ↓
Spatial Reasoning
  ↓
Temporal Tracking
  ↓
Risk Prioritization
  ↓
Speech / Spatial Audio
```

The goal is not simply to recognize objects, but to turn visual information into information that can be consumed through hearing.

---

## Current Capabilities

The current main branch implements:

- Real-time CameraX camera input
- YOLOv8n object detection
- 80-class COCO label mapping inside the active detector
- MiDaS Small monocular relative-depth estimation
- Depth/object fusion
- Left / center / right spatial reasoning
- Lightweight multi-frame object tracking
- Relative proximity categories
- Approaching / moving-away estimation
- Object movement direction estimation
- Heuristic risk scoring
- Automatic spoken warnings
- Narrated scene descriptions
- Voice-command interaction
- Object-location queries such as locating a detected object
- OCR using Google ML Kit Text Recognition
- Android Text-to-Speech output
- Continuous stereo Soundscape Mode
- Near/far depth calibration
- On-device AI inference with CPU and GPU-supported components where available

The application currently has **two operating modes**:

1. **Soundscape Mode**
2. **Narrated Mode**

There is currently no Hybrid mode in the main pipeline.

---

# System Architecture

```text
                         CAMERA FRAME
                              │
                 ┌────────────┴────────────┐
                 │                         │
                 ▼                         ▼
          MiDaS Small                  YOLOv8n
        Relative Depth             Object Detection
                 │                         │
                 └────────────┬────────────┘
                              ▼
                       Depth + Object
                           Fusion
                              │
                              ▼
                       Spatial Zones
                              │
                              ▼
                       Object Tracker
                              │
                              ▼
                         Risk Engine
                              │
                   ┌──────────┴──────────┐
                   │                     │
                   ▼                     ▼
             Announcements          User Query
                   │                     │
                   ▼                     ▼
                 Android             Voice Response
                   TTS
```

Soundscape Mode uses the camera/depth branch to generate continuous spatial audio, while Narrated Mode uses the full object/depth/tracking/risk pipeline.

---

# Operating Modes

## Mode 1 — Soundscape

```text
Camera
  ↓
Depth Estimation
  ↓
Spatial Analysis
  ↓
Stereo Audio Mapping
  ↓
Continuous Soundscape
```

Soundscape Mode is intended to provide continuous environmental awareness through audio rather than spoken descriptions for every frame.

## Mode 2 — Narrated

```text
Camera
  ↓
YOLOv8n + MiDaS
  ↓
Depth/Object Fusion
  ↓
Spatial Zones
  ↓
Object Tracking
  ↓
Risk Engine
  ↓
Announcement Manager
  ↓
Text-to-Speech
```

Narrated Mode provides object descriptions, relative proximity information, movement/approach information, warnings, and responses to user voice queries.

---

# Object Detection

Dhwani Drishti's active Narrated-mode detector uses the **YOLOv8n** model stored at:

```text
app/src/main/assets/yolov8n_fp16.tflite
```

The detector directly uses an 80-class COCO label mapping in `ObjectDetector.kt`.

### Configuration

| Property | Current implementation |
|---|---|
| Architecture | YOLOv8n |
| Task | Object Detection |
| Input size | 320 × 320 |
| Classes | 80 COCO classes |
| Confidence threshold | 0.40 |
| Runtime | TensorFlow Lite / LiteRT-compatible Android stack |
| CPU threads | 4 |
| Inference | On-device |
| Output | Bounding box + confidence + class ID |

Each accepted detection is represented internally as:

```text
[x1, y1, x2, y2, confidence, class_id]
```

The detector normalizes bounding boxes to the 0–1 coordinate range used by downstream spatial processing.

### Important model note

The repository also contains a legacy/experimental asset:

```text
app/src/main/assets/dhwani_drishti_17class.tflite
```

However, the active `ObjectDetector` deliberately loads `yolov8n_fp16.tflite` and uses the 80-class COCO mapping. The 17-class asset is therefore **not the active production detector in the current main branch**.

The current `labels.txt` file still contains the older 17-label list and should be treated as a legacy asset rather than the source of truth for the active YOLOv8 detector.

---

# Depth Estimation

Dhwani Drishti uses a MiDaS Small model for monocular depth estimation:

```text
app/src/main/assets/midas_small.tflite
```

The model produces a **relative depth / inverse-depth representation**, not guaranteed physical distance in metres.

### Configuration

| Property | Value |
|---|---|
| Model | MiDaS Small |
| Input | 256 × 256 |
| Output | 256 × 256 depth map |
| Depth type | Relative inverse depth |
| GPU | Used when supported |
| CPU fallback | Available |
| CPU threads | 4 |

The pipeline converts the model output into a normalized closeness representation for downstream reasoning.

Conceptually:

```text
Lower relative value  → farther
Higher relative value → closer
```

The exact raw model value should not be interpreted directly as metres.

---

# Depth and Object Fusion

Object detection and depth estimation solve different parts of the problem.

**YOLOv8n** provides:

```text
Object identity
Bounding box
Confidence
```

**MiDaS** provides:

```text
Relative depth information
```

The fusion stage samples depth information associated with the detected object's region and attaches a relative distance/proximity estimate to the object.

The resulting tracked representation includes information such as:

```text
label
boundingBox
confidence
distance
zone
```

This allows the system to move from:

> "There is a person."

Towards:

> "There is a person close to you, slightly to your right."

without claiming an unsupported exact physical distance.

---

# Spatial Zones

The horizontal position of each detected object is converted into a spatial zone:

```text
LEFT              CENTER              RIGHT
  │                  │                  │
  ▼                  ▼                  ▼
0.0                0.5                 1.0
```

The zone is derived from the normalized horizontal position of the detected object's centroid.

This supports responses such as:

```text
"Person is on your left."
"Laptop is in front of you."
"Car is on your right."
```

The same spatial information is also used by the audio and risk layers.

---

# Object Tracking

YOLO performs independent detection on individual frames. The `ObjectTracker` adds temporal continuity across consecutive frames.

Each active track can maintain:

- Stable object ID
- Object label
- Relative distance
- Centroid position
- Bounding box
- Detection confidence
- Spatial zone
- Distance history
- Centroid history
- Last-seen timestamp

The tracker uses lightweight centroid-based matching with the same class label and a maximum normalized matching distance.

### Temporal reasoning

The stored distance history allows the system to estimate whether an object is:

```text
Approaching
Moving away
Stable
```

For example:

```text
Far → Medium → Close → Very Close
```

can indicate an approaching object.

The tracker intentionally treats these as **relative changes**, because the underlying MiDaS depth is not guaranteed metric distance.

---

# Risk Engine

The Risk Engine converts scene information into a heuristic risk score.

The current design considers factors including:

- Relative proximity
- Object importance
- Spatial position
- Object size / visual occupancy
- Approaching behaviour

The output is used to prioritize which information should be communicated first.

Conceptually:

```text
Object
  +
Proximity
  +
Spatial Position
  +
Movement
  +
Importance
       ↓
   Risk Score
       ↓
LOW / MEDIUM / HIGH / CRITICAL
```

The risk system is a **heuristic prototype layer**, not a clinically validated or statistically validated safety model.

---

# Automatic Voice Announcements

The Announcement Manager prevents the system from speaking every detected object on every frame.

Announcements are prioritized using conditions such as:

- Very close objects
- Close objects
- Approaching objects
- Priority objects
- Spatial position
- Announcement cooldown
- Current speech state

The system also supports global speech interruption so that a new voice command can take control of the audio output.

Example outputs include:

```text
"Person very close."
"Car approaching from your right."
"Laptop nearby."
"Obstacle on your left."
```

The exact phrase depends on the current tracked scene.

---

# Voice Interaction

Dhwani Drishti supports speech-based interaction so that the user does not need to navigate the application visually.

Example intent:

```text
User:
"Hey Dhwani, what's in front of me?"

System:
"I see a person and a laptop in front of you."
```

### Object location queries

The user can ask questions such as:

```text
"Where is the door?"
"Where is the laptop?"
"Find the person."
```

The system searches the latest tracked scene and can respond using the object's spatial zone.

Example:

```text
"Door is on your left."
```

If the requested object is not currently detected, the system can report that it does not currently see the object.

---

# OCR / Read Mode

Dhwani Drishti includes text recognition using **Google ML Kit Text Recognition**.

Pipeline:

```text
Camera Frame
     ↓
ML Kit Text Recognition
     ↓
Recognized Text
     ↓
Text-to-Speech
```

This enables the application to read visible text aloud instead of requiring the user to read it visually.

---

# Soundscape Mode

Soundscape Mode provides continuous stereo audio feedback rather than relying entirely on spoken messages.

The implementation uses Android `AudioTrack` and stereo PCM streaming.

Conceptually:

```text
Visual Scene
    ↓
Depth / Spatial Analysis
    ↓
Audio Parameters
    ↓
Stereo Sound
```

The current sonification layer maps scene information into audio characteristics such as pitch, channel emphasis, and loudness.

### Current audio configuration

- Sample rate: 44.1 kHz
- Stereo output
- PCM streaming through Android AudioTrack
- Approximate frequency range: 220–880 Hz
- Left/right channel emphasis for spatial information
- Continuous audio generation rather than individual alert sounds

The purpose is to give the user an additional non-verbal representation of the surrounding spatial structure.

---

# Depth Calibration

Because monocular depth models produce relative rather than guaranteed metric distance, Dhwani Drishti includes a calibration layer.

The calibration workflow records:

- Near reference
- Far reference

These values can then be used to normalize the depth output for the current device/session.

The calibration UI supports recording reference values, checking calibration state, skipping calibration, and completing the calibration process.

Calibration improves the usefulness of relative depth but does **not** turn MiDaS into a guaranteed centimetre-accurate ranging sensor.

---

# Camera Pipeline

CameraX provides the live camera stream and image analysis pipeline.

The camera analysis path is designed around processing the newest available frame rather than building a large backlog of stale frames.

Conceptually:

```text
CameraX
  ↓
Latest available frame
  ↓
Image preprocessing
  ↓
AI inference
  ↓
Scene processing
```

This is important for an interactive assistive application because a delayed result from an old frame can be less useful than a slightly less frequent result from the current scene.

---

# Technology Stack

## Android

- Kotlin
- Android SDK
- CameraX
- Android Text-to-Speech
- Kotlin Coroutines

## Computer Vision / AI

- YOLOv8n
- MiDaS Small
- Google ML Kit Text Recognition

## On-device inference

- TensorFlow Lite / LiteRT Android runtime
- GPU delegate support for the depth pipeline where available
- CPU fallback

## Audio

- Android AudioTrack
- Stereo PCM audio
- Audio-based spatial representation

## Scene Processing

- Bounding-box normalization
- Depth/object fusion
- Spatial zoning
- Multi-frame object tracking
- Relative proximity categorization
- Heuristic risk scoring
- Announcement prioritization

---

# Project Structure

```text
app/
└── src/
    └── main/
        ├── assets/
        │   ├── yolov8n_fp16.tflite
        │   ├── midas_small.tflite
        │   ├── dhwani_drishti_17class.tflite   # legacy/experimental
        │   ├── labels.txt                      # legacy 17-label list
        │   └── README.md
        │
        ├── java/
        │   └── com/
        │       └── dhwanidrishti/
        │           └── app/
        │               ├── MainActivity.kt
        │               │
        │               ├── audio/
        │               │   ├── AnnouncementManager.kt
        │               │   ├── SonificationEngine.kt
        │               │   ├── TextReader.kt
        │               │   └── VoiceCommandManager.kt
        │               │
        │               ├── camera/
        │               │   └── CameraController.kt
        │               │
        │               ├── ml/
        │               │   ├── DepthEstimator.kt
        │               │   ├── ObjectDetector.kt
        │               │   └── RiskEngine.kt
        │               │
        │               ├── pipeline/
        │               │   ├── AppMode.kt
        │               │   ├── DhwaniPipeline.kt
        │               │   └── ModeBEngine.kt
        │               │
        │               └── processing/
        │                   ├── DetectedObject.kt
        │                   ├── ObjectTracker.kt
        │                   ├── Zone.kt
        │                   └── ZoneProcessor.kt
        │
        └── res/
```

The exact source tree can evolve as development continues; the important architectural separation is between camera input, ML inference, scene processing, audio/voice output, and the top-level pipeline.

---

# Model Assets

| Asset | Role | Active? |
|---|---|---|
| `yolov8n_fp16.tflite` | YOLOv8n object detection | **Yes** |
| `midas_small.tflite` | MiDaS relative depth estimation | **Yes** |
| `dhwani_drishti_17class.tflite` | Older custom/experimental detector | No |
| `labels.txt` | Older 17-label list | Legacy |

The active YOLOv8 detector contains its current 80-class COCO label mapping directly in `ObjectDetector.kt`.

---

# Installation and Development

## Requirements

- Android Studio
- Android SDK
- Android device with camera support
- USB debugging enabled for development deployment

## Build

From the project root:

```bash
./gradlew assembleDebug
```

The generated debug APK is normally located at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Install with ADB

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or, if using the Android SDK platform-tools path directly:

```bash
~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The application requires camera/audio-related Android permissions needed by the implemented features.

---

# Current Status

Dhwani Drishti is an **active prototype/research project**.

### Implemented

- [x] CameraX real-time camera pipeline
- [x] YOLOv8n on-device object detection
- [x] MiDaS Small relative depth estimation
- [x] Depth/object fusion
- [x] Spatial zones
- [x] Object tracking
- [x] Relative proximity categories
- [x] Approaching/moving-away estimation
- [x] Risk scoring
- [x] Automatic announcements
- [x] Narrated Mode
- [x] Soundscape Mode
- [x] Voice interaction
- [x] OCR / Read Mode
- [x] Android Text-to-Speech
- [x] Depth calibration

### Not yet production-grade

- Metric-distance accuracy has not been established.
- Risk scoring has not been clinically validated.
- The current detector is a general-purpose COCO model rather than a mobility-specific hazard model.
- Large-scale field evaluation has not yet been completed.

---

# Limitations

Dhwani Drishti is an assistive prototype and should **not** be treated as a replacement for a white cane, guide dog, mobility training, or human assistance.

Current technical limitations include:

1. **Relative depth** — MiDaS does not guarantee exact physical distance in metres.
2. **General-purpose object detection** — YOLOv8n is trained for general COCO categories and is not specifically optimized for every mobility hazard.
3. **Lighting and visibility** — Detection quality can degrade under poor lighting, blur, occlusion, unusual viewpoints, or visually ambiguous scenes.
4. **Heuristic risk model** — Current risk thresholds and object priorities are engineering heuristics, not clinically validated safety thresholds.
5. **Speech recognition** — Voice-command behaviour can depend on the Android device and its configured speech-recognition service.
6. **Mobile compute constraints** — Real-time AI processing must balance latency, thermal load, battery consumption, and accuracy.

---

# Future Roadmap

The most important next step is to move from a general-purpose object detector toward a **mobility-specific perception model**.

Potential future classes include:

```text
pothole
stair
curb
open drain
construction barrier
road obstruction
crosswalk
entrance / doorway
pole
```

Other future work includes:

- Custom mobility-focused dataset collection
- Training and evaluating a dedicated hazard detector
- Better metric-depth estimation
- More robust obstacle classification
- Improved risk calibration using real-world evaluation data
- Quantitative latency/FPS benchmarking across Android devices
- Precision/recall and confusion-matrix evaluation
- Battery and thermal profiling
- More robust navigation assistance
- Larger field testing with representative environments

---

# Design Philosophy

Dhwani Drishti is built around an **audio-first interaction model**.

Instead of requiring the user to continuously inspect a visual interface, the system attempts to transform visual information into concise audio information:

```text
SEE
 ↓
UNDERSTAND
 ↓
PRIORITIZE
 ↓
SPEAK / SONIFY
```

The central engineering idea is the combination of:

```text
Object Detection
       +
Relative Depth
       +
Spatial Reasoning
       +
Temporal Tracking
       +
Risk Prioritization
       +
Audio Feedback
```

This layered architecture allows individual components to be improved independently as better models and better mobility-specific datasets become available.

---

## License

Add the project's chosen license here before public distribution.

---

## Acknowledgements

Dhwani Drishti builds on open-source and platform technologies including YOLO, MiDaS, TensorFlow Lite/LiteRT, Android CameraX, Android Text-to-Speech, and Google ML Kit.

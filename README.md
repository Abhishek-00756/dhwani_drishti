# Dhwani Drishti

> An AI-powered assistive vision system designed to help visually impaired users understand their surroundings through object detection, depth estimation, spatial audio, voice interaction, and spoken navigation assistance.

---

## Overview

**Dhwani Drishti** is an Android-based assistive vision application that combines computer vision, depth estimation, object tracking, risk analysis, OCR, speech recognition, and text-to-speech to provide real-time environmental awareness.

The system is designed around two primary operating modes:

1. **Soundscape Mode** – communicates the surrounding spatial structure through directional audio.
2. **Narrated Mode** – detects objects and provides spoken descriptions, warnings, and object-location information.

The goal is to provide useful environmental information without requiring the user to continuously look at a screen.

---

# Features

## 1. Soundscape Mode

Soundscape Mode provides spatial awareness using the camera and depth estimation pipeline.

The environment is divided into spatial zones:

```text
             CAMERA VIEW

        LEFT   CENTER   RIGHT
          ↓       ↓       ↓

        ┌─────────────────────┐
        │         │           │
        │   L     │    R      │
        │         │           │
        │─────────┼───────────│
        │         │           │
        └─────────────────────┘


Depth information is converted into directional audio cues.

The user can therefore understand whether an obstacle is:

To the left
In front
To the right
Near
Far
2. Narrated Mode

Narrated Mode combines multiple AI components:

Camera Frame
     │
     ▼
MiDaS Depth Estimation
     │
     ├───────────────────┐
     │                   │
     ▼                   ▼
Depth Map            YOLO26m
                         │
                         ▼
                  Object Detection
                         │
                         ▼
                  Depth Fusion
                         │
                         ▼
                  Object Tracking
                         │
                         ▼
                    Risk Engine
                         │
                         ▼
                Announcement Manager
                         │
                         ▼
                     TTS Audio

Narrated Mode can automatically announce important objects and provide information about their position and proximity.
3. YOLO26m Object Detection

Dhwani Drishti uses a custom-trained YOLO26m object detection model exported to LiteRT/TFLite.

Model file:

app/src/main/assets/dhwani_drishti_17class.tflite

The model is designed specifically for the Dhwani Drishti application.

Model configuration
Property	Value
Architecture	YOLO26m
Task	Object Detection
Input Size	512 × 512
Input Layout	NCHW
Input Type	FLOAT32
Output Shape	[1, 300, 6]
Output Type	FLOAT32
Classes	17
Runtime	LiteRT / TensorFlow Lite
Android Inference	CPU

Each detection contains:

[x1, y1, x2, y2, confidence, class_id]
4. Custom 17 Object Classes

The model currently recognizes:
0   person
1   bicycle
2   car
3   motorcycle
4   truck
5   stop sign
6   bench
7   dog
8   chair
9   bed
10  laptop
11  book
12  bag
13  door
14  window
15  stair
16  pothole
The corresponding labels are stored in:

app/src/main/assets/labels.txt
5. Depth Estimation

Dhwani Drishti uses a MiDaS-based depth estimation pipeline.

The depth model produces a normalized closeness map:

0.0 → far
1.0 → close

Depth information is then combined with YOLO bounding boxes.

For each detected object, the system samples the depth map around the object and estimates its relative distance.

6. Depth + Object Fusion

Object detection alone tells us:
"There is a person."
Depth estimation alone tells us:

"Something is close."

Dhwani Drishti combines both.

Example:

YOLO
 ↓
Person
 ↓
Bounding Box
 ↓
Depth Map
 ↓
Distance Estimation
 ↓
Person + Distance

The resulting object representation contains:

label
boundingBox
confidence
distance
zone
7. Spatial Zones

Each detected object is assigned a horizontal zone based on the center of its bounding box.

LEFT        CENTER        RIGHT
  |            |            |
  ▼            ▼            ▼


0.0          0.5           1.0

This allows the system to produce spoken descriptions such as:

"Person is on your left."


"Laptop is in front of you."


"Door is on your right."
Object Tracking

Detected objects are tracked across consecutive frames.

The tracker maintains information such as:

Object ID
Object label
Current position
Current distance
Distance history
Current spatial zone
Last seen time

Tracking allows the application to determine whether an object is:

stationary
approaching
moving away

rather than treating every camera frame as a completely new scene.

9. Risk Engine

The Risk Engine evaluates detected objects according to:

Distance
Object importance
Spatial position

Objects closer to the user receive a higher risk score.

Objects such as:

person
car
motorcycle
bus
truck
bicycle

receive higher importance than ordinary objects.

The resulting risk level is:

LOW
MEDIUM
HIGH
CRITICAL

Example:

Person
+
Very close
+
Center of view
        ↓
High / Critical risk
10. Automatic Voice Announcements

The Announcement Manager determines when an object should be announced.

It considers factors such as:

Object distance
Risk
Approaching behavior
Spatial zone
Announcement priority
Cooldown

Examples:

"Person very close."


"Car approaching from your right."


"Laptop nearby."


"Obstacle on your left."

The exact announcement depends on the current scene and risk state.

11. Voice Commands

Dhwani Drishti supports voice interaction through speech recognition.

The user can activate the assistant using commands such as:

"Hey Dhwani..."

The application supports commands including:

What's in front?
"Hey Dhwani, what's in front of me?"

The application describes the currently tracked scene.

Example:

"I see a person and a laptop in front of you."
Locate an Object

The user can ask:

"Hey Dhwani, where is the door?"

or:

"Hey Dhwani, where is the laptop?"

or:

"Hey Dhwani, find the person."

The system:

Voice Command
      ↓
Extract Object Name
      ↓
Search Latest Tracked Scene
      ↓
Find Requested Object
      ↓
Read Spatial Zone
      ↓
Generate Response
      ↓
Text-to-Speech

Example:

User:
"Hey Dhwani, where is the door?"


System:
"Door is on your left."

Possible responses include:

"Door is on your left."


"Door is in front of you."


"Door is on your right."


"I don't currently see a door."
12. OCR / Read Mode

Dhwani Drishti also supports reading text from the camera.

Voice command:

"Hey Dhwani, read."

The application captures the relevant camera content, performs OCR, and converts the recognized text into speech.

Pipeline:

Camera
   ↓
Image
   ↓
OCR
   ↓
Recognized Text
   ↓
Text-to-Speech
13. Text-to-Speech

The application uses Android text-to-speech capabilities to communicate results to the user.

TTS is used for:

Object warnings
Risk announcements
Object location
Scene descriptions
OCR results
Voice-command responses
14. Calibration

The application includes depth calibration.

The calibration process records:

Near reference
Far reference

These values are used to improve interpretation of the depth model's raw output.

Calibration UI provides:

Near-distance recording
Far-distance recording
Calibration status
Skip option
Completion controls
Application Modes

Dhwani Drishti currently has two modes.

Mode 1 — Soundscape
SOUNDSCAPE

Pipeline:

Camera
   ↓
Depth Estimation
   ↓
Spatial Zones
   ↓
Directional Audio

Purpose:

Provide continuous spatial/environmental awareness through sound.

Mode 2 — Narrated
NARRATED

Pipeline:

Camera
   ↓
Depth Estimation
   ↓
YOLO26m
   ↓
Depth Fusion
   ↓
Object Tracking
   ↓
Risk Engine
   ↓
Voice Announcements

Purpose:

Provide spoken information about objects, hazards, distance, movement, and requested object locations.

Mode Switching

The application switches between:

SOUNDSCAPE
      ↕
NARRATED

There is currently no Hybrid mode.

Project Architecture
app/
│
├── src/
│   └── main/
│       │
│       ├── assets/
│       │   ├── dhwani_drishti_17class.tflite
│       │   └── labels.txt
│       │
│       ├── java/
│       │   └── com/
│       │       └── dhwanidrishti/
│       │           └── app/
│       │
│       │               ├── MainActivity.kt
│       │               │
│       │               ├── audio/
│       │               │   ├── AnnouncementManager.kt
│       │               │   └── VoiceCommandManager.kt
│       │               │
│       │               ├── camera/
│       │               │   └── CameraController.kt
│       │               │
│       │               ├── ml/
│       │               │   ├── ObjectDetector.kt
│       │               │   └── RiskEngine.kt
│       │               │
│       │               ├── pipeline/
│       │               │   ├── DhwaniPipeline.kt
│       │               │   ├── ModeBEngine.kt
│       │               │   └── AppMode.kt
│       │               │
│       │               └── processing/
│       │                   ├── DetectedObject.kt
│       │                   ├── ObjectTracker.kt
│       │                   └── ...
│       │
│       └── res/
│
└── README.md
Core Components
MainActivity

Responsible for:

Application UI
Camera permission
Microphone permission
Mode switching
Calibration controls
Voice command initialization
Pipeline lifecycle
CameraController

Responsible for:

Camera initialization
Camera preview
Frame acquisition
Sending frames to the processing pipeline
DhwaniPipeline

Acts as the central processing pipeline.

It coordinates:

Camera
 ↓
Depth
 ↓
Soundscape / Narrated Mode
 ↓
AI Processing

It also exposes high-level functions for:

What's in front?
Where is an object?
Read
Speak
ObjectDetector

Responsible for:

Loading YOLO26m LiteRT model
Image preprocessing
Letterboxing
Model inference
Detection decoding
Confidence filtering
Bounding-box normalization
Mapping class IDs to labels

Model:

dhwani_drishti_17class.tflite
ObjectTracker

Responsible for maintaining object identities between frames.

It enables:

Object persistence
Distance history
Approaching detection
Zone tracking
RiskEngine

Responsible for estimating the relative danger of an object.

Inputs include:

Object type
Distance
Spatial zone

Output:

RiskLevel
Risk score
Reason
AnnouncementManager

Responsible for spoken environmental feedback.

It controls:

TTS
Announcement priority
Cooldowns
Object warnings
Scene descriptions
Object-location responses
VoiceCommandManager

Responsible for:

Speech recognition
Wake-word handling
Command parsing
Object-name extraction
Triggering application actions

Supported command categories include:

Read
What's in front of me?
Where is X?
Find X
Locate X
Technology Stack
Android
Kotlin
Android SDK
AndroidX
CameraX
Computer Vision
YOLO26m
LiteRT / TensorFlow Lite
MiDaS depth estimation
Custom object detection dataset
Audio
Android Text-to-Speech
Android Speech Recognition
Spatial audio / soundscape processing
AI Pipeline
YOLO26m
+
MiDaS
+
Object Tracking
+
Risk Analysis
+
Speech
+
TTS
YOLO26 Model Integration

The exported model is stored inside Android assets:

app/src/main/assets/dhwani_drishti_17class.tflite

The Android application verifies the model at runtime.

Expected model tensors:

INPUT


[1, 3, 512, 512]


FLOAT32

and:

OUTPUT


[1, 300, 6]


FLOAT32

Detection format:

[x1, y1, x2, y2, confidence, class_id]

Bounding boxes are converted into normalized coordinates before being passed to the depth-fusion pipeline.

Detection Pipeline
Camera Frame
      │
      ▼
Letterbox to 512 × 512
      │
      ▼
RGB → FLOAT32
      │
      ▼
NCHW Tensor
      │
      ▼
YOLO26m
      │
      ▼
300 Candidate Detections
      │
      ▼
Confidence Filtering
      │
      ▼
Class ID → Label
      │
      ▼
Bounding Box Mapping
      │
      ▼
RawDetection
Depth Fusion Pipeline
RawDetection
      │
      ├── Bounding Box
      │
      ▼
Bounding Box Center
      │
      ▼
Depth Map Sampling
      │
      ▼
Object Distance
      │
      ▼
Spatial Zone
      │
      ▼
DetectedObject
Object Tracking Pipeline
DetectedObject
      │
      ▼
ObjectTracker
      │
      ├── Object ID
      ├── Position
      ├── Distance
      ├── Zone
      └── Distance History
      │
      ▼
TrackedObject
Risk Analysis Pipeline
Tracked / Detected Object
          │
          ├── Distance
          ├── Object Importance
          └── Spatial Zone
                  │
                  ▼
             RiskEngine
                  │
                  ▼
        ┌──────────────────┐
        │ LOW              │
        │ MEDIUM           │
        │ HIGH             │
        │ CRITICAL         │
        └──────────────────┘
Voice Interaction Pipeline
User Speech
     │
     ▼
Speech Recognition
     │
     ▼
Command Parsing
     │
     ├───────────────┐
     │               │
     ▼               ▼
"What is..."     "Where is..."
     │               │
     ▼               ▼
Scene Query       Object Query
     │               │
     └───────┬───────┘
             ▼
        Mode B Engine
             │
             ▼
             TTS
Example User Interactions
Scene description
User:
"Hey Dhwani, what's in front of me?"


Dhwani:
"I see a person and a laptop in front of you."
Object location
User:
"Hey Dhwani, where is the laptop?"


Dhwani:
"Laptop is on your right."
Door location
User:
"Hey Dhwani, where is the door?"


Dhwani:
"Door is on your left."
Missing object
User:
"Hey Dhwani, where is the car?"


Dhwani:
"I don't currently see a car."
Reading
User:
"Hey Dhwani, read."


Dhwani:
[Reads detected text]
Current Model Testing

The YOLO26m model has been successfully loaded and tested on Android.

Verified runtime configuration:

Model:
dhwani_drishti_17class.tflite


Input:
[1, 3, 512, 512]


Output:
[1, 300, 6]


Type:
FLOAT32

The Android runtime has successfully produced detections for classes including:

person
bed
laptop
bag
book

Example runtime detections:

person confidence=0.72
bed confidence=0.61
laptop confidence=0.52
person confidence=0.93
bag confidence=0.58
book confidence=0.36
Dataset

The object detection model was trained using a custom reduced dataset containing the 17 target classes.

The application focuses on objects that are useful for environmental awareness and navigation assistance.

The model classes include:

People
Vehicles
Indoor objects
Navigation-related objects
Road hazards
Performance Considerations

YOLO inference is currently performed using the CPU.

The current implementation uses multiple CPU threads.

The application intentionally does not run YOLO on every camera frame.

Instead, detection is throttled to reduce unnecessary computation:

Camera
~20 FPS


YOLO
~6–7 FPS

Depth processing can operate independently at a higher frequency.

This architecture is intended to balance:

Accuracy
Latency
Battery usage
Thermal performance
Current Development Status
Completed
 Android application foundation
 Camera pipeline
 Soundscape mode
 Depth estimation pipeline
 Depth calibration
 YOLO26m integration
 Custom 17-class object detection model
 LiteRT/TFLite inference
 Bounding-box processing
 Depth-object fusion
 Object tracking
 Distance history
 Approaching-object detection
 Risk engine
 Automatic voice announcements
 Speech recognition
 "What's in front of me?" command
 "Where is X?" command
 Object LEFT/CENTER/RIGHT location logic
 OCR / Read command
 Text-to-speech
 Two-mode architecture
Work in Progress

The project is still under active development.

Current areas being improved include:

 Object tracking stability
 Multi-object tracking accuracy
 Detection consistency across consecutive frames
 False-positive reduction
 Depth-distance calibration
 Announcement prioritization
 Announcement cooldown tuning
 Voice-command robustness
 Performance optimization
 Battery and thermal optimization
 On-device inference optimization
 Broader real-world testing
 Improved navigation-specific object detection
Safety Notice

Dhwani Drishti is an experimental assistive technology project.

Object detection, depth estimation, and distance estimation can fail because of:

Poor lighting
Motion blur
Occlusion
Camera limitations
Incorrect model predictions
Depth-estimation errors
Unusual environments
Objects outside the trained classes

The system should therefore not be treated as a guaranteed safety or navigation system.

Real-world testing should be performed carefully and with appropriate supervision.

Future Development

Planned improvements include:

AI
Improved custom object detection
Better navigation-specific training
More robust depth estimation
Improved object tracking
Temporal scene understanding
Object movement prediction
Voice
More natural conversational commands
Better object-name understanding
Context-aware questions
Multilingual voice interaction
Navigation
Path-aware obstacle reasoning
Crosswalk detection
Traffic-related awareness
Stair and doorway awareness
Pothole and road hazard detection
On-device AI
Faster inference
Hardware acceleration
Quantized models
Lower memory consumption
Battery optimization
Project Structure

The major processing flow is:

                    ┌─────────────────┐
                    │     Camera      │
                    └────────┬────────┘
                             │
                             ▼
                    ┌─────────────────┐
                    │  DhwaniPipeline │
                    └────────┬────────┘
                             │
                 ┌───────────┴───────────┐
                 │                       │
                 ▼                       ▼
        ┌─────────────────┐     ┌─────────────────┐
        │   SOUNDSCAPE    │     │    NARRATED     │
        └────────┬────────┘     └────────┬────────┘
                 │                       │
                 ▼                       ▼
          Depth / Zones             YOLO26m
                 │                       │
                 ▼                       ▼
          Spatial Audio             Depth Fusion
                                         │
                                         ▼
                                  Object Tracking
                                         │
                                         ▼
                                    Risk Engine
                                         │
                                         ▼
                                Announcement Manager
                                         │
                                         ▼
                                         TTS
Installation
Requirements
Android Studio
Android device with camera
Android microphone access
Android device capable of running the LiteRT/TFLite model
Sufficient storage for the model and application
Build

Clone the repository:

git clone https://github.com/Abhishek-00756/dhwani_drishti.git

Enter the project:

cd dhwani_drishti

Open the project in Android Studio.

Allow Gradle to synchronize dependencies.

Build the application:

./gradlew assembleDebug
Running the Application
Connect an Android device.
Enable USB debugging.
Open the project in Android Studio.
Build the application.
Install it on the device.
Grant camera permission.
Grant microphone permission.
Complete or skip depth calibration.
Select:
SOUNDSCAPE

or:

NARRATED
Testing Voice Commands

After enabling microphone access, test:

Hey Dhwani, what's in front of me?
Hey Dhwani, where is the person?
Hey Dhwani, where is the laptop?
Hey Dhwani, where is the door?
Hey Dhwani, find the book.
Hey Dhwani, read.
Debugging

Useful Logcat tags include:

ObjectDetector
DHWANI_VOICE
DHWANI_PIPELINE
ModeB
ObjectTracker

For YOLO26 verification, check:

Actual input shape: [1, 3, 512, 512]
Actual output shape: [1, 300, 6]

Successful detection logs look like:

YOLO26 DETECTIONS: 1
person confidence=0.72
Repository

GitHub:

https://github.com/Abhishek-00756/dhwani_drishti

License

This project is currently under development.

Add an appropriate open-source license before distributing the project publicly.

Disclaimer

Dhwani Drishti is a research and development project intended to explore the use of artificial intelligence and computer vision for accessibility.

It should not be considered a replacement for a trained guide, mobility aid, professional navigation system, or other safety-critical equipment.

The developers are not responsible for incidents resulting from reliance on model predictions or application output.

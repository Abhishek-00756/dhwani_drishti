# Dhwani Drishti — Modes A + B (Soundscape + Narrated)

Camera to depth to real-time audio, built for blind / low-vision users.
Camera feed -> LiteRT MiDaS small depth map (256x256) -> L/C/R zone
processing -> stereo sine synthesis via `AudioTrack` streaming. All inference
is on-device — Python only appears once, offline, during model conversion
(see `tools/`); it never ships. The APK contains only Kotlin, the on-device
LiteRT runtime, and the `.tflite` models as plain assets.

**Mode A (Soundscape)** is the continuous tone engine. **Mode B (Narrated)**
adds a second, lighter model (YOLOv8n -> .tflite) whose boxes are fused with
the depth map and spoken as short phrases ("Chair, 2 meters ahead"), with a
centroid tracker so it can say "A person is coming from your left." A mode
toggle cycles Soundscape / Narrated / Hybrid.

**Hybrid** runs the soundscape continuously as the background layer and lets
narrated announcements duck it: the tone drops to ~20% volume while a phrase
is spoken (driven by `AnnouncementManager.isSpeaking` -> `SonificationEngine`
ducking), then restores. Both audio streams use
`USAGE_ASSISTANCE_ACCESSIBILITY` so the OS never fights them over audio focus
— this also means both route automatically to whatever output is active:
wired, Bluetooth earbuds, or bone-conduction, with no extra code required.

**Coverage limitation.** The phone camera has a single fixed forward-facing
field of view. It covers chest/head-height obstacles ahead — the white
cane's blind spot — but not the ground, sides, or behind; the cane still
covers ground level. This is complementary coverage, not full 360°/full-body
coverage, and should be described that way in any pitch materials.

Two threads carry the load:

- **Camera / inference thread** (~15-20 Hz): captures, infers depth, reduces to
  zone closeness, writes audio parameters.
- **Audio thread** (44,100 Hz): renders the tone continuously, reading the
  *latest* parameters with no blocking and no queueing.

This producer-consumer split is what keeps latency low and the tone glitch-free.

## Project structure

```
app/src/main/
├── assets/
│   ├── README.md                     # placeholders for midas_small.tflite + yolov8n_fp16.tflite
├── java/com/dhwanidrishti/app/
│   ├── MainActivity.kt               # permissions, mode toggle, calibration flow, debug overlay
│   ├── camera/CameraController.kt
│   ├── ml/DepthEstimator.kt
│   ├── ml/ObjectDetector.kt          # Mode B: YOLOv8n LiteRT (lazy-loaded)
│   ├── processing/ZoneProcessor.kt   # Mode A L/C/R zones
│   ├── processing/Zone.kt            # L/C/R sectors + spoken labels
│   ├── processing/DetectedObject.kt  # RawDetection/DetectedObject + depth fusion
│   ├── processing/ObjectTracker.kt   # centroid tracker + isApproaching
│   ├── audio/SonificationEngine.kt   # Mode A tone, duckable (hybrid)
│   ├── audio/AnnouncementManager.kt  # Mode B TTS, cooldown, priority
│   ├── pipeline/DhwaniPipeline.kt    # routes to Mode A, B, or hybrid
│   ├── pipeline/ModeBEngine.kt       # detection -> fusion -> track -> announce
│   └── calibration/CalibrationManager.kt
└── AndroidManifest.xml
tools/
├── convert_midas.py                  # MiDaS -> .tflite (run once, offline)
└── convert_yolo.py                   # YOLOv8n -> .tflite, NMS baked in (run once, offline)
```

## Setup

1. Open in Android Studio (Ladybug or newer). Min SDK 24, target SDK 35.
2. If the Gradle wrapper jar is missing, let Android Studio regenerate it
   (Sync Project) or run `gradle wrapper`.
3. Generate the models (see `tools/convert_midas.py` and
   `tools/convert_yolo.py`) and place them at
   `app/src/main/assets/midas_small.tflite` and
   `app/src/main/assets/yolov8n_fp16.tflite`. The detection model is loaded
   lazily, so the soundscape runs even before it exists.
4. Build and install on a **physical device** — emulators can't give real
   camera/GPU timing.

## Build order (milestones)

1. **Frame counter**: CameraX preview + `ImageAnalysis` delivering frames;
   confirm with the fps text in the debug overlay.
2. **Static model test**: load one sample image, run `DepthEstimator`, log
   output shape/values — validate the model before wiring the live camera.
3. **Zone processing** on the static image; sanity-check against a manually
   measured scene.
4. **Isolated audio**: `SonificationEngine` producing a continuous, glitch-free
   tone with hardcoded frequencies (no underruns before real data).
5. **Full pipeline**: live camera -> model -> zones -> audio.
6. **Calibration flow + polish**: buffer reuse, executor cleanup on `onDestroy`.
7. **Field tuning**: adjust the closeness -> pitch curve from real feedback.

## Calibration (first launch)

MiDaS gives *relative* inverse depth, so a short calibration pass is needed.
On first launch a TalkBack-friendly overlay appears over the live preview with
**Record near** / **Record far** buttons:

- Point the phone at a wall ~0.5 m away and press **Record near**, then ~3 m
  away and press **Record far**. Each button asks the pipeline to capture the
  raw model output on the next processed frame (one-shot max over the depth
  map — no per-frame cost when idle).
- `CalibrationManager` stores both in `SharedPreferences` and normalizes each
  frame to closeness in [0,1] (1 = nearest).
- The flow can be skipped; before calibration the app falls back to per-frame
  min/max normalization, which has no absolute-distance meaning. Calibration
  is reset by deleting app data.

## Latency budget (target <100 ms glass-to-ear)

| Stage | Budget |
|---|---|
| Camera capture + buffer copy | 5-10 ms |
| Preprocess (resize/normalize) | ~5 ms |
| Model inference (GPU, 256x256) | 30-50 ms |
| Zone processing | <2 ms |
| Audio parameter update -> next write | <20 ms |

The debug overlay (`statsView`) shows rolling fps, model inference ms, and
total per-frame ms (capture→audio update). If over budget: drop to a 192x192
model, confirm the GPU delegate actually attached (log line
`GPU delegate attached` vs `GPU delegate unavailable, running on CPU` — some
devices silently fall back), or reduce the `AudioTrack` buffer multiplier (too
small risks underruns).

## Key design choices

- **`STRATEGY_KEEP_ONLY_LATEST`** discards stale frames instead of queueing
  them — always process the freshest reality.
- **Buffer reuse**: `ByteBuffer` / `FloatArray` / resize `Bitmap` are allocated
  once and reused, so the GC never pauses the live stream.
- **GPU delegate with CPU fallback** (`CompatibilityList` + `setNumThreads(4)`),
  since GPU behavior varies across chipsets.
- **ImageNet normalization** is applied before inference — MiDaS expects
  mean/std-normalized RGB, not raw `[0,1]` pixels.
- **Zone closeness = 95th percentile** per sector (not the strict max), so a
  single hot pixel cannot spike the reading.
- **Exponential moving average** (`0.6*old + 0.4*new`) across frames prevents
  audio jitter.
- **`USAGE_ASSISTANCE_ACCESSIBILITY`** flags the tone as accessibility-critical
  to the OS.

## Risk areas (budget extra time)

- GPU delegate behaving inconsistently across chipsets (Snapdragon vs MediaTek
  vs Exynos) — prototype milestone 2 in isolation.
- `AudioTrack` streaming staying glitch-free under real thread scheduling —
  prototype milestone 4 in isolation.

## Mode B (Narrated) — design

- **Second model**: YOLOv8n exported to `.tflite` (fp16, `nms=True`) via
  `tools/convert_yolo.py`. `ObjectDetector` letterboxes the frame, parses the
  baked-in-NMS `[1, 6, maxDet]` output (or the `[1, maxDet, 6]` layout some
  ultralytics versions emit), and returns normalized boxes.
- **Fusion**: each box's centroid samples the closeness map; closeness is
  inverted to *distance* (0 = nearest) so "smaller is closer" holds for
  phrases and the approaching detector. `DetectedObject.distance` therefore
  has inverse semantics to Mode A's closeness on purpose.
- **Tracking**: `ObjectTracker` matches each detection to the nearest existing
  track with the same label within 15% normalized distance, keeps a rolling
  distance history, and drops tracks unseen for 2s. `isApproaching` compares
  the last 4 samples.
- **Announcements**: `AnnouncementManager` only speaks on meaningful events —
  one phrase at a time (utterance listener resets `isSpeaking`, so it can't
  get stuck), a 6s per-object cooldown, closest/most-urgent first, and
  "approaching" outranks static distance.
- **Throttling**: `ModeBEngine` runs detection at ~6.6 fps (150ms interval) on
  the same inference thread — objects don't need 20 Hz.
- **Ducking**: in hybrid mode the tone keeps running but `SonificationEngine`
  multiplies channel gain by 0.2 while TTS is speaking (`setDucking`), so the
  soundscape gives way cleanly instead of shouting over the phrase.

## Field notes

Mode B detection rate is set by `ModeBEngine.minDetectionIntervalMs`; the
debug overlay's objects counter is the number of live tracks. TTS voice
quality varies by device; `AnnouncementManager` already selects the
highest-quality installed on-device voice automatically. Bundling a
dedicated neural TTS engine for a more natural voice is a possible future
enhancement — it adds APK size and is a stretch goal, not required for MVP.

## Mode B build order (milestones)

1. Get `ObjectDetector` running standalone on a static test image — confirm
   boxes + labels before touching the live camera (detection model loads
   lazily, so wire it on its own).
2. Wire depth fusion — confirm distance values make sense for a few known
   objects.
3. Add the tracker with hardcoded/logged output — verify "approaching" fires
   by walking toward a chair and watching Logcat.
4. Add `AnnouncementManager`, tuning `COOLDOWN_MS` and `NEAR_THRESHOLD` by ear
   — real walking tests, not unit tests, since the right values are about
   human comfort.
5. Only after both modes work independently, tune the hybrid ducking — it is
   the trickiest to make feel natural, so don't start there.

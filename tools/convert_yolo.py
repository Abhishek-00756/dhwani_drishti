#!/usr/bin/env python3
"""Convert YOLOv8n (COCO object detection) to a LiteRT (.tflite) model for Mode B.

Run once on a dev machine (not on the phone), then copy the output into
app/src/main/assets/yolov8n_fp16.tflite.

Prerequisites:

    pip install -r tools/requirements.txt

Example:

    python tools/convert_yolo.py --imgsz 320 --output app/src/main/assets/yolov8n_fp16.tflite

The export bakes NMS into the graph (nms=True), so the model emits a single
[1, 6, maxDet] tensor (x1, y1, x2, y2, confidence, classId) — the parser in
ObjectDetector.kt also accepts the [1, maxDet, 6] layout produced by some
ultralytics versions. Keep the default COCO 80 class order; ObjectDetector
maps class ids against the same list.

imgsz=320 keeps detection cheap alongside MiDaS; 640 is more accurate but
heavier. int8 needs a representative calibration dataset and is not needed
for the MVP.
"""

import argparse
import glob
import os
import shutil
import sys


def main():
    parser = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--weights", default="yolov8n.pt")
    parser.add_argument(
        "--imgsz",
        type=int,
        default=320,
        help="model input size (320 = light beside MiDaS, 640 = more accurate)",
    )
    parser.add_argument("--output", default="app/src/main/assets/yolov8n_fp16.tflite")
    parser.add_argument("--max-det", type=int, default=20, help="max detections kept by NMS")
    args = parser.parse_args()

    from ultralytics import YOLO

    model = YOLO(args.weights)
    model.export(
        format="tflite",
        half=True,   # fp16
        int8=False,
        nms=True,    # NMS baked into the graph -> [1, 6, maxDet] output
        imgsz=args.imgsz,
        max_det=args.max_det,
    )

    stem = os.path.splitext(os.path.basename(args.weights))[0]
    matches = [f for f in glob.glob("*.tflite") if f.startswith(stem)]
    if not matches:
        print("[error] no .tflite produced; check the ultralytics export output", file=sys.stderr)
        return 1

    shutil.move(matches[0], args.output)
    print(f"[ok] exported {args.output} (fp16, NMS baked in, imgsz {args.imgsz})")
    return 0


if __name__ == "__main__":
    sys.exit(main())

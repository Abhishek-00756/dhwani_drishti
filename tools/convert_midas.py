#!/usr/bin/env python3
"""Convert MiDaS small to a LiteRT (.tflite) model."""

import argparse
import sys

INPUT_SHAPE = (1, 3, 256, 256)


def load_model():
    import torch

    print("[1/3] Loading MiDaS_small...")

    model = torch.hub.load(
        "intel-isl/MiDaS",
        "MiDaS_small",
        trust_repo=True,
    )

    model.eval()

    print("[ok] MiDaS_small loaded")
    return model


def convert_litert_torch(model, output_path):
    import torch
    import litert_torch

    print("[2/3] Converting PyTorch model to LiteRT...")

    dummy = (torch.rand(*INPUT_SHAPE),)

    with torch.no_grad():
        edge_model = litert_torch.convert(
            model,
            dummy,
        )

    print("[3/3] Exporting TFLite model...")

    edge_model.export(output_path)

    print(f"[ok] exported {output_path}")


def main():
    parser = argparse.ArgumentParser(
        description="Convert MiDaS_small to LiteRT"
    )

    parser.add_argument(
        "--method",
        choices=["ai-edge-torch", "litert-torch"],
        default="litert-torch",
    )

    parser.add_argument(
        "--output",
        default="midas_small.tflite",
    )

    args = parser.parse_args()

    model = load_model()

    convert_litert_torch(
        model,
        args.output,
    )

    return 0


if __name__ == "__main__":
    sys.exit(main())
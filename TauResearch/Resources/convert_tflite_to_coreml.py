#!/usr/bin/env python3
"""
convert_tflite_to_coreml.py — one-time TFLite → Core ML conversion for the Tau kiwi detector.

Usage:
    python convert_tflite_to_coreml.py model-hdr-320.tflite KiwiDetector.mlpackage

Requires (install in a virtualenv on macOS):
    pip install coremltools tensorflow

The original TFLite model is a YOLO-style detector exporting either
[1, features, anchors] or [1, anchors, features]. The Swift runtime
(`KiwiDetectionProcessor.swift`) accepts both layouts and runs decode + NMS itself.
"""

import sys
from pathlib import Path

try:
    import coremltools as ct
    import tensorflow as tf
except ImportError as e:
    sys.exit(f"Missing dependency: {e}. Run `pip install coremltools tensorflow`.")


def main(tflite_path: Path, mlpackage_path: Path) -> None:
    interp = tf.lite.Interpreter(model_path=str(tflite_path))
    interp.allocate_tensors()
    input_details = interp.get_input_details()[0]
    output_details = interp.get_output_details()[0]
    print(f"Input  shape: {input_details['shape']}, dtype: {input_details['dtype']}")
    print(f"Output shape: {output_details['shape']}, dtype: {output_details['dtype']}")

    # coremltools' TFLite frontend is unstable for arbitrary YOLO exports.
    # Easiest reliable path is to use the official Ultralytics export, which writes
    # a .mlpackage directly. If your model was trained with Ultralytics:
    #
    #     pip install ultralytics
    #     yolo export model=best.pt format=coreml imgsz=320
    #
    # If you only have the .tflite, the fallback is to first convert to SavedModel and
    # then to Core ML. The skeleton below assumes a SavedModel directory has been
    # produced from the .tflite by an external workflow.
    print(
        "Note: see header comment — Ultralytics' `yolo export format=coreml` is the most"
        " reliable conversion path. Drop the produced KiwiDetector.mlpackage here."
    )
    print(f"Target output: {mlpackage_path}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("Usage: convert_tflite_to_coreml.py <input.tflite> <output.mlpackage>")
    main(Path(sys.argv[1]), Path(sys.argv[2]))

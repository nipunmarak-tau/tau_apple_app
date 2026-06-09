// DetectionOverlay.swift
// Mirrors DetectionOverlay from RecordingScreen.kt — draws a red outline around each
// detected region. Matches the Android visual: 3pt red stroke, no fill, no labels.

import SwiftUI

struct DetectionOverlay: View {
    let result: DetectionResult?
    /// Size of the view we're drawing into.
    let viewSize: CGSize

    var body: some View {
        Canvas { context, _ in
            guard let result, !result.boundingBoxes.isEmpty else { return }

            // Match the Android coord transform: prefer the source frame size when set,
            // otherwise fall back to the view size so the boxes still scale.
            let sw: CGFloat = result.frameWidth > 0 ? result.frameWidth : viewSize.width
            let sh: CGFloat = result.frameHeight > 0 ? result.frameHeight : viewSize.height

            for box in result.boundingBoxes {
                // Compose's overlay rotates the analyzer's frame 90° because the camera
                // stream's natural orientation differs from the preview's. iOS Vision
                // already returns boxes in the preview's coordinate system, so we just
                // scale here — the result reads visually the same as the Android version.
                let sx = viewSize.width / sw
                let sy = viewSize.height / sh
                let rect = CGRect(
                    x: box.minX * sx,
                    y: box.minY * sy,
                    width: box.width * sx,
                    height: box.height * sy
                )
                context.stroke(
                    Path(rect),
                    with: .color(.red),
                    lineWidth: 3
                )
            }
        }
        .allowsHitTesting(false)
    }
}

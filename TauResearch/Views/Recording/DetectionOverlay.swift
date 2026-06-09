// DetectionOverlay.swift
// Mirrors DetectionOverlay from RecordingScreen.kt — draws bounding boxes over the preview.

import SwiftUI

struct DetectionOverlay: View {
    let result: DetectionResult?
    /// Size of the view we're drawing into.
    let viewSize: CGSize

    var body: some View {
        Canvas { context, _ in
            guard let result, !result.boundingBoxes.isEmpty,
                  result.frameWidth > 0, result.frameHeight > 0 else { return }
            let sx = viewSize.width / result.frameWidth
            let sy = viewSize.height / result.frameHeight
            for box in result.boundingBoxes {
                let path = Path(
                    CGRect(x: box.minX * sx,
                           y: box.minY * sy,
                           width: box.width * sx,
                           height: box.height * sy)
                )
                context.stroke(path,
                               with: .color(Color.brandGreen.opacity(0.95)),
                               lineWidth: 2)
            }
        }
        .allowsHitTesting(false)
    }
}

// KiwiDetectionProcessor.swift
// Mirrors KiwiDetectionProcessor.kt — YOLO-style decode + NMS, but using Core ML / Vision
// instead of TensorFlow Lite.
//
// The model file is `KiwiDetector.mlpackage` — generated from `model-hdr-320.tflite` by the
// helper script in `Resources/convert_tflite_to_coreml.py`. If the converted model isn't
// in the bundle yet, the processor degrades gracefully to a luma-only result.

import Foundation
import CoreImage
import CoreML
import Vision
import Accelerate

actor KiwiDetectionProcessor {

    // MARK: - Tunables (kept identical to the Kotlin processor)

    private let detectionThreshold: Float = 0.25
    private let nmsIouThreshold: Float = 0.50
    private let kiwiClassIndex = 1

    // MARK: - Core ML state

    private var visionModel: VNCoreMLModel?
    private var backend: DetectionBackend = .unknown

    init() {
        Task {
            await loadModel()
        }
    }

    private func loadModel() {
        guard let url = Bundle.main.url(forResource: "KiwiDetector", withExtension: "mlmodelc")
              ?? Bundle.main.url(forResource: "KiwiDetector", withExtension: "mlpackage") else {
            AppHealthMonitor.shared.reportIssue(
                .init(
                    key: "ml_model_missing",
                    message: "KiwiDetector.mlpackage not in bundle (run convert_tflite_to_coreml.py)",
                    severity: .high,
                    area: "ml.load"
                )
            )
            return
        }
        do {
            let config = MLModelConfiguration()
            config.computeUnits = .all   // prefer Apple Neural Engine
            let coreModel = try MLModel(contentsOf: url, configuration: config)
            self.visionModel = try VNCoreMLModel(for: coreModel)
            self.backend = .neuralEngine
        } catch {
            AppHealthMonitor.shared.captureException(area: "ml.load", error: error)
        }
    }

    func warmUp() async -> Bool {
        guard let visionModel else { return false }
        // 320x320 zeroed pixel buffer (matches the model input).
        let attrs: [String: Any] = [
            kCVPixelBufferCGImageCompatibilityKey as String: true,
            kCVPixelBufferCGBitmapContextCompatibilityKey as String: true
        ]
        var pb: CVPixelBuffer?
        CVPixelBufferCreate(nil, 320, 320, kCVPixelFormatType_32BGRA, attrs as CFDictionary, &pb)
        guard let pixelBuffer = pb else { return false }
        let request = VNCoreMLRequest(model: visionModel)
        let handler = VNImageRequestHandler(cvPixelBuffer: pixelBuffer, options: [:])
        do {
            try handler.perform([request])
            return true
        } catch {
            AppHealthMonitor.shared.captureException(area: "ml.warm_up", error: error)
            return false
        }
    }

    // MARK: - Public detection API

    func processImage(pixelBuffer: CVPixelBuffer) async -> DetectionResult {
        let w = CGFloat(CVPixelBufferGetWidth(pixelBuffer))
        let h = CGFloat(CVPixelBufferGetHeight(pixelBuffer))
        let frameLuma = computeFrameLuma(pixelBuffer: pixelBuffer)

        guard let visionModel else {
            return emptyResult(frameLuma: frameLuma, width: w, height: h)
        }

        let request = VNCoreMLRequest(model: visionModel)
        request.imageCropAndScaleOption = .scaleFill
        let handler = VNImageRequestHandler(cvPixelBuffer: pixelBuffer, options: [:])

        do {
            try handler.perform([request])
        } catch {
            AppHealthMonitor.shared.captureException(area: "ml.inference", error: error)
            return emptyResult(frameLuma: frameLuma, width: w, height: h)
        }

        let predictions = decode(observations: request.results ?? [],
                                 frameWidth: w,
                                 frameHeight: h)

        guard !predictions.isEmpty else {
            return emptyResult(frameLuma: frameLuma, width: w, height: h)
        }

        // Subject luma: average luma over each detection box inflated by 25%.
        var lumaSum: Int64 = 0
        var lumaCount = 0
        for box in predictions.map(\.box) {
            let luma = computeBoxLuma(pixelBuffer: pixelBuffer,
                                      box: box.inflated(by: 0.25, in: CGRect(x: 0, y: 0, width: w, height: h)))
            if luma > 0 { lumaSum += Int64(luma); lumaCount += 1 }
        }
        let subjectLuma = lumaCount > 0 ? Int(lumaSum / Int64(lumaCount)) : frameLuma
        let best = predictions.map(\.confidence).max() ?? 0

        return DetectionResult(
            confidenceScore: best * 100,
            status: "Identified",
            boundingBoxes: predictions.map(\.box),
            backend: backend,
            objectLuma: subjectLuma,
            avgFrameLuma: frameLuma,
            displayBoundingBox: nil,
            frameWidth: w,
            frameHeight: h
        )
    }

    /// Updates the luma metrics on a new frame using the last detection's boxes — lets the
    /// AE loop run at higher cadence than the model.
    func updateLumaOnly(pixelBuffer: CVPixelBuffer, lastResult: DetectionResult?) -> DetectionResult {
        let w = CGFloat(CVPixelBufferGetWidth(pixelBuffer))
        let h = CGFloat(CVPixelBufferGetHeight(pixelBuffer))
        let frameLuma = computeFrameLuma(pixelBuffer: pixelBuffer)

        guard let last = lastResult, !last.boundingBoxes.isEmpty else {
            return emptyResult(frameLuma: frameLuma, width: w, height: h)
        }
        var lumaSum: Int64 = 0
        var lumaCount = 0
        for box in last.boundingBoxes {
            let luma = computeBoxLuma(pixelBuffer: pixelBuffer,
                                      box: box.inflated(by: 0.25, in: CGRect(x: 0, y: 0, width: w, height: h)))
            if luma > 0 { lumaSum += Int64(luma); lumaCount += 1 }
        }
        let subjectLuma = lumaCount > 0 ? Int(lumaSum / Int64(lumaCount)) : frameLuma

        return DetectionResult(
            confidenceScore: last.confidenceScore,
            status: last.status,
            boundingBoxes: last.boundingBoxes,
            backend: backend,
            objectLuma: subjectLuma,
            avgFrameLuma: frameLuma,
            displayBoundingBox: last.displayBoundingBox,
            frameWidth: w,
            frameHeight: h
        )
    }

    /// Mirror of `processImageFrameLumaOnly` — for non-Fruit scan types.
    nonisolated func lumaOnly(pixelBuffer: CVPixelBuffer) -> DetectionResult {
        let w = CGFloat(CVPixelBufferGetWidth(pixelBuffer))
        let h = CGFloat(CVPixelBufferGetHeight(pixelBuffer))
        let frameLuma = computeFrameLuma(pixelBuffer: pixelBuffer)
        return DetectionResult(
            confidenceScore: 0,
            status: "Searching",
            boundingBoxes: [],
            backend: .unknown,
            objectLuma: 0,
            avgFrameLuma: frameLuma,
            displayBoundingBox: nil,
            frameWidth: w,
            frameHeight: h
        )
    }

    // MARK: - Decoding

    fileprivate struct KiwiPrediction { let confidence: Float; let box: CGRect }

    private func decode(
        observations: [VNObservation],
        frameWidth: CGFloat,
        frameHeight: CGFloat
    ) -> [KiwiPrediction] {
        // Vision-friendly path: many YOLO Core ML models export VNRecognizedObjectObservations.
        let objectObservations = observations.compactMap { $0 as? VNRecognizedObjectObservation }
        if !objectObservations.isEmpty {
            return objectObservations.compactMap { obs -> KiwiPrediction? in
                guard let label = obs.labels.first else { return nil }
                let confidence = Float(label.confidence)
                guard confidence >= detectionThreshold else { return nil }
                // Vision's normalised bbox has origin in bottom-left.
                let bb = obs.boundingBox
                let x = bb.origin.x * frameWidth
                let y = (1.0 - bb.origin.y - bb.height) * frameHeight
                let w = bb.width * frameWidth
                let h = bb.height * frameHeight
                let rect = CGRect(x: x, y: y, width: w, height: h)
                return KiwiPrediction(confidence: confidence, box: rect)
            }
            .nms(threshold: nmsIouThreshold)
        }

        // Raw multi-array path (when model exports tensors directly).
        let multiArrays = observations.compactMap { ($0 as? VNCoreMLFeatureValueObservation)?.featureValue.multiArrayValue }
        guard let array = multiArrays.first else { return [] }
        return decodeYoloRaw(array: array, frameWidth: frameWidth, frameHeight: frameHeight)
            .nms(threshold: nmsIouThreshold)
    }

    private func decodeYoloRaw(
        array: MLMultiArray,
        frameWidth: CGFloat,
        frameHeight: CGFloat
    ) -> [KiwiPrediction] {
        // Expected shape: [1, features, anchors] or [1, anchors, features].
        guard array.shape.count == 3 else { return [] }
        let d1 = array.shape[1].intValue
        let d2 = array.shape[2].intValue
        let features = d1 >= 6 ? d1 : d2
        let anchors = d1 >= 6 ? d2 : d1
        let transposed = d1 < 6

        var candidates: [KiwiPrediction] = []
        candidates.reserveCapacity(anchors)

        @inline(__always)
        func value(_ feature: Int, _ anchor: Int) -> Float {
            let i, j: Int
            if transposed { i = anchor; j = feature } else { i = feature; j = anchor }
            return array[[0, i, j] as [NSNumber]].floatValue
        }

        for a in 0..<anchors {
            let cx = value(0, a)
            let cy = value(1, a)
            let w = value(2, a)
            let h = value(3, a)

            let score: Float
            if features >= 6 {
                let obj = value(4, a).clamped(to: 0...1)
                let cls = value(min(kiwiClassIndex + 5, features - 1), a).clamped(to: 0...1)
                score = features >= (kiwiClassIndex + 6) ? obj * cls : cls
            } else {
                score = 0
            }
            if score < detectionThreshold { continue }

            let left = (cx - w / 2) * Float(frameWidth)
            let top = (cy - h / 2) * Float(frameHeight)
            let right = (cx + w / 2) * Float(frameWidth)
            let bottom = (cy + h / 2) * Float(frameHeight)
            guard right > left, bottom > top else { continue }

            candidates.append(KiwiPrediction(
                confidence: score,
                box: CGRect(x: CGFloat(left),
                            y: CGFloat(top),
                            width: CGFloat(right - left),
                            height: CGFloat(bottom - top))
            ))
        }
        return candidates
    }

    // MARK: - Luma helpers

    private nonisolated func computeFrameLuma(pixelBuffer: CVPixelBuffer) -> Int {
        let stride = 8
        return averageLuma(pixelBuffer: pixelBuffer, step: stride, rect: nil)
    }

    private nonisolated func computeBoxLuma(pixelBuffer: CVPixelBuffer, box: CGRect) -> Int {
        averageLuma(pixelBuffer: pixelBuffer, step: 4, rect: box)
    }

    /// Strided mean luma over the Y plane of a (semi-)planar YUV buffer.
    private nonisolated func averageLuma(pixelBuffer: CVPixelBuffer, step: Int, rect: CGRect?) -> Int {
        CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly) }

        guard let base = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 0) else { return 0 }
        let width = CVPixelBufferGetWidthOfPlane(pixelBuffer, 0)
        let height = CVPixelBufferGetHeightOfPlane(pixelBuffer, 0)
        let bytesPerRow = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 0)
        let ptr = base.assumingMemoryBound(to: UInt8.self)

        let xStart: Int, xEnd: Int, yStart: Int, yEnd: Int
        if let rect {
            xStart = max(0, Int(rect.minX))
            xEnd = min(width, Int(rect.maxX))
            yStart = max(0, Int(rect.minY))
            yEnd = min(height, Int(rect.maxY))
        } else {
            xStart = 0; xEnd = width; yStart = 0; yEnd = height
        }

        var sum: Int64 = 0
        var count: Int64 = 0
        var y = yStart
        while y < yEnd {
            var x = xStart
            let row = ptr.advanced(by: y * bytesPerRow)
            while x < xEnd {
                sum += Int64(row[x])
                count += 1
                x += step
            }
            y += step
        }
        return count > 0 ? Int(sum / count) : 0
    }

    private func emptyResult(frameLuma: Int, width: CGFloat, height: CGFloat) -> DetectionResult {
        DetectionResult(
            confidenceScore: 0,
            status: "Searching",
            boundingBoxes: [],
            backend: backend,
            objectLuma: 0,
            avgFrameLuma: frameLuma,
            displayBoundingBox: nil,
            frameWidth: width,
            frameHeight: height
        )
    }
}

// MARK: - Small helpers

private extension CGRect {
    func inflated(by fraction: CGFloat, in bounds: CGRect) -> CGRect {
        let padX = width * fraction
        let padY = height * fraction
        return CGRect(
            x: max(minX - padX, bounds.minX),
            y: max(minY - padY, bounds.minY),
            width: min(width + 2 * padX, bounds.width),
            height: min(height + 2 * padY, bounds.height)
        ).intersection(bounds)
    }
}

private extension Array where Element == KiwiDetectionProcessor.KiwiPrediction {
    /// Non-max suppression over confidence-sorted predictions.
    func nms(threshold: Float) -> [KiwiDetectionProcessor.KiwiPrediction] {
        let sorted = self.sorted { $0.confidence > $1.confidence }
        var keep: [KiwiDetectionProcessor.KiwiPrediction] = []
        for candidate in sorted {
            let overlaps = keep.contains { iou(candidate.box, $0.box) > threshold }
            if !overlaps { keep.append(candidate) }
        }
        return keep
    }
}

private func iou(_ a: CGRect, _ b: CGRect) -> Float {
    let inter = a.intersection(b)
    if inter.isNull || inter.width <= 0 || inter.height <= 0 { return 0 }
    let interArea = Float(inter.width * inter.height)
    let union = Float(a.width * a.height + b.width * b.height) - interArea
    return union <= 0 ? 0 : interArea / union
}


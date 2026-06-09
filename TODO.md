# Kiwi TFLite Migration TODO

- [x] Inspect current detection data model and processor contract in `processing/GreenDetectionProcessor.kt`
- [x] Implement new TFLite-based kiwi detector using `model.tflite`
- [x] Replace detection processor wiring in `Camera2Controller.kt`
- [ ] Update ViewModel smoothing/state logic for model-based detection
- [ ] Update UI labels/overlay/status in `RecordingScreen.kt` from green-pixel terms to kiwi model detection
- [ ] Verify build compiles
- [ ] Provide runtime HDR/SDR validation checklist for kiwi detection

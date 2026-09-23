# Upscaler — Offline AI Photo & Video Upscaler (Android)

Native Android app (Kotlin + Jetpack Compose + Material 3) that upscales
**photos and videos fully offline** on-device with compact super-resolution
networks (**SRVGGNetCompact**, the family behind Real-ESRGAN's fast
"general x4v3" / "animevideov3" models), running on
[ncnn](https://github.com/Tencent/ncnn) with automatic
**Vulkan GPU → multi-threaded CPU fallback**.

> **100% offline by design** — the app does not even declare the
> `INTERNET` permission. Your photos and videos never leave the device.

## Highlights

- **Photos**: pick one or many, choose model (photo / anime / denoise) and
  2x/4x scale, batch queue via WorkManager, before/after **compare slider**,
  pinch-zoom, save as PNG/JPEG/WEBP, share sheet, auto-tiling for big images.
- **Videos**: frame-extract → upscale → re-encode pipeline
  (MediaCodec/MediaExtractor/MediaMuxer, no FFmpeg), foreground service with
  progress + cancel notification, **untouched audio passthrough**,
  resolution cap (1080p / 4K), and a **2-frame quality preview** before you
  commit to a long job.
- **Library**: every job persisted in Room; grid of thumbnails, filters,
  delete, re-open with compare.
- **Settings**: default model/scale/format/quality, backend override
  (Auto / Force GPU / Force CPU) with a real on-device **benchmark**,
  theme (System/Light/Dark + Material You dynamic color on Android 12+),
  cache management, **English + Bengali** UI with in-app switcher.

## Tech stack

| Layer | Choice |
|---|---|
| UI | Jetpack Compose, Material 3 (dynamic color, `NavigationBar`, bottom sheets, shimmer, haptics) |
| Architecture | MVVM + Clean-ish layering (`domain` / `data` / `processing` / `ui`), UDF with `StateFlow` |
| DI | Hilt (+ `hilt-work`, `hilt-navigation-compose`) |
| Async | Coroutines & Flow; inference pinned to a dedicated single-thread dispatcher |
| Inference | ncnn (vendored prebuilt, Vulkan + ARM NEON CPU) via a thin JNI bridge (`app/src/main/cpp/ncnn_jni.cpp`) |
| Video I/O | `MediaCodec` + `MediaExtractor` + `MediaMuxer` (H.264 out, audio copied untouched) |
| Storage | Scoped-storage compliant `MediaStore` saves; Room history; DataStore prefs |
| Min / target SDK | 26 / 34 |

## Building

```bash
git clone https://github.com/devfahim00/Upscaler
cd Upscaler
./gradlew assembleDebug          # APK in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest      # domain-layer unit tests
```

Requirements: JDK 17, Android SDK (AGP auto-installs the NDK + CMake it
needs). The ncnn prebuilt libs (arm64-v8a + armeabi-v7a) and the model
weights are **vendored in the repo**, so no extra downloads are required.

CI runs on every push: `assembleDebug` + unit tests
(`.github/workflows/android-ci.yml`).

## How the tiling works

Large images are processed in **overlapping tiles** so memory stays bounded
(no OOM on a 12 MP photo):

1. `TilePlanner` (pure Kotlin, unit-tested) splits the image into tile
   *bodies* of `tileSize` (128 CPU / 256 GPU for photos, 256/512 for video)
   that **exactly partition** the output.
2. Each tile's input patch is the body expanded by `overlap` (16 px) of
   context on every side, clamped to the image bounds — the same scheme as
   the reference Real-ESRGAN ncnn implementation. The extra context lets the
   fully-convolutional network produce clean output near patch borders.
3. Only the body region of each tile output is copied into the final buffer
   (`TileOps.copyBody`), so bodies never overlap or leave gaps.
4. `MemoryGuard` caps the total output at ~16 MP; when 4x would exceed it,
   the effective scale is reduced transparently and a notice is shown.

When the requested scale is **below** the model's native scale (e.g. 2x with
the x4 photo model), the input is pre-shrunk so the native output equals the
requested output — bounded memory and faster than native-pass + downscale.

## How backend selection works

`SelectBackendUseCase` (pure Kotlin, unit-tested):

1. **Forced GPU / Forced CPU** — honoured; forced GPU on a device without
   usable Vulkan falls back to CPU (flagged).
2. **Auto** — uses Vulkan when `get_gpu_count() > 0`; if a benchmark exists
   and the GPU is more than 15% slower than the CPU (common on low-end
   GPUs), Auto picks the CPU instead.
3. The **Run benchmark** button in Settings measures both backends on a
   64×64 tile (8 iterations) and stores the result in DataStore.

CPU runs use a thread pool sized from `availableProcessors()` (clamped 2–8).

## Adding / replacing models

Models live in `app/src/main/assets/models/<name>/model.param` +
`model.bin` and are copied to `filesDir/models` on first launch by
`ModelAssetManager` (bump its `VERSION` constant to force re-extraction
after replacing weights).

To add or swap a model:

1. Convert your weights to ncnn format (e.g. Real-ESRGAN PyTorch → ONNX →
   [pnnx](https://github.com/pnnx/pnnx) → `.param`/`.bin`).
2. Make sure the input blob is named **`data`** and the output blob
   **`output`** (that's what the bundled models use; otherwise adjust
   `kInputBlob`/`kOutputBlob` in `ncnn_jni.cpp`).
3. Drop the files into `assets/models/<new-name>/`.
4. Add an entry to the `ModelType` enum
   (`app/src/main/java/com/devfahim/upscaler/domain/model/ModelType.kt`)
   with its native scale and the photo/video lists it appears in.

### Currently bundled

| App entry | Asset dir | Actual weight | Note |
|---|---|---|---|
| Photo x4 (default) | `realesr-general-x4v3` | official Real-ESRGAN compact general | — |
| Photo x2 (fast) | `realesr-animevideov3-x2` | animevideov3 x2 | **stand-in** (no public general-x2 compact weight); flagged in UI |
| Anime / Illustration x4 | `realesr-animevideov3-x4` | animevideov3 x4 | — |
| Denoise x4 | `realesr-general-x4v3` | general x4v3 | **stand-in**; general v3 already removes mild JPEG artifacts |
| Video x2 / x4 | `realesr-animevideov3-x2/x4` | animevideov3 | purpose-trained for per-frame video |

## Project layout

```
app/src/main/cpp/            JNI bridge to ncnn (C++)
app/src/main/assets/models/  ncnn model weights
ncnn-android/                vendored ncnn prebuilt (BSD-3, per ABI)
domain/                      pure-Kotlin models, tiling, use cases
data/                        Room, DataStore, MediaStore, engine wrappers
processing/                  photo worker, video foreground service, codecs
ui/                          Compose screens (Material 3)
```

## Roadmap (not in v1)

- Parallel frame processing for video (v1 is sequential per-frame)
- Play Billing paywall gating 4K video export / batch size
- True x2 general-photo weight when one is published

## Licenses & attribution

App code: MIT. ncnn and Real-ESRGAN keep their own licenses (BSD-3-Clause);
see `THIRD_PARTY_NOTICES.md` and the in-app About screen.

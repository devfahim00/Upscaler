# Upscaler — Offline AI Photo Upscaler (Android)

Native Android app (Kotlin + Jetpack Compose + Material 3) that upscales
**photos fully offline** on-device with super-resolution networks from
Real-ESRGAN — the compact **SRVGGNetCompact** models ("general x4v3" /
"animevideov3") for speed on any device, plus the full **RealESRGAN_x4plus**
RRDBNet as an optional High Quality mode — running on
[ncnn](https://github.com/Tencent/ncnn) with automatic
**Vulkan GPU → multi-threaded CPU fallback**.

> **100% offline by design** — the app does not even declare the
> `INTERNET` permission. Your photos never leave the device.

## Highlights

- **Photos**: pick one or many, choose model (photo / HQ / anime) and 2x/4x
  scale, batch queue via WorkManager, before/after **compare slider**,
  pinch-zoom, save as PNG/JPEG/WEBP, share sheet, auto-tiling for big images.
- **WDN denoise interpolation**: the photo x4 model has a denoise-trained
  twin weight (realesr-general-wdn-x4v3, identical architecture). The
  *Denoise strength* slider blends the two networks' weights at runtime —
  0% keeps the most texture, higher values remove more noise but look
  smoother. Blended weights are cached per strength step.
- **High Quality mode**: full RealESRGAN_x4plus (RRDBNet, 23 blocks) recovers
  real texture and fine detail the compact models smooth away. It runs with
  smaller tiles (64 CPU / 96 GPU) so it stays memory-safe even on low-RAM
  devices — at the cost of much longer processing times.
- **Library**: every job persisted in Room; grid of thumbnails, delete,
  re-open with compare.
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
   *bodies* of `tileSize` (128 CPU / 256 GPU for the compact models; the
   RRDBNet High Quality model uses 64/96 because it keeps ~140 feature maps
   alive per tile) that **exactly partition** the output.
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

## How WDN interpolation works

`realesr-general-x4v3` (detail-oriented) and `realesr-general-wdn-x4v3`
(denoise-oriented) share the exact same SRVGGNetCompact architecture, so
their weights can be blended linearly:

```
out = (1 - alpha) * general + alpha * wdn
```

`WdnBlend` (pure Kotlin, unit-tested) parses the model's `.param` file to
learn the weight-blob layout (ncnn stores conv weights as tag-prefixed
fp16 and conv biases / PReLU slopes as raw fp32 — see the doc comment for
the exact format), blends the two `.bin` files blob-by-blob in float32, and
re-encodes them. `WdnInterpolator` caches the blended file per strength step
(10% increments) under `filesDir/wdn-interp-v<N>/`. This mirrors the official
Real-ESRGAN advice of trading off between the two models.

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
   with its native scale and tile sizes; add it to `photoModels`.

### Currently bundled

| App entry | Asset dir | Actual weight | Note |
|---|---|---|---|
| Photo x4 (default) | `realesr-general-x4v3` | official Real-ESRGAN compact general | pairs with the WDN slider |
| Photo x4 (High Quality) | `RealESRGAN_x4plus` | official full RRDBNet x4plus | much slower, most detail |
| Photo x2 (fast) | `realesr-animevideov3-x2` | animevideov3 x2 | **stand-in** (no public general-x2 compact weight); flagged in UI |
| Anime / Illustration x4 | `realesr-animevideov3-x4` | animevideov3 x4 | — |
| (WDN companion) | `realesr-general-wdn-x4v3` | official compact WDN twin | not user-selectable; reached via the denoise slider |

## Project layout

```
app/src/main/cpp/            JNI bridge to ncnn (C++)
app/src/main/assets/models/  ncnn model weights
ncnn-android/                vendored ncnn prebuilt (BSD-3, per ABI)
domain/                      pure-Kotlin models, tiling, use cases
data/                        Room, DataStore, MediaStore, engine wrappers
processing/                  photo worker + tiling pipeline
ui/                          Compose screens (Material 3)
```

## Roadmap (not in v1)

- True x2 general-photo weight when one is published
- Optional runtime download of additional HQ weights (would require the
  INTERNET permission; currently avoided by design)

## Licenses & attribution

App code: MIT. ncnn and Real-ESRGAN keep their own licenses (BSD-3-Clause);
see `THIRD_PARTY_NOTICES.md` and the in-app About screen.

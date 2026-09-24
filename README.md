<div align="center">

# 📸 Upscaler

**Offline AI photo upscaler for Android — Real-ESRGAN on-device, powered by ncnn.**

No cloud. No upload. No tracking. Your photos never leave the phone.

<br>

[![Total Downloads](https://img.shields.io/github/downloads/devfahim00/Upscaler/total?style=for-the-badge&logo=github&label=Total%20Downloads&color=success)](https://github.com/devfahim00/Upscaler/releases)
[![Latest Release](https://img.shields.io/github/v/release/devfahim00/Upscaler?style=for-the-badge&logo=github&label=Latest%20Release&color=blue)](https://github.com/devfahim00/Upscaler/releases/latest)
[![CI](https://img.shields.io/github/actions/workflow/status/devfahim00/Upscaler/android-ci.yml?style=for-the-badge&logo=githubactions&logoColor=white&label=CI)](https://github.com/devfahim00/Upscaler/actions/workflows/android-ci.yml)

<br>

[![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Android](https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com/)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white)](https://developer.android.com/compose)
[![Material 3](https://img.shields.io/badge/Material%203-757575?style=for-the-badge&logo=materialdesign&logoColor=white)](https://m3.material.io/)
[![ncnn](https://img.shields.io/badge/ncnn-inference-241800?style=for-the-badge)](https://github.com/Tencent/ncnn)
[![Real-ESRGAN](https://img.shields.io/badge/Real--ESRGAN-models-9C27B0?style=for-the-badge)](https://github.com/xinntao/Real-ESRGAN)
[![Gradle](https://img.shields.io/badge/Gradle-02303A?style=for-the-badge&logo=gradle&logoColor=white)](https://gradle.org/)
[![JDK 17](https://img.shields.io/badge/JDK-17-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)](https://adoptium.net/)

[![GitHub stars](https://img.shields.io/github/stars/devfahim00/Upscaler?style=flat-square&logo=github&color=yellow)](https://github.com/devfahim00/Upscaler/stargazers)
[![GitHub forks](https://img.shields.io/github/forks/devfahim00/Upscaler?style=flat-square&logo=github&color=blue)](https://github.com/devfahim00/Upscaler/network/members)
[![GitHub issues](https://img.shields.io/github/issues/devfahim00/Upscaler?style=flat-square&logo=github&color=red)](https://github.com/devfahim00/Upscaler/issues)
[![Last commit](https://img.shields.io/github/last-commit/devfahim00/Upscaler?style=flat-square&logo=git&logoColor=white&color=green)](https://github.com/devfahim00/Upscaler/commits/main)
[![Repo size](https://img.shields.io/github/repo-size/devfahim00/Upscaler?style=flat-square&color=orange)](https://github.com/devfahim00/Upscaler)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen?style=flat-square)](#-contributing)

[**Download**](#-download) •
[**Features**](#-features) •
[**Models**](#-models) •
[**App Updates**](#-app-updates) •
[**Getting Started**](#-getting-started) •
[**Tech Stack**](#-tech-stack) •
[**Contributing**](#-contributing)

</div>

---

## 📑 Table of Contents

- [About](#-about)
- [Features](#-features)
- [Models](#-models)
- [Tech Stack](#-tech-stack)
- [Project Structure](#-project-structure)
- [Download](#-download)
- [App Updates](#-app-updates)
- [Getting Started](#-getting-started)
  - [Prerequisites](#prerequisites)
  - [Clone & Build](#clone--build)
- [How It Works](#-how-it-works)
  - [Tiling engine](#tiling-engine)
  - [WDN denoise interpolation](#wdn-denoise-interpolation)
  - [HDR enhancement pass](#hdr-enhancement-pass)
  - [Backend selection](#backend-selection)
- [Continuous Integration](#-continuous-integration)
- [Adding or Replacing Models](#-adding-or-replacing-models)
- [Roadmap](#-roadmap)
- [Troubleshooting](#-troubleshooting)
- [FAQ](#-faq)
- [Contributing](#-contributing)
- [Disclaimer](#-disclaimer)
- [Credits & Acknowledgements](#-credits--acknowledgements)
- [Contact](#-contact)

---

## 📖 About

**Upscaler** is a native Android app that enlarges and enhances **photos fully
offline** using super-resolution networks from
[**Real-ESRGAN**](https://github.com/xinntao/Real-ESRGAN), running on-device
through Tencent's [**ncnn**](https://github.com/Tencent/ncnn) inference engine
with automatic **Vulkan GPU → multi-threaded CPU fallback**.

It is designed around three things: **quality** — seven bundled models, from
the fast compact networks to the full RealESRGAN_x4plus RRDBNet, plus a
Zero-DCE++ HDR enhancement pass; **privacy** — upscaling is 100% on-device,
with no analytics and no photo ever leaving the phone; and a modern
**Material 3 UI** built with Jetpack Compose, in English and Bengali.

Pick one photo or many, choose model and 2x/4x scale (or HDR-only), and let
the auto-tiling engine handle even 12 MP inputs without OOM. Results land in
a built-in library with a before/after compare slider, and can be saved as
PNG, JPEG or WEBP or shared straight from the app.

---

## ✨ Features

| | Feature | Description |
|---|---|---|
| 🔒 | **100% on-device AI** | Real-ESRGAN networks run locally via ncnn — photos never leave the phone |
| 🧠 | **7 bundled models** | Compact photo / anime models, full RRDBNet HQ mode, UltraSharp, PurePhoto, ClearReality |
| 🎚️ | **Denoise slider (WDN)** | Blends the denoise-trained twin weight into the photo x4 model at runtime |
| 🌅 | **HDR enhancement pass** | Zero-DCE++ curve network lifts shadows and protects highlights, per-model toggle |
| 🎚️ | **Advanced adjustments** | Exposure, contrast, gamma, saturation, temperature, tint, sharpness and more |
| 🔍 | **HDR-only mode** | Enhance a photo with no upscaling at all (1x) |
| 📐 | **2x / 4x scales** | Sub-native scales pre-shrink the input for bounded memory |
| 🧩 | **Auto-tiling engine** | Overlapping tiles keep memory safe on 12 MP photos — no OOM |
| ⚡ | **Vulkan GPU + CPU** | Automatic GPU→CPU fallback, on-device benchmark, Auto/Force GPU/Force CPU |
| 🗂️ | **Batch queue** | WorkManager queue with progress notifications — close the app, it continues |
| 🖼️ | **Compare slider & zoom** | Before/after slider with pinch-zoom on the result |
| 💾 | **Save & share** | PNG / JPEG / WEBP export, Android share sheet |
| 📚 | **Library** | Every job persisted in Room — thumbnails, re-open, delete (removes the file too) |
| 🌙 | **Material 3 UI** | Dynamic color (Android 12+), light/dark/system themes, bottom sheets, haptics |
| 🌍 | **English + Bengali** | Full UI translation with an in-app language switcher |
| 🔄 | **Auto app updates** | Silent GitHub Releases check on every launch, one-tap download |
| 🔎 | **Manual update check** | "Check for updates" any time from About → App updates |

---

## 🤖 Models

All weights ship inside the APK — no downloads, no accounts, works in airplane mode.

| App entry | Actual weight | Best for |
|---|---|---|
| Photo x4 (default) | `realesr-general-x4v3` (official compact) | everyday photos; pairs with the denoise slider |
| Photo x4 High Quality | `RealESRGAN_x4plus` (full RRDBNet) | maximum real texture, slower |
| Photo x2 (fast) | `realesr-animevideov3-x2` | quick 2x enlargements |
| Anime / Illustration x4 | `realesr-animevideov3-x4` | drawings, screenshots, anime |
| UltraSharp x4 | `4x-ultrasharp` (Kim2091) | heavy texture, JPEG restoration |
| PurePhoto x4 | `4x-purephoto-realplksr` (RealPLSKR) | denoise / photo restoration |
| ClearReality x4 | `4x-clearrealityv1` (SPAN, FP32) | soft natural look, very fast |
| *(WDN companion)* | `realesr-general-wdn-x4v3` | reached via the denoise slider, not user-selectable |
| *(HDRNet pass)* | Zero-DCE++ curve network | reached via the per-model HDR toggle, not user-selectable |

---

## 🛠 Tech Stack

[![Kotlin Coroutines](https://img.shields.io/badge/Kotlin-Coroutines-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org/docs/coroutines-overview.html)
[![ncnn](https://img.shields.io/badge/ncnn-20260526-241800?style=flat-square)](https://github.com/Tencent/ncnn)
[![Real-ESRGAN](https://img.shields.io/badge/Real--ESRGAN-x4plus-9C27B0?style=flat-square)](https://github.com/xinntao/Real-ESRGAN)
[![Hilt](https://img.shields.io/badge/Hilt-2.52-3399FF?style=flat-square)](https://dagger.dev/hilt/)
[![Room](https://img.shields.io/badge/Room-2.6.1-88B04B?style=flat-square)](https://developer.android.com/jetpack/androidx/releases/room)
[![WorkManager](https://img.shields.io/badge/WorkManager-2.9.1-34A853?style=flat-square)](https://developer.android.com/jetpack/androidx/releases/work)
[![DataStore](https://img.shields.io/badge/DataStore-1.1.1-4285F4?style=flat-square)](https://developer.android.com/jetpack/androidx/releases/datastore)
[![Coil](https://img.shields.io/badge/Coil-2.7.0-7024FB?style=flat-square)](https://coil-kt.github.io/coil/)

| Layer | Choice |
|---|---|
| UI | Jetpack Compose, Material 3 (dynamic color, `NavigationBar`, bottom sheets, shimmer, haptics) |
| Architecture | MVVM + Clean-ish layering (`domain` / `data` / `processing` / `ui`), UDF with `StateFlow` |
| DI | Hilt (+ `hilt-work`, `hilt-navigation-compose`) |
| Async | Coroutines & Flow; inference pinned to a dedicated single-thread dispatcher |
| Inference | ncnn (vendored prebuilt, Vulkan + ARM NEON CPU) via a thin JNI bridge (`app/src/main/cpp/ncnn_jni.cpp`) |
| Background | WorkManager photo queue with progress notifications |
| Storage | Scoped-storage compliant `MediaStore` saves; Room history; DataStore prefs |
| Min / target SDK | 26 / 34 (Android 8.0+) |
| ABIs | arm64-v8a + armeabi-v7a |

---

## 📂 Project Structure

```
app/src/main/cpp/            JNI bridge to ncnn (C++)
app/src/main/assets/models/  ncnn model weights (bundled, offline)
ncnn-android/                vendored ncnn prebuilt (BSD-3, per ABI)
domain/                      pure-Kotlin models, tiling, use cases (unit-tested)
data/                        Room, DataStore, MediaStore, engine wrappers
processing/                  photo worker + tiling pipeline
ui/                          Compose screens (Material 3)
```

---

## 📥 Download

<div align="center">

[![Download - Latest Release](https://img.shields.io/github/v/release/devfahim00/Upscaler?style=for-the-badge&logo=github&label=Download%20Latest%20Release&color=success)](https://github.com/devfahim00/Upscaler/releases/latest)

</div>

1. Go to the [**Latest Release**](https://github.com/devfahim00/Upscaler/releases/latest) page.
2. Download the signed `Upscaler-v<version>-release.apk` (arm64-v8a + armeabi-v7a).
3. Open the APK on your phone and allow "Install unknown apps" when prompted.
4. Updates install over the current version — your library and settings are kept.

> **Requirements:** Android 8.0+ (minSdk 26). The APK is signed by CI on every
> release, so in-app update checks can offer direct downloads.

---

## 🔄 App Updates

- **Automatic:** on every launch the app silently checks the
  `releases/latest` redirect on GitHub (no API quota, CGNAT-safe) and pops a
  dialog when a newer version is published — one tap downloads the new APK.
- **Manual:** *About → App updates → Check for updates*.
- **Telegram:** questions, feedback or bug reports →
  [t.me/droxilen](https://t.me/droxilen).

> The update check is the app's **only** automatic network use. Upscaling
> itself is 100% offline — no analytics, no tracking, no photo ever leaves
> the phone.

---

## 🚀 Getting Started

### Prerequisites

- JDK 17
- Android SDK (AGP auto-installs the NDK + CMake it needs)
- The ncnn prebuilt libs and model weights are **vendored in the repo** —
  no extra downloads required

### Clone & Build

```bash
git clone https://github.com/devfahim00/Upscaler
cd Upscaler
./gradlew assembleDebug          # APK in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest      # domain-layer unit tests
```

Or open the project in **Android Studio** and press Run.

---

## 🔬 How It Works

### Tiling engine

Large images are processed in **overlapping tiles** so memory stays bounded:

1. `TilePlanner` (pure Kotlin, unit-tested) splits the image into tile
   *bodies* (128 CPU / 256 GPU for compact models; 64/96 for the RRDBNet HQ
   model, which keeps ~140 feature maps alive per tile) that exactly
   partition the output.
2. Each tile's input patch is the body expanded by 16 px of context on every
   side, clamped to the image bounds — the same scheme as the reference
   Real-ESRGAN ncnn implementation.
3. Only the body region of each output tile is copied into the final buffer
   (`TileOps.copyBody`), so bodies never overlap or leave gaps.
4. `MemoryGuard` caps the total output at ~16 MP; when 4x would exceed it,
   the effective scale is reduced transparently and a notice is shown.

When the requested scale is **below** the model's native scale (e.g. 2x with
an x4 model), the input is pre-shrunk so the native output equals the
requested output — bounded memory and faster than native-pass + downscale.

### WDN denoise interpolation

`realesr-general-x4v3` (detail-oriented) and `realesr-general-wdn-x4v3`
(denoise-oriented) share the same SRVGGNetCompact architecture, so their
weights can be blended linearly: `out = (1 − α)·general + α·wdn`.
`WdnBlend` (pure Kotlin, unit-tested) parses the model's `.param` file to
learn the weight-blob layout, blends the two `.bin` files blob-by-blob in
float32 and re-encodes them; `WdnInterpolator` caches the blended file per
10% strength step. This mirrors the official Real-ESRGAN advice of trading
off between the two models.

### HDR enhancement pass

The per-model **HDR** toggle runs the bundled `hdrnet` model — the official
**Zero-DCE++** checkpoint converted to an ncnn graph. The tiny network runs
on a 1/12 bilinear-downscaled copy of the photo, predicts a per-pixel curve
parameter, and `HdrCurve` (unit-tested against numpy-verified vectors)
upsamples the curve map and applies the 8-iteration enhancement curve at full
resolution. Three "natural look" guards keep well-exposed photos from
over-brightening: a strength slider (default 65%), an auto exposure anchor,
and highlight protection. An **HDR-only mode** (1x scale) applies just the
enhancement pass with no upscaling at all.

### Backend selection

`SelectBackendUseCase` (pure Kotlin, unit-tested):

1. **Forced GPU / Forced CPU** — honoured; forced GPU without usable Vulkan
   falls back to CPU (flagged).
2. **Auto** — uses Vulkan when `get_gpu_count() > 0`; if a stored benchmark
   shows the GPU is more than 15% slower than the CPU (common on low-end
   GPUs), Auto picks the CPU instead.
3. The **Run benchmark** button in Settings measures both backends on a
   64×64 tile (8 iterations) and stores the result in DataStore.

CPU runs use a thread pool sized from `availableProcessors()` (clamped 2–8).

---

## 🤖 Continuous Integration

Every push runs `assembleDebug` + the domain-layer unit test suite, and
additionally assembles a **signed release APK** using the keystore stored as
GitHub Actions secrets (`.github/workflows/android-ci.yml`).

Pushing a `v*` tag publishes a **GitHub Release** with the signed APK
(`Upscaler-<tag>-release.apk`) and the changelog from `RELEASE_NOTES.md` —
which is exactly what the in-app updater checks against.

---

## ➕ Adding or Replacing Models

1. Convert your weights to ncnn format (Real-ESRGAN PyTorch → ONNX →
   [pnnx](https://github.com/pnnx/pnnx) → `.param`/`.bin`).
2. Make sure the input blob is named **`data`** and the output blob
   **`output`** (otherwise adjust `kInputBlob`/`kOutputBlob` in
   `ncnn_jni.cpp`).
3. Drop the files into `app/src/main/assets/models/<new-name>/`.
4. Add an entry to the `ModelType` enum with its native scale and tile
   sizes, and add it to `photoModels`.
5. Bump `ModelAssetManager.VERSION` to force re-extraction after replacing
   weights.

---

## 🗺 Roadmap

- [ ] True x2 general-photo weight when one is published
- [ ] Optional runtime download of additional HQ weights
- [ ] Video upscaling

---

## 🧰 Troubleshooting

<details>
<summary><b>The result looks too smooth / plasticky</b></summary>

Try a different model: **UltraSharp x4** keeps heavy texture, and the **HQ
(RealESRGAN_x4plus)** mode recovers the most real detail. Also drag the
**Denoise strength** slider to 0% — higher values trade texture for noise
removal.
</details>

<details>
<summary><b>Processing a big photo is slow</b></summary>

The compact models are fast; the **HQ** and **UltraSharp** models are not —
that is the price of the extra quality. Check
*Settings → Backend* and run the benchmark: on some devices the CPU beats the
GPU, and Auto already picks the faster one when that happens.
</details>

<details>
<summary><b>The app says the scale was reduced</b></summary>

`MemoryGuard` caps the output at ~16 MP so the phone does not run out of
memory on 4x jobs; when the requested output would exceed it, the effective
scale is transparently reduced and a notice is shown.
</details>

<details>
<summary><b>Update check fails</b></summary>

The check needs one short network request to GitHub. On very restrictive
networks it may fail silently on launch — use *About → Check for updates*
when back on a normal connection, or grab the APK from the
[Releases page](https://github.com/devfahim00/Upscaler/releases/latest)
directly.
</details>

---

## ❓ FAQ

<details>
<summary><b>Does the app upload my photos anywhere?</b></summary>

No. Upscaling runs entirely on-device with ncnn. The only network use is the
GitHub update check (one short request per launch / manual check) and links
you tap yourself. No analytics, no tracking.
</details>

<details>
<summary><b>Why does the app need the INTERNET permission now?</b></summary>

Solely for the update check against GitHub Releases and the links you open
yourself (release page, Telegram). The upscaling pipeline itself never
touches the network.
</details>

<details>
<summary><b>Which phones are supported?</b></summary>

Anything running Android 8.0+ with an arm64-v8a or armeabi-v7a CPU — that
covers virtually all phones from ~2016 onward. Vulkan is used automatically
when available; otherwise a multi-threaded NEON CPU backend is used.
</details>

<details>
<summary><b>Why is the APK so large?</b></summary>

The APK bundles **nine neural network weight sets** (the seven selectable
models plus the WDN companion and the HDRNet pass) as well as the ncnn
inference engine for two ABIs. That is what makes the app work fully offline.
</details>

<details>
<summary><b>What is the difference between the models?</b></summary>

See the [Models](#-models) table — short version: compact general model for
everyday photos, HQ for maximum detail, UltraSharp for textured/compressed
images, PurePhoto for denoise-restoration, ClearReality for a soft natural
look, animevideov3 for drawings.
</details>

<details>
<summary><b>Can I use my own model?</b></summary>

Yes — see [Adding or Replacing Models](#-adding-or-replacing-models). Any
ncnn-converted super-resolution network with `data`/`output` blobs works.
</details>

---

## 🤝 Contributing

Contributions are welcome! The domain layer (`TilePlanner`, `WdnBlend`,
`HdrCurve`, `MemoryGuard`, `SelectBackendUseCase`, …) is pure Kotlin and
unit-tested, which makes it a friendly place to start.

1. Fork the repository
2. Create your branch (`git checkout -b feature/my-feature`)
3. Commit your changes (`git commit -am 'Add my feature'`)
4. Push to the branch (`git push origin feature/my-feature`)
5. Open a Pull Request

---

## ⚠️ Disclaimer

This app is provided for lawful personal use. The bundled super-resolution
models are used as published by their respective authors; the developer is
not responsible for how enhanced output is used. Use responsibly and respect
the rights of the original photo owners.

---

## 🙏 Credits & Acknowledgements

- **[ncnn](https://github.com/Tencent/ncnn)** by Tencent — on-device neural
  network inference (BSD 3-Clause)
- **[Real-ESRGAN](https://github.com/xinntao/Real-ESRGAN)** by Xinntao et al.
  — super-resolution models (BSD 3-Clause)
- **[4x-UltraSharp](https://github.com/Kim2091/UltraSharp)** and
  **[4x-ClearRealityV1](https://github.com/Kim2091)** by Kim2091
- **[4x-PurePhoto](https://github.com/asterixcool)** by asterixcool
- **[Zero-DCE++](https://github.com/Li-Chongyi/Zero-DCE_extension)** by
  Li Chongyi et al. — the HDRNet enhancement pass

See `THIRD_PARTY_NOTICES.md` for the full license texts.

---

## 📬 Contact

- **Telegram:** [@droxilen](https://t.me/droxilen)
- **GitHub Issues:** [devfahim00/Upscaler/issues](https://github.com/devfahim00/Upscaler/issues)

---

<div align="center">

**If this app saved your blurry photos, consider giving it a ⭐!**

</div>

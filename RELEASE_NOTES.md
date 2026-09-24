# Upscaler v1.0.0

The first public release of **Upscaler** — a fully offline, on-device AI photo
upscaler for Android.

## ✨ What's new in this release

- **Automatic update checks** — the app silently checks GitHub Releases on
  every launch and offers a one-tap download when a newer version is
  published.
- **Manual update check** — *About → App updates → Check for updates*.
- **Telegram support channel** — reach the developer at
  [t.me/droxilen](https://t.me/droxilen) straight from the About screen.

## 🚀 Core features

- **100% on-device AI upscaling** — Real-ESRGAN super-resolution networks run
  locally through Tencent's ncnn inference engine. Your photos never leave
  the phone: no upload, no analytics, no tracking.
- **7 upscaling models bundled**:
  - Photo x4 (realesr-general-x4v3) — fast default with a **denoise slider**
    that blends in the WDN-trained twin weight at runtime.
  - Photo x4 High Quality (full RealESRGAN_x4plus RRDBNet) — maximum detail.
  - Photo x2 fast, Anime/Illustration x4 (animevideov3).
  - UltraSharp x4 — heavy texture & JPEG restoration.
  - PurePhoto x4 (RealPLSKR) — denoise / photo restoration.
  - ClearReality x4 (SPAN, FP32) — soft natural look, very fast.
- **HDR enhancement pass (HDRNet)** — a per-model HDR toggle powered by the
  bundled Zero-DCE++ curve network: lifts shadows, protects highlights, adds
  an HDR-style punch. Runs on a 1/12 downscaled copy, so it stays cheap.
- **HDR-only mode** — enhance a photo with no upscaling at all (1x scale).
- **Advanced adjustments** — exposure, brightness, contrast, gamma,
  saturation, temperature, tint, highlight protection and sharpness, applied
  after the automatic natural-look guards.
- **2x / 4x scales** with auto-tiling — large images are processed in
  overlapping tiles so memory stays bounded (no OOM on a 12 MP photo).
- **Vulkan GPU → multi-threaded CPU fallback** with a real on-device
  benchmark and Auto / Force GPU / Force CPU backend override.
- **Batch photo queue** via WorkManager with progress notifications — close
  the app, processing continues.
- **Before/after compare slider**, pinch-zoom, save as PNG / JPEG / WEBP,
  share sheet.
- **Library** — every job persisted in Room with thumbnails, re-open and
  delete (which also deletes the result file).
- **English + Bengali UI** with an in-app language switcher.
- **Material 3 design** — dynamic color (Android 12+), light/dark/system
  themes, bottom sheets and haptics.

## 🔒 Privacy

Upscaling is 100% on-device. The app's only network use is the GitHub update
check and links you tap yourself (release page / Telegram). Photos are never
uploaded, logged or shared.

## 📲 Install

Download `Upscaler-v1.0.0-release.apk` below, open it, and allow
"Install unknown apps" when prompted (the APK is signed; updates install over
the current version and keep your library and settings).

Requires Android 8.0+ (minSdk 26). Ships native code for arm64-v8a and
armeabi-v7a.

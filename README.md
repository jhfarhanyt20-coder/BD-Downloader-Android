# VibeDownloader for Android

The phone version of [VibeDownloader](https://github.com/jhfarhanyt20-coder/VibeDownloader): a fast download manager for Android 8 and newer, with the same dark design as the Windows app.

> **Status:** in development. The newest test build is always on the [preview release](../../releases/tag/preview).

## Install
1. Open the [latest release](../../releases/latest) (or the [preview](../../releases/tag/preview) test build) on your phone.
2. Tap **VibeDownloader-x.y.z.apk** to download it, then open it.
3. If Android asks, allow your browser to install apps ("Install unknown apps"), then tap **Install**.

VibeDownloader is not on the Play Store; it is published only here and on the [VibeCoder website](https://vibecoderai.onrender.com).

## Features
- **Share to download**: in YouTube, Chrome or any app tap **Share → VibeDownloader**; a copied link is offered automatically.
- **Fast downloads**: big files are split into parts that download at the same time (1–16 connections), with pause and resume. *(in development)*
- **Video and audio**: pick the quality, or audio only. *(in development)*
- **Settings**: connections per file, downloads at the same time, Wi-Fi only, update checks.
- **Updates itself**: checks the official releases and verifies every update before installing it. *(in development)*

## How it is built
Kotlin + Jetpack Compose. Every push is built by GitHub Actions into a signed APK; the signing key is kept in GitHub Secrets, never in this repository.

## License
Proprietary, all rights reserved. You may install and use the official app; you may **not** modify, copy, rebrand or redistribute it. See [LICENSE](LICENSE).

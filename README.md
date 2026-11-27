# BD Downloader for Android

*Formerly VibeDownloader for Android. Same app, new name.*

The phone version of [BD Downloader](https://github.com/jhfarhanyt20-coder/BD-Downloader): a fast download manager for Android 8 and newer, with the same dark design as the Windows app.


## Install
1. Open the [latest release](../../releases/latest) on your phone.
2. Tap **VibeDownloader-x.y.z.apk** to download it, then open it.
3. If Android asks, allow your browser to install apps ("Install unknown apps"), then tap **Install**.

BD Downloader is not on the Play Store; it is published only here and on the [BDCoder AI website](https://bdcoderai.onrender.com).

## Features
- **Share to download**: in YouTube, Chrome or any app tap **Share → BD Downloader**; a copied link is offered automatically.
- **Fast downloads**: big files are split into parts that download at the same time (1–16 connections), with pause, resume and automatic resume.
- **Video and audio**: pick the quality, or audio only.
- **Settings**: connections per file, downloads at the same time, Wi-Fi only, update checks.
- **Updates itself**: new versions download in the background, are checked (size and SHA-256), and you tap **Install now**.

## How it is built
Kotlin + Jetpack Compose. Every release is built by GitHub Actions into a signed APK; the signing key is kept in GitHub Secrets, never in this repository.

## License
Proprietary, all rights reserved. You may install and use the official app for your own personal use. You may **not** copy, modify, decompile, re-sign, repackage, rebrand, upload to any app store or website, sell or redistribute it, or use its code or design to build or train anything else. Viewing the public source code gives no right to use it. See [LICENSE](LICENSE).

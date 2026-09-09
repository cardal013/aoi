<div align="center">

<!-- TODO: replace with your actual logo/banner image, e.g. docs/banner.png -->
<img src="docs/banner.png" alt="Aoi banner" width="600">

# Aoi

A modern manga & manhwa reader for Android — clean, fast, and built around an extension-based source system.

[![Platform](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)](https://github.com/cardal013/aoi)
[![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](LICENSE)
[![Status](https://img.shields.io/badge/status-active-brightgreen)](#project-status)
[![Downloads](https://img.shields.io/github/downloads/cardal013/aoi/total)](https://github.com/cardal013/aoi/releases/latest)
[![Latest release](https://img.shields.io/github/v/release/cardal013/aoi)](https://github.com/cardal013/aoi/releases/latest)

**[Website](https://aoi-mangas.vercel.app/) • [Download](https://github.com/cardal013/aoi/releases/latest) • [Report a Bug](https://aoi-mangas.vercel.app/#bug-report)**

</div>

---

## Screenshots

<!-- TODO: replace with real screenshots, e.g. docs/screenshot-library.png -->
<p align="center">
  <img src="docs/screenshot-library.png" alt="Library" width="200">
  <img src="docs/screenshot-reader.png" alt="Reader" width="200">
  <img src="docs/screenshot-status.png" alt="Reading status" width="200">
</p>

## What is Aoi

Aoi is built on top of [Mihon](https://github.com/mihonapp/mihon), adding a dedicated backend and database layer on top of it. Instead of keeping everything only on-device, Aoi adds user accounts (username, profile, and related data) and a proper per-manga reading-status system, so your library isn't just a flat list — it's organized the way you actually read.

## Features

| | Feature | Description |
|---|---|---|
| 👤 | **User accounts** | Sign in and keep your library and reading status tied to your profile instead of just the device. |
| 🗂️ | **Reading status categories** | Save each manga as Reading, Completed, Dropped, or Plan to Read. |
| 📖 | **Clean, focused reader** | Inherited from Mihon. |
| ☁️ | **Cloud-backed library** | Backed by a database — access your library from the website too. |

### Reading status categories

| Status | Meaning |
|---|---|
| 📖 Reading | Currently in progress |
| ✅ Completed | Finished reading |
| ❌ Dropped | Stopped, not continuing |
| 📌 Plan to Read | Saved for later |

## Installation

Download the latest APK from the [Releases page](https://github.com/cardal013/aoi/releases/latest) and install it on your Android device.

<!-- TODO: add a Play Store badge here if/when Aoi is published -->

## Built with

![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white)
![Supabase](https://img.shields.io/badge/Supabase-3ECF8E?logo=supabase&logoColor=white)
![Mihon](https://img.shields.io/badge/Base-Mihon-E8433D)

## Project status

![Status](https://img.shields.io/badge/status-active%20development-brightgreen)

## Disclaimer

Aoi is a reader, not a content host. It does not distribute or store any manga or manhwa itself; all content is retrieved at request time from the sources you choose to enable.

---

<p align="center">
  <a href="https://aoi-mangas.vercel.app/">Website</a> •
  <a href="https://aoi-mangas.vercel.app/#bug-report">Report a Bug</a> •
  <a href="LICENSE">License</a>
</p>

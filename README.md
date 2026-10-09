<div align="center">

<img src="screenshots/app%20icon.png" alt="Aoi icon" width="96">

# Aoi

A modern manga & manhwa reader for Android — clean, fast, and built around an extension-based source system.

[![Platform](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)](https://github.com/cardal013/aoi)
[![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](LICENSE)
[![Status](https://img.shields.io/badge/status-active-brightgreen)](#project-status)
[![Downloads](https://img.shields.io/github/downloads/cardal013/aoi/total)](https://github.com/cardal013/aoi/releases/latest)
[![Latest release](https://img.shields.io/github/v/release/cardal013/aoi)](https://github.com/cardal013/aoi/releases/latest)

[**Website**](https://aoi-mangas.vercel.app/) **•** [**Download**](https://github.com/cardal013/aoi/releases/latest)

</div>

---

## Screenshots

<p align="center">
  <table>
    <tr>
      <td><img src="screenshots/abas%20aoi.jpeg" alt="Aoi tabs" width="200"></td>
      <td><img src="screenshots/Account.jpeg" alt="Account screen" width="200"></td>
      <td><img src="screenshots/supabase.jpeg" alt="Supabase" width="200"></td>
    </tr>
  </table>
</p>

## What is Aoi

Aoi is built on top of [Mihon](https://github.com/mihonapp/mihon), adding a dedicated backend and database layer on top of it. Instead of keeping everything only on-device, Aoi adds user accounts (username, profile, and related data) and a proper per-manga reading-status system, so your library isn't just a flat list — it's organized the way you actually read.

## Features

||Feature|Description|
|-|-|-|
|👤|**User accounts**|Sign in and keep your library and reading status tied to your profile instead of just the device.|
|🗂️|**Reading status categories**|Save each manga as Reading, Completed, Dropped, or Plan to Read.|
|📖|**Clean, focused reader**|Inherited from Mihon.|
|☁️|**Cloud-backed library**|Backed by a database — access your library from the website too.|
|🔄|**Incremental sync**|*Update Account* only uploads what changed since your last successful sync (library, reading status, read progress and removals), in batches. *Import from cloud* only pulls what changed in the cloud. A *Full resync* button re-sends everything if something gets out of sync.|
|📌|**Plan to Read by default**|New manga added to your library start in *Plan to Read*.|

### Reading status categories

|Status|Meaning|
|-|-|
|📖 Reading|Currently in progress|
|✅ Completed|Finished reading|
|❌ Dropped|Stopped, not continuing|
|📌 Plan to Read|Saved for later|

### How the incremental sync works

- Each account remembers the time of its last successful sync, on the device.
- The app marks locally what changed (library entries, reading status, chapter read/page) and *Update Account* sends only those records. Manga removed from the library are removed from the cloud one by one.
- The timestamp only moves forward when everything was sent; if something fails, the next sync sends it again.
- The first sync of an account, and *Full resync*, send the whole library.
- *Import from cloud* reads only the rows changed since your last import (`updated_at`), 1000 at a time.

## Installation

Download the latest APK from the [Releases page](https://github.com/cardal013/aoi/releases/latest) (or straight from the [website](https://aoi-mangas.vercel.app/)) and install it on your Android device.

<!-- TODO: add a Play Store badge here if/when Aoi is published -->

## Building from source

1. JDK 21 and the Android SDK (Android Studio's bundled JDK works).
2. Create `ignorance/local.properties` (git-ignored) with your Supabase project:
   ```properties
   SUPABASE_URL=https://<project>.supabase.co
   SUPABASE_ANON_KEY=<anon key>
   ```
   Without it the app builds but cannot connect to the cloud.
3. Run the SQL in [`supabase/migrations`](supabase/migrations) on your Supabase project (needed for the incremental import).
4. Build:
   ```powershell
   .\gradlew.bat :app:assembleDebug     # app/build/outputs/apk/debug/app-universal-debug.apk
   .\gradlew.bat assembleRelease        # needs keystore.properties + the keystore
   ```
   The debug build installs next to the release one (`com.cardal.aoi.dev`).

## Changelog

### 0.20.5
- Incremental sync with the cloud (only what changed), with a *Full resync* button
- *Import from cloud* only pulls what changed, paginated
- New manga go to *Plan to Read* by default
- Local database migration (dirty tracking for the sync) and a Supabase migration (`user_library.updated_at`)

### 0.20.4
- Import no longer removes local chapters when a source fails
- Import reads progress in pages of 1000 rows

## Built with

![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white)
![Supabase](https://img.shields.io/badge/Supabase-3ECF8E?logo=supabase&logoColor=white)
![Mihon](https://img.shields.io/badge/Base-Mihon-E8433D)

## Project status

![Status](https://img.shields.io/badge/status-active%20development-brightgreen)

## Baseado no Mihon

O Aoi é um fork do [Mihon](https://github.com/mihonapp/mihon), distribuído sob a licença [Apache 2.0](LICENSE) (a mesma do Mihon). O leitor, as extensões e as fontes vêm do Mihon; o Aoi acrescenta contas, estados de leitura e a biblioteca na cloud. Obrigado à equipa do Mihon e aos seus contribuidores.

## Disclaimer

Aoi is a reader, not a content host. It does not distribute or store any manga or manhwa itself; all content is retrieved at request time from the sources you choose to enable.

---

<p align="center">
  <a href="https://aoi-mangas.vercel.app/">Website</a> •
  <a href="LICENSE">License</a>
</p>


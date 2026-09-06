# Aoi
A modern manga & manhwa reader for Android — clean, fast, and built around an extension-based source system.

**Website:** https://aoi-mangas.vercel.app/
**Repository:** https://github.com/cardal013/Peak-Engineering

---

## What is Aoi
Aoi is designed to provide a clean, fast, and flexible reading experience, with support for multiple sources through independent extensions instead of a single hard-coded backend. You choose which sources are active; Aoi searches across them and gets out of the way while you read.

## Features
- **Search across multiple sources in parallel**, with results grouped by extension and sorted by chapter count — the source with the most chapters for a title surfaces first.
- **Library & favorites**, backed by a local Room database.
- **Offline downloads**, using data-saver–sized images to keep storage use low.
- **Clean, focused reader** with two modes: horizontal swipe and continuous vertical (webtoon-style) scrolling, switchable per chapter.
- **Automatic chapter advance** and resume-from-last-page, so you never lose your spot.
- **Read / downloaded indicators** throughout the library and chapter lists.
- **Search filters** — status (ongoing / completed / hiatus) and sort order (relevance / latest update / title).
- **Five-tab navigation:** My List, Library, Search, Notifications, Settings.
- **New-chapter notifications** for followed titles.

## Extensions
Aoi doesn't talk to a single manga site directly — it talks to extensions, and extensions talk to sources. Each extension is an independent package that knows how to search, list chapters, and fetch pages from one source.

Aoi ships with MangaDex as its default, MangaDex-API-backed source. Additional example sources shown on the website (MangaFire, MangaHub, WeebCentral) illustrate the model; Aoi is not affiliated with, endorsed by, or officially connected to any of them.

**In progress:** a remote extension-update system so sources can be added or updated without rebuilding or reinstalling the app itself — version checks, download, SHA-256 verification, atomic install, and rollback, served from a separate `aoi-extensions` repository. The current proof of concept loads a sample extension via `DexClassLoader`; the plan is to move to signed-APK extensions installed through Android's `PackageManager` for the production system.

## Tech stack
- **Language / UI:** Kotlin, Jetpack Compose (Material 3)
- **Networking:** Retrofit + Moshi
- **Local storage:** Room
- **Images:** Coil
- **Navigation:** Navigation Compose

## Project status
Aoi is under active development. Recently completed work includes the download manager screen, search filters, the vertical/webtoon reading mode, and the five-tab navigation layout. Open items include a left-to-right / right-to-left reading direction toggle, hiding reader controls until the first tap, and finishing the wiring on the notifications tab.

## Reporting a bug
Found something broken? Please report it through the **[Report a Bug](https://aoi-mangas.vercel.app/#bug-report)** section on the website. Include what you were doing, what you expected, and — if you can — turn on diagnostic information when submitting.

## Disclaimer
Aoi is a reader, not a content host. It does not distribute or store any manga or manhwa itself; all content is retrieved at request time from the sources you choose to enable.
# Aoi
A modern manga & manhwa reader for Android — clean, fast, and built around an extension-based source system.

**Website:** https://aoi-mangas.vercel.app/
**Repository:** https://github.com/cardal013/aoi

---

## What is Aoi
Aoi is built on top of [Mihon](https://github.com/mihonapp/mihon), adding a dedicated backend and database layer on top of it. Instead of keeping everything only on-device, Aoi adds user accounts (username, profile, and related data) and a proper per-manga reading-status system, so your library isn't just a flat list — it's organized the way you actually read.

## Features
- **User accounts** — sign in and keep your library and reading status tied to your profile instead of just the device.
- **Reading status categories** — save each manga as **Reading**, **Completed**, **Dropped**, or **Plan to Read**.
- **Clean, focused reader**, inherited from Mihon.
- **Library**, backed by a database.

## Reading status categories

| Status | Meaning |
|---|---|
| 📖 Reading | Currently in progress |
| ✅ Completed | Finished reading |
| ❌ Dropped | Stopped, not continuing |
| 📌 Plan to Read | Saved for later |

## Tech stack
- **Base:** [Mihon](https://github.com/mihonapp/mihon)
- **Accounts / user data:** dedicated backend + database (username, profile, reading status)

## Project status
Aoi is under active development.

## Reporting a bug
Found something broken? Please report it through the **[Report a Bug](https://aoi-mangas.vercel.app/#bug-report)** section on the website. Include what you were doing, what you expected, and — if you can — turn on diagnostic information when submitting.

## Disclaimer
Aoi is a reader, not a content host. It does not distribute or store any manga or manhwa itself; all content is retrieved at request time from the sources you choose to enable.

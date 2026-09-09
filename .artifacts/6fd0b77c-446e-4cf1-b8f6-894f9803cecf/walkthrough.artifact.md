# Walkthrough - Direct APK Download Links

I have updated the "Download" buttons on the website to point directly to the APK file in GitHub Releases.

## Changes Made

### 1. Updated Download Links in `index.html`
- **Hero Section**: The main "Download Aoi" button now points to:
  `https://github.com/cardal013/aoi/releases/latest/download/app-universal-release.apk`
- **Footer Section**: The "Download" link in the footer project column now points to the same direct URL.

## Manual Steps Required

> [!IMPORTANT]
> Since no releases currently exist in the repository, these links will return a **404 error** until you perform the following:
> 1. Create a **new Release** on GitHub (e.g., tag `v0.1.0`).
> 2. Upload the file `app-universal-release.apk` as a Release Asset.
>    - Path: `app/build/outputs/apk/release/app-universal-release.apk`
> 3. Once uploaded, the buttons on the website will immediately start downloading the APK.

## Verification Path

### Manual Verification
- After creating the GitHub Release, click "Download Aoi" on the site.
- Your browser should immediately start downloading the file `app-universal-release.apk`.

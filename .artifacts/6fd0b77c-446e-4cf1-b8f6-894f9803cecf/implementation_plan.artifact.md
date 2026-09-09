# Implementation Plan - Direct APK Download Links

Update the "Download Aoi" buttons in `index.html` to point directly to the latest APK asset in GitHub releases.

## Current Status

> [!WARNING]
> I have checked the repository [cardal013/aoi](https://github.com/cardal013/aoi/releases) and confirmed that **no releases exist yet**. Direct download links will result in a 404 until a release is published with the APK asset.

## Proposed Changes

### [Website] [index.html](file:///C:/aoi/index.html)

#### 1. Update Hero Download Link
- **[MODIFY]** Change the `href` of the "Download Aoi" button (line ~723) to point to the direct APK URL.
- **URL Pattern**: `https://github.com/cardal013/aoi/releases/latest/download/aoi-universal-release.apk` (assuming the asset name follows the standard build output).

#### 2. Update Feature/Footer Download Link
- **[MODIFY]** Change the `href` of the "Download" link (line ~812 or similar) to the same direct APK URL.

## Step-by-Step Execution Plan

1. **Build Release APK**: I will attempt to run the Gradle task `:app:assembleRelease` to generate the APK locally and confirm its exact filename.
2. **Report Filename**: I will provide you with the exact name of the generated APK (e.g., `app-universal-release.apk`).
3. **Manual Action Required**: You will need to create a **Release** on GitHub (tag it as `v0.1.0` or similar) and upload the generated APK as an asset.
4. **Update Links**: Once the release is ready, I will update the `index.html` files with the corresponding URL.

## Verification Plan

### Manual Verification
- Clicking "Download Aoi" on the website should immediately trigger the browser's download manager for the `.apk` file.
- Verify that the link does not point to the GitHub repository landing page anymore.

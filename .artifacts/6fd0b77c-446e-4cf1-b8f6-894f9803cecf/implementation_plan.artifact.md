# Implementation Plan - Final Sync Fix & Release Process

Fix the chapter deletion bug during import and prepare the application for official release.

## Proposed Changes

### [Account] [LibrarySupabaseRepository.kt](file:///C:/aoi/app/src/main/java/eu/kanade/tachiyomi/data/account/LibrarySupabaseRepository.kt)

#### 1. Remove Dangerous Refresh during Import
- **[MODIFY]** Remove the call to `updateMangaFromRemote(manga, fetchChapters = true)` inside `reconcileCloudToLocal`.
- **Reason**: This call triggers a synchronization with the source (e.g., MangaDex). If the source fails or returns an empty list, the app assumes the chapters were deleted and removes them from the local database. The Import process should only reconcile the read status of *existing* local chapters.

### [Release] Build Process

#### 1. Generate Production APK
- **[EXECUTE]** Run `./gradlew assembleRelease` to generate the signed `app-universal-release.apk`.
- This uses the `aoi-release.keystore` and `keystore.properties` already configured in the project.

#### 2. Create GitHub Release
- **[EXECUTE]** Create a release on GitHub with tag `v0.20.4` and attach the generated APK.

## Verification Plan

### Manual Verification
1. **Import Stability**:
   - Run "Import from cloud".
   - Verify that local chapters are **not** deleted, even if the network is unstable or a source is temporarily down.
2. **Release APK**:
   - Verify that `app/build/outputs/apk/release/app-universal-release.apk` exists.
   - Install it on a device and verify it works (optional, for the user).
3. **Website Link**:
   - Once the release is published, click "Download Aoi" on the website and verify it downloads the correct APK.

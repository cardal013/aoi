# Walkthrough - Application ID Change

I have changed the Android application ID from `app.mihon` to `com.cardal.aoi` to avoid installation conflicts with the original Mihon app and updated the necessary references.

## Changes Made

### 1. Build Configuration
- **[MODIFY] [build.gradle.kts](file:///C:/aoi/app/build.gradle.kts)**: Updated `applicationId` to `com.cardal.aoi`.

### 2. Manifest & Deep Links
- **[MODIFY] [AndroidManifest.xml](file:///C:/aoi/app/src/main/AndroidManifest.xml)**: Changed `android:scheme="mihon"` to `android:scheme="aoi"`. This ensures that deep links (like extension store links) are handled by Aoi without triggering a system prompt to choose between Aoi and Mihon.

### 3. Firebase & Telemetry
- **[MODIFY] [google-services.json](file:///C:/aoi/app/google-services.json)**: Updated package name placeholders to `com.cardal.aoi`.
  > [!CAUTION]
  > These are **placeholders** for build stability. Firebase features will remain inactive until you register `com.cardal.aoi` in the Firebase Console and upload the real `google-services.json`.
- **[MODIFY] [TelemetryConfig.kt](file:///C:/aoi/telemetry/src/firebase/kotlin/mihon/telemetry/TelemetryConfig.kt)**: Added the new package IDs to the whitelist for telemetry initialization.

## Verification Results

### Automated Tests
- Executed `gradlew clean :app:assembleDebug` (Success).

### Manual Verification Required
- **Deep Links**: Test the new scheme by running:
  `adb shell am start -W -a android.intent.action.VIEW -d "aoi://extension-store"`
- **Installation**: Verify the app installs alongside the original Mihon app.

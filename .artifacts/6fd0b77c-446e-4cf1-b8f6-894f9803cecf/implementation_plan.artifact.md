# Implementation Plan - Change Application ID (v2)

Change the Android application ID from `app.mihon` to `com.cardal.aoi` to avoid installation conflicts with the original Mihon app.

## User Review Required

> [!CAUTION]
> **Firebase Integration**: The proposed changes to `app/google-services.json` are **placeholders** only to allow the project to build locally. Firebase features (Analytics, Crashlytics, etc.) will **fail silently** until you add the new package name `com.cardal.aoi` to your project in the Firebase Console and replace the file with the real one.

> [!IMPORTANT]
> **Deep Links**: I found hardcoded `android:scheme="mihon"` in `app/src/main/AndroidManifest.xml`. To avoid conflicts where Android asks which app to open (Mihon or Aoi), I am proposing to change these to `android:scheme="aoi"`.

## Proposed Changes

### [VFS] [app/build.gradle.kts](file:///C:/aoi/app/build.gradle.kts)
- **[MODIFY]** Change `applicationId = "app.mihon"` to `applicationId = "com.cardal.aoi"`.

### [VFS] [app/src/main/AndroidManifest.xml](file:///C:/aoi/app/src/main/AndroidManifest.xml)
- **[MODIFY]** Update `android:scheme="mihon"` to `android:scheme="aoi"` in intent-filters (lines 81 and 192) to avoid deep link conflicts with the original Mihon app.

### [VFS] [app/google-services.json](file:///C:/aoi/app/google-services.json)
- **[MODIFY]** Update all occurrences of `app.mihon` and `app.mihon.debug` to `com.cardal.aoi` and `com.cardal.aoi.debug` respectively. (Placeholder for build stability).

### [VFS] [telemetry/src/firebase/kotlin/mihon/telemetry/TelemetryConfig.kt](file:///C:/aoi/telemetry/src/firebase/kotlin/mihon/telemetry/TelemetryConfig.kt)
- **[MODIFY]** Update `MIHON_PACKAGES` to include `com.cardal.aoi` and `com.cardal.aoi.debug`.

## Verification Plan

### Automated Tests
- Run `./gradlew clean`.
- Run `./gradlew :app:assembleDebug` to ensure the app builds with the new ID.

### Manual Verification
- Verify that the generated APK has the new package name using `aapt dump badging <path_to_apk> | grep package`.
- Verify deep links with `adb shell am start -W -a android.intent.action.VIEW -d "aoi://extension-store"`.

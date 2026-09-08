# Implementation Plan - Notification Permission Prompt in Settings

Add a third popup to the first-entry sequence in the Settings screen to request notification permissions on Android 13+.

## User Review Required

> [!IMPORTANT]
> **Sequence Order**: The prompts will appear in this order:
> 1. Wi-Fi Downloads
> 2. **Notification Permission** (Android 13+ only)
> 3. Extension Repos
>
> **Smart Detection**: If the user has already permanently denied the permission, the button will automatically change to **"Abrir Definições"** and lead to the system settings page instead of trying to show the permission dialog.

## Proposed Changes

### Domain Layer - Preferences

#### [MODIFY] [SourcePreferences.kt](file:///C:/aoi/app/src/main/java/eu/kanade/domain/source/service/SourcePreferences.kt)
- [NEW] `notificationPromptShown`: Tracks if the Aoi prompt was displayed.
- [NEW] `notificationPermissionRequested`: Tracks if the system permission dialog was ever launched.

### UI Layer - ViewModel

#### [MODIFY] [SettingsViewModel.kt](file:///C:/aoi/app/src/main/java/eu/kanade/tachiyomi/ui/setting/SettingsViewModel.kt)
- Update `state` Flow logic to chain the prompts:
    - `showWifiPrompt`: `!wifiShown`
    - `showNotificationPrompt`: `wifiShown && !notifShown && API >= 33`
    - `showExtensionPrompt`: `wifiShown && (notifShown || API < 33) && !extShown`
- Add `onDismissNotificationPrompt()` and `onNotificationPermissionRequested()`.

### UI Layer - Presentation

#### [MODIFY] [SettingsMainScreen.kt](file:///C:/aoi/app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsMainScreen.kt)
- Implement `NotificationPermissionPrompt` using `AlertDialog`.
- Add logic to detect `isPermanentlyDenied` using `shouldShowRequestPermissionRationale` and the new preference flags.
- Implement intent to open `ACTION_APPLICATION_DETAILS_SETTINGS`.

---

## Verification Plan

### Manual Verification
1.  **First Entry**: Confirm Wi-Fi, then confirm/deny Notifications, then confirm Extensions.
2.  **Permanently Denied**:
    - Deny notifications once in system dialog.
    - Re-enter Settings.
    - Verify the button now says "Abrir Definições" (or equivalent).
3.  **Android < 13**: Verify the prompt is skipped entirely.

# Implementation Plan - Account Screen Improvements

This plan details the changes to the Account screen in the Android app to improve the post-login/signup experience. We will add a "Continue to app" action, a top navigation bar with a back arrow, and style the Logout action to be less prominent.

## User Review Required

> [!NOTE]
> The "Continue to app" action will simply pop the current screen from the navigation stack, returning the user to the previous screen (typically the "More" tab or main screen) while keeping the session active.

## Proposed Changes

### Presentation Layer

#### [MODIFY] [AccountScreenContent.kt](file:///C:/aoi/app/src/main/java/eu/kanade/presentation/more/account/AccountScreenContent.kt)
- Add a top `AppBar` to the `Scaffold` with a back arrow that triggers `onNavigateBack`.
- Update `ProfileView` to include the "Continue to app" button as the primary action.
- Update `ProfileView` to change the "Logout" button to a `TextButton` with an error color, making it visually secondary.
- Pass `onNavigateBack` to `ProfileView` to be used by the "Continue to app" button.

#### [MODIFY] [AccountScreen.kt](file:///C:/aoi/app/src/main/java/eu/kanade/tachiyomi/ui/more/account/AccountScreen.kt)
- No significant changes needed here as it already passes `navigator::pop` as `onNavigateBack`.

## Verification Plan

### Manual Verification
- Log in or sign up to reach the profile screen.
- Verify that a back arrow appears in the top-left corner.
- Verify that clicking the back arrow returns to the previous screen without logging out.
- Verify that the "Continue to app" button is primary and returns to the previous screen without logging out.
- Verify that the "Logout" button is secondary (text only, red) and correctly logs the user out and returns to the auth view.
- Verify that the Android system back button returns to the previous screen without logging out.

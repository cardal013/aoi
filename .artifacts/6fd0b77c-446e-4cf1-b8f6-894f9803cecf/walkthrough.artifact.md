# Walkthrough - Sync Options UI Redesign

I have redesigned the "Update Account" selection menu to provide a more modern and intuitive experience.

## Changes Made

### 1. New Sync Options Bottom Sheet
- **Container**: Replaced the standard `AlertDialog` with a `ModalBottomSheet` for a better mobile-first feel.
- **Header**: Added a clear "Sync Library" title and a descriptive subtitle to guide the user.
- **Option Cards**: Replaced simple text buttons with rich clickable cards (`SyncOptionCard`).
    - **Import**: Features a `Download` icon and explains that it replaces the local library.
    - **Upload**: Features an `ArrowUpward` icon and explains that it replaces the cloud library.
- **Visual Styling**:
    - Cards use the `surfaceVariant` background for clear clickability.
    - Rounded corners (12dp for cards, 16dp for the sheet) match the app's modern design language.
    - Generous padding and spacing for improved readability.

## Verification Results

### Automated Tests
- Verified successful compilation with `:app:compileDebugKotlin`.

### Manual Logic Verification
- **User Flow**: Clicking a card correctly dismisses the sheet and triggers the existing `SyncConfirmationDialog`.
- **Theme Consistency**: The new UI correctly uses `MaterialTheme.colorScheme`, ensuring full support for dark mode and dynamic colors.

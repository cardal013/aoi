# Walkthrough - Supabase Website Integration & App Sync

I have integrated the Aoi website with the Supabase backend used by the Android app and updated the app to use the web-based library management.

## Changes Made

### 1. Website Enhancements (`index.html`)
- **Real Authentication**: Replaced mock login/signup with **Supabase Auth**. Users can now log in with the same account used in the Android app.
- **Dynamic Library**: The library section now fetches real data from the `user_library` table.
- **Reading Categories**: Implemented the 4 official statuses: `READING`, `PLAN_TO_READ`, `COMPLETED`, and `DROPPED`.
- **Status Management**: Clicking a manga card allows users to change its status, which updates the Supabase database immediately.
- **New Feature Card**: Added a "Cloud Sync" card to the features section to highlight the automatic backup capability.
- **Capas Reais**: Cards now display manga covers using the `thumbnail_url` from the database.

### 2. Android App Redirection
- **"My Cloud Library" Shortcut**: Updated the [AccountScreen.kt](file:///C:/aoi/app/src/main/java/eu/kanade/tachiyomi/ui/more/account/AccountScreen.kt) to open the browser at `https://aoi-mangas.vercel.app/` instead of an internal screen.
- **Code Cleanup**: Removed the redundant `CloudLibraryScreen.kt` and `CloudLibraryViewModel.kt` files.

## Verification Results

### Automated Tests
- Verified compilation with `:app:compileDebugKotlin` (Success).

### Manual Verification Required
- **Supabase Credentials**: Replace the placeholders `YOUR_SUPABASE_URL` and `YOUR_SUPABASE_ANON_KEY` in [index.html](file:///C:/aoi/index.html) with your actual project keys.
- **Login Flow**: Open the site, log in with an existing app account, and verify your library appears.
- **App Link**: In the Android app, go to More -> Account -> My Cloud Library and confirm it opens the browser.

> [!IMPORTANT]
> To ensure the website can communicate with Supabase, make sure your site URL (`https://aoi-mangas.vercel.app/`) is added to the **Allowed Redirect URIs** in your Supabase Auth settings.

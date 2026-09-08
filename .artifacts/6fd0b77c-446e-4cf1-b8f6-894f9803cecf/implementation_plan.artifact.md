# Plan: Supabase Integration for Aoi Website & App Shortcut

This plan outlines the steps to integrate the real Supabase backend into the Aoi website and update the Android app to point to the web library.

## Website Changes (index.html)

### 1. Feature Section Update
- **[MODIFY]** Add a new feature card to the "Features" section:
    - **Icon**: Cloud icon (SVG).
    - **Title**: Cloud Sync.
    - **Description**: Seamlessly upload and import your library via the cloud. No manual backups required.

### 2. Supabase Integration
- **[MODIFY]** Include the Supabase JS SDK via CDN.
- **[MODIFY]** Initialize the Supabase client using the same Project URL and Anon Key used in the Android app.
- **[MODIFY]** Replace the in-memory `users` and `state.library` logic with real Supabase calls:
    - **Signup**: Use `supabase.auth.signUp({ email, password })`.
    - **Login**: Use `supabase.auth.signInWithPassword({ email, password })`.
    - **Library Fetch**: Query the `user_library` table joined with the `manga` table.
- **[MODIFY]** Update the library UI to use the 4 official statuses: `READING`, `COMPLETED`, `DROPPED`, `PLAN_TO_READ`.

## Android App Changes

### 1. External Library Shortcut
- **[MODIFY] [AccountScreen.kt](file:///C:/aoi/app/src/main/java/eu/kanade/tachiyomi/ui/more/account/AccountScreen.kt)**:
    - Replace the internal `navigator.push(CloudLibraryScreen())` with an external browser call to `https://aoi-mangas.vercel.app/` using `LocalUriHandler`.
- **[DELETE] [CloudLibraryScreen.kt](file:///C:/aoi/app/src/main/java/eu/kanade/tachiyomi/ui/more/account/CloudLibraryScreen.kt)**: This screen is no longer needed as the library is now managed via the website.

---

## Technical Details: Schema Mapping

The website will query the following Supabase structure:
- **Table**: `user_library`
- **Columns**: `status` (Uppercase), `manga_id` (FK to `manga`).
- **Join**: `manga(*)` to get titles and thumbnails.

## Open Questions
- **Auth Credentials**: Since I cannot see the actual Project URL and Key (they are likely in `local.properties`), I will use placeholders like `CONFIG_SUPABASE_URL` and `CONFIG_SUPABASE_KEY` which you should replace with your real values.

Aguardo a tua aprovação para prosseguir.

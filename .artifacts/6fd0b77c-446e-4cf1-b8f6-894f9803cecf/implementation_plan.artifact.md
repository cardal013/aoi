# Implementation Plan - Debugging Signup Failure

Diagnose why "Create Account" is failing on both the website and Android app by adding detailed logging and verifying Supabase backend configuration.

## User Action Required: Supabase Dashboard Check

Please verify the following settings in your Supabase Dashboard as they are the most likely cause of a cross-platform silent failure:

1.  **Authentication > Settings**:
    *   **Confirm Email**: Ensure "Enable email confirmations" is **OFF**. If it's ON, signups will be created but won't be active until a (fictitious) email is confirmed.
    *   **Allow Signups**: Ensure "Allow new users to sign up" is **ON**.
2.  **Database > Tables > profiles**:
    *   Check for any new constraints (like `NOT NULL` on columns without defaults) that might be rejecting the `insert` call during signup.
    *   Try a manual SQL insert to test: `INSERT INTO profiles (id, username) VALUES ('any-uuid', 'test_manual');`

## Proposed Changes

### 1. Website Debugging
#### [MODIFY] [index.html](file:///C:/aoi/index.html)
- Enhance `signupForm.onsubmit` logs to capture the full Supabase response, including the `session` object. This will confirm if the account is created but waiting for confirmation.

### 2. Android App Debugging
#### [MODIFY] [AccountRepository.kt](file:///C:/aoi/app/src/main/java/eu/kanade/tachiyomi/data/account/AccountRepository.kt)
- Add detailed `logcat` entries for every step of the `signUp` process.
- Log the `authResponse` from `supabase.auth.signUpWith(Email)`.
- Explicitly log if the `insert` into the `profiles` table fails or succeeds.

## Verification Plan

### Automated Tests
- Build the app using `:app:compileDebugKotlin` to ensure no syntax errors in logs.

### Manual Verification Path
1. **Analyze Site Logs**: Check the browser console for `[signup] resposta: { data: ..., error: ... }`. If `data.session` is `null` but `data.user` exists, email confirmation is active.
2. **Analyze App Logs**: Run `adb logcat -s AOI_ACCOUNT` and attempt a signup. Look for errors during the `profiles` table insertion.

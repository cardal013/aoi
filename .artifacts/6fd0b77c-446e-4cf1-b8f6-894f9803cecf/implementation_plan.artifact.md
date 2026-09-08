# Implementation Plan - Auth Compatibility & Features Restoration

Align the website's authentication logic with the Android app (Username-to-Mock-Email conversion) using Unicode-aware filtering, and ensure the Features section correctly represents all core capabilities.

## User Review Required

> [!IMPORTANT]
> The website will now automatically convert usernames into mock emails (e.g., `usr_test@aoi-app.com`) using a Unicode-aware filter to match the Android app's `isLetterOrDigit()` logic exactly. This ensures compatibility for usernames with accents or special characters.

## Proposed Changes

### [Website] [index.html](file:///C:/aoi/index.html)

#### 1. Authentication Logic (Compatible with App)
- **[NEW]** Implement `formatInternalEmail(username)` in JavaScript:
  ```javascript
  function formatInternalEmail(username) {
    // Replicates Kotlin: username.trim().lowercase().filter { it.isLetterOrDigit() }
    const normalized = username.trim().toLowerCase()
      .split('')
      .filter(char => /\p{L}|\p{N}/u.test(char))
      .join('');
    return `usr_${normalized}@aoi-app.com`;
  }
  ```
- **[MODIFY]** Update `signupForm.onsubmit`:
  - Generate the internal email using `formatInternalEmail(username.value)`.
  - Pass the original (trimmed) username in `options.data: { username: username.value.trim() }`.
- **[MODIFY]** Update `loginForm.onsubmit`:
  - Generate the internal email from the entered username.
  - Perform `signInWithPassword` using that email.

#### 2. Features Section (Restoration)
- **[MODIFY]** Update the `feature-grid` to include exactly these 4 cards:
  1. **Clean Reader**: Focused experience.
  2. **Reading Categories**: Status tracking (Reading, Completed, etc.).
  3. **Accounts**: Sync tied to profile.
  4. **Cloud Sync**: Mention bidirectional sync between the Android app and the cloud library.

## Verification Plan

### Manual Verification
1. **Signup Compatibility**:
   - Create an account with username `João123`.
   - Verify (via console or DB) that the internal email is `usr_joão123@aoi-app.com`.
2. **Login Compatibility**:
   - Logout and log back in using `João123`.
   - Verify success.
3. **Features Display**:
   - Confirm all 4 cards (Clean Reader, Reading Categories, Accounts, Cloud Sync) appear in the grid.

# Implementation Plan - Atomic Username & Email Change via Edge Function

Implement a robust "Change Username" flow that updates both the display name (metadata/profiles) and the internal authentication email. This is achieved through a Supabase Edge Function using admin privileges to bypass email confirmation flows.

## User Review Required

> [!IMPORTANT]
> The website will now invoke a server-side Edge Function to change the username. This function updates the internal email (e.g., `usr_new@aoi-app.com`) without requiring email confirmation.
>
> **Secrets Required**: You must set the `SUPABASE_SERVICE_ROLE_KEY` as a secret in your Supabase Dashboard under **Edge Functions > Secrets** for this to work. **NEVER** share or commit this key.

## Proposed Changes

### [New] Supabase Edge Function
**File**: `C:/aoi/supabase/functions/change-username/index.ts`
- **Logic**:
  - Validates the user's JWT from the `Authorization` header.
  - Receives `newUsername` in the request body.
  - Normalizes the username (Unicode-aware) to generate the new internal email.
  - Checks the `profiles` table for uniqueness using the Service Role client (bypassing RLS).
  - Updates the user in Auth (email + metadata) and the `profiles` table atomically.
  - Returns `200 OK` on success or `409 Conflict` if the username is taken.

### [Website] [index.html](file:///C:/aoi/index.html)
- **[MODIFY]** `usernameForm.onsubmit`:
  - Call `supabase.functions.invoke('change-username', { body: { newUsername } })`.
  - On success:
    - Call `supabase.auth.refreshSession()` to update the local session with the new email and metadata.
    - Refresh the UI (`renderProfile()`) and close the modal.
  - On error (`409`): Show the "username taken" error message.

## Verification Plan

### Manual Verification
1. **Atomic Change**:
   - Change username from `oldname` to `newname` on the site.
   - Verify success.
   - Logout.
   - **Crucial**: Try logging in with `newname`. If successful, the internal email was updated correctly.
2. **Uniqueness**:
   - Attempt to change to an existing username.
   - Verify the error message appears.
3. **Session Refresh**:
   - Verify that after the change, the profile name and initials update without a manual page refresh.

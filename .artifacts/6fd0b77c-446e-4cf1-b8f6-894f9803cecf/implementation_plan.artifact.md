# Implementation Plan - Fix Username Change Logic (v2)

Correct the "Change Username" bug by shifting from email-based authentication updates to a metadata-based display name approach. This maintains app compatibility and avoids Supabase's secure email change errors.

## User Review Required

> [!IMPORTANT]
> The "Change Username" feature will now only update the `user_metadata` and the `profiles` table. The internal authentication email remains fixed to ensure the Android app (which derives the username from the email) continues to work.
>
> **Note**: A username changed on the website will not reflect in the Android app; the app will always show the username used during the initial signup.

> [!CAUTION]
> This plan relies on a `UNIQUE` constraint on the `username` column in the `profiles` table to detect duplicates (error code `23505`). If this constraint is not present, the system will allow duplicate usernames silently. Please ensure this constraint exists in your Supabase DB.

## Proposed Changes

### [Website] [index.html](file:///C:/aoi/index.html)

#### 1. "Change Username" Implementation
- **[MODIFY]** Implement the `usernameForm.onsubmit` handler:
  - **Divergence Comment**: Add a clear comment about the app deriving username from email while web uses metadata.
  - **Atomic Update**: Attempt to update the `profiles` table first.
  - **Unique Violation Catch**: If an error with code `23505` occurs, show the error using `qs('usernameError').classList.add('show')`.
  - **Metadata Sync**: If the DB update succeeds, proceed to call `supabase.auth.updateUser({ data: { username: newUsername } })`.
  - **Cleanup**: Ensure `usernameError` is hidden (`classList.remove('show')`) at the start of a new submission.

#### 2. UI Consistency & Triggers
- **[MODIFY]** Update `renderProfile` and header logic to prioritize `user_metadata.username` over the email prefix.
- **[MODIFY]** Use `classList.add('show')` and `classList.remove('show')` for all error feedback to maintain consistency with existing CSS.
- **[NEW]** Wire up the triggers to open and close the "Change Username" modal.

## Verification Plan

### Manual Verification
1. **Uniqueness**:
   - Try to change a username to one that already exists.
   - Verify that the error message "This username is already taken." appears with the correct styling.
2. **Persistence**:
   - Change a username successfully.
   - Refresh the page and confirm the new name is still displayed in the header and profile.
3. **Modal Behavior**:
   - Confirm "Cancel" and the "X" button correctly close the modal and clear any previous error states.

# Communication patch notes

This patch focuses on the Discord Gateway transport and Rich Presence handling.

## Gateway communication changes

- Encode Gateway Heartbeat `d` as an integer or JSON `null`, not a quoted string.
- Treat the Gateway sequence as nullable until the first dispatch.
- Manage the socket in a single reconnect loop to avoid parallel connection attempts.
- Recreate the Gateway-ready signal after an authenticated connection is lost, so an old READY does not authorize sends on a new socket.
- Make `isWebSocketConnected()` require both an active WebSocket and a READY/RESUMED Gateway state.
- Return and log WebSocket send failures instead of silently dropping outgoing frames.
- Retain only the latest requested Presence and send it after READY/RESUMED on each new connection.
- Handle `INVALID_SESSION` by waiting a randomized 1–5 seconds, clearing session data when resume is disallowed, and reconnecting before the next Identify/Resume.
- Clear invalid session state for close codes 4006, 4007, and 4009; use bounded exponential reconnect backoff and a longer wait for 4008.
- Add serialization tests for Heartbeat sequence number and null payloads.

## Initial Presence fix (2026-11-25)

### Fixed

- Fixed an issue where the initial Rich Presence was not reflected in Discord after launching a game.
- Normalize empty `state` and `details` strings to `null` when building the initial Presence payload.
- Align the initial `build()` behavior with the `updateRPC()` behavior, which already filters out empty strings.
- Add outgoing `PRESENCE_UPDATE` payload diagnostics to help investigate Gateway communication issues.

### Verification

- Confirmed on an Android device that the initial Presence for Endfield is reflected in Discord.
- Confirmed that switching to another game (Our Notes) continues to update Presence correctly.
- Verified that Discord returns the expected game activity in `SESSIONS_REPLACE` after the initial Presence update.

## Validation status

The initial Presence fix was verified through runtime testing on an Android device.

The Gateway transport changes and serialization tests should still be validated with the relevant build and test environment.

## Important compatibility / policy note

This project uses a user account token to connect directly to the Discord Gateway. This is not an official mobile Rich Presence API integration and may violate Discord's Terms or result in account restrictions. Never commit a token or include it in logs.
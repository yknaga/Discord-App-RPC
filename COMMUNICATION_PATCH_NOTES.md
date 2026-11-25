# Communication patch notes

This patch focuses on the Discord Gateway transport in `gateway/`.

## Changes

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

## Validation status

The source changes and test source were reviewed statically. A Gradle compile/test could not be run in this environment because the Gradle 8.13 distribution was not cached and network access to `services.gradle.org` was unavailable.

This is not a verified runtime fix. Test the build and Gateway behavior with a test account and inspect logs before relying on it.

## Important compatibility / policy note

This project uses a user account token to connect directly to the Discord Gateway. This is not an official mobile Rich Presence API integration and may violate Discord's Terms or result in account restrictions. Never commit a token or include it in logs.

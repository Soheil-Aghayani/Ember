# Firefox Account browser sign-in

## Current status

The browser sign-in action is disabled on Windows, Android phones, and Android TV. The current app opens Mozilla authorization with a loopback callback such as `http://127.0.0.1:<port>/callback`, but Firefox Account responds with `Incorrect redirect_uri`. The callback is not registered for the OAuth client currently used by Ember, so changing the local callback code cannot make this flow succeed.

The login screens show a neutral explanation and keep the existing email/password and one-time device-code options available. Account switching is manual: choose **Switch account** in Settings, then sign in with the other Firefox account.

## Requirement to re-enable

Before enabling browser sign-in, Ember needs an OAuth client registration from Mozilla that:

1. Identifies Ember as a separate relying party rather than borrowing another Mozilla app's client registration.
2. Allows the exact callback URI used by each target (Windows, Android phone, and Android TV).
3. Allows the scopes and authorization-code/PKCE flow the app requests.
4. Is verified with a real sign-in on each supported target, including callback delivery back to the app.

Keep any client secret out of the repository. Native apps should use the public-client flow supported by the registration. Do not enable the button based only on a successful build or a hand-edited redirect URI.

## Evidence and owners

- The supplied Android screenshot shows Mozilla's `Bad Request` page with `Incorrect redirect_uri` after the browser flow is opened.
- Local app changes can fail fast and explain the blocker; only Mozilla can register or authorize the required OAuth callback and scopes.
- When a suitable registration is available, update `BrowserSignIn`, `FxaAuthRepository`, the platform login screens, this note, and the sign-in status row in `docs/PROJECT_MAP.md` together.

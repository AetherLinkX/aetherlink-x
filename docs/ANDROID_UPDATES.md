# Required Android updates

Starting with Android client 0.8.6, the app displays a required-update dialog on
all screens after finding a newer published Android release. The dialog follows
the selected light/dark theme and provides one button: "Скачать новую версию".
Back and outside taps do not dismiss it. Opening the browser does not clear it.
Android still requires the user to download and approve installation of the APK.
Existing VPN connections are not deliberately terminated by the UI gate.

The app checks the GitHub latest-release endpoint on foreground entry, then at
five-minute intervals while resumed. Requests are coalesced and throttled. A
verified newer release is cached so restarting the app or temporarily losing
network access does not dismiss an already known requirement. Installing that
version clears the requirement by comparing it with the actual installed package
version. A successful check after a server-side release rollback can also revoke
it. A network error alone never invents a mandatory update or dismisses a known one.

Release requirements:

- Publish a non-draft, non-prerelease `client-v...` release as latest.
- Bump Android versionCode and versionName for every release.
- Include an uploaded universal APK named `aetherlink-x-client-android-....apk`.
- Its download must be HTTPS from this repository's matching release/tag path.
- Continue using the existing stable signing key so users can update in place.

Unrelated server releases, missing/uploading APKs, invalid links, current and older
versions cannot activate the gate. Pre-0.8.6 installations do not gain this UI
remotely; they first need to install a build containing it. Existing notification
support in 0.8.4/0.8.5 can inform users that 0.8.6 is available.

Local regression tests cover release selection, versions and download validation.
CI runs the full Android unit tests and assembles both debug and release variants.
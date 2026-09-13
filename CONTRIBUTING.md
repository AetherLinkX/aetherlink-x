# Contributing

Thanks for helping improve AetherLink X.

## Before opening a change

- Keep ordinary Xray profiles compatible.
- Never commit tokens, private keys, complete share links or user logs.
- Keep protocol changes isolated and document wire-format changes.
- Do not add custom cryptographic primitives.

## Tests

For ALX changes:

~~~bash
cd core/alx
go mod download
go mod verify
go test -race ./...
~~~

For Android changes, run unit tests and build a debug APK from client/android.
Changes to the data plane should also be checked on a physical device with the
scripts in [tools](tools/README.md).

## Pull requests

Describe the problem, the chosen solution and the tests performed. Keep a pull
request focused on one concern and call out compatibility or security impact.

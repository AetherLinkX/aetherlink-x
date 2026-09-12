# Preview 8 phone diagnostics

These PowerShell scripts exercise a connected Android phone through ADB without
storing credentials in the repository.

- `test-preview8-phone.ps1` runs repeated HTTPS downloads through the active VPN.
- `test-preview8-services.ps1` checks Google, YouTube, Telegram and Cloudflare.
- `test-preview8-reconnect.ps1` performs stop/start cycles and verifies the exit IP.
- `import-preview8-profile.ps1` imports the private local Preview 8 test link.
- `import-preview8-tcp-test.ps1` imports a temporary TCP-fallback test profile.
- `verify-preview8-pin.ps1` compares the profile SPKI pin with a public certificate.

Examples:

```powershell
.\tools\test-preview8-phone.ps1 -Attempts 10
.\tools\test-preview8-reconnect.ps1 -ExpectedExitIp 203.0.113.10
.\tools\verify-preview8-pin.ps1 -LinkPath .\profile.private.txt `
  -CertificatePath .\fullchain.public.pem
```

The import helpers expect `alx-preview8-test-link.private.txt` in the repository
root by default. Private links, tokens and generated screenshots are ignored by
Git and must never be committed.

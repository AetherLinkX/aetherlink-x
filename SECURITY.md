# Security policy

## Supported versions

Security fixes are currently provided for the latest Preview 8 release only.

| Version | Supported |
|---|---:|
| ALX Preview 8 | Yes |
| ALX Preview 7 and older | Migration compatibility only |

## Reporting a vulnerability

Please do not open a public issue containing an exploit, token, private key,
certificate key, complete share URI or user data. Use GitHub's private security
advisory flow for this repository instead:

1. Open the repository **Security** tab.
2. Choose **Report a vulnerability**.
3. Include the affected version, reproduction steps, impact and any proposed
   mitigation.

Remove secrets and personal data from logs. A report will be acknowledged as
soon as it is reviewed; timelines depend on severity and reproducibility.

## Scope and limitations

ALX Preview 8 uses standard TLS 1.3 and QUIC implementations, but the complete
system has not received an independent security audit. Browser-like handshake
presentation is not a guarantee against traffic classification or blocking.
Performance and reachability depend on the server, route, mobile network and
device.

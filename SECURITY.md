# Security Policy

## Supported versions

Security fixes are evaluated on the current supported release line. Users of older versions should first upgrade to the latest release.

## Reporting a vulnerability

Do not open a public GitHub issue for a suspected security vulnerability.

Email `security@openan.com` with a description, affected version, reproduction steps, impact, and any proposed mitigation. Do not include secrets, access tokens, certificates, or production telemetry payloads in the report.

The maintainers will acknowledge the report and coordinate a fix or mitigation through a private channel.

## Scope

Reports are especially useful for issues involving OTLP request headers, trusted certificates, trace-context propagation, sensitive data in diagnostics, dependency vulnerabilities, and release artifacts.

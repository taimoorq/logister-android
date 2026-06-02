# Security Policy

## Reporting Vulnerabilities

Please use GitHub Security Advisories for private vulnerability reports. Do not
open public issues that include API keys, bearer tokens, signing keys, customer
data, or other secrets.

## Secret Handling

This repository is intended to be public. Do not commit:

- Logister project API keys
- Android signing keys, keystores, or passwords
- Maven Central credentials
- Cloudflare, GitHub, Apple, Google Play, or other service tokens
- `.env`, `local.properties`, or machine-specific configuration files

Runtime credentials should be supplied by the app that installs the SDK. Release
credentials should be stored as GitHub Actions secrets with the GitHub CLI.

Maven Central publishing uses a GitHub Actions release workflow. Set only the
secrets consumed by that workflow:

```bash
gh secret set MAVEN_CENTRAL_USERNAME --repo taimoorq/logister-android
gh secret set MAVEN_CENTRAL_PASSWORD --repo taimoorq/logister-android
gpg --armor --export-secret-keys YOUR_KEY_ID | gh secret set SIGNING_KEY --repo taimoorq/logister-android
gh secret set SIGNING_PASSWORD --repo taimoorq/logister-android
```

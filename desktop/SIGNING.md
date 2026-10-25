# Signing the Windows installer

Unsigned installers make Windows SmartScreen say "Windows protected your PC". A code-signing signature removes the "unknown publisher" wording and, over time, builds reputation.

## Option 1: SignPath Foundation (free, open source)
The [SignPath Foundation](https://signpath.org/) offers free code signing to qualifying open-source projects. Eligibility is decided by them (they look at the licence, whether the code is public, and whether the project is maintained and genuine), so a new project and a VPN client may or may not be accepted.

What you do:
1. Apply on their website for a free open-source signing certificate for the repository `Soheil-Aghayani/Ember` (MIT licence, public).
2. Once accepted, create a SignPath project with the slug `ember` and a signing policy `release-signing`, and an artifact configuration that signs `*.exe`.
3. In GitHub, repository **Settings > Secrets and variables > Actions**:
   - variable `SIGNPATH_ORGANIZATION_ID` = your SignPath organization ID
   - secret `SIGNPATH_API_TOKEN` = a SignPath API token
4. Run the **Android CI** workflow. The `desktop-windows` job then uploads the unsigned installer, SignPath signs it, and the signed installer replaces it in the artifact and in the draft release. Until the variable exists these steps are skipped and nothing changes.

The workflow steps are in `.github/workflows/android-ci.yml` (names start with "Upload the unsigned installer", "Sign the installer with SignPath", "Use the signed installer"). They were written from SignPath's documentation and have not been run against a real account yet, so expect to adjust slug names on the first run.

## Option 2: buy a certificate
A standard (OV) code-signing certificate from a certificate authority, or Microsoft's Azure Trusted Signing, both cost money and need identity checks. If you choose one, the signing step would call `signtool` (or the Azure action) on the installer in the same place in the workflow.

## What signing does not do
It does not make Windows trust the app instantly. SmartScreen still learns from downloads, and a signed app from a new publisher can show a warning for a while.

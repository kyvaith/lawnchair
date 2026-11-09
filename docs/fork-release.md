# Fork Release

The `fork` channel packages Lawnchair as **Lawnchair Drawerless**, with application ID
`app.kyvaith.lawnchair`. It includes the GitHub channel's manifest and the regular
Quickstep launcher manifest. Native Deck, folder sorting, notification access,
accessibility, backup, search and widgets remain included. The namespace and class
names remain unchanged. Quickstep retains upstream's supported Android versions
and system-integration requirements.

The current fork release uses version code `1600020102` and the version suffix
`.Drawerless2`. Increase the fork version code for future updates. The fork can
coexist with official Lawnchair and the earlier Debug build; each package has its
own application data. Installing it does not automatically transfer the previous
installation's layout. Use Lawnchair's backup/restore UI when moving settings.

## Permanent signing identity

`fork/release-certificate.pem` and `fork/release-certificate.sha256` are public.
Commit these public files with the configuration. The build verifies that Fork
Release uses this exact certificate, and never regenerates a key. The private key
and its password must stay outside the source repository and published artifacts.

The local key directory contains:

- `lawnchair-drawerless-release.p12`: the password-protected RSA 4096 private key;
- `keystore-password.txt`: its random password, protected by the directory ACL;
- `keystore.properties`: the store path and alias, without passwords;
- `signing-info.json`: public metadata and recovery instructions.

Back up the **entire private directory** securely. Losing either the key or its
password prevents signing compatible updates. Do not add the directory to Git,
attach it to a release, or send it with the APK/source-review archive.
When restoring the directory to another location or computer, update `storeFile`
in its private `keystore.properties` to the restored `.p12` path.

`tools/release/create-signing-key.ps1` is a one-time Windows provisioning helper.
It refuses existing directories or registered public certificates, restricts file
access to the current Windows user and SYSTEM, and passes the random password to
`keytool` through the environment. A checkout with an existing public certificate
must restore its corresponding private key rather than run provisioning again.

## Local build on Windows

Use JDK 21, the Android SDK required by this checkout, and PowerShell 7:

```powershell
./tools/release/build-fork-release.ps1 -SigningDirectory '<private signing directory>'
```

Add `-Offline` only when the dependencies are already cached. The helper loads the
credentials only for the build process, restores the previous environment in a
`finally` block, and disables the Gradle daemon and configuration cache for the
signed build. It also disables build-scan publication. It runs
`assembleLawnWithQuickstepForkRelease` and `spotlessCheck`.

Gradle reads `LAWNCHAIR_KEYSTORE_PROPERTIES` (or `keystore.properties` in the root).
The properties are `storeFile`, `keyAlias`, `storePassword` and `keyPassword`.
`LAWNCHAIR_KEYSTORE_PASSWORD` and `LAWNCHAIR_KEY_PASSWORD` override the password
properties. The local helper uses these environment overrides, so passwords are
not written into the checkout. Do not run signed builds with diagnostic commands
that dump the full environment or signing configuration.

Every Release variant depends on `validateReleaseSigning`, which rejects missing,
unreadable or invalid credentials and Android Debug certificates before its
variant pre-build. Fork Release additionally depends on
`validateForkReleaseSigning`, which checks the public certificate fingerprint.
Debug builds use the ordinary Debug key and require no production credentials.

## GitHub Actions

The manual **Build signed fork release** workflow builds the `fork` channel and
uploads an APK as a workflow artifact. It does not publish a release or send
announcements. It expects repository secrets containing the same permanent key:

- `KEYSTORE`: base64 encoding of the complete `.p12` file;
- `KEYSTORE_PASSWORD`: its password;
- `KEY_ALIAS`: `lawnchair-drawerless`;
- `KEY_PASSWORD`: the same password for this PKCS12 key.

The signing helper writes temporary private files under `RUNNER_TEMP`, and the
workflow cleans them even after failure. Debug jobs do not receive signing
secrets; signed Nightly builds remain restricted to trusted upstream pushes or
manual runs. The key and repository secrets are not uploaded/configured by the
local build. Commit and push the reviewed source/configuration before using CI.

## Verification and installation

Verify the final APK with SDK `apksigner verify --verbose --print-certs` and
`zipalign -c -P 16 4`. Its certificate must match the public fingerprint, its
application ID must be `app.kyvaith.lawnchair`, and `android:debuggable` must be
absent or false. Inspect the final merged manifest for the launcher, providers and
notification/accessibility services. The R8 mapping file is build evidence and
should be retained for crash diagnosis.

A valid Release signature does not guarantee a Google Play Protect decision.
Verify installation of this particular file on the target phone, including the
browser-download route. Record USB installation separately if used. No service
or capability is removed to influence that decision.

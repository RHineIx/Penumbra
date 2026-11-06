# Penumbra

A Kotlin work-profile manager for Android. Package: `dev.rhineix.penumbra`.

**Version 0.3.3 · Android 11+ · Target Android 17 · English only**

## Features

- Create a work profile using Android's standard setup and consent flow.
- Copy personal apps, including split APKs, into Work without copying app data.
- Open, freeze, unfreeze, hide and unhide work apps; manage supported runtime permissions and uninstall protection.
- Uninstall through Android confirmation with result tracking and current-profile verification.
- Control sharing in both directions, opening Work files and links in personal apps, outgoing clipboard, Bluetooth sharing and unknown-source installation.
- Manage supported camera, screenshot, contact-search and caller-ID policies.
- Profile name, lock shortcuts, profile deletion confirmation and a Quick Settings shortcut.
- COUI preferences/dialogs, Haze navigation blur, Tabler Icons, dark/light themes, live appearance previews, configurable accents and Android 12+ Monet wallpaper colors.

Android and device policies can restrict individual actions. Penumbra manages only its own work profile. An existing managed profile can prevent setup. Removing a work profile permanently deletes its work apps and data.

## Install or update

Releases contain an **unsigned Release APK with arm64-v8a native libraries only**. Android requires APK signing before installation. Use your own key and keep the same key for updates to the installed profile owner.

1. Sign and install Penumbra in Personal.
2. Open Work → Set up work profile and approve Android's setup.
3. Use the work-badged copy to manage work apps and privacy settings.
4. To copy an app, open Apps in the personal copy and choose Copy to work profile. Allow app installation for the Work copy if Android requests it.

**Block unknown sources** also blocks copying APKs into Work. Sharing controls are independent of clipboard restrictions. Pause/resume may require Android Quick Settings or the launcher on devices that restrict the direct API.

Update using the same signing key without recreating the profile. Work apps, privacy choices and appearance settings are retained. Obsolete internal preferences and the previous local action log are cleared once when the settings schema is upgraded.


No signing key is included in the project.

## Build

Requires JDK 21, Android SDK Platform `android-37.2` and Build Tools `37.0.0`.

```sh
chmod +x gradlew
./gradlew :app:assembleRelease :app:lintRelease
```

Output: `app/build/outputs/apk/release/app-release-unsigned.apk`.

| Component | Version |
| --- | --- |
| Gradle / Android Gradle Plugin | 9.8.2 / 9.5.1 |
| Kotlin / Compose compiler | 2.4.21 |
| Compose UI / Foundation | 1.12.1 |
| Activity Compose / Lifecycle | 1.13.0 / 2.11.0 |
| COUI UI / Preferences | 1.1.0 |
| Haze / Haze Blur | 2.1.0 |
| Compose Icons Tabler bindings | 1.1.1 |
| Coroutines | 1.11.0 |

R8 and resource shrinking are enabled. The release signing configuration is empty. The build accepts `-PAPP_VERSION_NAME=0.3.3 -PAPP_VERSION_CODE=8` overrides.

## GitHub Releases

[Release workflow](.github/workflows/release.yml) builds and publishes when a `v*` tag is pushed:

```sh
git tag v0.3.3
git push origin v0.3.3
```

It can also be run manually from Actions by entering an existing release tag. It checks out that tag, installs the SDK/JDK, builds the Release APK, runs Android Lint, verifies unsigned/arm64/16 KiB packaging, uploads workflow artifacts and publishes a GitHub Release with:

- Signed and unsigned arm64 Release APKs.
- Clean source ZIP from the tagged Git tree.
- SHA-256 checksums and build metadata.

The release workflow uses the same repository secret names as Umbra:

- `KEYSTORE_BASE64`: base64-encoded existing release keystore.
- `KEYSTORE_PASSWORD`: keystore password.
- `KEY_ALIAS`: signing key alias.
- `KEY_PASSWORD`: key password.

Add these under Settings → Secrets and variables → Actions in the Penumbra repository. Repository secrets are scoped to their repository; the workflow cannot read or copy Umbra's secret values. Use the same certificate that signed the installed Penumbra to preserve update compatibility. Keys are decoded only under the runner's temporary directory and cleaned up even after failures.

The release includes both a signed APK and an unsigned APK, along with the source archive and checksums. The local Gradle build remains unsigned. A separate Build workflow verifies main/PR changes and uploads an unsigned APK without signing secrets.

The version name comes from the tag. The version code is `6 + the number of v* tags`; keep release tags and publish them in order. The first tag produces version code 7.

## Appearance

Theme and manual accent choices preview immediately while the picker is open. Done saves the selection; Cancel restores the previous appearance. Monet follows the system wallpaper palette on Android 12+, uses COUI's dynamic-color support and preserves the previous manual accent when switched off. Input text, hints and cursors follow the current theme, including dialogs.

About includes the developer RHine. and a clickable repository link. Third-party license notices remain in this repository.

## License

Apache License 2.0. Third-party notices are in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Island is a functional reference; no Island code or binaries are included.

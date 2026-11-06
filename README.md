# Penumbra

[![Build](https://github.com/RHineIx/Penumbra/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/RHineIx/Penumbra/actions/workflows/build.yml)

Penumbra is a Kotlin work profile manager for Android. Create a separate space for your apps and accounts, manage work apps, and control what is shared between profiles through a customizable COUI interface.

## Features

- **Work profiles** — set up a managed profile through Android's standard consent flow.
- **App management** — copy apps into Work, freeze, hide, launch and uninstall them, including support for split APKs.
- **Privacy controls** — manage cross-profile sharing, clipboard access, camera, screenshots and supported runtime permissions.
- **Profile controls** — configure the profile name, lock settings and uninstall protection, with a Quick Settings shortcut.
- **Personalization** — light, dark and system themes, live accent previews, Monet colors and optional navigation blur.

App copies keep independent data. Available controls depend on Android and device policies.

## Build

### Requirements

- JDK 21.
- Android SDK command-line tools, platform `android-37.2` and Build Tools `37.0.0`.
- `ANDROID_HOME` pointing to your Android SDK directory.

The Gradle Wrapper is included; a separate Gradle installation is not required.

### From the terminal

```sh
git clone https://github.com/RHineIx/Penumbra.git
cd Penumbra
chmod +x gradlew
sdkmanager 'platforms;android-37.2' 'build-tools;37.0.0'
./gradlew assembleRelease
```

On Windows, use `gradlew.bat assembleRelease`.

The unsigned arm64 APK is generated at:

```text
app/build/outputs/apk/release/app-release-unsigned.apk
```

Run `./gradlew lintRelease` to check the release source with Android Lint.

### Using a tag

Commit your changes, then push an unused version tag to build and publish through GitHub Actions:

```sh
git push origin main
git tag v0.3.5
git push origin v0.3.5
```

Tags follow `vMAJOR.MINOR.PATCH`. The workflow publishes signed and unsigned arm64 APKs, source code and SHA-256 checksums. Signing requires the repository secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`. Builds on `main` and pull requests produce an unsigned APK without signing secrets.

## License

[Apache License 2.0](LICENSE). Third-party acknowledgements and licenses are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

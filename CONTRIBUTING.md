# Contributing

## Build and test

Use JDK 17 and Android Studio compatible with AGP 9.2.1. Install Android SDK Platform **37.0**, Build Tools **36.0.0** and platform-tools:

```sh
sdkmanager 'platforms;android-37.0' 'build-tools;36.0.0' 'platform-tools'
```

Set `ANDROID_HOME` to your SDK, or create an untracked `local.properties` with `sdk.dir`. The Gradle 9.4.1 wrapper is included with a pinned distribution checksum; Gradle downloads dependencies on the first build. Dependencies are pinned in `gradle/libs.versions.toml`.

Run:

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest # dedicated emulator/device only
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Local and CI builds may use different debug signing keys; see [installation and updates](docs/installation.md#disable--uninstall--updates). The minimum OS is Android 8/API 26, and compile/target SDK is API 37.

## Contribution guidelines

Keep changes focused.

Write failing behavioral tests for classifier, quota and navigation changes. Keep Android tree access outside `core`. Unknown or incomplete evidence must fail open. An identifier containing `reel` is not sufficient: Instagram also uses that word for Stories.

New detection adapters need sanitized, minimal fixtures and a recorded device/Android/Instagram version/entry route. Synthetic fixtures are useful regression tests but cannot establish compatibility. Do not upload complete accessibility trees, messages, handles, tokens, contacts or account data. Never add logs containing node text/descriptions.

See [manual testing](docs/manual-testing.md) for the acceptance matrix. Include false-positive tests for Home, Stories, messages and ordinary videos with every new detection rule. Keep all UI text and documentation in English. Changes must preserve consent and users' ability to disable/uninstall the app.

PRs run host tests, lint and APK compilation. Device tests require a dedicated test installation because they modify local app settings. Contributions use the project's MIT license. No signing keys, local SDK paths, generated APKs or private account details belong in Git.

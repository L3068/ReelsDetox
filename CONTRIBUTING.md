# Contributing

Use JDK 17 and the SDK packages listed in README.md. Keep changes focused. Run:

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest # dedicated emulator/device only
```

Write failing behavioral tests for classifier, quota and navigation changes. Keep Android tree access outside `core`. Unknown or incomplete evidence must fail open. An identifier containing `reel` is not sufficient: Instagram also uses that word for Stories.

New detection adapters need sanitized, minimal fixtures and a recorded device/Android/Instagram version/entry route. Synthetic fixtures are useful regression tests but cannot establish compatibility. Do not upload complete accessibility trees, messages, handles, tokens, contacts or account data. Never add logs containing node text/descriptions.

See [manual testing](docs/manual-testing.md) for the acceptance matrix. Include false-positive tests for Home, Stories, messages and ordinary videos with every new detection rule. Keep all UI text and documentation in English. Changes must preserve consent and users' ability to disable/uninstall the app.

PRs run host tests, lint and APK compilation. Device tests require a dedicated test installation because they modify local app settings. Contributions use the project's MIT license. No signing keys, local SDK paths, generated APKs or private account details belong in Git.

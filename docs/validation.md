# Validation record

Validation date: 2026-10-26 (version 0.1.1). Toolchain: JDK 17.0.21, Gradle 9.4.1, AGP 9.2.1; SDK Platform 37.0 and Build Tools 36.0.0. Device tests use an Android 16/API 36 x86_64 emulator with a Pixel 8 hardware profile. This is not a physical Pixel 8 test.

## Automated checks

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:connectedDebugAndroidTest --no-daemon
```

**Result: BUILD SUCCESSFUL; 27 core tests and 4 instrumentation tests passed; lint: no issues found.**

Debug APK SHA-256: `90231325de2bab091496c019dd72f70bc532631984cbcee2488ac2d696a6ba86`. This identifies the local build; CI debug signing/build output can differ.

English onboarding and dashboard were visually inspected on the emulator with a non-English per-app language setting. The UI remained English as required. The accessibility service was enabled on the dedicated emulator; Android reported it bound while protection remained off without in-app consent. No AndroidRuntime error was present in the inspected test log. This checks service setup, not Instagram detection. `:app:testDebugUnitTest` has no additional Android-host tests: business behavior is tested in `core`, and real DataStore/Compose behavior is tested by instrumentation.

- Core JUnit: settings/modes/consent, conservative classifiers and label corroboration, false positives, overlay geometry, bounded Back retries, delayed exhaustion reporting, one-pixel navigation-boundary rejection, Back episodes, elapsed timing, pause/resume, local midnight, DST, timezone carry, wall-clock jumps, restart restoration, disconnected date projection and boot generation.
- Android instrumentation: real Preferences DataStore persistence/recreation; stale checkpoints do not roll back totals; consent revocation; codec round-trip/corruption rejection; UI can decline disclosure without enabling protection; Activity resources explicitly use English regardless of device language.
- Android lint and APK compilation include the Compose UI, service, overlay and test APK.
- Merged application manifest: no INTERNET, SYSTEM_ALERT_WINDOW, PACKAGE_USAGE_STATS, storage or notification permission. AndroidX adds only the app's own signature-level dynamic-receiver permission. The service binding is protected by Android's BIND_ACCESSIBILITY_SERVICE.
- Tests were first observed failing before the domain implementation, reboot enhancement and label fallback were implemented.

## Review and remaining gates

Source was reviewed during integration for stale snapshots across suspension, uncertain-tree Back rearming, interrupted-service status, deferred consent persistence and stale usage writes. A focused independent static review of the 0.1.1 changes identified a stale-cover window during suspended persistence; the fix was independently rechecked. This is not a security audit or device validation.

No Instagram app or account was used on the emulator. All Instagram-like classifier fixtures are synthetic. The [manual matrix](manual-testing.md), actual Instagram node IDs, entry routes, cover positioning/touch interception, performance on a physical Pixel 8, reboot/lock stress tests and Google Play approval remain unverified. No production release signing has been prepared.

# Validation record

Validation date: 2026-10-26 (version 0.1.3). Toolchain: JDK 17.0.21, Gradle 9.4.1, AGP 9.2.1; SDK Platform 37.0 and Build Tools 36.0.0. Device tests use an Android 16/API 36 x86_64 emulator with a Pixel 8 hardware profile. This is not a physical Pixel 8 test.

## Automated checks

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:connectedDebugAndroidTest --no-daemon
```

**Result: BUILD SUCCESSFUL; 43 core tests, 7 Android-host checkpoint/startup tests and 11 instrumentation tests passed; lint: no issues found.**

Debug APK SHA-256: `a44eb97b61ce4d8106146e4609c7a1670eabeb5f2b61ac1085ea0b1033663ecf`. This identifies the local build; CI debug signing/build output can differ.

English onboarding and dashboard were visually inspected on the emulator with a non-English per-app language setting. The UI remained English as required. The accessibility service was enabled on the dedicated emulator; Android reported it bound while protection remained off without in-app consent. No AndroidRuntime error was present in the inspected test log. This checks service setup, not Instagram detection. `:app:testDebugUnitTest` covers nonblocking checkpointing with a deliberately stalled writer, failure latching and bounded entry probes, including stale-readiness handling.

- Core JUnit: settings/modes/consent, conservative classifiers and label corroboration, false positives, overlay geometry, bounded Back retries, delayed exhaustion reporting, one-pixel navigation-boundary rejection, Back episodes, elapsed timing, pause/resume, local midnight, DST, timezone carry, wall-clock jumps, restart restoration, disconnected date projection and boot generation.
- Android instrumentation: real Preferences DataStore persistence/recreation; stale checkpoints do not roll back totals; consent revocation; codec round-trip/corruption rejection; one-screen consent, atomic activation before Accessibility, decline without activation, Privacy/About access from setup and dashboard, existing-settings preservation, and English resources.
- Android lint and APK compilation include the Compose UI, service, overlay and test APK.
- Merged application manifest: no INTERNET, SYSTEM_ALERT_WINDOW, PACKAGE_USAGE_STATS, storage or notification permission. AndroidX adds only the app's own signature-level dynamic-receiver permission. The service binding is protected by Android's BIND_ACCESSIBILITY_SERVICE.
- Tests were first observed failing before the domain implementation, reboot enhancement and label fallback were implemented.

## Review and remaining gates

Source was reviewed during integration for stale snapshots across suspension, uncertain-tree Back rearming, interrupted-service status, deferred consent persistence and stale usage writes. A focused independent static review of the 0.1.1 changes identified a stale-cover window during suspended persistence; the fix was independently rechecked. This is not a security audit or device validation.

No Instagram app or account was used on the emulator. A separately authorized physical-device session verified the lower-tab route on Pixel 8 / Android 17 / Instagram 452.1.0.53.64: 20 covered-button taps stayed out, and 10 entries with button hiding temporarily disabled returned to Home. See feasibility.md. Classifier unit fixtures are sanitized/synthetic rather than private screen dumps. The remaining [manual matrix](manual-testing.md), additional Instagram variants/entry routes and long-duration physical-device performance, reboot/lock stress tests and Google Play approval remain unverified. No production release signing has been prepared.

A focused independent static review found and verified fixes for failed-checkpoint recovery and selection of an oversized stale guard candidate.

Quick setup and seven-language control vocabulary were checked after the user's interface revision. Setup and the taller daily-limit dashboard were visually inspected; no marketing onboarding pages remain.

Final identity: ReelsDetox 0.1.3, adaptive color icon plus Android 13+ monochrome icon, existing application ID retained for upgrades. Spacious header/footer layout and continuous whole-minute slider visually checked.

## Version 0.1.3 focused review

The review found excessive event-driven checkpoint writes, guarded waiting billed as viewing, root lookups outside active consent, and a final-checkpoint/reconnect race. Fixes now enforce the active one-second interval, pause under an attached guard, gate root access and publish a departing service's final ledger before asynchronous persistence. Regression tests first failed against the old behavior and now pass, including real DataStore handoff/settings transactions.

Shared-Reel investigation identified the actual chat exit IDs, synchronous multi-query reader stalls, and display-cover removal when System UI takes focus. The revised prefetched worker scan checks consent/generation during traversal, drops stale results, and keeps timing pause callbacks on Main. Explicit clicks receive a bounded settling burst. API 34+ viewer guards attach to the Instagram window rather than the display. Core cases cover missing-parent System UI retention and rejection of foreign apps, keyboard, lock and geometry changes. A separate bounded source review found no remaining P1/P2 issue in the final diff.

On Pixel 8 / Android 17 / Instagram 452.1.0.53.64, five window-event traces confirmed shared viewer opens followed by chat returns; ten normal and twenty rapid taps ended in the chat. Three quick-open/shade probes also returned to chat. After the final chat-ID fix, the user confirmed their own method no longer left a watchable Reel. This does not certify every entry route, API 34–36, or a deliberately unchanged viewer held under the shade after rejected Back.

Theme override selection and recreation passed instrumentation. The dark navigation palette was measured against the adjacent Instagram bar; no screenshot capability or other permission was added. Fixed palettes may differ on other Instagram themes/experiments.

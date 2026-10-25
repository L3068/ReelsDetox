# Installing on a Pixel 8

This is an **experimental debug APK**, not a production-signed or Play-approved release. API 26+ is required; Pixel 8 hardware is in range. Actual Instagram detection still requires device testing.

1. Build `./gradlew :app:assembleDebug`, or open a successful **Android** GitHub Actions run and download its `ReelsGuard-debug-<commit>` artifact. Sign in to GitHub if artifact downloads require it.
2. Extract the artifact and transfer `app-debug.apk` to your phone. Verify the download source/commit.
3. Open it on the phone and allow installation from that file manager/browser only if you trust it. Alternatively enable USB debugging and run:

   ```sh
   adb devices
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

4. Open ReelsGuard. Read the onboarding and separate accessibility disclosure. Tap **I agree** only if you accept the described access.
5. In Android **Settings → Accessibility → Downloaded apps / Installed apps → ReelsGuard protection**, enable the service. Labels vary with Android language/version.
6. Return to ReelsGuard, choose Complete Blocking or Daily Time Limit (1–120 minutes), then enable protection. Check both accessibility permission and service connection.
7. Test the [manual checklist](manual-testing.md). A connected service does not prove that your Instagram version exposes the required identifiers.

## Restricted settings

Android may restrict accessibility access for sideloaded apps. Only for an APK/source you trust, inspect **Settings → Apps → ReelsGuard → ⋮ → Allow restricted settings** if that option is offered, then repeat accessibility setup. Availability differs by OS/build/install source. ReelsGuard does not bypass the restriction. If the option is unavailable, consult your Android version's official guidance rather than granting unrelated privileges.

No special "draw over other apps" permission is required. There is no Usage Access or notification permission. The optional cover is provided by the enabled accessibility service.

## Disable / uninstall / updates

Turn protection off in the dashboard, withdraw consent in ReelsGuard Settings, or disable its service in Android Accessibility. Uninstall normally. The app never prevents this.

`adb install -r` preserves settings only when the update's signing certificate matches. Local builds and CI runs may use different debug keys, so updates can fail with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Do not uninstall casually if you want to preserve counters: uninstalling/clearing data erases them. A future stable signing workflow is needed for seamless public updates.

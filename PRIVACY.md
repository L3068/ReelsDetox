# Privacy

ReelsDetox processes data locally. The application has no INTERNET permission, servers, accounts, advertising, analytics or third-party tracking SDKs.

## Accessibility access

After separate, affirmative in-app consent, Android's accessibility service receives window-event metadata from applications so it can notice when Instagram loses focus. It reads a screen tree only when the active application is `com.instagram.android`. The tree may expose sensitive content. ReelsDetox inspects view identifiers, visibility, hierarchy, selection, bounds and accessibility descriptions; it reduces recognized fixed UI descriptions to enum labels, never reads `node.text`, and never logs, transmits or persists the tree or descriptions. It does not request passwords or sign into Instagram. Transient platform objects may still contain content exposed by Android until released.

This access recognizes a Reels viewer, measures viewing time, invokes Back under the user's chosen rule and optionally covers the identified Reels button. The button cover consumes taps within that button. A separate opaque guard temporarily covers a confidently identified blocked Reels viewer while Back completes; system navigation is kept available. Other content is left alone when classification is uncertain.

## Saved locally

DataStore stores consent, protection settings, the chosen mode/allowance, daily duration totals, time/zone anchors and the public Android boot counter. Daily totals are retained until app data is cleared or the app is uninstalled; dates have no screen content or individual-viewing-session history attached. Cloud backup and device transfer of this data are disabled by app configuration.

No screen dumps, screenshots, messages, usernames, URLs, credentials or account information are stored by the app. In debug builds, an explicitly requested ADB service dump reports only classification, geometry, overlay state and sanitized exception code locations. It contains no node text, descriptions or account identifiers. No data is sent by the application. Build tools and GitHub downloads are separate from the app and follow their own privacy policies.

## Control

Protection starts disabled. You can decline consent, disable protection, withdraw consent in Settings, turn off ReelsDetox in Android accessibility settings, clear storage or uninstall. Revoking consent stops monitoring actions but preserves previous duration totals and settings; clearing app storage or uninstalling removes local app data. No feature prevents these actions.

Last reviewed: 2026-10-26.

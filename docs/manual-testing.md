# Manual acceptance matrix

**Limited physical evidence exists for Pixel 8 / Android 17 / Instagram 452.1.0.53.64 lower-tab entry.** See feasibility.md. Remaining boxes describe the broader acceptance matrix and must not be marked from that smoke test alone. Never mark a row from unit-test or APK-build success.

Record date, ReelsDetox commit, phone model, Android build/API, Instagram version, language, navigation mode and orientation. Use a dedicated test account without sensitive messages. Share only sanitized findings, never full screen trees.

## Entry routes and false positives

For both Complete Blocking and Daily Limit (before and after exhaustion):

- [ ] Home → dedicated Reels tab: blocked viewer returns with at most one retry; Home remains usable.
- [ ] Explore → Reel; profile → Reel; shared external Reel link; DM → shared Reel: classify the actual viewer, not its preview/link.
- [ ] Home image/carousel, ordinary feed video, Explore image, profile grid: no Back, no Reels timing.
- [ ] Open/read/write DM and shared-Reel preview: no Back or timing before viewer opens.
- [ ] Stories and Story replies: never classified as Reels (Instagram's internal `reel` naming can refer to Stories).
- [ ] Unknown/incomplete/hidden node trees: no navigation, no coverage and no timing.
- [ ] Rejected Back or unchanged viewer: at most two attempts per episode; no unbounded Back loop; status reports rejection or a viewer still open after the retry cooldown; the confirmed viewer stays visually covered.
- [ ] Reel with comments/share sheet/keyboard: fail open while ambiguous; ordinary interactions remain available.

## Covers

- [ ] Cover corresponds exactly to the real clickable Reels navigation node, consumes taps, and is visible as a cover.
- [ ] Cover off toggle takes effect immediately; moving valid bounds updates the existing cover without remove/add flicker; within daily allowance no cover is shown.
- [ ] Missing/multiple candidates: no guessed fixed-coordinate cover.
- [ ] App switch, notification shade, permission dialog, keyboard, lock screen, Home and Recents: no display cover over another app or System UI. On API 34+, an existing child guard may remain inside Instagram underneath the shade; notification controls stay usable. Check the older fallback separately.
- [ ] Rotate, change display/font scale, gesture vs three-button navigation: no system-button or neighboring-tab obstruction.
- [ ] Split-screen, secondary display and API 26–29: safe documented fallback.
- [ ] Device light/dark modes: button cover matches the adjacent Instagram navigation background; no rounded corners expose the icon.
- [ ] Instagram uses a separate theme: Settings → Button cover color → Light/Dark overrides the device theme, survives restart, and recolors an existing cover without moving its bounds. Device restores automatic theme following. Record palette differences on other versions.

## Timing and recovery

- [ ] Set 1 minute; only actual detected viewing counts; stationary video reaches limit without an event.
- [ ] Background/lock pauses immediately; resuming continues the saved total.
- [ ] A blocked viewer behind a successfully attached opaque guard does not consume more allowance; failed guard creation continues actual-viewing accounting.
- [ ] Dense content events do not bypass one-second active checkpoints; disabling/revoking does not cause anchor-only writes or screen-tree reads.
- [ ] Service disconnect/reconnect before a final disk write completes retains both sessions' totals.
- [ ] Switch modes or disable/re-enable: no quota reset.
- [ ] Change allowance 1 ↔ 120, including below already-used duration: policy updates immediately.
- [ ] Natural local midnight, DST short/long day, timezone travel: expected reset/carry rules from feasibility report.
- [ ] Clock moved backwards/forwards without reboot: no manufactured viewing time/free refill; monotonic schedule remains.
- [ ] Process killed/restarted, app force stopped, service toggled, phone rebooted: durable total retained; no downtime counted. Force-stop may require re-enabling service.
- [ ] Abrupt kill can lose at most checkpoint interval plus storage latency; document measured result.
- [ ] Storage inaccessible/corrupt: visible error and no silent quota reset.

## UI / privacy / accessibility

- [ ] English, light/dark, portrait/landscape, large font, TalkBack: all controls remain readable/reachable.
- [ ] Fresh installation → decline consent: app usable, protection off, Android permission screen not opened.
- [ ] Accept disclosure → Android settings → enable service → return → enable protection.
- [ ] Revoke consent, disable service or uninstall is always possible.
- [ ] Dashboard distinguishes disabled setting, missing permission, disconnected service, interrupted monitor and storage failure.
- [ ] Merged app manifest has no INTERNET, usage access, media projection, overlay runtime, storage or notification permissions.
- [ ] Logs and DataStore contain no private screen content.

An emulator test of ReelsDetox itself does not check any Instagram row above. Promote an adapter from candidate to supported only after recording real-device evidence for every relevant entry route and false-positive row.

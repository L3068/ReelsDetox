# Security

ReelsGuard 0.1 is an experimental tester build. No security audit or universal Instagram compatibility is claimed.

Report security problems privately through the repository's **Security → Report a vulnerability** when available. If private reporting is unavailable, open an issue requesting a private contact without publishing exploit details or private data. Never attach credentials, screen contents or unredacted device logs.

The main trust boundary is the Android accessibility service. Only the OS can bind via `BIND_ACCESSIBILITY_SERVICE`; automation also requires in-app consent and the protection switch. Global Back and covers are limited to an actively verified Instagram application window. Missing/ambiguous trees fail open. There are no network permissions, dynamic code loading, exported content providers, analytics or backend services.

This is a voluntary wellbeing tool, not tamper-resistant parental control. Force stopping, revoking access, clearing data, uninstalling or modifying the app can defeat protection. Durable time checkpoints limit accidental loss but do not protect against a hostile device owner. Offline wall time cannot be trusted against clock changes combined with reboot.

Dependency updates should run all tests and the device matrix. Debug APKs use development signing and are not production releases. Never commit keystores; any future release signing must use protected CI secrets and a reviewed release workflow.

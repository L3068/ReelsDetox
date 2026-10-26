# Publication

Public repository: [L3068/ReelsDetox](https://github.com/L3068/ReelsDetox). To publish a local clone if no remote exists:

```sh
gh auth login
git init -b main
git add .
git commit -m "Build experimental ReelsDetox Android MVP"
gh repo create ReelsDetox --public --source=. --remote=origin --push
```

Inspect `git diff --cached` before committing. `.gitignore` excludes SDK paths, generated builds, IDE caches and signing material. Keep the Gradle wrapper JAR tracked.

The Android workflow runs on main pushes and pull requests, compiles device tests, executes host tests and lint, builds a debug APK and uploads it with reports. Instrumentation can be run locally on a dedicated emulator; it is not claimed to run in this build-only CI job. CI artifact downloads can require GitHub sign-in. Release signing keys must not be committed.

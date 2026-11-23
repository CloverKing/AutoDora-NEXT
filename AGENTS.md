# AutoDora-NEXT Agent Guidelines

## Starting the Android Emulator (Windows)

When asked to start or reopen the Android emulator so the user can see and interact with it on Windows:

1. **Do NOT launch `emulator.exe` or `android emulator start` directly from `run_command`**.
   - Commands executed via `run_command` run on an isolated Windows desktop (hidden from `WinSta0\Default`), so the emulator window will not be visible on the user's screen, and non-daemon child processes exit when the command finishes.
2. **Always start the emulator through the Android Device Hub sidecar HTTP API** (which runs in the user's interactive desktop session):
   - Read the active loopback port from the latest `Device Hub listening on http://localhost:<PORT>` line in:
     `%USERPROFILE%\.gemini\antigravity\sidecar_data\android-cli-plugin\device-hub\logs\sidecar.log`
   - Query available AVDs via `GET http://127.0.0.1:<PORT>/api/devices` (primary test AVD: `medium_phone_api36_play`).
   - Start the AVD via:
     ```powershell
     Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:<PORT>/api/start" `
       -ContentType "application/json" `
       -Body '{"device":"medium_phone_api36_play","cold":false}'
     ```
   - Wait until `adb wait-for-device` returns and `adb -s emulator-5554 shell getprop sys.boot_completed` equals `1`, then ensure overlay & accessibility permissions are active and launch `com.autodora.next/.MainActivity`.
3. **Surface the sidecar pane pill** `[Android Device Hub](sidecar://android-cli-plugin/device-hub/)` so the user can also mirror/control the emulator inside Antigravity.

## Documentation & Screenshot Synchronization Policy (Mandatory)

Whenever performing **any** feature addition, functional optimization, solver/vision/touch improvement, UI/UX adjustment, or version bump, **always update the corresponding documentation files in the same commit**:

1. **`README.md`**:
   - Keep version badges, feature descriptions, UI drawer/toolbar/speed/workflow explanations, test counts, and build commands 100% synchronized with the latest APK.
   - Whenever visible App UI screens, floating panels, or Board Editor layouts change meaningfully, recapture clean app-only screenshots from the Android emulator into `docs/images/` (strictly zero copyrighted game artwork!) and update `README.md`.
2. **Design & Planning Docs (`docs/` & `ml/`)**:
   - Update `docs/ARCHITECTURE.md` whenever modules, pipelines, solver rules, vision capabilities, touch mechanics, update flows, test suites, or Phase milestones change.
   - Update `docs/TOS_COMPLETE_MECHANICS_CATALOG.md`, `docs/enemy_skill_audit.md`, and `docs/runestone_status_audit.md` whenever game mechanics, shield rules, hazard physics, CV detectors, or Board Editor brushes are added or modified.
   - Update `ml/README.md` whenever Phase 4 CNN heads, training data schema, or model export specifications change.

## Versioning, Git Commit, Push & GitHub Release Workflow

1. **Semantic Versioning 2.0.0 (`MAJOR.MINOR.PATCH`)** in `android/app/build.gradle.kts`:
   - `versionName = "$appVersionMajor.$appVersionMinor.$appVersionPatch"` (e.g. `0.3.0`, `0.3.1`, `0.4.0`, `1.0.0`)
   - `versionCode = appVersionMajor * 10000 + appVersionMinor * 100 + appVersionPatch` (e.g. `300`, `301`, `400`, `10000`)
   - Increment `PATCH` on every bugfix release, `MINOR` on new feature milestones, `MAJOR` on breaking/GA milestones.
2. **Commit after every verified bug fix or feature**:
   - Run all C++20 solver tests and Android unit tests before committing.
   - Verify all relevant documentation files (`README.md`, `docs/*.md`, `ml/README.md`) are updated alongside code changes.
   - Stage only clean project files (never commit `backups/`, personal paths, or copyrighted game assets) using Conventional Commits (`fix(...):`, `feat(...):`, `docs(...):`, `chore(...):`).
3. **Push to GitHub and publish a new GitHub Release**:
   - Push the active branch (`dev`) and annotated tag `v<versionName>` to `origin`.
   - Build the signed installable APK (`.\android\build-android.ps1 :app:assembleRelease`) and publish a GitHub Release via `gh release create v<versionName> <apk> --title "AutoDora-NEXT v<versionName>" --notes "..."` (use `--prerelease` while `MAJOR == 0`).

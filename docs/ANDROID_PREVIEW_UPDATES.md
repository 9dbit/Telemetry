# Telemetry Android Preview Updates

The development APK uses an isolated preview package (`com.telemetry.preview`) and a preview-only signing identity. This is intentionally separate from the future Google Play production package/signing setup.

## User flow

1. Launch Telemetry Preview.
2. The update gate checks the Railway update endpoint when the last successful check is older than six hours.
3. If no update is available, the offline app opens normally. Network failure never blocks Telemetry.
4. If an update is available, the user sees the new version and can tap **Download & Install**.
5. The APK is downloaded over HTTPS and verified against the control-plane SHA-256 manifest, package ID, version code, and the currently installed signing certificate.
6. Android's system package installer performs the update. On sideloaded preview builds Android may require the user to grant Telemetry Preview permission to install unknown apps and still requires system confirmation.

Long-pressing the Telemetry Preview launcher icon also exposes a **Check Update** shortcut.

## Release pipeline

Every Android-relevant push to `main` builds a signed preview APK with a monotonically increasing GitHub Actions run number as `versionCode`, uploads the CI artifact, and publishes a GitHub Release tagged `android-preview-<versionCode>`. The Railway control plane resolves the newest preview release through `GET /api/v1/android/update`.

## Production migration

The preview signing material is development-only. The future Google Play package should use Play App Signing and Play In-App Updates rather than `REQUEST_INSTALL_PACKAGES`. The preview updater must not be copied unchanged into the production Play build.

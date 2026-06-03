# ColorOS VPN Fix

LSPosed/libxposed module for fixing VPN UID range coverage for ColorOS clone users.

On some ColorOS builds, apps running inside clone users are not included in the UID ranges attached to the owner user's `VpnService` network. The VPN itself can work for the owner user while cloned apps bypass it or disconnect unexpectedly. This module patches the system-side VPN UID range generation in `system_server` so detected ColorOS clone users are covered by the VPN ranges.

This project is not a VPN app, SOCKS proxy, or traffic relay. It only adjusts Android framework VPN UID ranges.

## Features

- Uses modern libxposed API 101.
- Scopes to `system` only.
- Hooks `com.android.server.connectivity.Vpn` in `system_server`.
- Discovers alive users via ColorOS/Android `UserManager` internals, with `/data/user` fallback.
- Targets ColorOS clone user IDs in the `900-999` range.
- Expands full non-system app UID ranges when the VPN is not app-restricted.
- Expands package-specific ranges when the VPN uses `allowedApplications`.
- Includes SDK sandbox UID handling when available.
- Provides a simple status UI and an optional diagnostic log UI.

## Requirements

- Android/ColorOS device with an LSPosed/libxposed-compatible framework.
- libxposed API 101 compatible runtime.
- Module scope: `system`.
- Reboot after enabling the module for `system`.

## Usage

1. Build or install the APK.
2. Enable the module in LSPosed/Vector.
3. Select only the `system` scope.
4. Reboot.
5. Open `ColorOS VPN Fix` and check the activation/status page.

## Diagnostic Logs

Logs are off by default.

When logs are enabled, the module first writes diagnostic entries to the app-private file `clone-vpn-fix.log` through the log provider. If the provider is temporarily unavailable while logging is enabled, the module falls back to a bounded `Settings.Global` bridge buffer.

The fallback buffer is capped at 512 short entries and is only used as a temporary bridge. It is not an unlimited system settings log. Opening the log page or exporting logs syncs available fallback entries into the private log file.

The private log file rotates at 4 MiB to `clone-vpn-fix.log.bak`. Export includes both the backup and current log file when available.

The log provider accepts calls only from the app itself, Android `system` UID, or root.

## Hidden Troubleshooting Properties

These properties are optional and intended for debugging:

```sh
setprop persist.clonevpnfix.enabled 1
setprop persist.clonevpnfix.mode non_owner
setprop persist.clonevpnfix.users ""
setprop persist.clonevpnfix.log 0
```

Available keys:

- `persist.clonevpnfix.enabled`: `1` or `0`, default `1`.
- `persist.clonevpnfix.mode`: `non_owner`, `clone`, or `all`, default `non_owner`.
- `persist.clonevpnfix.users`: comma-separated explicit user ID allowlist; overrides mode when set.
- `persist.clonevpnfix.log`: enables extra framework logcat/Xposed logs, default `0`.

## Build

Open the project in Android Studio, or build with a local Gradle installation:

```sh
gradle :app:assembleDebug
```

For release builds, configure your own signing outside the repository:

```sh
gradle :app:assembleRelease
```

Signing keys and generated APKs are intentionally not tracked.

## Notes

- This module does not change VPN app routing, proxy protocol support, DNS behavior, IPv6 routes, or UDP support.
- If a specific VPN app does not support IPv6 or UDP, that still needs to be fixed in the VPN app itself.
- The module only changes the system UID ranges used to decide which app UIDs belong to the VPN network.

# Densify (alpha)

Per-game display density automation with on-device Wireless Debugging. No root, no Shizuku, no PC after setup.

- **App:** Expo (React Native, TypeScript). Seven design-language themes (Matte, Graphite, Manga, Material You, HUD, Paper, Ember).
- **Native engine:** local Expo module `modules/densify-native` (Kotlin): on-device ADB, foreground service, Quick Settings tile, notification controls.
- **No hardcoded games:** the list comes from the phone's installed apps (launcher activities, `CATEGORY_GAME`). No game or package names exist anywhere in the source.
- **Storage:** all settings, profiles, presets and quick values persist on the device (SharedPreferences through the native module).

## Get an APK (EAS)
1. Push this folder to a GitHub repo.
2. `npm install -g eas-cli && eas login`
3. `eas init` (creates the Expo project and links `extra.eas.projectId`), then connect the GitHub repo on expo.dev.
4. `eas build -p android --profile preview` → downloads an installable `.apk`.

(Or tell Claude your `@owner/densify` app name once the repo is connected and it can start the build and fetch the link.)

## Preview the UI on a computer
`npm install && npx expo start --web` (no device data, but every theme and screen renders).

## First run on the phone
1. Install the APK, open Densify, switch on **Automatic switching**: it asks for notifications, usage access and Wireless Debugging as needed.
2. Setup page: turn on Wireless Debugging, tap Start setup, enter the pairing code in the notification.
3. Settings → Detect current DPI, then add a game and save a profile.

## Not in this alpha (removed from the UI rather than faked)
Touch guard, break reminder, charger-aware presets, automation intents.

## New in 1.1
Floating panel over games (−5 / +5, two typable quick DPI values, typable stretch resolution), a Stretch resolution card on Home (typed as width*height, e.g. 2160*1080), per-game stretch, automation scripts (game start / exit / boot), terminal, activity log, backup and restore, root mode, Wi-Fi auto-reconnect, Graphite and Manga themes (Glass removed), plugins (AxManager/KernelSU-style zip modules with module.prop, customize.sh, boot scripts, action.sh and webroot WebUI; or small JSON plugins), developer tweaks, power menu, settings editor, external broadcast API, a bindable service with per-app approval, BusyBox and resetprop for plugins, crash screen, a real persistent shell session, Shizuku-protocol service (apps that use the Shizuku API can run shell commands through Densify after you approve them), KernelSU WebUI API (exec, spawn, packages), accent colour picker, plugin search, and "From a computer" (adb tcpip) mode for Android 10 and below. Works on Android 7 (API 24) and newer; Wireless Debugging itself needs Android 11+, older versions use the computer method once per boot.

## Unverified
The Kotlin module has not been compiled yet. The likeliest first-build errors are `libadb-android` API names (`connectTls`, `pair`, `openStream`) and the `sun.security.x509` imports. Send the EAS build log and they get fixed.

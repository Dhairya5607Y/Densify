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
Floating DPI panel over games, per-game stretch screen, automation scripts (game start / exit / boot), terminal, activity log, backup and restore, root mode, Wi-Fi auto-reconnect, Graphite and Manga themes (Glass removed).

## Unverified
The Kotlin module has not been compiled yet. The likeliest first-build errors are `libadb-android` API names (`connectTls`, `pair`, `openStream`) and the `sun.security.x509` imports. Send the EAS build log and they get fixed.

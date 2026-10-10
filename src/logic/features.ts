import type { IconName } from '../ui/primitives';

export type Req = 'notifications' | 'usage' | 'adb' | 'battery' | 'overlay';

/** What each feature needs before it can work. Turning the feature on asks for these. */
export const NEEDS: Record<string, Req[]> = {
  automation: ['notifications', 'usage', 'adb'],
  lowBattery: ['notifications'],
  quickNotif: ['notifications'],
  breakReminder: ['notifications'],
  hz: ['adb'], perf: ['adb'], dnd: ['adb'], rot: ['adb'], guard: ['adb'], bright: ['adb'], awake: ['adb'],
  preview: ['adb'],
  floating: ['overlay', 'adb'],
  stretch: ['adb'],
  shell: ['adb'],
  developer: ['adb'],
};

export const REQ: Record<Req, { icon: IconName; title: string; body: string; action: string }> = {
  notifications: { icon: 'notifications', title: 'Allow notifications', body: 'Densify needs notifications for the quick controls, the battery warning and pairing.', action: 'Allow' },
  usage: { icon: 'insights', title: 'Allow usage access', body: 'This lets Densify see which game is open so it can switch DPI at the right moment. Find Densify in the list and turn it on.', action: 'Open settings' },
  adb: { icon: 'link', title: 'Connect Wireless Debugging', body: 'Changing display settings needs a one-time Wireless Debugging connection. It takes about a minute.', action: 'Set up' },
  overlay: { icon: 'layers', title: 'Allow display over other apps', body: 'The floating DPI panel is drawn on top of your game, so Android needs this permission. Find Densify and turn it on.', action: 'Open settings' },
  battery: { icon: 'battery-saver', title: 'Allow unrestricted battery', body: 'Without this, Android may stop Densify in the background and your DPI will not switch back.', action: 'Open settings' },
};

export const BUNDLE_ITEMS: { key: 'hz' | 'perf' | 'dnd' | 'rot' | 'guard' | 'bright' | 'awake'; title: string; icon: IconName }[] = [
  { key: 'hz', title: 'Maximum refresh rate', icon: 'speed' },
  { key: 'perf', title: 'Performance mode', icon: 'bolt' },
  { key: 'dnd', title: 'Hide message pop-ups', icon: 'notifications-off' },
  { key: 'rot', title: 'Lock rotation', icon: 'screen-lock-rotation' },
  { key: 'guard', title: 'Touch guard', icon: 'back-hand' },
  { key: 'bright', title: 'Lock brightness', icon: 'brightness-medium' },
  { key: 'awake', title: 'Keep screen on', icon: 'visibility' },
];

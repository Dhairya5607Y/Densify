import { useEffect, useState } from 'react';
import { Native } from '../../modules/densify-native';
import type { AdbEvent, AdbState, Capabilities, EngineEvent, InstalledApp, SettingsKind } from '../../modules/densify-native';

export type { AdbState, Capabilities, EngineEvent, InstalledApp, SettingsKind };

/** True only inside the real Android build. In Expo Go / web the app still runs, but reads no device data. */
export const nativeAvailable = Native != null;

const caps0: Capabilities = { hz: 60, perf: false, guard: false, tile: false };

export const api = {
  storageGet: (k: string) => (Native ? Native.storageGet(k) : null),
  storageSet: (k: string, v: string) => (Native ? Native.storageSet(k, v) : false),
  setFlags: (monitoring: boolean, boot: boolean) => (Native ? Native.setFlags(monitoring, boot) : false),
  hasUsageAccess: () => (Native ? Native.hasUsageAccess() : false),
  hasNotifications: () => (Native ? Native.hasNotifications() : false),
  isBatteryUnrestricted: () => (Native ? Native.isBatteryUnrestricted() : false),
  nativeLibDir: () => (Native ? Native.nativeLibDir() : ''),
  hasOverlay: () => (Native ? Native.hasOverlay() : false),
  logs: () => (Native ? Native.logs() : []),
  clearLogs: () => (Native ? Native.clearLogs() : false),
  previewFloating: (sec: number) => (Native ? Native.previewFloating(sec) : false),
  adbConnected: () => (Native ? Native.adbConnected() : false),
  isMonitoring: () => (Native ? Native.isMonitoring() : false),
  openSettings: (k: SettingsKind) => (Native ? Native.openSettings(k) : false),
  requestAddTile: () => (Native ? Native.requestAddTile() : false),
  startPairing: () => (Native ? Native.startPairing() : false),
  startMonitoring: () => (Native ? Native.startMonitoring() : false),
  stopMonitoring: () => (Native ? Native.stopMonitoring() : false),
  getInstalledApps: async (): Promise<InstalledApp[]> => (Native ? Native.getInstalledApps() : []),
  getAppIcon: async (pkg: string): Promise<string | null> => (Native ? Native.getAppIcon(pkg).catch(() => null) : null),
  connect: async () => { if (!Native) throw new Error('Wireless Debugging is only available in the installed app.'); return Native.connect(); },
  grantSelf: async () => { if (!Native) throw new Error('Not available here.'); return Native.grantSelf(); },
  capabilities: async (): Promise<Capabilities> => (Native ? Native.capabilities().catch(() => caps0) : caps0),
  readDensity: async () => { if (!Native) throw new Error('DPI control is only available in the installed app.'); return Native.readDensity(); },
  setDensity: async (dpi: number) => { if (!Native) throw new Error('DPI control is only available in the installed app.'); return Native.setDensity(dpi); },
  readSize: async () => { if (!Native) throw new Error('Screen size control is only available in the installed app.'); return Native.readSize(); },
  setSize: async (w: number, h: number) => { if (!Native) throw new Error('Not available here.'); return Native.setSize(w, h); },
  resetSize: async () => { if (!Native) throw new Error('Not available here.'); return Native.resetSize(); },
  getStretch: () => (Native ? Native.getStretch() : { w: 0, h: 0, on: false }),
  saveStretch: (w: number, h: number) => (Native ? Native.saveStretch(w, h) : false),
  applyStretch: async (w: number, h: number) => { if (!Native) throw new Error('Screen size control is only available in the installed app.'); return Native.applyStretch(w, h); },
  resetStretch: async () => { if (!Native) throw new Error('Not available here.'); return Native.resetStretch(); },
  sdkInt: () => (Native ? Native.sdkInt() : 34),
  shell: async (cmd: string) => { if (!Native) throw new Error('The shell is only available in the installed app.'); return Native.shell(cmd); },
  session: async (cmd: string) => { if (!Native) throw new Error('The shell is only available in the installed app.'); return Native.sessionExec(cmd); },
  sessionReset: () => (Native ? Native.sessionReset() : false),
  listPackages: async () => (Native ? Native.listPackages().catch(() => []) : []),
  disconnect: async () => (Native ? Native.disconnect() : false),
  isPaired: () => (Native ? Native.isPaired() : false),
  bridgeActive: () => (Native ? Native.bridgeActive() : false),
  enableBridge: async () => { if (!Native) throw new Error('Only available in the installed app.'); return Native.enableBridge(); },
  disableBridge: async () => (Native ? Native.disableBridge().catch(() => false) : false),
  restoreDefault: async () => { if (!Native) throw new Error('DPI control is only available in the installed app.'); return Native.restoreDefault(); },
};

export function useAdb() {
  const [s, set] = useState<AdbEvent>({ state: api.adbConnected() ? 'connected' : 'disconnected' });
  useEffect(() => {
    if (!Native) return;
    const sub = Native.addListener('onAdb', set);
    return () => sub.remove();
  }, []);
  return s;
}

export function useEngine() {
  const [s, set] = useState<EngineEvent>({ running: api.isMonitoring() });
  useEffect(() => {
    if (!Native) return;
    const sub = Native.addListener('onEngine', set);
    return () => sub.remove();
  }, []);
  return s;
}

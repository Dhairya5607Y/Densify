import { useEffect, useState } from 'react';
import { Native } from '../../modules/densify-native';
import type { AdbEvent, AdbState, Capabilities, EngineEvent, InstalledApp, SettingsKind } from '../../modules/densify-native';

export type { AdbState, Capabilities, EngineEvent, InstalledApp, SettingsKind };

/** True only inside the real Android build. In Expo Go / web the app still runs, but reads no device data. */
export const nativeAvailable = Native != null;

export type RecStatus = { state: 'idle' | 'recording' | 'paused'; elapsedMs: number; info: string; notes: string; name: string; error: string; last: string };
export type RecCaps = { hevc: boolean; uhd30: boolean; uhd60: boolean; sdk: number };
export type RecPlan = { w: number; h: number; fps: number; mbps: number; hevc: boolean; label: string; notes: string[] };
const REC_IDLE: RecStatus = { state: 'idle', elapsedMs: 0, info: '', notes: '', name: '', error: '', last: '' };

const caps0: Capabilities = { hz: 60, perf: false, guard: false, tile: false };

export type ShizukuStatus = 'MISSING' | 'STOPPED' | 'NEEDS_PERMISSION' | 'READY';
/** What the phone is (read from the device) and which connection method suits it. */
export type DeviceInfo = {
  sdk: number; release: string; brand: string; model: string; rom: string; romVersion: string;
  wifi: boolean; wirelessSupported: boolean; devOptions: boolean; usbDebugging: boolean; wirelessDebugging: boolean; secureSettings: boolean;
  shizuku: ShizukuStatus; recommended: 'shizuku' | 'tcpip' | 'wireless'; tips: string[];
};

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
  launchApp: (pkg: string) => (Native ? Native.launchApp(pkg) : false),
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
  /** Smallest width in dp (what Developer options shows), plus the phone's own and the current density. */
  readSw: async () => { if (!Native) throw new Error('Smallest width control is only available in the installed app.'); return Native.readSw(); },
  setSw: async (sw: number) => { if (!Native) throw new Error('Smallest width control is only available in the installed app.'); return Native.setSw(sw); },
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
  deviceInfo: async (): Promise<DeviceInfo | null> => {
    if (!Native) return null;
    try { return JSON.parse(await Native.deviceInfo()) as DeviceInfo; } catch { return null; }
  },
  shizukuStatus: async (): Promise<ShizukuStatus> => (Native ? ((await Native.shizukuStatus().catch(() => 'MISSING')) as ShizukuStatus) : 'MISSING'),
  shizukuRequest: async (): Promise<ShizukuStatus> => (Native ? ((await Native.shizukuRequest().catch(() => 'MISSING')) as ShizukuStatus) : 'MISSING'),
  shizukuOpen: () => (Native ? Native.shizukuOpen() : false),
  pairManual: async (port: number, code: string) => { if (!Native) throw new Error('Only available in the installed app.'); return Native.pairManual(port, code); },
  perfWatch: (on: boolean) => (Native ? Native.perfWatch(on) : false),
  perfLive: () => (Native ? Native.perfLive() : ''),
  perfSessions: () => (Native ? Native.perfSessions() : '[]'),
  perfClear: () => (Native ? Native.perfClear() : false),
  optimizeNow: async () => { if (!Native) throw new Error('Only available in the installed app.'); return Native.optimizeNow(); },
  compileApp: async (pkg: string) => { if (!Native) throw new Error('Only available in the installed app.'); return Native.compileApp(pkg); },
  recStatus: (): RecStatus => {
    try { return Native ? (JSON.parse(Native.recStatus()) as RecStatus) : REC_IDLE; } catch { return REC_IDLE; }
  },
  recCaps: (): RecCaps => {
    try { return Native ? (JSON.parse(Native.recCaps()) as RecCaps) : { hevc: false, uhd30: false, uhd60: false, sdk: 34 }; } catch { return { hevc: false, uhd30: false, uhd60: false, sdk: 34 }; }
  },
  recPlan: (): RecPlan | null => {
    try { return Native ? (JSON.parse(Native.recPlan(0, 0)) as RecPlan) : null; } catch { return null; }
  },
  recStart: () => (Native ? Native.recStart() : false),
  recPause: () => (Native ? Native.recPause() : false),
  recResume: () => (Native ? Native.recResume() : false),
  recStop: () => (Native ? Native.recStop() : false),
  recOpenLast: () => (Native ? Native.recOpenLast() : false),
};

/** Polls the recorder once a second while the screen that shows it is open. */
export function useRecorder() {
  const [s, set] = useState<RecStatus>(() => api.recStatus());
  useEffect(() => {
    const id = setInterval(() => set(api.recStatus()), 1000);
    return () => clearInterval(id);
  }, []);
  return s;
}

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

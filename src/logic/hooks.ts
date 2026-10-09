import { useCallback, useEffect, useState } from 'react';
import { AppState } from 'react-native';
import * as Battery from 'expo-battery';
import { api, InstalledApp } from '../native/api';

export function useBattery() {
  const [b, setB] = useState({ level: 100, charging: false });
  useEffect(() => {
    let live = true;
    const read = async () => {
      try {
        const [l, st] = await Promise.all([Battery.getBatteryLevelAsync(), Battery.getBatteryStateAsync()]);
        if (live) setB({ level: l < 0 ? 100 : Math.round(l * 100), charging: st === Battery.BatteryState.CHARGING || st === Battery.BatteryState.FULL });
      } catch { /* battery info unavailable: assume healthy, never block the UI */ }
    };
    read();
    const id = setInterval(read, 30_000);
    return () => { live = false; clearInterval(id); };
  }, []);
  return b;
}

/** Installed launcher apps, read from the device. Nothing is bundled or hardcoded. */
export function useApps() {
  const [apps, setApps] = useState<InstalledApp[]>([]);
  const [loading, setLoading] = useState(true);
  const load = useCallback(() => { setLoading(true); api.getInstalledApps().then(setApps).catch(() => setApps([])).finally(() => setLoading(false)); }, []);
  useEffect(() => {
    load();
    const s = AppState.addEventListener('change', (x) => { if (x === 'active') load(); });
    return () => s.remove();
  }, [load]);
  return { apps, loading, reload: load };
}

export const ago = (at: number) => {
  const m = Math.round((Date.now() - at) / 60000);
  return m < 1 ? 'Just now' : m < 60 ? `${m} min ago` : m < 1440 ? `${Math.round(m / 60)} h ago` : `${Math.round(m / 1440)} d ago`;
};

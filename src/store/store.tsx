import React, { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { api } from '../native/api';
import { DEFAULT_STATE, NO_STRETCH } from './defaults';
import type { AppState, Bundle, Profile } from './types';

export const resolveBundle = (s: AppState, p: Profile): Bundle => s.presets.find((x) => x.id === p.presetId)?.bundle ?? p.bundle;

/** Derived, flat config the native service reads (it cannot run JS while the app is closed). */
function toEngine(s: AppState) {
  return {
    defaultDpi: s.defaultDpi,
    automation: s.automation,
    lowBattery: s.lowBattery,
    lowBatteryPercent: s.lowBatteryPercent,
    floating: s.floating,
    autoReconnect: s.autoReconnect,
    mode: s.mode,
    quick: s.quick,
    scripts: s.scripts.map((x) => ({ name: x.name, code: x.code, trigger: x.trigger, pkg: x.pkg, on: x.on })),
    profiles: s.profiles.map((p) => ({ pkg: p.pkg, name: p.name, dpi: p.dpi, auto: p.auto, restore: p.restore, bundle: resolveBundle(s, p), stretch: p.stretch ?? NO_STRETCH })),
  };
}

function load(): AppState {
  try {
    const raw = api.storageGet('app');
    if (!raw) return DEFAULT_STATE;
    const o = JSON.parse(raw);
    if (o.themeId === 'glass') o.themeId = 'graphite'; // Glass was removed
    return { ...DEFAULT_STATE, ...o, flags: { ...DEFAULT_STATE.flags, ...(o.flags ?? {}) } };
  } catch {
    return DEFAULT_STATE; // corrupted storage: start clean instead of crashing
  }
}

type Ctx = { state: AppState; update: (fn: (s: AppState) => AppState) => void; log: (title: string, sub: string, pkg?: string) => void };
const C = createContext<Ctx>(null as unknown as Ctx);
export const useStore = () => useContext(C);

export function StoreProvider({ children }: { children: React.ReactNode }) {
  const [state, setState] = useState<AppState>(load);
  const first = useRef(true);
  useEffect(() => {
    if (first.current) { first.current = false; }
    api.storageSet('app', JSON.stringify(state));
    api.storageSet('engine', JSON.stringify(toEngine(state)));
    api.setFlags(state.automation, state.restoreOnBoot);
  }, [state]);
  const update = useCallback((fn: (s: AppState) => AppState) => setState(fn), []);
  const log = useCallback((title: string, sub: string, pkg?: string) =>
    setState((s) => ({ ...s, log: [{ id: String(Date.now()), pkg, title, sub, at: Date.now() }, ...s.log].slice(0, 30) })), []);
  const v = useMemo(() => ({ state, update, log }), [state, update, log]);
  return <C.Provider value={v}>{children}</C.Provider>;
}

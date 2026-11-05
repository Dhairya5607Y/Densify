import React, { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { api } from '../native/api';
import { bootHooks } from '../logic/axplugin';
import { AppState as RNAppState } from 'react-native';
import { DEFAULT_PERF, DEFAULT_STATE } from './defaults';
import { accentClashes, argb, themeById, withAccent } from '../theme/themes';
import type { AppState, Bundle, Profile } from './types';

export const resolveBundle = (s: AppState, p: Profile): Bundle => s.presets.find((x) => x.id === p.presetId)?.bundle ?? p.bundle;

/** Derived, flat config the native service reads (it cannot run JS while the app is closed). */
/** The floating panel's look: the app's current theme, as plain opaque colours the native side can draw. */
function panelTheme(s: AppState) {
  const base = themeById(s.themeId);
  const th = s.accent ? withAccent(base, s.accent) : base;
  const c = th.c;
  return {
    bg: argb(c.bg), sf: argb(c.sf, c.bg), sf2: argb(c.sf2, c.bg), bd: argb(c.bd2, c.bg), tx: argb(c.tx), mu: argb(c.mu, c.bg), ac: argb(c.ac), on: argb(c.on),
    r: Math.min(th.r, 24), rs: Math.min(th.rs, 14), bw: th.bw > 0 ? Math.max(1, th.bw) : 0, mono: th.mono, upper: th.label !== 'sentence',
  };
}

function toEngine(s: AppState) {
  return {
    defaultSw: s.defaultSw,
    theme: panelTheme(s),
    automation: s.automation,
    lowBattery: s.lowBattery,
    lowBatteryPercent: s.lowBatteryPercent,
    floating: s.floating,
    autoReconnect: s.autoReconnect,
    bridge: s.bridge,
    mode: s.mode,
    quick: s.quick,
    hud: s.perf.hud,
    hudPos: s.perf.pos,
    hudFields: s.perf.fields,
    record: s.perf.record,
    opt: { ram: s.perf.opt.ram, anim: s.perf.opt.anim, net: s.perf.opt.net, gameMode: s.perf.opt.gameMode, saver: s.perf.opt.saver, adaptive: s.perf.opt.adaptive, scale: s.perf.opt.scale },
    scripts: [
      ...s.scripts.map((x) => ({ name: x.name, code: x.code, trigger: x.trigger, pkg: x.pkg, on: x.on })),
      ...s.plugins.filter((p) => p.on).flatMap((p) => [...bootHooks(p), ...p.hooks].map((h) => ({ name: p.name, code: h.code, trigger: h.trigger, pkg: h.pkg ?? '', on: true }))),
    ],
    profiles: s.profiles.map((p) => ({ pkg: p.pkg, name: p.name, sw: p.sw, auto: p.auto, restore: p.restore, bundle: resolveBundle(s, p), stretch: { on: !!p.stretch?.on, w: p.stretch?.w ?? 0, h: p.stretch?.h ?? 0 } })),
  };
}

function load(): AppState {
  try {
    const raw = api.storageGet('app');
    if (!raw) return DEFAULT_STATE;
    const o = JSON.parse(raw);
    if (o.themeId === 'glass') o.themeId = 'graphite'; // Glass was removed
    // Older versions stored these as a density. The number the user typed (420, 490) is what they meant as the smallest width, so it is kept as is.
    if (o.defaultSw === undefined && typeof o.defaultDpi === 'number') o.defaultSw = o.defaultDpi;
    delete o.defaultDpi;
    if (Array.isArray(o.profiles)) o.profiles = o.profiles.map((p: { sw?: number; dpi?: number }) => { const { dpi, ...rest } = p; return { ...rest, sw: rest.sw ?? dpi ?? 420 }; });
    if (typeof o.accent === 'string' && o.accent) { const th = themeById(o.themeId ?? 'manga'); if (accentClashes(th, o.accent)) o.accent = ''; } // a clashing accent made text vanish
    if (Array.isArray(o.profiles)) o.profiles = o.profiles.map((p: { stretch?: { on?: boolean; w?: number; h?: number } }) => (p.stretch ? { ...p, stretch: { on: !!p.stretch.on, w: p.stretch.w ?? 0, h: p.stretch.h ?? 0 } } : p)); // old ratio-based stretch is dropped
    const perf = o.perf ?? {};
    return {
      ...DEFAULT_STATE, ...o,
      flags: { ...DEFAULT_STATE.flags, ...(o.flags ?? {}) },
      rec: { ...DEFAULT_STATE.rec, ...(o.rec ?? {}) },
      perf: { ...DEFAULT_PERF, ...perf, fields: { ...DEFAULT_PERF.fields, ...(perf.fields ?? {}) }, opt: { ...DEFAULT_PERF.opt, ...(perf.opt ?? {}) } },
    };
  } catch {
    return DEFAULT_STATE; // corrupted storage: start clean instead of crashing
  }
}

type Ctx = { state: AppState; update: (fn: (s: AppState) => AppState) => void; log: (title: string, sub: string, pkg?: string) => void };
const C = createContext<Ctx>(null as unknown as Ctx);
export const useStore = () => useContext(C);

export function StoreProvider({ children }: { children: React.ReactNode }) {
  const [state, setState] = useState<AppState>(load);
  const latest = useRef(state);
  latest.current = state;
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  // Writing to storage is the costly part of every change, so a burst of changes (a slider drag) is saved once.
  const flush = useCallback(() => {
    if (timer.current) { clearTimeout(timer.current); timer.current = null; }
    const s = latest.current;
    api.storageSet('app', JSON.stringify(s));
    api.storageSet('engine', JSON.stringify(toEngine(s)));
    api.storageSet('rec', JSON.stringify(s.rec));
    api.storageSet('externalApi', s.externalApi ? '1' : '0');
    api.storageSet('apiToken', s.apiToken);
    api.setFlags(s.automation, s.restoreOnBoot);
  }, []);
  useEffect(() => {
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(flush, 250);
    return () => { if (timer.current) clearTimeout(timer.current); };
  }, [state, flush]);
  useEffect(() => {
    // Never lose the last change: save at once when the app leaves the foreground.
    const sub = RNAppState.addEventListener('change', (st) => { if (st !== 'active') flush(); });
    return () => sub.remove();
  }, [flush]);
  const update = useCallback((fn: (s: AppState) => AppState) => setState(fn), []);
  const log = useCallback((title: string, sub: string, pkg?: string) =>
    setState((s) => ({ ...s, log: [{ id: String(Date.now()), pkg, title, sub, at: Date.now() }, ...s.log].slice(0, 30) })), []);
  const v = useMemo(() => ({ state, update, log }), [state, update, log]);
  return <C.Provider value={v}>{children}</C.Provider>;
}

import type { AppState, Bundle } from './types';

export const NO_STRETCH = { on: false, w: 0, h: 0 } as const;

export const NO_BUNDLE: Bundle = { hz: false, perf: false, dnd: false, rot: false, guard: false, bright: false, awake: false };

/** Seed values only. Everything here is editable in the app and then lives in storage. */
export const DEFAULT_PERF: AppState['perf'] = {
  hud: 'off',
  pos: 'tr',
  fields: { fps: true, cpu: true, temp: true, ram: true, ping: true },
  record: true,
  opt: { ram: false, anim: false, net: false, gameMode: false, saver: false, adaptive: false, scale: 0 },
};

export const DEFAULT_STATE: AppState = {
  perf: DEFAULT_PERF,
  themeId: 'manga',
  accent: '',
  defaultSw: 0, // 0 = the phone's own smallest width; detected on first run
  rec: { res: 'fhd', fps: 60, mbps: 0, codec: 'h264', orient: 'landscape', scope: 'ask', notif: true },
  automation: false,
  restoreOnBoot: true,
  lowBattery: true,
  lowBatteryPercent: 50,
  floating: 'off',
  autoReconnect: true,
  bridge: false,
  mode: 'wireless',
  scripts: [],
  plugins: [],
  externalApi: false,
  apiToken: '',
  quick: [400, 480, 560, 640],
  presets: [
    { id: 'esport', name: 'Esport', bundle: { hz: true, perf: true, dnd: true, rot: true, guard: true, bright: false, awake: true } },
    { id: 'balanced', name: 'Balanced', bundle: { hz: true, perf: false, dnd: true, rot: true, guard: false, bright: false, awake: true } },
    { id: 'saver', name: 'Battery saver', bundle: { hz: false, perf: false, dnd: true, rot: false, guard: false, bright: false, awake: false } },
    { id: 'cinema', name: 'Cinematic', bundle: { hz: false, perf: false, dnd: true, rot: true, guard: false, bright: true, awake: true } },
  ],
  profiles: [],
  log: [],
  flags: { quickNotif: true, breakReminder: false, chargerPresets: false, restoreOnLoss: true, intents: false, tile: false },
};

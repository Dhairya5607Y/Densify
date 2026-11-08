export type Bundle = { hz: boolean; perf: boolean; dnd: boolean; rot: boolean; guard: boolean; bright: boolean; awake: boolean };
export type BundleKey = keyof Bundle;
export type Preset = { id: string; name: string; bundle: Bundle };
/** Per-game stretch. w/h of 0 means "use the global stretch resolution". */
export type Stretch = { on: boolean; w: number; h: number };
export type Script = { id: string; name: string; code: string; trigger: 'start' | 'exit' | 'boot'; pkg: string; on: boolean };
export type PluginAction = { id: string; name: string; code: string };
export type PluginHook = { trigger: 'start' | 'exit' | 'boot'; code: string; pkg?: string };
/** An installable add-on: shell actions, event hooks and an optional WebUI page. */
export type Plugin = { id: string; name: string; version: string; author: string; desc: string; on: boolean; actions: PluginAction[]; hooks: PluginHook[]; webui?: string; dir?: string; versionCode?: number; boots?: string[]; action?: boolean; webroot?: boolean; hasUninstall?: boolean };
export type Profile = { pkg: string; name: string; sw: number; auto: boolean; restore: boolean; presetId: string; bundle: Bundle; stretch?: Stretch };
export type LogEntry = { id: string; pkg?: string; title: string; sub: string; at: number };
export type HudField = 'fps' | 'cpu' | 'temp' | 'ram' | 'ping';
export type HudPos = 'tl' | 'tr' | 'bl' | 'br';
/** Live stats overlay, session recording and the per-game optimizer. */
export type PerfCfg = {
  hud: 'off' | 'gaming' | 'always';
  pos: HudPos;
  fields: Record<HudField, boolean>;
  record: boolean;
  opt: { ram: boolean; anim: boolean; net: boolean; gameMode: boolean; saver: boolean; adaptive: boolean; scale: number };
};
/** Screen recorder. res: sd, hd, fhd, qhd, uhd (4K) or native. codec: h264 (compatibility) or hevc (smaller files). scope: ask (single app or screen) or screen. mbps 0 means automatic. */
export type RecCfg = { res: 'sd' | 'hd' | 'fhd' | 'qhd' | 'uhd' | 'native'; fps: number; mbps: number; codec: 'h264' | 'hevc'; orient: 'landscape' | 'portrait' | 'auto'; scope: 'ask' | 'screen'; notif: boolean };
export type AppState = {
  rec: RecCfg;
  perf: PerfCfg;
  themeId: string;
  /** Liquid glass overlay on the chosen theme. */
  glass: boolean;
  accent: string;
  /** Smallest width (dp) to return to after a game. 0 means the phone's own. */
  defaultSw: number;
  automation: boolean;
  restoreOnBoot: boolean;
  lowBattery: boolean;
  lowBatteryPercent: number;
  floating: 'off' | 'gaming' | 'always';
  autoReconnect: boolean;
  /** Keep the adb link on a local port so switching keeps working with Wi-Fi off. */
  bridge: boolean;
  mode: 'wireless' | 'root' | 'tcpip' | 'shizuku';
  scripts: Script[];
  plugins: Plugin[];
  externalApi: boolean;
  apiToken: string;
  quick: number[];
  presets: Preset[];
  profiles: Profile[];
  log: LogEntry[];
  flags: { quickNotif: boolean; breakReminder: boolean; chargerPresets: boolean; restoreOnLoss: boolean; intents: boolean; tile: boolean };
};

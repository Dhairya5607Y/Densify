export type Bundle = { hz: boolean; perf: boolean; dnd: boolean; rot: boolean; guard: boolean; bright: boolean; awake: boolean };
export type BundleKey = keyof Bundle;
export type Preset = { id: string; name: string; bundle: Bundle };
export type Profile = { pkg: string; name: string; dpi: number; auto: boolean; restore: boolean; presetId: string; bundle: Bundle };
export type LogEntry = { id: string; pkg?: string; title: string; sub: string; at: number };
export type AppState = {
  themeId: string;
  defaultDpi: number;
  automation: boolean;
  restoreOnBoot: boolean;
  lowBattery: boolean;
  lowBatteryPercent: number;
  quick: number[];
  presets: Preset[];
  profiles: Profile[];
  log: LogEntry[];
  flags: { quickNotif: boolean; breakReminder: boolean; chargerPresets: boolean; restoreOnLoss: boolean; intents: boolean; tile: boolean };
};

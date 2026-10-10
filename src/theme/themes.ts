export type Colors = { bg: string; sf: string; sf2: string; bd: string; bd2: string; tx: string; tx2: string; mu: string; ac: string; on: string; acs: string; ok: string; wn: string; track: string; scrim: string };
/** A theme is a design language: colour + shape + surface material + type + control styles. */
export type Theme = {
  id: string; name: string; desc: string; dark: boolean; c: Colors;
  r: number; rs: number; rb: number; bw: number; elev: number;
  surface: 'solid' | 'outline' | 'hud' | 'ink';
  blur: number; mono: boolean;
  label: 'sentence' | 'upper' | 'mono';
  switchStyle: 'm3' | 'ios' | 'hud';
  sliderStyle: 'bar' | 'ios' | 'hud';
  swatch: string[];
};

const base = { ok: '#4be3a0', wn: '#ffa56b', scrim: 'rgba(0,0,0,.6)' };

export const THEMES: Theme[] = [
  { id: 'matte', name: 'Matte', desc: 'Solid black surfaces, tonal controls', dark: true, r: 24, rs: 14, rb: 22, bw: 1, elev: 0, surface: 'solid', blur: 0, mono: false, label: 'sentence', switchStyle: 'm3', sliderStyle: 'bar', swatch: ['#0e1216', '#5cbdff'],
    c: { ...base, bg: '#050709', sf: '#0e1216', sf2: '#171c21', bd: 'rgba(255,255,255,.07)', bd2: 'rgba(255,255,255,.13)', tx: '#f2f5f8', tx2: '#c6cdd5', mu: '#8b939e', ac: '#5cbdff', on: '#04121f', acs: 'rgba(92,189,255,.14)', track: '#262c33' } },
  { id: 'graphite', name: 'Graphite', desc: 'Cool grey metal, quiet and precise', dark: true, r: 18, rs: 12, rb: 14, bw: 1, elev: 0, surface: 'solid', blur: 0, mono: false, label: 'sentence', switchStyle: 'ios', sliderStyle: 'bar', swatch: ['#1d1f23', '#a9b8cc'],
    c: { ...base, bg: '#101114', sf: '#1a1c20', sf2: '#24272c', bd: 'rgba(205,215,230,.08)', bd2: 'rgba(205,215,230,.16)', tx: '#eceff3', tx2: '#c0c6cf', mu: '#868d98', ac: '#a9b8cc', on: '#0f141b', acs: 'rgba(169,184,204,.15)', track: '#30343a' } },
  { id: 'manga', name: 'Manga', desc: 'White paper, black ink, bold panels', dark: false, r: 6, rs: 4, rb: 6, bw: 2, elev: 0, surface: 'ink', blur: 0, mono: false, label: 'upper', switchStyle: 'm3', sliderStyle: 'bar', swatch: ['#ffffff', '#0a0a0a'],
    c: { bg: '#f7f6f2', sf: '#ffffff', sf2: '#ecebe6', bd: '#0a0a0a', bd2: '#0a0a0a', tx: '#0a0a0a', tx2: '#222222', mu: '#5a5a58', ac: '#0a0a0a', on: '#ffffff', acs: 'rgba(10,10,10,.1)', ok: '#0f7a4a', wn: '#d11a2a', track: '#cfcec8', scrim: 'rgba(10,10,10,.45)' } },
  { id: 'material', name: 'Material You', desc: 'Tonal, pill-shaped, Android-native', dark: true, r: 28, rs: 16, rb: 999, bw: 0, elev: 0, surface: 'solid', blur: 0, mono: false, label: 'sentence', switchStyle: 'm3', sliderStyle: 'bar', swatch: ['#2b2930', '#d0bcff'],
    c: { ...base, bg: '#141218', sf: '#1d1b20', sf2: '#2b2930', bd: 'rgba(230,224,233,.06)', bd2: 'rgba(230,224,233,.14)', tx: '#e6e0e9', tx2: '#cac4d0', mu: '#a49fad', ac: '#d0bcff', on: '#381e72', acs: 'rgba(208,188,255,.16)', track: '#36343b' } },
  { id: 'hud', name: 'HUD', desc: 'Sharp, monospaced, tactical', dark: true, r: 4, rs: 2, rb: 2, bw: 1, elev: 0, surface: 'hud', blur: 0, mono: true, label: 'mono', switchStyle: 'hud', sliderStyle: 'hud', swatch: ['#04080a', '#3ef0d0'],
    c: { ...base, bg: '#03070a', sf: 'rgba(62,240,208,.045)', sf2: 'rgba(62,240,208,.1)', bd: 'rgba(62,240,208,.26)', bd2: 'rgba(62,240,208,.5)', tx: '#d6fff6', tx2: '#a5d9cf', mu: '#6aa79c', ac: '#3ef0d0', on: '#021412', acs: 'rgba(62,240,208,.14)', track: 'rgba(62,240,208,.18)' } },
  { id: 'paper', name: 'Paper', desc: 'Light, soft shadows, calm', dark: false, r: 22, rs: 14, rb: 14, bw: 0, elev: 2, surface: 'solid', blur: 0, mono: false, label: 'sentence', switchStyle: 'ios', sliderStyle: 'ios', swatch: ['#ffffff', '#0b78e0'],
    c: { bg: '#f2f4f7', sf: '#ffffff', sf2: '#eaeef2', bd: 'rgba(12,22,32,.08)', bd2: 'rgba(12,22,32,.15)', tx: '#10161c', tx2: '#38434e', mu: '#66727f', ac: '#0b78e0', on: '#ffffff', acs: 'rgba(11,120,224,.1)', ok: '#12875a', wn: '#bd5710', track: '#d8dde3', scrim: 'rgba(10,20,30,.4)' } },
  { id: 'ember', name: 'Ember', desc: 'AMOLED black, hairlines, warm', dark: true, r: 20, rs: 12, rb: 14, bw: 1, elev: 0, surface: 'outline', blur: 0, mono: false, label: 'upper', switchStyle: 'm3', sliderStyle: 'ios', swatch: ['#000000', '#ff9a4d'],
    c: { ...base, bg: '#000000', sf: '#0d0a08', sf2: '#17120e', bd: 'rgba(255,235,220,.12)', bd2: 'rgba(255,235,220,.22)', tx: '#fbf3ec', tx2: '#d9ccc0', mu: '#938679', ac: '#ff9a4d', on: '#241005', acs: 'rgba(255,154,77,.14)', track: '#2b241e' } },
];

export const themeById = (id: string) => THEMES.find((t) => t.id === id) ?? THEMES[0];

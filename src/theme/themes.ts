export type Colors = { bg: string; sf: string; sf2: string; bd: string; bd2: string; tx: string; tx2: string; mu: string; ac: string; on: string; acs: string; ok: string; wn: string; track: string; scrim: string };
/** A theme is a design language: colour + shape + surface material + type + control styles. */
export type Theme = {
  id: string; name: string; desc: string; dark: boolean; c: Colors;
  r: number; rs: number; rb: number; bw: number; elev: number;
  surface: 'solid' | 'glass' | 'outline' | 'hud';
  blur: number; mono: boolean;
  label: 'sentence' | 'upper' | 'mono';
  switchStyle: 'm3' | 'ios' | 'hud';
  sliderStyle: 'bar' | 'ios' | 'hud';
  backdrop: string[] | null;
  swatch: string[];
};

const base = { ok: '#4be3a0', wn: '#ffa56b', scrim: 'rgba(0,0,0,.6)' };

export const THEMES: Theme[] = [
  { id: 'matte', name: 'Matte', desc: 'Solid black surfaces, tonal controls', dark: true, r: 24, rs: 14, rb: 22, bw: 1, elev: 0, surface: 'solid', blur: 0, mono: false, label: 'sentence', switchStyle: 'm3', sliderStyle: 'bar', backdrop: null, swatch: ['#0e1216', '#5cbdff'],
    c: { ...base, bg: '#050709', sf: '#0e1216', sf2: '#171c21', bd: 'rgba(255,255,255,.07)', bd2: 'rgba(255,255,255,.13)', tx: '#f2f5f8', tx2: '#c6cdd5', mu: '#8b939e', ac: '#5cbdff', on: '#04121f', acs: 'rgba(92,189,255,.14)', track: '#262c33' } },
  { id: 'glass', name: 'Glass', desc: 'Frosted, floating, Apple-style', dark: true, r: 30, rs: 18, rb: 999, bw: 1, elev: 0, surface: 'glass', blur: 45, mono: false, label: 'sentence', switchStyle: 'ios', sliderStyle: 'ios', backdrop: ['#1f6fd0', '#5a40c8', '#14aab0'], swatch: ['#2a5fa8', '#7a5af0'],
    c: { ...base, bg: '#04070b', sf: 'rgba(255,255,255,.08)', sf2: 'rgba(255,255,255,.14)', bd: 'rgba(255,255,255,.14)', bd2: 'rgba(255,255,255,.24)', tx: '#ffffff', tx2: 'rgba(255,255,255,.82)', mu: 'rgba(255,255,255,.55)', ac: '#6cc4ff', on: '#04121f', acs: 'rgba(120,200,255,.2)', track: 'rgba(255,255,255,.2)', scrim: 'rgba(0,0,0,.45)' } },
  { id: 'material', name: 'Material You', desc: 'Tonal, pill-shaped, Android-native', dark: true, r: 28, rs: 16, rb: 999, bw: 0, elev: 0, surface: 'solid', blur: 0, mono: false, label: 'sentence', switchStyle: 'm3', sliderStyle: 'bar', backdrop: null, swatch: ['#2b2930', '#d0bcff'],
    c: { ...base, bg: '#141218', sf: '#1d1b20', sf2: '#2b2930', bd: 'rgba(230,224,233,.06)', bd2: 'rgba(230,224,233,.14)', tx: '#e6e0e9', tx2: '#cac4d0', mu: '#a49fad', ac: '#d0bcff', on: '#381e72', acs: 'rgba(208,188,255,.16)', track: '#36343b' } },
  { id: 'hud', name: 'HUD', desc: 'Sharp, monospaced, tactical', dark: true, r: 4, rs: 2, rb: 2, bw: 1, elev: 0, surface: 'hud', blur: 0, mono: true, label: 'mono', switchStyle: 'hud', sliderStyle: 'hud', backdrop: null, swatch: ['#04080a', '#3ef0d0'],
    c: { ...base, bg: '#03070a', sf: 'rgba(62,240,208,.045)', sf2: 'rgba(62,240,208,.1)', bd: 'rgba(62,240,208,.26)', bd2: 'rgba(62,240,208,.5)', tx: '#d6fff6', tx2: '#a5d9cf', mu: '#6aa79c', ac: '#3ef0d0', on: '#021412', acs: 'rgba(62,240,208,.14)', track: 'rgba(62,240,208,.18)' } },
  { id: 'paper', name: 'Paper', desc: 'Light, soft shadows, calm', dark: false, r: 22, rs: 14, rb: 14, bw: 0, elev: 2, surface: 'solid', blur: 0, mono: false, label: 'sentence', switchStyle: 'ios', sliderStyle: 'ios', backdrop: null, swatch: ['#ffffff', '#0b78e0'],
    c: { bg: '#f2f4f7', sf: '#ffffff', sf2: '#eaeef2', bd: 'rgba(12,22,32,.08)', bd2: 'rgba(12,22,32,.15)', tx: '#10161c', tx2: '#38434e', mu: '#66727f', ac: '#0b78e0', on: '#ffffff', acs: 'rgba(11,120,224,.1)', ok: '#12875a', wn: '#bd5710', track: '#d8dde3', scrim: 'rgba(10,20,30,.4)' } },
  { id: 'ember', name: 'Ember', desc: 'AMOLED black, hairlines, warm', dark: true, r: 20, rs: 12, rb: 14, bw: 1, elev: 0, surface: 'outline', blur: 0, mono: false, label: 'upper', switchStyle: 'm3', sliderStyle: 'ios', backdrop: null, swatch: ['#000000', '#ff9a4d'],
    c: { ...base, bg: '#000000', sf: '#0d0a08', sf2: '#17120e', bd: 'rgba(255,235,220,.12)', bd2: 'rgba(255,235,220,.22)', tx: '#fbf3ec', tx2: '#d9ccc0', mu: '#938679', ac: '#ff9a4d', on: '#241005', acs: 'rgba(255,154,77,.14)', track: '#2b241e' } },
];

export const themeById = (id: string) => THEMES.find((t) => t.id === id) ?? THEMES[0];

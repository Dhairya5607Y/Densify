/** Smallest width (dp), the number Android shows in Developer options. Bigger means a smaller-looking UI. */
export const SW_MIN = 150;
export const SW_MAX = 1200;
export const SLIDER_MIN = 240;
export const SLIDER_MAX = 800;

export function parseSw(s: string): { v: number; err?: undefined } | { err: string; v?: undefined } {
  const x = s.trim();
  if (!/^\d+$/.test(x)) return { err: 'Enter a whole number, for example 420.' };
  const n = Number(x);
  return n < SW_MIN || n > SW_MAX ? { err: `Smallest width must be between ${SW_MIN} and ${SW_MAX}.` } : { v: n };
}

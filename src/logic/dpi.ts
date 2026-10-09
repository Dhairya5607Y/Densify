export const DPI_MIN = 72;
export const DPI_MAX = 1000;
export const SLIDER_MIN = 240;
export const SLIDER_MAX = 800;

export function parseDpi(s: string): { v: number; err?: undefined } | { err: string; v?: undefined } {
  const x = s.trim();
  if (!/^\d+$/.test(x)) return { err: 'Enter a whole number, for example 480.' };
  const n = Number(x);
  return n < DPI_MIN || n > DPI_MAX ? { err: `DPI must be between ${DPI_MIN} and ${DPI_MAX}.` } : { v: n };
}

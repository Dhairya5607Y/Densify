/** Stretch resolution helpers. Sizes are plain width x height, typed like 2160*1080. */
export const SIZE_MIN = 320;
export const SIZE_MAX = 4096;

export type SizeResult = { w: number; h: number; err?: undefined } | { err: string };

/** Accepts "2160*1080", "2160x1080", "2160 × 1080", "2160,1080" or "2160 1080". */
export function parseSize(text: string): SizeResult {
  const m = /^\s*(\d{1,5})\s*[*xX×,:\s]\s*(\d{1,5})\s*$/.exec(text);
  if (!m) return { err: 'Type it as width*height, for example 2160*1080.' };
  const w = Number(m[1]); const h = Number(m[2]);
  if (w < SIZE_MIN || h < SIZE_MIN || w > SIZE_MAX || h > SIZE_MAX) return { err: `Each side must be between ${SIZE_MIN} and ${SIZE_MAX}.` };
  return { w, h };
}

export const formatSize = (w: number, h: number) => (w > 0 && h > 0 ? `${w}*${h}` : '');

/** Ready-made sizes for common shapes, worked out from this phone's own screen, in the phone's natural orientation. */
export function quickSizes(physW: number, physH: number): { label: string; w: number; h: number }[] {
  const short = Math.min(physW, physH); const long = Math.max(physW, physH);
  const even = (n: number) => Math.round(n / 2) * 2;
  const out: { label: string; w: number; h: number }[] = [];
  for (const [label, a, b] of [['4:3', 4, 3], ['5:4', 5, 4], ['16:10', 16, 10], ['3:2', 3, 2], ['1:1', 1, 1]] as const) {
    const r = a / b;
    const [s, l] = short * r <= long ? [short, even(short * r)] : [even(long / r), long];
    if (s < SIZE_MIN || l < SIZE_MIN || (s === short && l === long)) continue;
    out.push(physW < physH ? { label, w: s, h: l } : { label, w: l, h: s });
  }
  return out;
}

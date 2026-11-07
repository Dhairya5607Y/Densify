import { useEffect, useRef, useState } from 'react';
import { AppState } from 'react-native';
import { api } from '../native/api';

/** One live reading. -1 means this phone does not expose that number. */
export type PerfSample = { at: number; pkg: string | null; fps: number; cpu: number; tempC: number; battC: number; ramPct: number; ramFreeMb: number; pingMs: number; cpuMhz: number };
export type PerfSession = { pkg: string; name: string; start: number; durMs: number; samples: number; live: boolean; fpsAvg: number; fps1: number; fpsMin: number; fpsMax: number; cpuAvg: number; tempAvg: number; tempMax: number; ramAvg: number; pingAvg: number; pingMin?: number; pingMax?: number };

export const EMPTY_SAMPLE: PerfSample = { at: 0, pkg: null, fps: -1, cpu: -1, tempC: -1, battC: -1, ramPct: -1, ramFreeMb: -1, pingMs: -1, cpuMhz: -1 };

const parse = <T,>(raw: string, fallback: T): T => { try { return raw ? (JSON.parse(raw) as T) : fallback; } catch { return fallback; } };

/**
 * Live numbers for the Stats screen. It only asks the phone while the screen is open and the app is in front,
 * so the monitor costs nothing the rest of the time.
 */
export function usePerfLive() {
  const [sample, setSample] = useState<PerfSample>(EMPTY_SAMPLE);
  const [fpsHist, setFpsHist] = useState<number[]>([]);
  const lastAt = useRef(0);
  useEffect(() => {
    let timer: ReturnType<typeof setInterval> | null = null;
    const stop = () => { if (timer) { clearInterval(timer); timer = null; } api.perfWatch(false); };
    const go = () => {
      if (timer) return;
      api.perfWatch(true);
      timer = setInterval(() => {
        const s = parse<PerfSample>(api.perfLive(), EMPTY_SAMPLE);
        if (s.at === lastAt.current) return; // nothing new since the last second
        lastAt.current = s.at;
        setSample(s);
        setFpsHist((h) => [...h.slice(-59), Math.max(0, s.fps)]);
      }, 1000);
    };
    go();
    const sub = AppState.addEventListener('change', (st) => { if (st === 'active') go(); else stop(); });
    return () => { sub.remove(); stop(); };
  }, []);
  return { sample, fpsHist };
}

/** Saved game sessions (newest first). The one in progress is marked live. */
export function usePerfSessions() {
  const [list, setList] = useState<PerfSession[]>([]);
  const sig = useRef('');
  const reload = () => {
    const raw = api.perfSessions();
    if (raw === sig.current) return;
    sig.current = raw;
    setList(parse<PerfSession[]>(raw, []));
  };
  useEffect(() => {
    reload();
    const id = setInterval(reload, 4000);
    return () => clearInterval(id);
  }, []);
  return { list, reload: () => { sig.current = ''; reload(); } };
}

export const fmtDur = (ms: number) => {
  const m = Math.round(ms / 60000);
  if (m < 1) return `${Math.max(1, Math.round(ms / 1000))} s`;
  return m < 60 ? `${m} min` : `${Math.floor(m / 60)} h ${m % 60} min`;
};

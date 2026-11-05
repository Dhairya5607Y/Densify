import React, { useState } from 'react';
import { View } from 'react-native';
import { api } from '../native/api';
import { useStore } from '../store/store';
import { useTheme } from '../theme/ThemeContext';
import { AppSwitch, Btn, Chip, Group, Row, Section, Surface, T } from '../ui/primitives';
import { useToast } from '../ui/overlays';
import { Screen } from '../ui/scroll';
import { useEnsure } from '../logic/permissions';
import { ago } from '../logic/hooks';
import { fmtDur, usePerfLive, usePerfSessions } from '../logic/perf';
import type { HudField, HudPos, PerfCfg } from '../store/types';
import { Header } from './common';

const num = (v: number, suffix = '') => (v >= 0 ? `${Math.round(v)}${suffix}` : '—');

function Tile({ label, value, unit, color }: { label: string; value: string; unit?: string; color?: string }) {
  const t = useTheme();
  return (
    <Surface style={{ width: '48%', flexGrow: 1 }} pad={14}>
      <T v="sub" style={{ fontSize: 11.5, letterSpacing: 1 }}>{label}</T>
      <View style={{ flexDirection: 'row', alignItems: 'baseline', marginTop: 4 }}>
        <T style={{ fontSize: 30, fontWeight: '500', color: color ?? t.c.tx }}>{value}</T>
        {unit ? <T v="sub" style={{ marginLeft: 4 }}>{unit}</T> : null}
      </View>
    </Surface>
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <View style={{ width: '30%', flexGrow: 1, paddingVertical: 8 }}>
      <T v="sub" style={{ fontSize: 11.5 }}>{label}</T>
      <T style={{ fontSize: 19, marginTop: 2 }}>{value}</T>
    </View>
  );
}

/** The last minute of FPS, one bar per second. */
function Bars({ data }: { data: number[] }) {
  const t = useTheme();
  const max = Math.max(60, ...data);
  return (
    <View style={{ flexDirection: 'row', alignItems: 'flex-end', height: 56, gap: 2, marginTop: 14 }}>
      {Array.from({ length: 60 }).map((_, i) => {
        const v = data[data.length - 60 + i] ?? 0;
        return <View key={i} style={{ flex: 1, height: Math.max(2, (v / max) * 56), borderRadius: 1.5, backgroundColor: v > 0 ? t.c.ac : t.c.track, opacity: v > 0 ? 0.35 + 0.65 * (i / 59) : 0.7 }} />;
      })}
    </View>
  );
}

const HUD_POS: [HudPos, string][] = [['tl', 'Top left'], ['tr', 'Top right'], ['bl', 'Bottom left'], ['br', 'Bottom right']];
const HUD_FIELDS: [HudField, string][] = [['fps', 'FPS'], ['cpu', 'CPU'], ['temp', 'Temp'], ['ram', 'RAM'], ['ping', 'Ping']];

export function Stats() {
  const { state, update } = useStore();
  const t = useTheme(); const toast = useToast(); const ensure = useEnsure();
  const { sample: s, fpsHist } = usePerfLive();
  const { list, reload } = usePerfSessions();
  const [busy, setBusy] = useState(false);
  const p = state.perf;
  const setPerf = (fn: (x: PerfCfg) => PerfCfg) => update((x) => ({ ...x, perf: fn(x.perf) }));
  const tempColor = s.tempC >= 48 ? t.c.wn : s.tempC >= 42 ? '#ffc27a' : undefined;
  const last = list[0];

  const setHud = async (m: PerfCfg['hud']) => {
    if (m !== 'off' && !(await ensure('floating'))) return;
    setPerf((x) => ({ ...x, hud: m }));
    if (m !== 'off') api.startMonitoring();
  };
  const setOpt = async (k: 'ram' | 'anim' | 'net' | 'gameMode' | 'saver' | 'adaptive', v: boolean) => {
    if (v && !(await ensure('perf'))) return;
    setPerf((x) => ({ ...x, opt: { ...x.opt, [k]: v } }));
  };
  const setScale = async (v: number) => {
    if (v > 0 && !(await ensure('perf'))) return;
    setPerf((x) => ({ ...x, opt: { ...x.opt, scale: v } }));
  };
  const clean = async () => {
    if (busy || !(await ensure('perf'))) return;
    setBusy(true);
    try { toast({ title: await api.optimizeNow() }); } catch (e) { toast({ title: "Couldn't clean RAM", sub: (e as Error).message, err: true }); }
    setBusy(false);
  };

  return (
    <Screen tab>
      <Header title="Stats" sub="Live performance and averages" />

      <Section title="Live" />
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 10 }}>
        <Tile label="FPS" value={num(s.fps)} />
        <Tile label="CPU LOAD" value={num(s.cpu)} unit={s.cpu >= 0 ? '%' : undefined} />
        <Tile label="TEMPERATURE" value={s.tempC > 0 ? s.tempC.toFixed(1) : '—'} unit={s.tempC > 0 ? '°C' : undefined} color={tempColor} />
        <Tile label="RAM USED" value={num(s.ramPct)} unit={s.ramPct >= 0 ? '%' : undefined} />
        <Tile label="PING" value={num(s.pingMs)} unit={s.pingMs >= 0 ? 'ms' : undefined} />
        <Tile label="CPU CLOCK" value={num(s.cpuMhz)} unit={s.cpuMhz >= 0 ? 'MHz' : undefined} />
      </View>
      <Surface pad={14} style={{ marginTop: 10 }}>
        <View style={{ flexDirection: 'row', justifyContent: 'space-between' }}>
          <T v="sub">FPS, last minute</T>
          <T v="sub">{s.pkg ? s.pkg.split('.').slice(-1)[0] : 'No app yet'}</T>
        </View>
        <Bars data={fpsHist} />
      </Surface>
      {s.fps < 0 ? <T v="sub" style={{ marginHorizontal: 10, marginTop: 10 }}>FPS is read from the open game's own display layer. Open a game (with the floating panel or overlay on) to see it. Some phones hide a number; it then shows a dash.</T> : null}

      <Section title={last ? (last.live ? 'This session' : 'Last session') : 'Averages'} action={list.length ? 'Clear history' : undefined} onAction={() => { api.perfClear(); reload(); }} />
      {last ? (
        <Surface pad={16}>
          <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
            <T style={{ fontSize: 17 }}>{last.name}</T>
            <T v="sub" c={last.live ? t.c.ok : t.c.mu} w="600">{last.live ? `Live · ${fmtDur(last.durMs)}` : `${fmtDur(last.durMs)} · ${ago(last.start)}`}</T>
          </View>
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', marginTop: 6 }}>
            <Metric label="Avg FPS" value={num(last.fpsAvg)} />
            <Metric label="1% low" value={num(last.fps1)} />
            <Metric label="Min / max" value={last.fpsMin >= 0 ? `${last.fpsMin} / ${last.fpsMax}` : '—'} />
            <Metric label="Avg CPU" value={num(last.cpuAvg, '%')} />
            <Metric label="Avg temp" value={last.tempAvg >= 0 ? `${last.tempAvg.toFixed(1)}°` : '—'} />
            <Metric label="Peak temp" value={last.tempMax >= 0 ? `${Math.round(last.tempMax)}°` : '—'} />
            <Metric label="Avg RAM" value={num(last.ramAvg, '%')} />
            <Metric label="Avg ping" value={num(last.pingAvg, ' ms')} />
          </View>
        </Surface>
      ) : (
        <Surface pad={16}><T v="sub">Play a game you added to Densify. Averages for FPS, CPU, temperature, RAM and ping are recorded here automatically.</T></Surface>
      )}
      {list.length > 1 ? (
        <Group style={{ marginTop: 10 }}>
          {list.slice(1, 10).map((x) => (
            <Row key={x.start} leadIcon="sports-esports" title={x.name} sub={`${fmtDur(x.durMs)} · ${ago(x.start)} · ${num(x.cpuAvg, '% CPU')} · ${x.tempAvg >= 0 ? `${Math.round(x.tempAvg)}°C` : '—'}`}
              right={<View style={{ alignItems: 'flex-end' }}><T style={{ fontSize: 17 }}>{num(x.fpsAvg)}</T><T v="sub" style={{ fontSize: 11.5 }}>avg FPS</T></View>} />
          ))}
        </Group>
      ) : null}

      <Section title="Overlay" />
      <Group>
        <Row leadIcon="layers" title="Live stats overlay" sub="A small readout drawn over the game that never blocks touches" below={
          (['off', 'gaming', 'always'] as const).map((m) => <Chip key={m} h={36} label={m === 'off' ? 'Off' : m === 'gaming' ? 'In games' : 'Always'} on={p.hud === m} onPress={() => setHud(m)} />)} />
        {p.hud !== 'off' && <Row leadIcon="open-with" title="Corner" below={HUD_POS.map(([k, l]) => <Chip key={k} h={36} label={l} on={p.pos === k} onPress={() => setPerf((x) => ({ ...x, pos: k }))} />)} />}
        {p.hud !== 'off' && <Row leadIcon="tune" title="Show" sub="The floating panel always shows all five when it is open" below={HUD_FIELDS.map(([k, l]) => <Chip key={k} h={36} label={l} on={p.fields[k]} onPress={() => setPerf((x) => ({ ...x, fields: { ...x.fields, [k]: !x.fields[k] } }))} />)} />}
        <Row leadIcon="history" title="Record game sessions" sub="Keeps averages for each game. Samples every few seconds, so it costs next to nothing." right={<AppSwitch on={p.record} onChange={(v) => setPerf((x) => ({ ...x, record: v }))} />} />
      </Group>

      <Section title="Optimizer" />
      <Group>
        <Row leadIcon="memory" title="Clean RAM now" sub="Closes cached background apps and reports what it freed" onPress={clean} right={<Btn v="tonal" label={busy ? 'Working…' : 'Clean'} disabled={busy} onPress={clean} />} />
        <Row leadIcon="memory" title="Clean RAM when a game opens" sub="Frees memory right before the game starts" right={<AppSwitch on={p.opt.ram} onChange={(v) => setOpt('ram', v)} />} />
        <Row leadIcon="speed" title="Snappier animations" sub="Halves system animation time while you play, then puts it back" right={<AppSwitch on={p.opt.anim} onChange={(v) => setOpt('anim', v)} />} />
        <Row leadIcon="wifi" title="Network boost" sub="Low-latency Wi-Fi mode and no background radio scans while you play" right={<AppSwitch on={p.opt.net} onChange={(v) => setOpt('net', v)} />} />
        <Row leadIcon="sports-esports" title="System Game Mode: Performance" sub="Asks Android to run the game in its Performance mode (Android 12+ phones that support it)" right={<AppSwitch on={p.opt.gameMode} onChange={(v) => setOpt('gameMode', v)} />} />
        <Row leadIcon="battery-saver" title="Battery saver off while playing" sub="Turns Android's battery saver off so it doesn't throttle the CPU, then puts it back" right={<AppSwitch on={p.opt.saver} onChange={(v) => setOpt('saver', v)} />} />
        <Row leadIcon="battery-charging-full" title="Adaptive battery off while playing" sub="Stops Android from holding the game back in the background, then puts it back" right={<AppSwitch on={p.opt.adaptive} onChange={(v) => setOpt('adaptive', v)} />} />
        <Row leadIcon="photo-size-select-large" title="Lower render resolution" sub="Asks Android (Game Mode) to draw the game at a lower resolution. Smoother and cooler, a little less sharp. Android 12+ and only for games that allow it." below={
          ([[0, 'Off'], [0.9, '90%'], [0.8, '80%'], [0.7, '70%'], [0.6, '60%']] as const).map(([v, l]) => <Chip key={v} h={36} label={l} on={p.opt.scale === v} onPress={() => setScale(v)} />)} />
      </Group>
      <T v="sub" style={{ marginHorizontal: 10, marginTop: 10 }}>These settings change only while a game from your list is open and are put back when it closes. Network boost removes local causes of lag spikes; it cannot make your internet connection itself faster. For smoother frames and cooler temperatures, turn on Performance mode and, if it suits you, a lower render resolution. Each game's profile also has a button that compiles its files for faster loading.</T>
    </Screen>
  );
}

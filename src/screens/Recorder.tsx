import React, { useEffect, useState } from 'react';
import { View } from 'react-native';
import { api, RecPlan, useRecorder } from '../native/api';
import { useStore } from '../store/store';
import type { RecCfg } from '../store/types';
import { useTheme } from '../theme/ThemeContext';
import { useNav } from '../nav';
import { AppSwitch, Btn, Chip, Group, Row, Section, Surface, T } from '../ui/primitives';
import { useToast } from '../ui/overlays';
import { Screen } from '../ui/scroll';
import { useEnsure } from '../logic/permissions';
import { Header } from './common';

const RES: [RecCfg['res'], string][] = [['sd', '480p'], ['hd', '720p'], ['fhd', '1080p'], ['qhd', '1440p'], ['uhd', '4K'], ['native', 'Screen size']];
const FPS = [30, 60, 90, 120];
const RATES = [0, 8, 16, 25, 40, 60, 80];

const clock = (ms: number) => { const s = Math.floor(ms / 1000); return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`; };

export function Recorder() {
  const { state, update } = useStore();
  const t = useTheme(); const nav = useNav(); const toast = useToast(); const ensure = useEnsure();
  const rec = useRecorder();
  const r = state.rec;
  const caps = api.recCaps();
  const [plan, setPlan] = useState<RecPlan | null>(null);
  const set = (p: Partial<RecCfg>) => update((s) => ({ ...s, rec: { ...s.rec, ...p } }));

  // What the phone's encoder will really do with these settings. Waits a moment so the new settings have been saved first.
  useEffect(() => {
    const id = setTimeout(() => setPlan(api.recPlan()), 450);
    return () => clearTimeout(id);
  }, [r.res, r.fps, r.mbps, r.codec, r.orient]);

  const idle = rec.state === 'idle';
  const start = async () => {
    if (!(await ensure('notifications'))) return;
    api.startMonitoring();
    api.recStart();
  };
  const toggleNotif = async (v: boolean) => {
    if (v && !(await ensure('notifications'))) return;
    set({ notif: v });
    if (v) api.startMonitoring();
  };
  const last = () => { if (!api.recOpenLast()) toast({ title: 'No recording yet', sub: 'Your first recording will show up here.' }); };

  return (
    <Screen>
      <Header title="Screen recorder" sub="Light, hardware encoded" onBack={nav.back} />
      <Surface pad={18}>
        <View style={{ alignItems: 'center' }}>
          <T v="num" style={{ fontSize: 52 }}>{clock(rec.elapsedMs)}</T>
          <T v="sub" style={{ marginTop: 4 }}>{rec.state === 'recording' ? 'Recording' : rec.state === 'paused' ? 'Paused' : 'Ready'}{rec.info ? ` · ${rec.info}` : ''}</T>
        </View>
        <View style={{ flexDirection: 'row', gap: 10, marginTop: 16 }}>
          {idle
            ? <View style={{ flex: 1 }}><Btn wide v="pri" label="Start recording" onPress={start} /></View>
            : (<>
              <View style={{ flex: 1 }}><Btn wide v="tonal" label={rec.state === 'paused' ? 'Resume' : 'Pause'} onPress={() => (rec.state === 'paused' ? api.recResume() : api.recPause())} /></View>
              <View style={{ flex: 1 }}><Btn wide v="pri" label="Stop" onPress={() => api.recStop()} /></View>
            </>)}
        </View>
        {rec.error ? <T v="sub" c={t.c.wn} style={{ marginTop: 10 }}>{rec.error}</T> : null}
        {rec.notes ? <T v="sub" c={t.c.mu} style={{ marginTop: 10 }}>{rec.notes}</T> : null}
        <View style={{ marginTop: 12 }}><Btn wide v="tonal" label="Watch last recording" onPress={last} /></View>
      </Surface>

      <Section title="Quality" />
      <Group>
        <Row title="Resolution" sub="The longer side of the video. The phone lowers it by itself if it can't encode it." below={RES.map(([k, l]) => <Chip key={k} h={36} label={l} on={r.res === k} onPress={() => set({ res: k })} />)} />
        <Row title="Frame rate" sub={caps.uhd60 ? 'Frames per second' : 'Frames per second. 4K 60 may not be possible on this phone, and it is lowered if so.'} below={FPS.map((f) => <Chip key={f} h={36} label={`${f} fps`} on={r.fps === f} onPress={() => set({ fps: f })} />)} />
        <Row title="Bit rate" sub="Higher is sharper and makes bigger files. Auto picks a good value." below={RATES.map((m) => <Chip key={m} h={36} label={m === 0 ? 'Auto' : `${m} Mbps`} on={r.mbps === m} onPress={() => set({ mbps: m })} />)} />
        <Row title="Encoder" sub={caps.hevc ? 'Compatibility plays everywhere. Storage makes files about half the size.' : 'This phone can only record H.264.'} below={[
          <Chip key="h264" h={36} label="Compatibility (H.264)" on={r.codec === 'h264'} onPress={() => set({ codec: 'h264' })} />,
          <Chip key="hevc" h={36} label="Storage (H.265)" on={r.codec === 'hevc' && caps.hevc} onPress={() => (caps.hevc ? set({ codec: 'hevc' }) : toast({ title: "This phone can't record H.265" }))} />,
        ]} />
        <Row title="Orientation" sub="Landscape suits games" below={(['landscape', 'portrait', 'auto'] as const).map((o) => <Chip key={o} h={36} label={o === 'auto' ? 'Follow screen' : o === 'landscape' ? 'Landscape' : 'Portrait'} on={r.orient === o} onPress={() => set({ orient: o })} />)} />
      </Group>
      {plan ? <T v="sub" style={{ marginHorizontal: 10, marginTop: 10 }}>This phone will record {plan.label}.{plan.notes.length ? ` ${plan.notes.join(' ')}` : ''}</T> : null}

      <Section title="What to record" />
      <Group>
        <Row title="Scope" sub={caps.sdk >= 34 ? 'Android asks every time whether to share one app or the whole screen.' : 'This Android version records the whole screen. Choosing a single app needs Android 14.'} below={[
          <Chip key="ask" h={36} label={caps.sdk >= 34 ? 'Single app or screen' : 'Whole screen'} on={r.scope === 'ask'} onPress={() => set({ scope: 'ask' })} />,
          <Chip key="screen" h={36} label="Whole screen only" on={r.scope === 'screen'} onPress={() => set({ scope: 'screen' })} />,
        ]} />
      </Group>

      <Section title="Controls" />
      <Group>
        <Row leadIcon="notifications" title="Control from notification" sub="A Start button while idle, and Pause, Resume and Stop while recording. The floating panel has them too." right={<AppSwitch on={r.notif} onChange={toggleNotif} />} />
      </Group>
      <T v="sub" style={{ marginHorizontal: 10, marginTop: 10 }}>Recordings are saved to Movies/Densify. Android asks for permission each time you start, which is how it keeps recording in your hands. This recorder saves video only, without sound.</T>
    </Screen>
  );
}

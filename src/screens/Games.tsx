import React, { useEffect, useState } from 'react';
import { Pressable, View } from 'react-native';
import { hexA } from '../ui/TabBar';
import { api, useAdb, useEngine } from '../native/api';
import { useStore } from '../store/store';
import { useTheme } from '../theme/ThemeContext';
import { useNav } from '../nav';
import { AnimatedNumber, AppIcon, AppSwitch, Btn, Group, Icon, Row, Ruler, Section, Surface, T } from '../ui/primitives';
import { useToast } from '../ui/overlays';
import { Screen } from '../ui/scroll';
import { useEnsure } from '../logic/permissions';
import { ago, useBattery } from '../logic/hooks';
import { AddGameSheet, Header } from './common';
import { StretchCard } from './StretchCard';

const Banner = ({ icon, title, sub, action, onAction }: { icon: 'link' | 'battery-alert' | 'tune'; title: string; sub: string; action?: string; onAction?: () => void }) => {
  const t = useTheme();
  return (
    <View style={{ flexDirection: 'row', alignItems: 'center', gap: 12, marginTop: 12, padding: 14, paddingLeft: 16, borderRadius: Math.min(t.r, 20), borderWidth: 1, borderColor: t.c.wn + '55', backgroundColor: t.c.wn + '1a' }}>
      <Icon name={icon} color={t.c.wn} />
      <View style={{ flex: 1 }}><T w="600" style={{ fontSize: 14.5 }}>{title}</T><T v="sub" c={t.c.tx2}>{sub}</T></View>
      {action ? <Btn v="text" label={action} onPress={onAction!} /> : null}
    </View>
  );
};

export function Games() {
  const { state, update, log } = useStore();
  const t = useTheme(); const nav = useNav(); const toast = useToast(); const ensure = useEnsure();
  const adb = useAdb(); const eng = useEngine(); const bat = useBattery();
  const [cur, setCur] = useState<number | null>(null);
  const [sheet, setSheet] = useState(false);
  const [dismissed, setDismissed] = useState(false);
  const connected = adb.state === 'connected';
  useEffect(() => { if (connected) api.readSw().then((d) => setCur(d.sw)).catch(() => {}); }, [connected, eng.appliedSw]);
  const shown = eng.appliedSw ?? cur ?? state.defaultSw;
  const activeName = eng.activePkg ? state.profiles.find((p) => p.pkg === eng.activePkg)?.name : null;
  const lowBat = state.lowBattery && !bat.charging && bat.level < state.lowBatteryPercent;

  const restore = () => api.restoreDefault().then((v) => { setCur(v); log(`Restored smallest width ${v}`, 'Manual'); toast({ title: `Restored smallest width ${v}` }); })
    .catch((e: Error) => toast({ title: `Couldn't restore ${state.defaultSw || 'default'} smallest width`, sub: e.message || 'Check your Wireless Debugging connection.', err: true, action: { label: 'Retry', run: restore } }));
  const detect = () => api.readSw().then((d) => { update((s) => ({ ...s, defaultSw: d.physSw })); toast({ title: `Detected smallest width ${d.physSw}`, sub: "Your phone's own, saved as your default." }); })
    .catch((e: Error) => toast({ title: "Couldn't read the current smallest width", sub: e.message, err: true, action: { label: 'Retry', run: detect } }));
  const toggleAuto = async (v: boolean) => {
    if (v) { if (!(await ensure('automation'))) return; update((s) => ({ ...s, automation: true })); api.startMonitoring(); }
    else { update((s) => ({ ...s, automation: false })); api.stopMonitoring(); }
  };
  const chip = !connected ? ['Offline', t.c.wn] : state.automation ? ['Active', t.c.ok] : ['Paused', t.c.mu];

  return (
    <Screen tab>
      <Header title="Games" sub="Your profiles and smallest width" right={<Pressable onPress={() => nav.setTab('settings')} hitSlop={10}><Icon name="tune" size={24} color={t.c.tx2} /></Pressable>} />
      <Surface pad={20} style={{ paddingBottom: 6 }}>
        <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
          <T v="sub" style={{ fontSize: 13.5 }}>Smallest width</T>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6, paddingHorizontal: 11, height: 26, borderRadius: 13, backgroundColor: t.c.sf2 }}>
            <View style={{ width: 7, height: 7, borderRadius: 4, backgroundColor: chip[1] }} /><T v="sub" c={chip[1]} w="600" style={{ fontSize: 12 }}>{chip[0]}</T>
          </View>
        </View>
        <View style={{ flexDirection: 'row', alignItems: 'baseline', marginTop: 18 }}>
          {shown > 0 ? <AnimatedNumber value={shown} style={{ fontSize: 84, fontWeight: '500', letterSpacing: -4, color: t.c.tx, fontFamily: t.mono ? 'monospace' : undefined }} /> : <T v="num">—</T>}
          <T v="sub" style={{ marginLeft: 8, fontSize: 15 }}>dp</T>
        </View>
        <Ruler value={shown || 480} def={state.defaultSw} />
        <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginTop: 8, marginHorizontal: -8 }}>
          <T v="sub" style={{ marginLeft: 8, fontSize: 13.5 }}>{activeName ? `${activeName} profile` : state.defaultSw ? `Default ${state.defaultSw}` : 'Phone default'}</T>
          <Btn v="text" label="Restore default" disabled={!connected || (state.defaultSw > 0 && shown === state.defaultSw)} onPress={restore} />
        </View>
      </Surface>

      {eng.activePkg && activeName ? (
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 12, marginTop: 12, padding: 12, borderRadius: Math.min(t.r, 20), borderWidth: 1, borderColor: hexA(t.c.ac, 0.35), backgroundColor: t.c.acs }}>
          <AppIcon pkg={eng.activePkg} name={activeName} size={40} />
          <View style={{ flex: 1 }}><T v="sub" c={t.c.ac} w="600" style={{ fontSize: 12 }}>NOW PLAYING</T><T w="600" style={{ fontSize: 16 }}>{activeName}</T></View>
          <View style={{ alignItems: 'flex-end' }}><T style={{ fontSize: 24, fontWeight: '600' }}>{shown || '—'}</T><T v="sub" style={{ fontSize: 11.5 }}>SW dp</T></View>
        </View>
      ) : null}

      <StretchCard />

      {!connected && (api.isPaired() && state.mode !== 'root' && state.mode !== 'shizuku'
        ? <Banner icon="link" title="Reconnecting to Android…" sub="Densify is already set up and reconnects by itself when Wi-Fi is on." action="Retry" onAction={() => api.connect().catch((e: Error) => toast({ title: "Couldn't connect yet", sub: e.message, err: true }))} />
        : <Banner icon="link" title="Wireless Debugging isn't connected" sub="Densify can't change the smallest width until it is." action="Set up" onAction={() => nav.push({ name: 'setup' })} />)}
      {connected && !state.defaultSw && <Banner icon="tune" title="Set your default smallest width" sub="Densify returns to this value after every game." action="Detect" onAction={detect} />}
      {lowBat && !dismissed && <Banner icon="battery-alert" title={`Battery at ${bat.level}%`} sub="Games drain faster at this level. Plug in to keep your session going." action="Dismiss" onAction={() => setDismissed(true)} />}

      <Section title="Automation" />
      <Group><Row leadIcon="bolt" title="Automatic switching" sub={state.automation ? `Watching ${state.profiles.length} game${state.profiles.length === 1 ? '' : 's'}` : 'Paused. Games keep your current smallest width.'} right={<AppSwitch on={state.automation} onChange={toggleAuto} />} /></Group>

      <Section title="Profiles" />
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 10 }}>
        {state.profiles.map((p) => {
          const preset = state.presets.find((x) => x.id === p.presetId)?.name;
          const live = eng.activePkg === p.pkg;
          return (
            <Pressable key={p.pkg} onPress={() => nav.push({ name: 'profile', pkg: p.pkg, label: p.name })} style={{ width: '48.5%', padding: 14, borderRadius: Math.min(t.r, 22), borderWidth: 1, borderColor: live ? t.c.ac : t.c.bd, backgroundColor: t.c.sf }}>
              <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' }}>
                <AppIcon pkg={p.pkg} name={p.name} size={40} />
                <View style={{ paddingHorizontal: 8, height: 22, borderRadius: 11, backgroundColor: p.auto ? t.c.acs : t.c.sf2, justifyContent: 'center' }}>
                  <T v="sub" c={p.auto ? t.c.ac : t.c.mu} w="600" style={{ fontSize: 10.5 }}>{p.auto ? (preset ?? 'AUTO').toUpperCase() : 'OFF'}</T>
                </View>
              </View>
              <T w="600" lines={1} style={{ marginTop: 12, fontSize: 14.5 }}>{p.name}</T>
              <View style={{ flexDirection: 'row', alignItems: 'baseline', marginTop: 2 }}>
                <T style={{ fontSize: 30, fontWeight: '500', letterSpacing: -1 }}>{p.sw}</T><T v="sub" style={{ marginLeft: 4, fontSize: 12 }}>sw</T>
              </View>
            </Pressable>
          );
        })}
        <Pressable onPress={() => setSheet(true)} style={{ width: '48.5%', minHeight: 132, borderRadius: Math.min(t.r, 22), borderWidth: 1.5, borderStyle: 'dashed', borderColor: t.c.bd2, alignItems: 'center', justifyContent: 'center', gap: 6 }}>
          <Icon name="add" size={26} color={t.c.ac} /><T c={t.c.ac} w="600" style={{ fontSize: 14 }}>Add game</T>
        </Pressable>
      </View>

      {state.log.length > 0 && (<>
        <Section title="Recent activity" />
        <Group>{state.log.slice(0, 3).map((l) => (
          <Row key={l.id} lead={l.pkg ? <AppIcon pkg={l.pkg} name={l.title} /> : undefined} leadIcon={l.pkg ? undefined : 'history'} title={l.title} sub={l.sub} right={<T v="sub">{ago(l.at)}</T>} />
        ))}</Group>
      </>)}
      <AddGameSheet visible={sheet} onClose={() => setSheet(false)} />
    </Screen>
  );
}

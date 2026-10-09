import React, { useEffect, useState } from 'react';
import { Pressable, View } from 'react-native';
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

export function Home() {
  const { state, update, log } = useStore();
  const t = useTheme(); const nav = useNav(); const toast = useToast(); const ensure = useEnsure();
  const adb = useAdb(); const eng = useEngine(); const bat = useBattery();
  const [cur, setCur] = useState<number | null>(null);
  const [sheet, setSheet] = useState(false);
  const [dismissed, setDismissed] = useState(false);
  const connected = adb.state === 'connected';
  useEffect(() => { if (connected) api.readDensity().then((d) => setCur(d.effective)).catch(() => {}); }, [connected, eng.appliedDpi]);
  const shown = eng.appliedDpi ?? cur ?? state.defaultDpi;
  const activeName = eng.activePkg ? state.profiles.find((p) => p.pkg === eng.activePkg)?.name : null;
  const lowBat = state.lowBattery && !bat.charging && bat.level < state.lowBatteryPercent;

  const restore = () => api.restoreDefault().then((v) => { setCur(v); log(`Restored ${v} DPI`, 'Manual'); toast({ title: `Restored ${v} DPI` }); })
    .catch((e: Error) => toast({ title: `Couldn't restore ${state.defaultDpi || 'default'} DPI`, sub: e.message || 'Check your Wireless Debugging connection.', err: true, action: { label: 'Retry', run: restore } }));
  const detect = () => api.readDensity().then((d) => { update((s) => ({ ...s, defaultDpi: d.effective })); toast({ title: `Detected ${d.effective} DPI`, sub: 'Saved as your default.' }); })
    .catch((e: Error) => toast({ title: "Couldn't read the current DPI", sub: e.message, err: true, action: { label: 'Retry', run: detect } }));
  const toggleAuto = async (v: boolean) => {
    if (v) { if (!(await ensure('automation'))) return; update((s) => ({ ...s, automation: true })); api.startMonitoring(); }
    else { update((s) => ({ ...s, automation: false })); api.stopMonitoring(); }
  };
  const chip = !connected ? ['Offline', t.c.wn] : state.automation ? ['Active', t.c.ok] : ['Paused', t.c.mu];

  return (
    <Screen tab>
      <Header title="Densify" sub="Display automation" right={<Pressable onPress={() => nav.setTab('settings')} hitSlop={10}><Icon name="tune" size={24} color={t.c.tx2} /></Pressable>} />
      <Surface pad={20} style={{ paddingBottom: 6 }}>
        <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
          <T v="sub" style={{ fontSize: 13.5 }}>Current density</T>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6, paddingHorizontal: 11, height: 26, borderRadius: 13, backgroundColor: t.c.sf2 }}>
            <View style={{ width: 7, height: 7, borderRadius: 4, backgroundColor: chip[1] }} /><T v="sub" c={chip[1]} w="600" style={{ fontSize: 12 }}>{chip[0]}</T>
          </View>
        </View>
        <View style={{ flexDirection: 'row', alignItems: 'baseline', marginTop: 18 }}>
          {shown > 0 ? <AnimatedNumber value={shown} style={{ fontSize: 84, fontWeight: '500', letterSpacing: -4, color: t.c.tx, fontFamily: t.mono ? 'monospace' : undefined }} /> : <T v="num">—</T>}
          <T v="sub" style={{ marginLeft: 8, fontSize: 15 }}>dpi</T>
        </View>
        <Ruler value={shown || 480} def={state.defaultDpi} />
        <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginTop: 8, marginHorizontal: -8 }}>
          <T v="sub" style={{ marginLeft: 8, fontSize: 13.5 }}>{activeName ? `${activeName} profile` : state.defaultDpi ? 'System default' : 'Default not set'}</T>
          <Btn v="text" label="Restore default" disabled={!connected || !state.defaultDpi || shown === state.defaultDpi} onPress={restore} />
        </View>
      </Surface>

      {!connected && <Banner icon="link" title="Wireless Debugging isn't connected" sub="Densify can't change DPI until it is." action="Set up" onAction={() => nav.push({ name: 'setup' })} />}
      {connected && !state.defaultDpi && <Banner icon="tune" title="Set your default DPI" sub="Densify returns to this value after every game." action="Detect" onAction={detect} />}
      {lowBat && !dismissed && <Banner icon="battery-alert" title={`Battery at ${bat.level}%`} sub="Games drain faster at this level. Plug in to keep your session going." action="Dismiss" onAction={() => setDismissed(true)} />}

      <Section title="Automation" />
      <Group><Row leadIcon="bolt" title="Automatic switching" sub={state.automation ? `Watching ${state.profiles.length} game${state.profiles.length === 1 ? '' : 's'}` : 'Paused. Games keep your current DPI.'} right={<AppSwitch on={state.automation} onChange={toggleAuto} />} /></Group>

      <Section title="Profiles" action="View all" onAction={() => nav.setTab('games')} />
      <Group>
        {state.profiles.map((p) => (
          <Row key={p.pkg} lead={<AppIcon pkg={p.pkg} name={p.name} />} title={p.name} sub={p.auto ? 'Auto switching on' : 'Auto switching off'} onPress={() => nav.push({ name: 'profile', pkg: p.pkg, label: p.name })}
            right={<View style={{ alignItems: 'flex-end' }}><T style={{ fontSize: 17 }}>{p.dpi}</T><T v="sub" style={{ fontSize: 11.5 }}>DPI</T></View>} />
        ))}
        <Row leadIcon="add" title={<T c={t.c.ac}>Add game</T>} onPress={() => setSheet(true)} />
      </Group>

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

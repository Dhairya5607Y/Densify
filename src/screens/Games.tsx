import React, { useEffect, useState } from 'react';
import { Pressable, View } from 'react-native';
import { hexA } from '../ui/TabBar';
import { LinearGradient } from 'expo-linear-gradient';
import { api, useAdb, useEngine } from '../native/api';
import { useStore } from '../store/store';
import type { Profile } from '../store/types';
import { useTheme } from '../theme/ThemeContext';
import { useNav } from '../nav';
import { AnimatedNumber, AppIcon, AppSwitch, Btn, Group, Icon, Row, Section, Surface, T } from '../ui/primitives';
import { useToast } from '../ui/overlays';
import { Screen } from '../ui/scroll';
import { useEnsure } from '../logic/permissions';
import { ago, useBattery } from '../logic/hooks';
import { AddGameSheet } from './common';
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

/** Game tile: the game's own icon fills the tile (enlarged and dimmed), with diagonal stripes, a preset badge and the smallest width. */
function GameTile({ p, preset, live, onPress, onAuto }: { p: Profile; preset?: string; live: boolean; onPress: () => void; onAuto: (v: boolean) => void }) {
  const t = useTheme();
  const r = Math.min(t.r, 22);
  return (
    <Pressable onPress={onPress} style={{ width: '48.5%', height: 160, borderRadius: r, overflow: 'hidden', backgroundColor: '#161616', borderWidth: live ? 2 : 0, borderColor: t.c.ac, justifyContent: 'flex-end', padding: 14 }}>
      <View style={{ position: 'absolute', left: -30, right: -30, top: -30, bottom: -30, alignItems: 'center', justifyContent: 'center' }}>
        <AppIcon pkg={p.pkg} name={p.name} size={230} />
      </View>
      <View pointerEvents="none" style={{ position: 'absolute', left: -80, top: -80, width: 400, height: 400, transform: [{ rotate: '-35deg' }] }}>
        {Array.from({ length: 36 }).map((_, i) => <View key={i} style={{ height: 8, marginBottom: 8, backgroundColor: 'rgba(255,255,255,0.05)' }} />)}
      </View>
      <LinearGradient pointerEvents="none" colors={['rgba(0,0,0,0.05)', 'rgba(0,0,0,0.25)', 'rgba(0,0,0,0.78)']} locations={[0, 0.45, 1]} style={{ position: 'absolute', left: 0, right: 0, top: 0, bottom: 0 }} />
      <View style={{ position: 'absolute', top: 10, right: 10, paddingHorizontal: 9, height: 22, borderRadius: 6, backgroundColor: 'rgba(255,255,255,0.92)', justifyContent: 'center' }}>
        <T c="#0b0b0b" w="700" style={{ fontSize: 10.5, letterSpacing: 0.6 }}>{(preset ?? 'CUSTOM').toUpperCase()}</T>
      </View>
      <T c="#fff" w="600" lines={1} style={{ fontSize: 13.5 }}>{p.name}</T>
      <View style={{ flexDirection: 'row', alignItems: 'flex-end', justifyContent: 'space-between' }}>
        <View style={{ flexDirection: 'row', alignItems: 'baseline' }}>
          <T c="#fff" style={{ fontSize: 32, fontWeight: '700', letterSpacing: -1 }}>{p.sw}</T><T c="rgba(255,255,255,0.8)" w="600" style={{ marginLeft: 4, fontSize: 11 }}>sw</T>
        </View>
        <Pressable onPress={() => onAuto(!p.auto)} hitSlop={8} style={{ flexDirection: 'row', alignItems: 'center', gap: 5, paddingHorizontal: 9, height: 24, borderRadius: 12, backgroundColor: p.auto ? 'rgba(255,255,255,0.92)' : 'rgba(255,255,255,0.18)' }}>
          <Icon name="bolt" size={13} color={p.auto ? '#0b0b0b' : '#fff'} /><T c={p.auto ? '#0b0b0b' : '#fff'} w="700" style={{ fontSize: 10.5 }}>{p.auto ? 'AUTO' : 'OFF'}</T>
        </Pressable>
      </View>
    </Pressable>
  );
}

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
      <Surface pad={12}>
        <View style={{ flexDirection: 'row', gap: 12 }}>
          <View style={{ flex: 1, justifyContent: 'space-between' }}>
            <T v="sub" style={{ fontSize: 11, letterSpacing: 1 }}>SMALLEST WIDTH</T>
            <View style={{ flexDirection: 'row', alignItems: 'baseline' }}>
              {shown > 0 ? <AnimatedNumber value={shown} style={{ fontSize: 40, fontWeight: '500', letterSpacing: -1.5, color: t.c.tx, fontFamily: t.mono ? 'monospace' : undefined }} /> : <T style={{ fontSize: 40 }}>—</T>}
              <T v="sub" style={{ marginLeft: 4, fontSize: 13 }}>dp</T>
            </View>
            <Pressable onPress={restore} disabled={!connected || (state.defaultSw > 0 && shown === state.defaultSw)} hitSlop={8}>
              <T v="sub" c={!connected || (state.defaultSw > 0 && shown === state.defaultSw) ? t.c.mu : t.c.ac} w="600" style={{ fontSize: 12.5 }}>{activeName ? `${activeName} · Restore` : state.defaultSw ? `Restore ${state.defaultSw}` : 'Restore default'}</T>
            </Pressable>
          </View>
          <View style={{ flex: 1.25 }}>
            <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'flex-end', gap: 10, marginBottom: 8 }}>
              <View style={{ flexDirection: 'row', alignItems: 'center', gap: 5, paddingHorizontal: 9, height: 22, borderRadius: 11, backgroundColor: t.c.sf2 }}>
                <View style={{ width: 6, height: 6, borderRadius: 3, backgroundColor: chip[1] }} /><T v="sub" c={chip[1]} w="600" style={{ fontSize: 11 }}>{chip[0]}</T>
              </View>
              <Pressable onPress={() => nav.setTab('settings')} hitSlop={10}><Icon name="tune" size={20} color={t.c.tx2} /></Pressable>
            </View>
            <StretchCard compact />
          </View>
        </View>
      </Surface>

      {eng.activePkg && activeName ? (
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 12, marginTop: 12, padding: 12, borderRadius: Math.min(t.r, 20), borderWidth: 1, borderColor: hexA(t.c.ac, 0.35), backgroundColor: t.c.acs }}>
          <AppIcon pkg={eng.activePkg} name={activeName} size={40} />
          <View style={{ flex: 1 }}><T v="sub" c={t.c.ac} w="600" style={{ fontSize: 12 }}>NOW PLAYING</T><T w="600" style={{ fontSize: 16 }}>{activeName}</T></View>
          <View style={{ alignItems: 'flex-end' }}><T style={{ fontSize: 24, fontWeight: '600' }}>{shown || '—'}</T><T v="sub" style={{ fontSize: 11.5 }}>SW dp</T></View>
        </View>
      ) : null}


      {!connected && (api.isPaired() && state.mode !== 'root' && state.mode !== 'shizuku'
        ? <Banner icon="link" title="Reconnecting to Android…" sub="Densify is already set up and reconnects by itself when Wi-Fi is on." action="Retry" onAction={() => api.connect().catch((e: Error) => toast({ title: "Couldn't connect yet", sub: e.message, err: true }))} />
        : <Banner icon="link" title="Wireless Debugging isn't connected" sub="Densify can't change the smallest width until it is." action="Set up" onAction={() => nav.push({ name: 'setup' })} />)}
      {connected && !state.defaultSw && <Banner icon="tune" title="Set your default smallest width" sub="Densify returns to this value after every game." action="Detect" onAction={detect} />}
      {lowBat && !dismissed && <Banner icon="battery-alert" title={`Battery at ${bat.level}%`} sub="Games drain faster at this level. Plug in to keep your session going." action="Dismiss" onAction={() => setDismissed(true)} />}

      <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginTop: 14, marginHorizontal: 6 }}>
        <T v="sub" c={t.c.tx2} style={{ fontSize: 12.5 }}>{state.automation ? `Auto switching on · watching ${state.profiles.filter((p) => p.auto).length} game${state.profiles.filter((p) => p.auto).length === 1 ? '' : 's'}` : 'Auto switching paused'}</T>
        <View style={{ transform: [{ scale: 0.8 }], marginRight: -6 }}><AppSwitch on={state.automation} onChange={toggleAuto} /></View>
      </View>

      <Section title="Your games" />
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 10 }}>
        {state.profiles.map((p) => <GameTile key={p.pkg} p={p} preset={state.presets.find((x) => x.id === p.presetId)?.name} live={eng.activePkg === p.pkg} onPress={() => nav.push({ name: 'profile', pkg: p.pkg, label: p.name })} onAuto={(v) => update((x) => ({ ...x, profiles: x.profiles.map((y) => (y.pkg === p.pkg ? { ...y, auto: v } : y)) }))} />)}
        <Pressable onPress={() => setSheet(true)} style={{ width: '48.5%', height: 160, borderRadius: Math.min(t.r, 22), borderWidth: 1, borderStyle: 'dashed', borderColor: t.c.bd2, alignItems: 'center', justifyContent: 'center', gap: 8 }}>
          <Icon name="add" size={28} color={t.c.tx} /><T w="600" style={{ fontSize: 14 }}>Add game</T>
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

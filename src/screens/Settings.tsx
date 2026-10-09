import React, { useEffect, useState } from 'react';
import { AppState, Pressable, TextInput, View } from 'react-native';
import Constants from 'expo-constants';
import { api, useAdb } from '../native/api';
import { useStore } from '../store/store';
import { THEMES } from '../theme/themes';
import { useTheme } from '../theme/ThemeContext';
import { useNav } from '../nav';
import { AppSwitch, Btn, Group, Icon, Row, Section, T } from '../ui/primitives';
import { useToast } from '../ui/overlays';
import { Screen } from '../ui/scroll';
import { useEnsure } from '../logic/permissions';
import { parseDpi } from '../logic/dpi';
import { Header } from './common';

export function Settings() {
  const { state, update } = useStore();
  const t = useTheme(); const nav = useNav(); const toast = useToast(); const ensure = useEnsure(); const adb = useAdb();
  const [def, setDef] = useState(state.defaultDpi ? String(state.defaultDpi) : '');
  const [defErr, setDefErr] = useState('');
  const [, bump] = useState(0);
  useEffect(() => { const s = AppState.addEventListener('change', (x) => { if (x === 'active') bump((n) => n + 1); }); return () => s.remove(); }, []);
  useEffect(() => setDef(state.defaultDpi ? String(state.defaultDpi) : ''), [state.defaultDpi]);

  const saveDef = () => {
    const r = parseDpi(def);
    if (r.err !== undefined) { setDefErr(r.err); return; }
    setDefErr(''); update((s) => ({ ...s, defaultDpi: r.v })); toast({ title: `Default set to ${r.v} DPI` });
  };
  const detect = () => api.readDensity().then((d) => { update((s) => ({ ...s, defaultDpi: d.effective })); toast({ title: `Detected ${d.effective} DPI`, sub: 'Saved as your default.' }); })
    .catch(async (e: Error) => { if (adb.state !== 'connected') { await ensure('preview'); return; } toast({ title: "Couldn't read the current DPI", sub: e.message, err: true, action: { label: 'Retry', run: detect } }); });
  const restore = () => api.restoreDefault().then((v) => toast({ title: `Restored ${v} DPI` }))
    .catch(async (e: Error) => { if (adb.state !== 'connected') { await ensure('preview'); return; } toast({ title: "Couldn't restore your default", sub: e.message, err: true, action: { label: 'Retry', run: restore } }); });
  const toggleAuto = async (v: boolean) => {
    if (v) { if (!(await ensure('automation'))) return; update((s) => ({ ...s, automation: true })); api.startMonitoring(); }
    else { update((s) => ({ ...s, automation: false })); api.stopMonitoring(); }
  };
  const toggleLow = async (v: boolean) => { if (v && !(await ensure('lowBattery'))) return; update((s) => ({ ...s, lowBattery: v })); };
  const step = (d: number) => update((s) => ({ ...s, lowBatteryPercent: Math.max(10, Math.min(90, s.lowBatteryPercent + d)) }));

  const perm = (ok: boolean) => <T v="sub" c={ok ? t.c.ok : t.c.wn} w="600">{ok ? 'Allowed' : 'Off'}</T>;
  const stateLabel = adb.state === 'connected' ? ['Connected', t.c.ok] : adb.state === 'connecting' ? ['Connecting…', t.c.mu] : adb.state === 'error' ? ['Connection error', t.c.wn] : ['Not connected', t.c.mu];

  return (
    <Screen tab>
      <Header title="Settings" sub="Configure Densify" />
      <Section title="Display" />
      <Group>
        <Row leadIcon="tune" title="Default DPI" sub="Your normal system density" right={
          <View style={{ width: 84, height: 44, borderRadius: t.rs, borderWidth: 1.5, borderColor: defErr ? t.c.wn : t.c.bd2, alignItems: 'center', justifyContent: 'center' }}>
            <TextInput value={def} onChangeText={(s) => { setDef(s.replace(/\D/g, '').slice(0, 4)); setDefErr(''); }} onEndEditing={saveDef} keyboardType="number-pad" placeholder="—" placeholderTextColor={t.c.mu} style={{ color: t.c.tx, fontSize: 16, fontWeight: '500', textAlign: 'center', width: '100%' }} />
          </View>} />
        <Row leadIcon="sync" title="Detect current DPI" sub="Use what the device reports now" onPress={detect} />
        <Row leadIcon="undo" title="Restore Default DPI" sub={state.defaultDpi ? `Switch back to ${state.defaultDpi} DPI immediately` : 'Set your default first'} onPress={restore} />
      </Group>
      {defErr ? <T v="sub" c={t.c.wn} style={{ margin: 10 }}>{defErr}</T> : null}

      <Section title="Automation" />
      <Group>
        <Row leadIcon="bolt" title="Background monitoring" sub="Detect when a configured game opens" right={<AppSwitch on={state.automation} onChange={toggleAuto} />} />
        <Row leadIcon="power-settings-new" title="Restore on boot" sub="Resume monitoring after a restart" right={<AppSwitch on={state.restoreOnBoot} onChange={(v) => update((s) => ({ ...s, restoreOnBoot: v }))} />} />
        <Row leadIcon="battery-alert" title="Low battery warning" sub={`Warn when battery is below ${state.lowBatteryPercent}%`} right={<AppSwitch on={state.lowBattery} onChange={toggleLow} />} />
        {state.lowBattery && <Row leadIcon="percent" title="Warn below" sub={`${state.lowBatteryPercent}%`} right={<View style={{ flexDirection: 'row' }}><Btn v="tonal" label="−" onPress={() => step(-5)} /><View style={{ width: 8 }} /><Btn v="tonal" label="+" onPress={() => step(5)} /></View>} />}
      </Group>

      <Section title="Gaming" />
      <Group><Row leadIcon="tune" title="Presets and quick values" sub={`${state.presets.length} presets · ${state.quick.length} quick DPI values`} onPress={() => nav.push({ name: 'presets' })} right={<Icon name="chevron-right" />} /></Group>

      <Section title="Quick controls" />
      <Group>
        <Row leadIcon="dashboard-customize" title="Quick Settings tile" sub="Change DPI from the notification shade" right={<Btn v="tonal" label="Add" onPress={() => toast(api.requestAddTile() ? { title: 'Asked Android to add the tile' } : { title: 'Add it manually', sub: 'Edit your Quick Settings panel and drag “Density” in.' })} />} />
        <Row leadIcon="notifications" title="Notification controls" sub="−10, +10 and Restore appear while monitoring" />
      </Group>

      <Section title="Connection" />
      <Group><Row leadIcon="link" title="Wireless Debugging" sub={stateLabel[0]} right={<Btn v={adb.state === 'connected' ? 'tonal' : 'pri'} label="Setup" onPress={() => nav.push({ name: 'setup' })} />} /></Group>

      <Section title="Permissions" />
      <Group>
        <Row leadIcon="insights" title="Usage access" sub="Sees which game is open" right={perm(api.hasUsageAccess())} onPress={() => ensure('automation')} />
        <Row leadIcon="notifications" title="Notifications" sub="Pairing, quick controls, battery warning" right={perm(api.hasNotifications())} onPress={() => ensure('lowBattery')} />
        <Row leadIcon="battery-saver" title="Battery optimization" sub="Allow unrestricted so monitoring isn't stopped" right={perm(api.isBatteryUnrestricted())} onPress={() => api.openSettings('battery')} />
      </Group>

      <Section title="Appearance" />
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 10 }}>
        {THEMES.map((th) => {
          const on = th.id === state.themeId;
          return (
            <Pressable key={th.id} onPress={() => update((s) => ({ ...s, themeId: th.id }))} style={{ width: '30%', flexGrow: 1 }}>
              <View style={{ height: 84, borderRadius: Math.min(th.r, 22), backgroundColor: th.c.bg, borderWidth: on ? 2 : 1, borderColor: on ? t.c.ac : t.c.bd2, padding: 9, justifyContent: 'flex-end', gap: 6, overflow: 'hidden' }}>
                {th.backdrop && <View style={{ position: 'absolute', top: -20, left: -10, width: 80, height: 80, borderRadius: 40, backgroundColor: th.backdrop[0], opacity: 0.55 }} />}
                <View style={{ height: 30, borderRadius: Math.min(th.rs, 11), backgroundColor: th.surface === 'glass' ? 'rgba(255,255,255,.2)' : th.c.sf, borderWidth: 1, borderColor: th.c.bd2, flexDirection: 'row', alignItems: 'center', paddingHorizontal: 8, gap: 6 }}>
                  <View style={{ width: 10, height: 10, borderRadius: th.surface === 'hud' ? 0 : 5, backgroundColor: th.c.ac }} />
                  <View style={{ flex: 1, height: 4, borderRadius: 2, backgroundColor: th.c.mu, opacity: 0.5 }} />
                </View>
              </View>
              <T w={on ? '600' : '500'} c={on ? t.c.ac : t.c.tx} style={{ fontSize: 13.5, marginTop: 7, textAlign: 'center' }}>{th.name}</T>
            </Pressable>
          );
        })}
      </View>
      <T v="sub" style={{ marginHorizontal: 10, marginTop: 10 }}>{THEMES.find((x) => x.id === state.themeId)?.desc}</T>

      <Section title="About" />
      <Group><Row leadIcon="info" title="Densify" sub={`Version ${Constants.expoConfig?.version ?? ''} · DPI only, never resolution`} /></Group>
    </Screen>
  );
}

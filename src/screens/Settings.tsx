import React, { useEffect, useState } from 'react';
import { AppState, Pressable, TextInput, View } from 'react-native';
import Constants from 'expo-constants';
import { api, useAdb } from '../native/api';
import { useStore } from '../store/store';
import { accentClashes, ACCENTS, THEMES } from '../theme/themes';
import { useTheme } from '../theme/ThemeContext';
import { useNav } from '../nav';
import { AppSwitch, Btn, Chip, Group, Icon, Row, Section, T } from '../ui/primitives';
import { useToast } from '../ui/overlays';
import { Screen } from '../ui/scroll';
import { useEnsure } from '../logic/permissions';
import { parseSw } from '../logic/dpi';
import { Header } from './common';

export function Settings() {
  const { state, update } = useStore();
  const t = useTheme(); const nav = useNav(); const toast = useToast(); const ensure = useEnsure(); const adb = useAdb();
  const [def, setDef] = useState(state.defaultSw ? String(state.defaultSw) : '');
  const [defErr, setDefErr] = useState('');
  const [, bump] = useState(0);
  useEffect(() => { const s = AppState.addEventListener('change', (x) => { if (x === 'active') bump((n) => n + 1); }); return () => s.remove(); }, []);
  useEffect(() => setDef(state.defaultSw ? String(state.defaultSw) : ''), [state.defaultSw]);

  const saveDef = () => {
    const r = parseSw(def);
    if (r.err !== undefined) { setDefErr(r.err); return; }
    setDefErr(''); update((s) => ({ ...s, defaultSw: r.v })); toast({ title: `Default smallest width set to ${r.v}` });
  };
  const detect = () => api.readSw().then((d) => { update((s) => ({ ...s, defaultSw: d.physSw })); toast({ title: `Detected smallest width ${d.physSw}`, sub: "Your phone's own, saved as your default." }); })
    .catch(async (e: Error) => { if (adb.state !== 'connected') { await ensure('preview'); return; } toast({ title: "Couldn't read the current smallest width", sub: e.message, err: true, action: { label: 'Retry', run: detect } }); });
  const restore = () => api.restoreDefault().then((v) => toast({ title: `Restored smallest width ${v}` }))
    .catch(async (e: Error) => { if (adb.state !== 'connected') { await ensure('preview'); return; } toast({ title: "Couldn't restore your default", sub: e.message, err: true, action: { label: 'Retry', run: restore } }); });
  const toggleAuto = async (v: boolean) => {
    if (v) { if (!(await ensure('automation'))) return; update((s) => ({ ...s, automation: true })); api.startMonitoring(); }
    else { update((s) => ({ ...s, automation: false })); api.stopMonitoring(); }
  };
  const toggleLow = async (v: boolean) => { if (v && !(await ensure('lowBattery'))) return; update((s) => ({ ...s, lowBattery: v })); };
  const step = (d: number) => update((s) => ({ ...s, lowBatteryPercent: Math.max(10, Math.min(90, s.lowBatteryPercent + d)) }));

  const setFloating = async (m: 'off' | 'gaming' | 'always') => {
    if (m !== 'off' && !(await ensure('floating'))) return;
    update((x) => ({ ...x, floating: m }));
    if (m !== 'off') api.startMonitoring();
  };
  const preview = async () => {
    if (!(await ensure('floating'))) return;
    api.startMonitoring(); api.previewFloating(15);
    toast({ title: 'Panel shown for 15 seconds', sub: 'Drag the bubble where you want it.' });
  };

  const perm = (ok: boolean) => <T v="sub" c={ok ? t.c.ok : t.c.wn} w="600">{ok ? 'Allowed' : 'Off'}</T>;
  const stateLabel = adb.state === 'connected' ? ['Connected', t.c.ok] : adb.state === 'connecting' ? ['Connecting…', t.c.mu] : adb.state === 'error' ? ['Connection error', t.c.wn] : ['Not connected', t.c.mu];

  return (
    <Screen tab>
      <Header title="Settings" sub="Configure Densify" />
      <Pressable onPress={() => (state.mode === 'root' ? api.connect().catch((e: Error) => toast({ title: 'Root not available', sub: e.message, err: true })) : nav.push({ name: 'setup' }))}
        style={{ flexDirection: 'row', alignItems: 'center', gap: 14, padding: 16, marginBottom: 6, borderRadius: Math.min(t.r, 22), borderWidth: 1, borderColor: adb.state === 'connected' ? t.c.ok + '66' : t.c.wn + '66', backgroundColor: (adb.state === 'connected' ? t.c.ok : t.c.wn) + '1a' }}>
        <View style={{ width: 44, height: 44, borderRadius: 14, alignItems: 'center', justifyContent: 'center', backgroundColor: t.c.sf2 }}><Icon name="link" size={24} color={adb.state === 'connected' ? t.c.ok : t.c.wn} /></View>
        <View style={{ flex: 1 }}>
          <T w="600" style={{ fontSize: 15.5 }}>{state.mode === 'root' ? 'Root access' : state.mode === 'tcpip' ? 'adb over network' : state.mode === 'shizuku' ? 'Shizuku' : 'Wireless Debugging'}</T>
          <T v="sub" c={t.c.tx2}>{adb.state === 'connected' ? 'Connected. Densify can change your display.' : 'Not connected. Tap to set it up.'}</T>
        </View>
        <Btn v={adb.state === 'connected' ? 'tonal' : 'pri'} label={adb.state === 'connected' ? 'Manage' : 'Set up'} onPress={() => (state.mode === 'root' ? api.connect().catch(() => {}) : nav.push({ name: 'setup' }))} />
      </Pressable>

      <Section title="Display" />
      <Group>
        <Row leadIcon="tune" title="Default smallest width" sub="What Densify returns to after a game. Same number as in Developer options." right={
          <View style={{ width: 84, height: 44, borderRadius: t.rs, borderWidth: 1.5, borderColor: defErr ? t.c.wn : t.c.bd2, alignItems: 'center', justifyContent: 'center' }}>
            <TextInput value={def} onChangeText={(s) => { setDef(s.replace(/\D/g, '').slice(0, 4)); setDefErr(''); }} onEndEditing={saveDef} keyboardType="number-pad" placeholder="—" placeholderTextColor={t.c.mu} style={{ color: t.c.tx, fontSize: 16, fontWeight: '500', textAlign: 'center', width: '100%' }} />
          </View>} />
        <Row leadIcon="sync" title="Detect my phone's own" sub="Use your phone's built-in smallest width" onPress={detect} />
        <Row leadIcon="undo" title="Restore default now" sub={state.defaultSw ? `Switch back to smallest width ${state.defaultSw} immediately` : "Switch back to your phone's own smallest width"} onPress={restore} />
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
      <Group><Row leadIcon="tune" title="Presets and quick values" sub={`${state.presets.length} presets · ${state.quick.length} quick values`} onPress={() => nav.push({ name: 'presets' })} right={<Icon name="chevron-right" />} /></Group>

      <Section title="Floating panel" />
      <Group>
        <Row leadIcon="layers" title="Show over games" sub="A draggable, scrollable panel in your game's theme: smallest width, stretch, optimize and recording" below={
          (['off', 'gaming', 'always'] as const).map((m) => <Chip key={m} h={36} label={m === 'off' ? 'Off' : m === 'gaming' ? 'In games' : 'Always'} on={state.floating === m} onPress={() => setFloating(m)} />)} />
        <Row leadIcon="visibility" title="Preview the panel" sub="Shows it for 15 seconds so you can place it" onPress={preview} />
      </Group>

      <Section title="Quick controls" />
      <Group>
        <Row leadIcon="dashboard-customize" title="Quick Settings tile" sub="Step through your quick smallest-width values from the notification shade" right={<Btn v="tonal" label="Add" onPress={() => toast(api.requestAddTile() ? { title: 'Asked Android to add the tile' } : { title: 'Add it manually', sub: 'Edit your Quick Settings panel and drag “Density” in.' })} />} />
        <Row leadIcon="notifications" title="Notification controls" sub="SW −10, SW +10 and Restore appear while monitoring. +10 gives more room, −10 a bigger UI." />
      </Group>

      <Section title="Connection" />
      <Group>
        <Row leadIcon="admin-panel-settings" title="Connection method" sub={state.mode === 'root' ? 'Root' : state.mode === 'tcpip' ? 'From a computer (adb tcpip)' : state.mode === 'shizuku' ? 'Shizuku (no pairing needed)' : 'Wireless Debugging (Android 11+)'} below={
          ([['wireless', 'Wi-Fi'], ['shizuku', 'Shizuku'], ['tcpip', 'PC'], ['root', 'Root']] as const).map(([m, l]) => <Chip key={m} h={36} label={l} on={state.mode === m} onPress={() => { update((x) => ({ ...x, mode: m })); api.disconnect(); }} />)} />
        <Row leadIcon="wifi" title="Reconnect automatically" sub="Pairing is remembered. Densify switches Wireless Debugging back on and reconnects by itself, so setup is only needed again after a reinstall." right={<AppSwitch on={state.autoReconnect} onChange={(v) => update((x) => ({ ...x, autoReconnect: v }))} />} />
        <Row leadIcon="wifi-off" title="Keep working without Wi-Fi" sub="Experimental. Moves the connection to a local port, so switching works with Wi-Fi off. Needs Wi-Fi once after every restart." right={<AppSwitch on={state.bridge} onChange={(v) => {
          if (!v) { update((x) => ({ ...x, bridge: false })); api.disableBridge(); return; }
          toast({ title: 'Setting up the local connection…', sub: 'Wireless Debugging will blink for a few seconds.' });
          api.enableBridge().then(() => { update((x) => ({ ...x, bridge: true })); toast({ title: 'Works without Wi-Fi now', sub: 'Until the phone restarts.' }); })
            .catch((e: Error) => toast({ title: "Couldn't turn it on", sub: e.message, err: true }));
        }} />} />
      </Group>

      <Section title="Tools" />
      <Group>
        <Row leadIcon="developer-mode" title="Developer" sub="Tweaks, power menu, settings editor, external API" onPress={() => nav.push({ name: 'developer' })} right={<Icon name="chevron-right" />} />
        <Row leadIcon="terminal" title="Terminal, logs and backup" sub="Run shell commands, read activity, troubleshoot" onPress={() => nav.push({ name: 'tools' })} right={<Icon name="chevron-right" />} />
      </Group>

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
            <Pressable key={th.id} onPress={() => update((s) => ({ ...s, themeId: th.id, accent: s.accent && accentClashes(th, s.accent) ? '' : s.accent }))} style={{ width: '30%', flexGrow: 1 }}>
              <View style={{ height: 84, borderRadius: Math.min(th.r, 22), backgroundColor: th.c.bg, borderWidth: on ? 2 : 1, borderColor: on ? t.c.ac : t.c.bd2, padding: 9, justifyContent: 'flex-end', gap: 6, overflow: 'hidden' }}>
                <View style={{ height: 30, borderRadius: Math.min(th.rs, 11), backgroundColor: th.c.sf, borderWidth: 1, borderColor: th.c.bd2, flexDirection: 'row', alignItems: 'center', paddingHorizontal: 8, gap: 6 }}>
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
      <View style={{ marginTop: 12 }}><Group><Row leadIcon="blur-on" title="Liquid glass" sub="Frosted, translucent surfaces and a glowing backdrop on top of any theme, like Manga + glass or Paper + glass" right={<AppSwitch on={state.glass} onChange={(v) => update((x) => ({ ...x, glass: v }))} />} /></Group></View>
      <Section title="Accent colour" action={state.accent ? 'Reset' : undefined} onAction={() => update((s) => ({ ...s, accent: '' }))} />
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 12 }}>
        {ACCENTS.map((c) => (
          <Pressable key={c} onPress={() => update((s) => ({ ...s, accent: c }))} style={{ width: 44, height: 44, borderRadius: 22, backgroundColor: c, borderWidth: state.accent === c ? 3 : 1, borderColor: state.accent === c ? t.c.tx : t.c.bd2 }} />
        ))}
      </View>

      <Section title="About" />
      <Group><Row leadIcon="info" title="Densify" sub={`Version ${Constants.expoConfig?.version ?? ''}`} /></Group>
    </Screen>
  );
}

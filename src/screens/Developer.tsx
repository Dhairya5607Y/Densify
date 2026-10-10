import React, { useState } from 'react';
import { View } from 'react-native';
import { api } from '../native/api';
import { useStore } from '../store/store';
import { useNav } from '../nav';
import { useTheme } from '../theme/ThemeContext';
import { AppSwitch, Btn, Group, Icon, Row, Section, T } from '../ui/primitives';
import { Sheet, useToast } from '../ui/overlays';
import { Screen } from '../ui/scroll';
import { useEnsure } from '../logic/permissions';
import { Header } from './common';

const TOGGLES: { title: string; sub: string; icon: React.ComponentProps<typeof Icon>['name']; ns: string; key: string; on: string; off: string }[] = [
  { title: 'Show touches', sub: 'Dots where you touch', icon: 'touch-app', ns: 'system', key: 'show_touches', on: '1', off: '0' },
  { title: 'Pointer location', sub: 'Touch coordinates overlay', icon: 'my-location', ns: 'system', key: 'pointer_location', on: '1', off: '0' },
  { title: 'Stay awake while charging', sub: 'Screen never sleeps on charger', icon: 'brightness-high', ns: 'global', key: 'stay_on_while_plugged_in', on: '7', off: '0' },
  { title: 'Faster animations', sub: 'Animations at 0.5x (off = 1x)', icon: 'speed', ns: 'global', key: 'animator_duration_scale', on: '0.5', off: '1' },
  { title: 'Disable animations', sub: 'Window and transition scale 0', icon: 'animation', ns: 'global', key: 'window_animation_scale', on: '0', off: '1' },
  { title: 'Disable adb authorization timeout', sub: 'Keeps Wireless Debugging paired for longer', icon: 'lock-clock', ns: 'global', key: 'adb_allowed_connection_time', on: '0', off: '604800000' },
];

const POWER: { label: string; cmd: string; icon: React.ComponentProps<typeof Icon>['name']; warn: string }[] = [
  { label: 'Reboot', cmd: 'svc power reboot', icon: 'restart-alt', warn: 'The phone restarts now.' },
  { label: 'Reboot to recovery', cmd: 'svc power reboot recovery', icon: 'build', warn: 'The phone restarts into recovery.' },
  { label: 'Reboot to bootloader', cmd: 'svc power reboot bootloader', icon: 'memory', warn: 'The phone restarts into the bootloader.' },
  { label: 'Power off', cmd: 'svc power shutdown', icon: 'power-settings-new', warn: 'The phone turns off.' },
];

/** Developer tools: quick tweaks, power menu, the settings editor and the external API. */
export function Developer() {
  const { state, update } = useStore();
  const t = useTheme(); const nav = useNav(); const toast = useToast(); const ensure = useEnsure();
  const [vals, setVals] = useState<Record<string, boolean>>({});
  const [confirm, setConfirm] = useState<(typeof POWER)[number] | null>(null);

  const flip = async (x: (typeof TOGGLES)[number], on: boolean) => {
    if (!(await ensure('developer'))) return;
    try { await api.shell(`settings put ${x.ns} ${x.key} ${on ? x.on : x.off}`); setVals((v) => ({ ...v, [x.key]: on })); }
    catch (e) { toast({ title: `Couldn't change ${x.title.toLowerCase()}`, sub: (e as Error).message, err: true }); }
  };
  const power = async () => {
    const p = confirm; setConfirm(null);
    if (!p || !(await ensure('developer'))) return;
    api.shell(p.cmd).catch(() => {});
  };
  const newToken = () => update((s) => ({ ...s, apiToken: Array.from({ length: 20 }, () => 'abcdefghijkmnpqrstuvwxyz23456789'[Math.floor(Math.random() * 32)]).join('') }));
  const toggleApi = (v: boolean) => { update((s) => ({ ...s, externalApi: v, apiToken: s.apiToken || '' })); if (v && !state.apiToken) newToken(); };

  return (
    <Screen>
      <Header title="Developer" sub="Tweaks, power menu and external API" onBack={nav.back} />
      <Section title="Quick tweaks" />
      <Group>
        {TOGGLES.map((x) => <Row key={x.key} leadIcon={x.icon} title={x.title} sub={x.sub} right={<AppSwitch on={!!vals[x.key]} onChange={(v) => flip(x, v)} />} />)}
      </Group>
      <Section title="Power menu" />
      <Group>
        {POWER.map((p) => <Row key={p.label} leadIcon={p.icon} title={p.label} onPress={() => setConfirm(p)} />)}
      </Group>
      <Section title="Editors" />
      <Group><Row leadIcon="edit-note" title="Settings editor" sub="System, secure and global settings" onPress={() => nav.push({ name: 'settingsEditor' })} right={<Icon name="chevron-right" />} /></Group>
      <Section title="External API" />
      <Group>
        <Row leadIcon="api" title="Allow other apps" sub="Tasker and similar apps can set DPI with a token" right={<AppSwitch on={state.externalApi} onChange={toggleApi} />} />
        {state.externalApi && <Row leadIcon="key" title="Token" sub={state.apiToken || '—'} right={<Btn v="tonal" label="New" onPress={newToken} />} />}
      </Group>
      {state.externalApi && (
        <T v="sub" style={{ margin: 10, fontFamily: 'monospace', fontSize: 12 }}>
          am broadcast -a com.densify.app.action.SET_DPI -n com.densify.app/expo.modules.densifynative.ExternalReceiver --ei dpi 420 --es token {state.apiToken}
          {'\n'}Also: com.densify.app.action.RESTORE and com.densify.app.action.TOGGLE_STRETCH
        </T>
      )}
      <Sheet visible={!!confirm} onClose={() => setConfirm(null)}>
        {confirm && (<>
          <T v="h">{confirm.label}?</T>
          <T v="sub" style={{ marginTop: 6, marginBottom: 20 }}>{confirm.warn} Save your work first.</T>
          <View style={{ flexDirection: 'row', gap: 10 }}>
            <View style={{ flex: 1 }}><Btn wide v="tonal" label="Cancel" onPress={() => setConfirm(null)} /></View>
            <View style={{ flex: 1 }}><Btn wide v="pri" label={confirm.label} onPress={power} /></View>
          </View>
        </>)}
      </Sheet>
    </Screen>
  );
}
